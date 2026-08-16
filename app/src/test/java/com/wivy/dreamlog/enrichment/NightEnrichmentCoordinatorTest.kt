package com.wivy.dreamlog.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightEnrichmentCoordinatorTest {
    @Test
    fun successfulRunValidatesOneBareJsonObjectClosesEngineAndPublishesFiniteProgress() {
        val source = completedSource()
        val store = FakeStore(source)
        val engineFactory = FakeEngineFactory(response = {
            EnrichmentEngineResult(validOutput())
        })
        val gate = FakeGate()
        val progress = mutableListOf<EnrichmentOperationSnapshot>()
        val coordinator = coordinator(store, engineFactory, gate)

        val outcome = coordinator.processNight(NIGHT_ID, progress::add)

        assertTrue(outcome is EnrichmentRunOutcome.Completed)
        outcome as EnrichmentRunOutcome.Completed
        assertEquals(1, outcome.dreamCount)
        assertFalse(outcome.inferenceSkippedForEmptyInput)
        assertTrue(outcome.rawFallbackAvailable)
        assertEquals(1, engineFactory.openCount)
        assertEquals(1, engineFactory.closeCount)
        assertEquals(1, gate.closeCount)
        assertEquals(1, store.claimCount)
        assertEquals(1, store.completeCount)
        assertNull(store.failed)
        assertEquals(
            listOf(
                EnrichmentOperationPhase.PREPARING,
                EnrichmentOperationPhase.LOADING_MODEL,
                EnrichmentOperationPhase.GENERATING,
                EnrichmentOperationPhase.VALIDATING,
                EnrichmentOperationPhase.SAVING,
                EnrichmentOperationPhase.COMPLETE,
            ),
            progress.map(EnrichmentOperationSnapshot::phase),
        )
        assertEquals(EnrichmentOperationPhase.COMPLETE, coordinator.operationState.current().phase)
    }

    @Test
    fun malformedOrWrongJsonOutputFailsDurablyAndKeepsRawFallback() {
        listOf(
            EnrichmentEngineResult("{") to EnrichmentOutputReason.MALFORMED_JSON,
            EnrichmentEngineResult("{}") to EnrichmentOutputReason.WRONG_FIELDS,
            EnrichmentEngineResult("{\"parts\":[]} trailing") to
                EnrichmentOutputReason.MALFORMED_JSON,
        ).forEach { (response, expectedReason) ->
            val source = completedSource()
            val store = FakeStore(source)
            val engineFactory = FakeEngineFactory(response = { response })
            val coordinator = coordinator(store, engineFactory, FakeGate())

            val outcome = coordinator.processNight(NIGHT_ID)

            assertTrue(outcome is EnrichmentRunOutcome.Failure)
            outcome as EnrichmentRunOutcome.Failure
            assertEquals(EnrichmentFailureCode.OUTPUT_INVALID, outcome.code)
            assertTrue(outcome.rawFallbackAvailable)
            assertEquals(source, store.source)
            assertEquals(0, store.completeCount)
            assertEquals("output_invalid", store.failed?.code)
            assertEquals(expectedReason.safeDetail, store.failed?.detail)
            assertFalse(store.failed!!.detail.contains("quiet moon"))
            assertEquals(1, engineFactory.closeCount)
        }
    }

    @Test
    fun emptyCompletedInputCommitsNoDreamsWithoutOpeningModel() {
        val store = FakeStore(completedSource(segments = emptyList()))
        val engineFactory = FakeEngineFactory(response = {
            error("The engine must not open or generate for an empty input.")
        })
        val progress = mutableListOf<EnrichmentOperationSnapshot>()
        val coordinator = coordinator(store, engineFactory, FakeGate())

        val outcome = coordinator.processNight(NIGHT_ID, progress::add)

        assertTrue(outcome is EnrichmentRunOutcome.Completed)
        outcome as EnrichmentRunOutcome.Completed
        assertTrue(outcome.inferenceSkippedForEmptyInput)
        assertEquals(0, outcome.dreamCount)
        assertEquals(0, engineFactory.openCount)
        assertEquals(1, store.completeCount)
        assertTrue(store.completed!!.dreams.isEmpty())
        assertTrue(store.completedDescriptor!!.inferenceSkippedForEmptyInput)
        assertEquals(
            listOf(
                EnrichmentOperationPhase.PREPARING,
                EnrichmentOperationPhase.SAVING,
                EnrichmentOperationPhase.COMPLETE,
            ),
            progress.map(EnrichmentOperationSnapshot::phase),
        )
    }

    @Test
    fun activeIncompleteOrUnreviewableNightsFailBeforeClaimAndModelLoad() {
        val cases = listOf(
            completedSource().copy(captureEnded = false) to EnrichmentFailureCode.NIGHT_ACTIVE,
            completedSource().copy(transcriptionComplete = false) to
                EnrichmentFailureCode.TRANSCRIPTION_INCOMPLETE,
            completedSource().copy(rawTranscriptReviewable = false) to
                EnrichmentFailureCode.RAW_SOURCE_UNAVAILABLE,
        )
        cases.forEach { (source, expectedCode) ->
            val store = FakeStore(source)
            val factory = FakeEngineFactory(response = { error("Inference must not run.") })

            val outcome = coordinator(store, factory, FakeGate()).processNight(NIGHT_ID)

            assertTrue(outcome is EnrichmentRunOutcome.Failure)
            assertEquals(expectedCode, (outcome as EnrichmentRunOutcome.Failure).code)
            assertEquals(0, store.claimCount)
            assertEquals(0, factory.openCount)
        }
    }

    @Test
    fun modelAndPersistenceFailuresRemainRetryableAndNeverCreatePartialResult() {
        val modelFailureStore = FakeStore(completedSource())
        val modelFailureFactory = FakeEngineFactory(
            response = { error("unused") },
            openFailure = IllegalStateException("native detail must stay private"),
        )
        val modelOutcome = coordinator(
            modelFailureStore,
            modelFailureFactory,
            FakeGate(),
        ).processNight(NIGHT_ID)
        assertEquals(
            EnrichmentFailureCode.MODEL_LOAD_FAILED,
            (modelOutcome as EnrichmentRunOutcome.Failure).code,
        )
        assertEquals("model_load_failed", modelFailureStore.failed?.code)
        assertEquals(0, modelFailureStore.completeCount)

        val saveFailureStore = FakeStore(completedSource()).apply { allowComplete = false }
        val saveFactory = FakeEngineFactory(response = {
            EnrichmentEngineResult(validOutput())
        })
        val saveOutcome = coordinator(saveFailureStore, saveFactory, FakeGate())
            .processNight(NIGHT_ID)
        assertEquals(
            EnrichmentFailureCode.PERSISTENCE_FAILED,
            (saveOutcome as EnrichmentRunOutcome.Failure).code,
        )
        assertEquals(0, saveFailureStore.completeCount)
        assertEquals("persistence_failed", saveFailureStore.failed?.code)
    }

    @Test
    fun measuredContextOverflowIsNonRetryableAndPreservesRawFallback() {
        val store = FakeStore(completedSource())
        val factory = FakeEngineFactory(response = { throw EnrichmentInputTooLargeException() })

        val outcome = coordinator(store, factory, FakeGate()).processNight(NIGHT_ID)

        assertTrue(outcome is EnrichmentRunOutcome.Failure)
        outcome as EnrichmentRunOutcome.Failure
        assertEquals(EnrichmentFailureCode.INPUT_TOO_LARGE, outcome.code)
        assertFalse(outcome.retryable)
        assertTrue(outcome.rawFallbackAvailable)
        assertEquals("input_too_large", store.failed?.code)
        assertEquals(0, store.completeCount)
        assertEquals(1, factory.closeCount)
    }

    @Test
    fun knownOversizedRequestIsRejectedBeforeTheModelLoads() {
        val segments = (0 until 700).map { index ->
            NightTranscriptSegment(
                nightId = NIGHT_ID,
                sessionId = "session-a",
                sessionOrder = 0,
                transcriptAttempt = 1,
                segmentIndex = index,
                sourceStartMillis = index * 100L,
                sourceEndMillis = (index + 1L) * 100L,
                text = "abcdefghij",
            )
        }
        val store = FakeStore(completedSource(segments))
        val factory = FakeEngineFactory(response = { error("The model must not load.") })

        val outcome = coordinator(store, factory, FakeGate()).processNight(NIGHT_ID)

        assertTrue(outcome is EnrichmentRunOutcome.Failure)
        outcome as EnrichmentRunOutcome.Failure
        assertEquals(EnrichmentFailureCode.INPUT_TOO_LARGE, outcome.code)
        assertFalse(outcome.retryable)
        assertTrue(outcome.rawFallbackAvailable)
        assertEquals("input_too_large", store.failed?.code)
        assertEquals(0, factory.openCount)
        assertEquals(0, factory.closeCount)
    }

    @Test
    fun unavailableOperationGateFailsBeforeSourceReadAndReleasesNoLease() {
        val store = FakeStore(completedSource())
        val gate = FakeGate(available = false)

        val outcome = coordinator(
            store,
            FakeEngineFactory(response = { error("Inference must not run.") }),
            gate,
        ).processNight(NIGHT_ID)

        assertEquals(EnrichmentFailureCode.BUSY, (outcome as EnrichmentRunOutcome.Failure).code)
        assertEquals(0, store.loadCount)
        assertEquals(0, gate.closeCount)
    }

    @Test
    fun batchUsesOneGateAndOneReusableEngineForMultipleNights() {
        val firstNightId = "night-first"
        val secondNightId = "night-second"
        val store = FakeStore(
            source = null,
            sourcesByNight = mapOf(
                firstNightId to completedSourceFor(firstNightId, "first dream source"),
                secondNightId to completedSourceFor(secondNightId, "second dream source"),
            ),
        )
        val factory = FakeEngineFactory(response = { EnrichmentEngineResult(validOutput()) })
        val gate = FakeGate()
        val progress = mutableListOf<EnrichmentBatchProgress>()

        val outcome = coordinator(store, factory, gate).processBatch(
            listOf(firstNightId, secondNightId),
            progress::add,
        )

        assertEquals(listOf(firstNightId, secondNightId), outcome.requestedNightIds)
        assertEquals(listOf(firstNightId, secondNightId), outcome.outcomes.map { it.nightId })
        assertEquals(2, outcome.completedNightCount)
        assertEquals(0, outcome.failedNightCount)
        assertEquals(0, outcome.unstartedNightCount)
        assertFalse(outcome.stoppedEarly)
        assertEquals(listOf(firstNightId, secondNightId), store.loadedNightIds)
        assertEquals(listOf(firstNightId, secondNightId), store.claimedNightIds)
        assertEquals(listOf(firstNightId, secondNightId), store.completedNightIds)
        assertEquals(1, gate.acquireCount)
        assertEquals(1, gate.closeCount)
        assertEquals(1, factory.openCount)
        assertEquals(2, factory.generateCount)
        assertEquals(1, factory.closeCount)
        assertEquals(listOf(1, 2), progress.map { it.currentNightNumber }.distinct())
        assertTrue(progress.all { it.totalNightCount == 2 })
        assertEquals(2, progress.last().completedNightCount)
        assertEquals(0, progress.last().failedNightCount)
    }

    @Test
    fun batchLoadsModelLazilyAfterAnEarlyEmptyNight() {
        val emptyNightId = "night-empty"
        val narrativeNightId = "night-narrative"
        val store = FakeStore(
            source = null,
            sourcesByNight = mapOf(
                emptyNightId to completedSourceFor(emptyNightId, segments = emptyList()),
                narrativeNightId to completedSourceFor(narrativeNightId, "narrative source"),
            ),
        )
        val factory = FakeEngineFactory(response = { EnrichmentEngineResult(validOutput()) })
        val gate = FakeGate()
        val progress = mutableListOf<EnrichmentBatchProgress>()

        val outcome = coordinator(store, factory, gate).processBatch(
            listOf(emptyNightId, narrativeNightId),
            progress::add,
        )

        assertEquals(2, outcome.completedNightCount)
        assertTrue(
            (outcome.outcomes.first() as EnrichmentRunOutcome.Completed)
                .inferenceSkippedForEmptyInput,
        )
        assertFalse(
            (outcome.outcomes.last() as EnrichmentRunOutcome.Completed)
                .inferenceSkippedForEmptyInput,
        )
        assertEquals(1, factory.openCount)
        assertEquals(1, factory.generateCount)
        assertEquals(1, factory.closeCount)
        assertEquals(1, gate.acquireCount)
        assertEquals(1, gate.closeCount)
        assertFalse(
            progress.any {
                it.currentNightNumber == 1 &&
                    it.operation.phase == EnrichmentOperationPhase.LOADING_MODEL
            },
        )
        assertTrue(
            progress.any {
                it.currentNightNumber == 2 &&
                    it.operation.phase == EnrichmentOperationPhase.LOADING_MODEL
            },
        )
    }

    @Test
    fun ordinaryPerNightFailureDoesNotStopLaterNights() {
        val invalidNightId = "night-invalid-output"
        val validNightId = "night-valid-output"
        val store = FakeStore(
            source = null,
            sourcesByNight = mapOf(
                invalidNightId to completedSourceFor(invalidNightId, "malformed response source"),
                validNightId to completedSourceFor(validNightId, "valid response source"),
            ),
        )
        val factory = FakeEngineFactory(response = { request ->
            if (request.userContent.contains("malformed response source")) {
                EnrichmentEngineResult("{")
            } else {
                EnrichmentEngineResult(validOutput())
            }
        })
        val gate = FakeGate()
        val progress = mutableListOf<EnrichmentBatchProgress>()

        val outcome = coordinator(store, factory, gate).processBatch(
            listOf(invalidNightId, validNightId),
            progress::add,
        )

        assertEquals(2, outcome.outcomes.size)
        assertEquals(
            EnrichmentFailureCode.OUTPUT_INVALID,
            (outcome.outcomes.first() as EnrichmentRunOutcome.Failure).code,
        )
        assertTrue(outcome.outcomes.last() is EnrichmentRunOutcome.Completed)
        assertEquals(1, outcome.completedNightCount)
        assertEquals(1, outcome.failedNightCount)
        assertFalse(outcome.stoppedEarly)
        assertEquals(listOf(invalidNightId, validNightId), store.claimedNightIds)
        assertEquals(listOf(validNightId), store.completedNightIds)
        assertEquals("output_invalid", store.failuresByNight[invalidNightId]?.code)
        assertEquals(1, factory.openCount)
        assertEquals(2, factory.generateCount)
        assertEquals(1, factory.closeCount)
        assertEquals(1, gate.acquireCount)
        assertEquals(1, gate.closeCount)
        assertEquals(1, progress.last().completedNightCount)
        assertEquals(1, progress.last().failedNightCount)
    }

    @Test
    fun systemicModelLoadFailureStopsWithoutClaimingLaterNights() {
        val firstNightId = "night-model-failure"
        val laterNightId = "night-must-remain-unstarted"
        val store = FakeStore(
            source = null,
            sourcesByNight = mapOf(
                firstNightId to completedSourceFor(firstNightId, "first source"),
                laterNightId to completedSourceFor(laterNightId, "later source"),
            ),
        )
        val factory = FakeEngineFactory(
            response = { error("Generation must not run when model loading fails.") },
            openFailure = IllegalStateException("private native detail"),
        )
        val gate = FakeGate()
        val progress = mutableListOf<EnrichmentBatchProgress>()

        val outcome = coordinator(store, factory, gate).processBatch(
            listOf(firstNightId, laterNightId),
            progress::add,
        )

        assertTrue(outcome.stoppedEarly)
        assertEquals(1, outcome.outcomes.size)
        assertEquals(1, outcome.failedNightCount)
        assertEquals(1, outcome.unstartedNightCount)
        assertEquals(
            EnrichmentFailureCode.MODEL_LOAD_FAILED,
            (outcome.outcomes.single() as EnrichmentRunOutcome.Failure).code,
        )
        assertEquals(listOf(firstNightId), store.loadedNightIds)
        assertEquals(listOf(firstNightId), store.claimedNightIds)
        assertEquals("model_load_failed", store.failuresByNight[firstNightId]?.code)
        assertFalse(store.failuresByNight.containsKey(laterNightId))
        assertEquals(1, factory.openCount)
        assertEquals(0, factory.generateCount)
        assertEquals(0, factory.closeCount)
        assertEquals(1, gate.acquireCount)
        assertEquals(1, gate.closeCount)
        assertEquals(0, progress.last().completedNightCount)
        assertEquals(1, progress.last().failedNightCount)
    }

    @Test
    fun batchPreservesFirstOccurrenceOrderAndDeduplicatesTrimmedIds() {
        val firstNightId = "night-b"
        val secondNightId = "night-a"
        val thirdNightId = "night-c"
        val store = FakeStore(
            source = null,
            sourcesByNight = listOf(firstNightId, secondNightId, thirdNightId).associateWith {
                completedSourceFor(it, "source for $it")
            },
        )
        val factory = FakeEngineFactory(response = { EnrichmentEngineResult(validOutput()) })
        val gate = FakeGate()

        val outcome = coordinator(store, factory, gate).processBatch(
            listOf(" $firstNightId ", secondNightId, firstNightId, thirdNightId, " $secondNightId"),
        )

        val expectedOrder = listOf(firstNightId, secondNightId, thirdNightId)
        assertEquals(expectedOrder, outcome.requestedNightIds)
        assertEquals(expectedOrder, outcome.outcomes.map { it.nightId })
        assertEquals(expectedOrder, store.loadedNightIds)
        assertEquals(expectedOrder, store.claimedNightIds)
        assertEquals(expectedOrder, store.completedNightIds)
        assertEquals(3, outcome.completedNightCount)
        assertEquals(1, factory.openCount)
        assertEquals(3, factory.generateCount)
        assertEquals(1, factory.closeCount)
        assertEquals(1, gate.acquireCount)
        assertEquals(1, gate.closeCount)
    }

    @Test
    fun appOpenStateMachineEnforcesLegalFiniteTransitionsAndCanStartNextRun() {
        val state = AppOpenEnrichmentStateMachine()
        assertTrue(state.current().requiresAppToRemainOpen)
        state.begin("night-a")
        assertTrue(runCatching { state.begin("night-b") }.isFailure)
        state.advance(EnrichmentOperationPhase.LOADING_MODEL, 1, true)
        state.advance(EnrichmentOperationPhase.GENERATING, 1, true)
        state.advance(EnrichmentOperationPhase.VALIDATING, 1, true)
        state.advance(EnrichmentOperationPhase.SAVING, 1, true)
        state.complete(1, true)
        assertEquals(EnrichmentOperationPhase.COMPLETE, state.current().phase)

        state.begin("night-b")
        state.fail(EnrichmentFailureCode.OUTPUT_INVALID, 2, true)
        assertEquals(EnrichmentOperationPhase.FAILED, state.current().phase)
        assertEquals(EnrichmentFailureCode.OUTPUT_INVALID, state.current().failureCode)
    }

    private fun coordinator(
        store: FakeStore,
        factory: FakeEngineFactory,
        gate: FakeGate,
    ): NightEnrichmentCoordinator {
        var time = 100L
        return NightEnrichmentCoordinator(
            store = store,
            engineFactory = factory,
            operationGate = gate,
            clock = { time++ },
        )
    }

    private fun completedSource(
        segments: List<NightTranscriptSegment> = listOf(
            NightTranscriptSegment(
                nightId = NIGHT_ID,
                sessionId = "session-a",
                sessionOrder = 0,
                transcriptAttempt = 1,
                segmentIndex = 0,
                sourceStartMillis = 0L,
                sourceEndMillis = 500L,
                text = "quiet moon",
            ),
        ),
    ) = EnrichmentNightSource(
        nightId = NIGHT_ID,
        captureEnded = true,
        transcriptionComplete = true,
        rawTranscriptReviewable = true,
        segments = segments,
    )

    private fun completedSourceFor(
        nightId: String,
        text: String = "quiet moon",
        segments: List<NightTranscriptSegment> = listOf(
            NightTranscriptSegment(
                nightId = nightId,
                sessionId = "session-$nightId",
                sessionOrder = 0,
                transcriptAttempt = 1,
                segmentIndex = 0,
                sourceStartMillis = 0L,
                sourceEndMillis = 500L,
                text = text,
            ),
        ),
    ) = EnrichmentNightSource(
        nightId = nightId,
        captureEnded = true,
        transcriptionComplete = true,
        rawTranscriptReviewable = true,
        segments = segments,
    )

    private fun validOutput(): String =
        "{\"parts\":[{\"dream\":\"d0\",\"kind\":\"dream\",\"uncertain\":false," +
            "\"start\":\"s0\",\"end\":\"s0\"}]}"

    private class FakeStore(
        var source: EnrichmentNightSource?,
        private val sourcesByNight: Map<String, EnrichmentNightSource> = emptyMap(),
    ) : NightEnrichmentStore {
        var loadCount = 0
        var claimCount = 0
        var completeCount = 0
        var allowComplete = true
        var completed: ValidatedEnrichment? = null
        var completedDescriptor: EnrichmentRunDescriptor? = null
        var failed: PersistedEnrichmentFailure? = null
        val loadedNightIds = mutableListOf<String>()
        val claimedNightIds = mutableListOf<String>()
        val completedNightIds = mutableListOf<String>()
        val failuresByNight = linkedMapOf<String, PersistedEnrichmentFailure>()

        override fun loadNightSource(nightId: String): EnrichmentNightSource? {
            loadCount += 1
            loadedNightIds += nightId
            return if (sourcesByNight.isEmpty()) source else sourcesByNight[nightId]
        }

        override fun claimAttempt(
            nightId: String,
            descriptor: EnrichmentRunDescriptor,
            startedAtEpochMillis: Long,
        ): EnrichmentAttemptClaim {
            claimCount += 1
            claimedNightIds += nightId
            return EnrichmentAttemptClaim(nightId, "run-$claimCount", claimCount, startedAtEpochMillis)
        }

        override fun completeAttempt(
            claim: EnrichmentAttemptClaim,
            descriptor: EnrichmentRunDescriptor,
            result: ValidatedEnrichment,
            completedAtEpochMillis: Long,
        ): Boolean {
            if (!allowComplete) return false
            completeCount += 1
            completedNightIds += claim.nightId
            completed = result
            completedDescriptor = descriptor
            return true
        }

        override fun failAttempt(
            claim: EnrichmentAttemptClaim,
            failure: PersistedEnrichmentFailure,
            completedAtEpochMillis: Long,
        ): Boolean {
            failed = failure
            failuresByNight[claim.nightId] = failure
            return true
        }
    }

    private class FakeEngineFactory(
        private val response: (EnrichmentModelRequest) -> EnrichmentEngineResult,
        private val openFailure: Throwable? = null,
    ) : EnrichmentEngineFactory {
        override val metadata = EnrichmentEngineMetadata(
            localeTag = "en-US",
            engineId = "test-engine",
            engineVersion = "1",
            runtimeId = "test-runtime",
            runtimeVersion = "1",
            modelId = "test-model",
            modelVersion = "1",
            modelSha256 = "a".repeat(64),
            backendId = "cpu",
            modelBytes = 1_024L,
            contextWindowTokens = 4_096,
            maxTotalTokens = 2_048,
        )
        var openCount = 0
        var generateCount = 0
        var closeCount = 0

        override fun open(): EnrichmentEngine {
            openCount += 1
            openFailure?.let { throw it }
            return object : EnrichmentEngine {
                override fun generate(request: EnrichmentModelRequest): EnrichmentEngineResult {
                    generateCount += 1
                    return response(request)
                }

                override fun close() {
                    closeCount += 1
                }
            }
        }
    }

    private class FakeGate(private val available: Boolean = true) : EnrichmentOperationGate {
        var acquireCount = 0
        var closeCount = 0

        override fun tryAcquire(): EnrichmentOperationLease? {
            acquireCount += 1
            return if (available) {
                EnrichmentOperationLease { closeCount += 1 }
            } else {
                null
            }
        }
    }

    private companion object {
        const val NIGHT_ID = "night-1"
    }
}
