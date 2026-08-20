package com.wivy.dreamlog.enrichment.persistence

import com.wivy.dreamlog.enrichment.DreamSourceRole
import com.wivy.dreamlog.enrichment.ENRICHMENT_PROMPT_VERSION
import com.wivy.dreamlog.enrichment.ENRICHMENT_SCHEMA_VERSION
import com.wivy.dreamlog.enrichment.EnrichedDreamDraft
import com.wivy.dreamlog.enrichment.EnrichedDreamKind
import com.wivy.dreamlog.enrichment.EnrichedSourceSpan
import com.wivy.dreamlog.enrichment.EnrichmentEngineMetadata
import com.wivy.dreamlog.enrichment.EnrichmentFailureCode
import com.wivy.dreamlog.enrichment.EnrichmentOutputReason
import com.wivy.dreamlog.enrichment.EnrichmentRunDescriptor
import com.wivy.dreamlog.enrichment.INTERRUPTED_ENRICHMENT_FAILURE_DETAIL
import com.wivy.dreamlog.enrichment.OrderedNightTranscript
import com.wivy.dreamlog.enrichment.PersistedEnrichmentFailure
import com.wivy.dreamlog.enrichment.SourceSegmentId
import com.wivy.dreamlog.enrichment.ValidatedEnrichment
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamDraft
import com.wivy.dreamlog.history.EnrichmentProvenance
import com.wivy.dreamlog.history.EnrichmentRunEntity
import com.wivy.dreamlog.history.EnrichmentSourceSegment
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightEntity
import com.wivy.dreamlog.history.NightEventEntity
import com.wivy.dreamlog.history.NightWithDetails
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioState
import com.wivy.dreamlog.history.SessionTranscriptEntity
import com.wivy.dreamlog.history.SessionTranscriptWithSegments
import com.wivy.dreamlog.history.TranscriptSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomNightEnrichmentStoreTest {
    @Test
    fun persistedFailureRetryabilityRequiresTheExactSafeSuffix() {
        assertTrue(
            persistedEnrichmentFailureIsRetryable(
                "Local enrichment inference stopped. [code=inference_failed; retryable=true]",
            ),
        )
        assertTrue(
            persistedEnrichmentFailureIsRetryable(
                "The raw transcript remains available. [code=input_too_large; retryable=false]",
            ),
        )
        assertFalse(
            persistedEnrichmentFailureIsRetryable(
                "The raw transcript is invalid. [code=invalid_source; retryable=false]",
            ),
        )
        assertTrue(
            persistedEnrichmentFailureIsRetryable(INTERRUPTED_ENRICHMENT_FAILURE_DETAIL),
        )
        assertEquals(
            "Local enrichment was interrupted before completion. " +
                "The raw transcript remains reviewable.",
            persistedEnrichmentFailureDisplayDetail(INTERRUPTED_ENRICHMENT_FAILURE_DETAIL),
        )
        assertFalse(persistedEnrichmentFailureIsRetryable("retryable=true"))
        assertFalse(persistedEnrichmentFailureIsRetryable(null))
        assertNull(persistedEnrichmentFailureDisplayDetail(null))
    }

    @Test
    fun completeTranscriptRowsAreMappedChronologicallyWithAttemptIdentity() {
        val specs = listOf(
            SessionSpec("session-b", order = 1, attempt = 4, texts = listOf("later")),
            SessionSpec("session-a", order = 0, attempt = 2, texts = listOf("first")),
        )
        val gateway = FakeGateway(
            record = recordFor(specs),
            sourceRows = rowsFor(specs),
        )

        val source = store(gateway).loadNightSource(NIGHT_ID)!!

        assertTrue(source.captureEnded)
        assertTrue(source.transcriptionComplete)
        assertTrue(source.rawTranscriptReviewable)
        assertEquals(listOf("session-a", "session-b"), source.segments.map { it.sessionId })
        assertEquals(listOf(2, 4), source.segments.map { it.transcriptAttempt })
        assertEquals(listOf(0, 1), source.segments.map { it.sessionOrder })
    }

    @Test
    fun completeTranscriptRemainsReviewableWhenItsAudioIsNoLongerAvailable() {
        val specs = listOf(SessionSpec("session-a", order = 0, attempt = 2, texts = listOf("source")))
        val record = recordFor(specs, rawAudioState = RawAudioState.UNAVAILABLE).let { ready ->
            ready.copy(
                sessions = ready.sessions.map { session ->
                    session.copy(audioState = AudioEvidenceState.MISSING)
                },
            )
        }

        val source = store(FakeGateway(record, rowsFor(specs))).loadNightSource(NIGHT_ID)!!

        assertTrue(source.captureEnded)
        assertTrue(source.transcriptionComplete)
        assertTrue(source.rawTranscriptReviewable)
        assertEquals(listOf("source"), source.segments.map { it.text })
    }

    @Test
    fun finalizedIncompleteSessionWithCompleteTranscriptRemainsReviewable() {
        val specs = listOf(SessionSpec("session-a", order = 0, attempt = 2, texts = listOf("source")))
        val record = recordFor(specs).let { ready ->
            ready.copy(
                night = ready.night.copy(reportedIncompleteSessionCount = 1),
                sessions = ready.sessions.map { session ->
                    session.copy(incompleteReason = "night_ended")
                },
            )
        }

        val store = store(FakeGateway(record, rowsFor(specs)))
        val source = store.loadNightSource(NIGHT_ID)!!

        assertTrue(source.captureEnded)
        assertTrue(source.transcriptionComplete)
        assertTrue(source.rawTranscriptReviewable)
        assertEquals(listOf("source"), source.segments.map { it.text })
        assertNotNull(store.claimAttempt(NIGHT_ID, descriptor(source), startedAtEpochMillis = 100L))
    }

    @Test
    fun onlyAGenuinelyZeroSessionEndedNightIsReviewableAsEmpty() {
        val empty = FakeGateway(
            record = recordFor(
                specs = emptyList(),
                transcriptionState = ProcessingState.NOT_STARTED,
                rawAudioState = RawAudioState.NONE,
            ),
            sourceRows = emptyList(),
        )

        val emptySource = store(empty).loadNightSource(NIGHT_ID)!!

        assertTrue(emptySource.captureEnded)
        assertTrue(emptySource.transcriptionComplete)
        assertTrue(emptySource.rawTranscriptReviewable)
        assertTrue(emptySource.segments.isEmpty())

        val unavailableSession = SessionSpec(
            id = "session-missing",
            order = 0,
            attempt = 1,
            texts = emptyList(),
        )
        val unavailableRecord = recordFor(
            specs = listOf(unavailableSession),
            transcriptionState = ProcessingState.NOT_STARTED,
            rawAudioState = RawAudioState.UNAVAILABLE,
        ).let { record ->
            record.copy(
                sessions = record.sessions.map {
                    it.copy(
                        audioState = AudioEvidenceState.MISSING,
                        finalizedAtEpochMillis = null,
                    )
                },
                transcripts = emptyList(),
            )
        }

        val unavailable = store(
            FakeGateway(unavailableRecord, sourceRows = emptyList()),
        ).loadNightSource(NIGHT_ID)!!

        assertFalse(unavailable.transcriptionComplete)
        assertFalse(unavailable.rawTranscriptReviewable)
        assertTrue(unavailable.segments.isEmpty())

        val completedButEmptySpecs = listOf(SessionSpec("session-silent", 0, 1, emptyList()))
        val completedButEmpty = store(
            FakeGateway(recordFor(completedButEmptySpecs), sourceRows = emptyList()),
        ).loadNightSource(NIGHT_ID)!!

        assertTrue(completedButEmpty.transcriptionComplete)
        assertFalse(completedButEmpty.rawTranscriptReviewable)
        assertTrue(completedButEmpty.segments.isEmpty())
    }

    @Test
    fun failedTranscriptIsRefusedWithoutExposingPartialSegments() {
        val ready = recordFor(listOf(SessionSpec("session-a", 0, 2, listOf("source"))))
        val failedTranscript = ready.transcripts.single().let { value ->
            value.copy(
                transcript = value.transcript.copy(
                    state = ProcessingState.FAILED,
                    failureDetail = "safe failure",
                    rawText = null,
                    completedAtEpochMillis = null,
                ),
            )
        }
        val failed = ready.copy(
            night = ready.night.copy(
                transcriptionState = ProcessingState.FAILED,
                transcriptionFailure = "safe failure",
            ),
            transcripts = listOf(failedTranscript),
        )

        val source = store(
            FakeGateway(failed, rowsFor(listOf(SessionSpec("session-a", 0, 2, listOf("source"))))),
        ).loadNightSource(NIGHT_ID)!!

        assertFalse(source.transcriptionComplete)
        assertFalse(source.rawTranscriptReviewable)
        assertTrue(source.segments.isEmpty())
    }

    @Test
    fun claimPersistsCompleteEngineRuntimeModelAndPromptProvenance() {
        val specs = listOf(SessionSpec("session-a", 0, 7, listOf("source")))
        val gateway = FakeGateway(recordFor(specs), rowsFor(specs), nextAttempt = 3)
        val store = store(gateway)
        val descriptor = descriptor(store.loadNightSource(NIGHT_ID)!!)

        val claim = store.claimAttempt(NIGHT_ID, descriptor, startedAtEpochMillis = 100L)

        assertNotNull(claim)
        assertEquals(RUN_ID, claim!!.runId)
        assertEquals(3, claim.attempt)
        val provenance = gateway.startedProvenance!!
        assertEquals("en-US", provenance.localeTag)
        assertEquals("engine", provenance.engineId)
        assertEquals("engine-v1", provenance.engineVersion)
        assertEquals("litert-lm", provenance.runtimeId)
        assertEquals("0.9.0", provenance.runtimeVersion)
        assertEquals("cpu", provenance.backendId)
        assertEquals("model", provenance.modelId)
        assertEquals("revision", provenance.modelVersion)
        assertEquals(hash('b'), provenance.modelSha256)
        assertEquals(497_664_000L, provenance.modelBytes)
        assertEquals(2_048, provenance.contextWindowTokens)
        assertEquals(512, provenance.maxTotalTokens)
        assertEquals("dreamlog-whole-night-enrichment", provenance.promptId)
        assertEquals(ENRICHMENT_PROMPT_VERSION, provenance.promptVersion)
        assertTrue(Regex("[0-9a-f]{64}").matches(provenance.promptSha256))
        assertEquals(
            enrichmentPromptSha256(ENRICHMENT_PROMPT_VERSION, ENRICHMENT_SCHEMA_VERSION),
            provenance.promptSha256,
        )
        assertEquals(ENRICHMENT_SCHEMA_VERSION, provenance.outputSchemaVersion)
        assertEquals(descriptor.inputFingerprintSha256, gateway.startedInputSha256)
    }

    @Test
    fun completionMapsStableDreamAndSourceIdsThenCommitsThroughDaoBoundary() {
        val specs = listOf(SessionSpec("session-a", 0, 7, listOf("first", "second")))
        val firstGateway = FakeGateway(recordFor(specs), rowsFor(specs), nextAttempt = 2)
        val firstStore = store(firstGateway, RUN_ID)
        val source = firstStore.loadNightSource(NIGHT_ID)!!
        val descriptor = descriptor(source)
        val firstClaim = firstStore.claimAttempt(NIGHT_ID, descriptor, 100L)!!
        val firstResult = result(descriptor, firstClaim.attempt)

        assertTrue(firstStore.completeAttempt(firstClaim, descriptor, firstResult, 200L))

        val firstDraft = firstGateway.completedDreams!!.single()
        assertTrue(Regex("dream_[0-9a-f]{32}").matches(firstDraft.dreamId))
        assertEquals(null, firstDraft.generatedTitle)
        assertEquals("First second.", firstDraft.generatedText)
        assertEquals(7, firstDraft.sourceSpans.single().sourceTranscriptAttemptCount)
        assertEquals(0, firstDraft.sourceSpans.single().firstSegmentIndex)
        assertEquals(1, firstDraft.sourceSpans.single().lastSegmentIndex)

        val secondGateway = FakeGateway(recordFor(specs), rowsFor(specs), nextAttempt = 9)
        val secondStore = store(secondGateway, SECOND_RUN_ID)
        val secondClaim = secondStore.claimAttempt(NIGHT_ID, descriptor, 300L)!!
        assertTrue(
            secondStore.completeAttempt(
                secondClaim,
                descriptor,
                result(descriptor, secondClaim.attempt),
                400L,
            ),
        )
        assertEquals(firstDraft.dreamId, secondGateway.completedDreams!!.single().dreamId)
    }

    @Test
    fun completionRefusesSourceChangedAfterClaim() {
        val originalSpecs = listOf(SessionSpec("session-a", 0, 1, listOf("original")))
        val gateway = FakeGateway(recordFor(originalSpecs), rowsFor(originalSpecs))
        val store = store(gateway)
        val descriptor = descriptor(store.loadNightSource(NIGHT_ID)!!)
        val claim = store.claimAttempt(NIGHT_ID, descriptor, 100L)!!
        val changedSpecs = listOf(SessionSpec("session-a", 0, 2, listOf("changed")))
        gateway.record = recordFor(changedSpecs)
        gateway.sourceRows = rowsFor(changedSpecs)

        val saved = store.completeAttempt(
            claim,
            descriptor,
            result(descriptor, claim.attempt, segmentCount = 1),
            200L,
        )

        assertFalse(saved)
        assertEquals(0, gateway.completeCalls)
        assertNull(gateway.completedDreams)
    }

    @Test
    fun failurePersistsOnlySafeCodeRetryabilityAndKnownDetail() {
        val specs = listOf(SessionSpec("session-a", 0, 1, listOf("source")))
        val gateway = FakeGateway(recordFor(specs), rowsFor(specs))
        val store = store(gateway)
        val descriptor = descriptor(store.loadNightSource(NIGHT_ID)!!)
        val claim = store.claimAttempt(NIGHT_ID, descriptor, 100L)!!

        assertTrue(
            store.failAttempt(
                claim = claim,
                failure = PersistedEnrichmentFailure(
                    code = "inference_failed",
                    detail = "private dream content must never be copied",
                    retryable = true,
                ),
                completedAtEpochMillis = 200L,
            ),
        )

        assertEquals(
            "Local enrichment inference stopped. [code=inference_failed; retryable=true]",
            gateway.failedDetail,
        )
        assertFalse(gateway.failedDetail!!.contains("private dream content"))
    }

    @Test
    fun outputFailurePersistsOnlyAClosedContentFreeValidationReason() {
        val specs = listOf(SessionSpec("session-a", 0, 1, listOf("source")))
        val gateway = FakeGateway(recordFor(specs), rowsFor(specs))
        val store = store(gateway)
        val descriptor = descriptor(store.loadNightSource(NIGHT_ID)!!)
        val claim = store.claimAttempt(NIGHT_ID, descriptor, 100L)!!

        assertTrue(
            store.failAttempt(
                claim = claim,
                failure = PersistedEnrichmentFailure(
                    code = EnrichmentFailureCode.OUTPUT_INVALID.persistedValue,
                    detail = EnrichmentOutputReason.INCOMPLETE_COVERAGE.safeDetail,
                    retryable = true,
                ),
                completedAtEpochMillis = 200L,
            ),
        )

        assertEquals(
            "${EnrichmentOutputReason.INCOMPLETE_COVERAGE.safeDetail} " +
                "[code=output_invalid; retryable=true]",
            gateway.failedDetail,
        )
        assertEquals(
            EnrichmentOutputReason.INCOMPLETE_COVERAGE.safeDetail,
            persistedEnrichmentFailureDisplayDetail(gateway.failedDetail),
        )
    }

    private fun store(
        gateway: FakeGateway,
        runId: String = RUN_ID,
    ) = RoomNightEnrichmentStore(gateway) { runId }

    private fun descriptor(source: com.wivy.dreamlog.enrichment.EnrichmentNightSource):
        EnrichmentRunDescriptor {
        val input = OrderedNightTranscript.create(source.nightId, source.segments)
        return EnrichmentRunDescriptor(
            inputFingerprintSha256 = input.fingerprintSha256,
            engine = EnrichmentEngineMetadata(
                localeTag = "en-US",
                engineId = "engine",
                engineVersion = "engine-v1",
                runtimeId = "litert-lm",
                runtimeVersion = "0.9.0",
                modelId = "model",
                modelVersion = "revision",
                modelSha256 = hash('b'),
                backendId = "cpu",
                modelBytes = 497_664_000L,
                contextWindowTokens = 2_048,
                maxTotalTokens = 512,
            ),
            inferenceSkippedForEmptyInput = input.isEmpty,
        )
    }

    private fun result(
        descriptor: EnrichmentRunDescriptor,
        attempt: Int,
        segmentCount: Int = 2,
    ): ValidatedEnrichment {
        val ids = (0 until segmentCount).map { SourceSegmentId("session-a", it) }
        return ValidatedEnrichment(
            schemaVersion = ENRICHMENT_SCHEMA_VERSION,
            attempt = attempt,
            inputFingerprintSha256 = descriptor.inputFingerprintSha256,
            dreams = listOf(
                EnrichedDreamDraft(
                    order = 0,
                    kind = EnrichedDreamKind.DREAM,
                    generatedTitle = null,
                    generatedText = if (segmentCount == 1) "Original." else "First second.",
                    uncertain = false,
                    sourceSpans = listOf(
                        EnrichedSourceSpan(
                            role = DreamSourceRole.NARRATIVE,
                            sessionId = "session-a",
                            startSegmentIndex = 0,
                            endSegmentIndexInclusive = segmentCount - 1,
                            sourceStartMillis = 0L,
                            sourceEndMillis = segmentCount * 100L,
                            segmentIds = ids,
                        ),
                    ),
                ),
            ),
        )
    }

    private data class SessionSpec(
        val id: String,
        val order: Int,
        val attempt: Int,
        val texts: List<String>,
    )

    private fun recordFor(
        specs: List<SessionSpec>,
        transcriptionState: String = ProcessingState.COMPLETE,
        rawAudioState: String = if (specs.isEmpty()) RawAudioState.NONE else RawAudioState.RETAINED,
    ): NightWithDetails = NightWithDetails(
        night = NightEntity(
            nightId = NIGHT_ID,
            displayDate = "2026-07-31",
            startedAtEpochMillis = 0L,
            startedUtcOffsetSeconds = 0,
            endedAtEpochMillis = 1_000L,
            endedUtcOffsetSeconds = 0,
            captureState = NightCaptureState.ENDED,
            endReason = "owner_ended",
            interrupted = false,
            lastHeartbeatEpochMillis = null,
            lastHeartbeatUtcOffsetSeconds = null,
            reportedSessionCount = specs.size,
            reportedIncompleteSessionCount = 0,
            hadMicrophoneSilencing = false,
            hadAudioGap = false,
            rawAudioState = rawAudioState,
            transcriptionState = transcriptionState,
            transcriptionFailure = null,
            enrichmentState = ProcessingState.NOT_STARTED,
            enrichmentFailure = null,
            importWarning = null,
        ),
        sessions = specs.map(::sessionFor),
        events = emptyList<NightEventEntity>(),
        transcripts = specs.map(::transcriptFor),
        dreams = emptyList(),
    )

    private fun sessionFor(spec: SessionSpec) = CaptureSessionEntity(
        sessionId = spec.id,
        nightId = NIGHT_ID,
        captureOrder = spec.order,
        startedAtEpochMillis = spec.order * 1_000L,
        startedUtcOffsetSeconds = 0,
        finalizedAtEpochMillis = spec.order * 1_000L + 500L,
        finalizedUtcOffsetSeconds = 0,
        incompleteReason = null,
        audioFileName = "${spec.id}.wav",
        audioState = AudioEvidenceState.RETAINED,
        sampleRateHz = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
        sampleCount = 16_000L,
        preRollSampleCount = 0L,
        cueStartSample = null,
        cueEndSampleExclusive = null,
    )

    private fun transcriptFor(spec: SessionSpec) = SessionTranscriptWithSegments(
        transcript = SessionTranscriptEntity(
            sessionId = spec.id,
            nightId = NIGHT_ID,
            state = ProcessingState.COMPLETE,
            failureDetail = null,
            rawText = spec.texts.joinToString(" "),
            localeTag = "en-US",
            engineId = "asr",
            engineVersion = "5",
            runtimeId = "sherpa",
            runtimeVersion = "1",
            modelId = "zipformer",
            modelVersion = "revision",
            modelSha256 = hash('a'),
            attemptCount = spec.attempt,
            startedAtEpochMillis = 10L,
            completedAtEpochMillis = 20L,
        ),
        segments = spec.texts.mapIndexed { index, text ->
            TranscriptSegmentEntity(
                sessionId = spec.id,
                segmentIndex = index,
                sourceStartMillis = index * 100L,
                sourceEndMillis = (index + 1) * 100L,
                text = text,
            )
        },
    )

    private fun rowsFor(specs: List<SessionSpec>): List<EnrichmentSourceSegment> =
        specs.flatMap { spec ->
            spec.texts.mapIndexed { index, text ->
                EnrichmentSourceSegment(
                    nightId = NIGHT_ID,
                    sessionId = spec.id,
                    captureOrder = spec.order,
                    transcriptAttemptCount = spec.attempt,
                    segmentIndex = index,
                    sourceStartMillis = index * 100L,
                    sourceEndMillis = (index + 1) * 100L,
                    text = text,
                    narrationStartedAtEpochMillis = spec.order * 1_000L,
                    narrationStartedUtcOffsetSeconds = 0,
                )
            }
        }

    private class FakeGateway(
        var record: NightWithDetails,
        var sourceRows: List<EnrichmentSourceSegment>,
        private val nextAttempt: Int = 1,
    ) : RoomNightEnrichmentGateway {
        var startedProvenance: EnrichmentProvenance? = null
        var startedInputSha256: String? = null
        var run: EnrichmentRunEntity? = null
        var completeCalls: Int = 0
        var completedDreams: List<DreamDraft>? = null
        var failedDetail: String? = null

        override fun readNight(nightId: String): NightWithDetails? =
            record.takeIf { it.night.nightId == nightId }

        override fun readNightSourceSegments(nightId: String): List<EnrichmentSourceSegment> =
            sourceRows.filter { it.nightId == nightId }

        override fun startRun(
            runId: String,
            nightId: String,
            provenance: EnrichmentProvenance,
            inputSha256: String,
            startedAtEpochMillis: Long,
        ): Boolean {
            startedProvenance = provenance
            startedInputSha256 = inputSha256
            run = EnrichmentRunEntity(
                runId = runId,
                nightId = nightId,
                attemptNumber = nextAttempt,
                state = ProcessingState.RUNNING,
                failureDetail = null,
                localeTag = provenance.localeTag,
                engineId = provenance.engineId,
                engineVersion = provenance.engineVersion,
                runtimeId = provenance.runtimeId,
                runtimeVersion = provenance.runtimeVersion,
                backendId = provenance.backendId,
                modelId = provenance.modelId,
                modelVersion = provenance.modelVersion,
                modelSha256 = provenance.modelSha256,
                modelBytes = provenance.modelBytes,
                contextWindowTokens = provenance.contextWindowTokens,
                maxTotalTokens = provenance.maxTotalTokens,
                promptId = provenance.promptId,
                promptVersion = provenance.promptVersion,
                promptSha256 = provenance.promptSha256,
                outputSchemaVersion = provenance.outputSchemaVersion,
                inputSha256 = inputSha256,
                startedAtEpochMillis = startedAtEpochMillis,
                completedAtEpochMillis = null,
            )
            return true
        }

        override fun readRun(runId: String): EnrichmentRunEntity? = run?.takeIf { it.runId == runId }

        override fun completeRun(
            runId: String,
            expectedInputSha256: String,
            dreams: List<DreamDraft>,
            completedAtEpochMillis: Long,
        ): Boolean {
            completeCalls += 1
            completedDreams = dreams
            run = run?.copy(
                state = ProcessingState.COMPLETE,
                completedAtEpochMillis = completedAtEpochMillis,
            )
            return true
        }

        override fun markRunFailed(
            runId: String,
            failureDetail: String,
            completedAtEpochMillis: Long,
        ): Boolean {
            failedDetail = failureDetail
            run = run?.copy(
                state = ProcessingState.FAILED,
                failureDetail = failureDetail,
                completedAtEpochMillis = completedAtEpochMillis,
            )
            return true
        }
    }

    private companion object {
        const val NIGHT_ID = "night-1"
        const val RUN_ID = "12345678-1234-1234-1234-123456789abc"
        const val SECOND_RUN_ID = "87654321-4321-4321-4321-cba987654321"

        fun hash(character: Char): String = character.toString().repeat(64)
    }
}
