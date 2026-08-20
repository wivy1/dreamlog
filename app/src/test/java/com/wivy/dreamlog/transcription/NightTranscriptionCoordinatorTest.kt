package com.wivy.dreamlog.transcription

import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightDao
import com.wivy.dreamlog.history.NightEntity
import com.wivy.dreamlog.history.NightEventEntity
import com.wivy.dreamlog.history.NightTranscriptionCounts
import com.wivy.dreamlog.history.NightWithDetails
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.SessionTranscriptReplacementDraft
import com.wivy.dreamlog.history.SessionTranscriptEntity
import com.wivy.dreamlog.history.SessionTranscriptWithSegments
import com.wivy.dreamlog.history.TranscriptSegmentDraft
import com.wivy.dreamlog.history.TranscriptSegmentEntity
import com.wivy.dreamlog.history.TranscriptionDao
import com.wivy.dreamlog.history.TranscriptionProvenance
import com.wivy.dreamlog.history.TranscriptionSessionTarget
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NightTranscriptionCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun processNightClaimsEligibleFilesInCaptureOrderAndPublishesProgress() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_B,
                    captureOrder = 1,
                    preRollSampleCount = null,
                    cueStartSample = 30L,
                ),
                session(
                    ID_A,
                    captureOrder = 0,
                    preRollSampleCount = null,
                    cueStartSample = 10L,
                    cueEndSample = 20L,
                ),
                session(
                    ID_C,
                    captureOrder = 2,
                    audioState = AudioEvidenceState.MISSING,
                    finalizedAt = null,
                ),
            ),
        )
        val engine = FakeEngine()
        val progress = mutableListOf<NightTranscriptionProgress>()
        val coordinator = harness.coordinator(engine)

        val final = coordinator.processNight(NIGHT_ID, progress::add)

        assertEquals(listOf(fileName(ID_A), fileName(ID_B)), engine.calls.map { it.first.name })
        assertEquals(
            input(
                startSample = 10L,
                endSampleExclusive = 16_000L,
                contentStartSample = 10L,
                openingRecoveryFloorSample = 20L,
            ),
            engine.calls.first().second,
        )
        assertEquals(
            input(30L, 16_000L),
            engine.calls[1].second,
        )
        assertEquals(2, final.completedSessionCount)
        assertEquals(1, final.unavailableSessionCount)
        assertEquals(ProcessingState.FAILED, final.persistedState)
        assertFalse(final.requiresAppToRemainOpen)
        assertTrue(progress.any { it.activeSessionId == ID_A })
        assertTrue(progress.any { it.activeSessionId == ID_B })

        val transcripts = harness.transcriptionDao.readNightTranscripts(NIGHT_ID)
        assertEquals(2, transcripts.size)
        assertTrue(transcripts.all { it.transcript.engineId == "fake-engine" })
        assertTrue(transcripts.all { it.transcript.modelSha256 == "a".repeat(64) })
        assertTrue(
            transcripts.all {
                it.segments.map { segment -> segment.segmentIndex } == listOf(0, 1)
            },
        )
    }

    @Test
    fun activeCaptureIsRejectedBeforeClaimOrEngineCall() {
        val harness = harness(
            night = night(
                captureState = NightCaptureState.ACTIVE,
                endedAtEpochMillis = null,
            ),
            sessions = listOf(session(ID_A, captureOrder = 0)),
        )
        val engine = FakeEngine()

        val failure = runCatching {
            harness.coordinator(engine).processNight(NIGHT_ID)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(engine.calls.isEmpty())
        assertTrue(harness.transcriptionDao.readNightTranscripts(NIGHT_ID).isEmpty())
    }

    @Test
    fun startsAfterKnownPreRollWhenCueMetadataIsMissingAndDoesNotGuessWithoutMetadata() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    sampleCount = 64_000L,
                    preRollSampleCount = 32_000L,
                ),
                session(
                    ID_B,
                    captureOrder = 1,
                    sampleCount = 64_000L,
                    preRollSampleCount = null,
                ),
            ),
        )
        val engine = FakeEngine()

        harness.coordinator(engine).processNight(NIGHT_ID)

        assertEquals(
            input(32_000L, 64_000L),
            engine.calls[0].second,
        )
        assertEquals(
            input(0L, 64_000L),
            engine.calls[1].second,
        )
    }

    @Test
    fun exactSessionWakePhraseEnablesFullPreRollContextWithoutMovingContentBoundary() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    sampleCount = 64_000L,
                    preRollSampleCount = 32_000L,
                ),
            ),
            events = listOf(sessionStartedEvent(ID_A, "dream_log")),
        )
        val engine = FakeEngine()

        harness.coordinator(engine).processNight(NIGHT_ID)

        assertEquals(
            input(
                startSample = 0L,
                endSampleExclusive = 64_000L,
                contentStartSample = 32_000L,
                triggeringWakePhrase = TriggeringWakePhrase.DREAM_LOG,
            ),
            engine.calls.single().second,
        )
    }

    @Test
    fun ambiguousSessionStartEvidenceKeepsTheNarrowPostDetectionRange() {
        val first = sessionStartedEvent(ID_A, "dream_log")
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    sampleCount = 64_000L,
                    preRollSampleCount = 32_000L,
                ),
            ),
            events = listOf(first, first.copy(eventId = "duplicate-session-start")),
        )
        val engine = FakeEngine()

        harness.coordinator(engine).processNight(NIGHT_ID)

        assertEquals(input(32_000L, 64_000L), engine.calls.single().second)
    }

    @Test
    fun zeroPreRollFallsBackToTheKnownLegacyCueStart() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    sampleCount = 64_000L,
                    preRollSampleCount = 0L,
                    cueStartSample = 16_000L,
                ),
            ),
        )
        val engine = FakeEngine()

        harness.coordinator(engine).processNight(NIGHT_ID)

        assertEquals(
            input(16_000L, 64_000L),
            engine.calls.single().second,
        )
    }

    @Test
    fun modernSessionUsesCompleteWakeContextAndKeepsTheCompleteFinalizedTail() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    incompleteReason = null,
                    sampleCount = 400_000L,
                    preRollSampleCount = 32_000L,
                    cueStartSample = 35_000L,
                    cueEndSample = 56_000L,
                    automaticSilenceTailSampleCount = 160_256L,
                ),
            ),
            events = listOf(sessionStartedEvent(ID_A, "dream_log")),
        )
        val engine = FakeEngine()

        harness.coordinator(engine).processNight(NIGHT_ID)

        assertEquals(
            input(
                startSample = 0L,
                endSampleExclusive = 400_000L,
                contentStartSample = 35_000L,
                triggeringWakePhrase = TriggeringWakePhrase.DREAM_LOG,
                openingRecoveryFloorSample = 56_000L,
            ),
            engine.calls.single().second,
        )
    }

    @Test
    fun completeSessionWithOnlyThePostCueSilenceTailSkipsRecognition() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    incompleteReason = null,
                    sampleCount = 218_256L,
                    preRollSampleCount = 32_000L,
                    cueStartSample = 35_000L,
                    cueEndSample = 58_000L,
                    automaticSilenceTailSampleCount = 160_768L,
                ),
            ),
            events = listOf(sessionStartedEvent(ID_A, "dream_log")),
        )
        val engine = FakeEngine()

        harness.coordinator(engine).processNight(NIGHT_ID)

        assertEquals(
            input(58_000L, 58_000L),
            engine.calls.single().second,
        )
    }

    @Test
    fun modernCompleteSessionWithoutTailMetadataStillKeepsTheFullSourceTail() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    incompleteReason = null,
                    sampleCount = 378_000L,
                    preRollSampleCount = 32_000L,
                    cueStartSample = 35_000L,
                    cueEndSample = 58_000L,
                    automaticSilenceTailSampleCount = null,
                ),
            ),
        )
        val engine = FakeEngine()

        harness.coordinator(engine).processNight(NIGHT_ID)

        assertEquals(
            input(
                startSample = 35_000L,
                endSampleExclusive = 378_000L,
                contentStartSample = 35_000L,
                openingRecoveryFloorSample = 58_000L,
            ),
            engine.calls.single().second,
        )
    }

    @Test
    fun speechAnalyzerCannotCropAnIncompleteNarrationButStillCloses() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    incompleteReason = "night_ended",
                    sampleCount = 500_000L,
                    preRollSampleCount = 32_000L,
                    cueStartSample = 35_072L,
                    cueEndSample = 58_624L,
                ),
            ),
        )
        val analyzer = FakeSpeechBoundaryAnalyzer(
            SessionSpeechBoundaryResult(
                speechDetected = true,
                leadingNonSpeechSamples = 39_424L,
                trailingNonSpeechSamples = 285_184L,
            ),
        )
        val engine = FakeEngine()
        val coordinator = harness.coordinator(engine, analyzer)

        coordinator.processNight(NIGHT_ID)
        coordinator.close()

        assertEquals(
            input(
                startSample = 35_072L,
                endSampleExclusive = 500_000L,
                contentStartSample = 35_072L,
                openingRecoveryFloorSample = 58_624L,
            ),
            engine.calls.single().second,
        )
        assertTrue(analyzer.analysisStarts.isEmpty())
        assertTrue(analyzer.closed)
    }

    @Test
    fun incompleteSessionStartsAfterCueAndKeepsTheCompleteIncompleteTail() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    incompleteReason = "night_ended",
                    sampleCount = 500_000L,
                    preRollSampleCount = 32_000L,
                    cueStartSample = 35_072L,
                    cueEndSample = 58_624L,
                ),
            ),
        )
        val analyzer = FakeSpeechBoundaryAnalyzer(
            SessionSpeechBoundaryResult(
                speechDetected = true,
                leadingNonSpeechSamples = 1_024L,
                trailingNonSpeechSamples = 100_000L,
            ),
        )
        val engine = FakeEngine()

        harness.coordinator(engine, analyzer).processNight(NIGHT_ID)

        assertEquals(
            input(
                startSample = 35_072L,
                endSampleExclusive = 500_000L,
                contentStartSample = 35_072L,
                openingRecoveryFloorSample = 58_624L,
            ),
            engine.calls.single().second,
        )
        assertTrue(analyzer.analysisStarts.isEmpty())
    }

    @Test
    fun analyzerNoSpeechResultCannotDiscardQuietNarration() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    incompleteReason = "night_ended",
                    sampleCount = 500_000L,
                    preRollSampleCount = 32_000L,
                    cueStartSample = 35_072L,
                    cueEndSample = 58_624L,
                ),
            ),
        )
        val analyzer = FakeSpeechBoundaryAnalyzer(
            SessionSpeechBoundaryResult(
                speechDetected = false,
                leadingNonSpeechSamples = 441_376L,
                trailingNonSpeechSamples = 441_376L,
            ),
        )
        val engine = FakeEngine()

        harness.coordinator(engine, analyzer).processNight(NIGHT_ID)

        assertEquals(
            input(
                startSample = 35_072L,
                endSampleExclusive = 500_000L,
                contentStartSample = 35_072L,
                openingRecoveryFloorSample = 58_624L,
            ),
            engine.calls.single().second,
        )
        assertTrue(analyzer.analysisStarts.isEmpty())
    }

    @Test
    fun longBoundedEngineReceivesAbsoluteNonSpeechHintsWithoutCroppingSource() {
        val harness = harness(
            sessions = listOf(
                session(
                    ID_A,
                    captureOrder = 0,
                    sampleCount = 640_000L,
                    preRollSampleCount = 32_000L,
                ),
            ),
        )
        val observedRanges = listOf(
            SessionNonSpeechRange(400_000L, 416_000L),
            SessionNonSpeechRange(630_000L, 650_000L),
        )
        val analyzer = FakeSpeechBoundaryAnalyzer(
            SessionSpeechBoundaryResult(
                speechDetected = true,
                leadingNonSpeechSamples = 0L,
                trailingNonSpeechSamples = 10_000L,
                nonSpeechRanges = observedRanges,
            ),
        )
        val engine = FakeEngine().apply { decodeLimit = 480_000L }

        harness.coordinator(engine, analyzer).processNight(NIGHT_ID)

        assertEquals(listOf(32_000L), analyzer.analysisStarts)
        assertEquals(
            input(
                startSample = 32_000L,
                endSampleExclusive = 640_000L,
                observedNonSpeechRanges = listOf(
                    SessionNonSpeechRange(400_000L, 416_000L),
                    SessionNonSpeechRange(630_000L, 640_000L),
                ),
            ),
            engine.calls.single().second,
        )
    }

    @Test
    fun failedSessionRetriesInPlaceWithoutLosingAudioOrDuplicatingSegments() {
        val harness = harness(sessions = listOf(session(ID_A, captureOrder = 0)))
        val engine = FakeEngine().apply { failuresRemaining = 1 }
        val coordinator = harness.coordinator(engine)

        val failed = coordinator.processNight(NIGHT_ID)

        assertEquals(1, failed.failedSessionCount)
        assertEquals(listOf(ID_A), failed.retryableSessionIds)
        assertTrue(harness.audioFile(ID_A).isFile)
        val firstAttempt = harness.transcriptionDao.readSessionTranscript(ID_A)!!
        assertEquals(1, firstAttempt.transcript.attemptCount)
        assertTrue(firstAttempt.segments.isEmpty())
        assertEquals(
            "Local transcription failed (local_inference_failed). The retained audio was kept; " +
                "resume transcription.",
            firstAttempt.transcript.failureDetail,
        )
        assertFalse(firstAttempt.transcript.failureDetail!!.contains("forced local inference"))

        val complete = coordinator.retrySession(NIGHT_ID, ID_A)

        assertEquals(1, complete.completedSessionCount)
        assertEquals(0, complete.failedSessionCount)
        assertFalse(complete.canRetry)
        val onlyRecord = harness.transcriptionDao.readNightTranscripts(NIGHT_ID).single()
        assertEquals(2, onlyRecord.transcript.attemptCount)
        assertEquals(ProcessingState.COMPLETE, onlyRecord.transcript.state)
        assertEquals(listOf(0, 1), onlyRecord.segments.map { it.segmentIndex })
        assertTrue(harness.audioFile(ID_A).isFile)
    }

    @Test
    fun resumeNightSkipsCompletedRetriesFailedOnceAndClaimsPendingWithoutDuplicates() {
        val harness = harness(
            sessions = listOf(
                session(ID_A, captureOrder = 0),
                session(ID_B, captureOrder = 1),
                session(ID_C, captureOrder = 2),
            ),
        )
        seedCompletedTranscript(harness, ID_A, "saved A")
        assertTrue(harness.transcriptionDao.startSession(ID_B, provenance(), 300L))
        assertTrue(
            harness.transcriptionDao.markSessionFailed(
                sessionId = ID_B,
                failureDetail = "interrupted",
                completedAtEpochMillis = 301L,
            ),
        )
        val engine = FakeEngine()

        val final = harness.coordinator(engine).resumeNight(NIGHT_ID)

        assertEquals(listOf(fileName(ID_B), fileName(ID_C)), engine.calls.map { it.first.name })
        assertEquals(
            "saved A",
            harness.transcriptionDao.readSessionTranscript(ID_A)!!.transcript.rawText,
        )
        assertEquals(
            1,
            harness.transcriptionDao.readSessionTranscript(ID_A)!!.transcript.attemptCount,
        )
        assertEquals(
            2,
            harness.transcriptionDao.readSessionTranscript(ID_B)!!.transcript.attemptCount,
        )
        assertEquals(
            1,
            harness.transcriptionDao.readSessionTranscript(ID_C)!!.transcript.attemptCount,
        )
        assertEquals(3, final.completedSessionCount)
        assertEquals(0, final.pendingSessionCount)
        assertTrue(final.retryableSessionIds.isEmpty())
    }

    @Test
    fun thermalGateFinishesClaimedSessionThenDefersBeforeNextAndResumeKeepsProgress() {
        val harness = harness(
            sessions = listOf(
                session(ID_A, captureOrder = 0),
                session(ID_B, captureOrder = 1),
                session(ID_C, captureOrder = 2),
            ),
        )
        var checks = 0
        val gate = TranscriptionContinuationGate {
            checks += 1
            if (checks == 1) {
                TranscriptionContinuationDecision.Continue
            } else {
                TranscriptionContinuationDecision.Defer(
                    reason = TranscriptionPauseReason.THERMAL,
                    message = "waiting to cool",
                    signal = TranscriptionThermalSignal(
                        platformStatus = 2,
                        batteryTemperatureDeciCelsius = 349,
                    ),
                )
            }
        }
        val firstEngine = FakeEngine()

        val paused = harness.coordinator(firstEngine, continuationGate = gate)
            .processNight(NIGHT_ID)

        assertEquals(listOf(fileName(ID_A)), firstEngine.calls.map { it.first.name })
        assertEquals(1, paused.completedSessionCount)
        assertEquals(2, paused.pendingSessionCount)
        assertEquals(TranscriptionPauseReason.THERMAL, paused.pauseReason)
        assertEquals("waiting to cool", paused.pauseMessage)
        assertEquals(2, paused.platformThermalStatus)
        assertEquals(34.9, paused.batteryTemperatureCelsius!!, 0.0)

        val resumeEngine = FakeEngine()
        val complete = harness.coordinator(resumeEngine).resumeNight(NIGHT_ID)

        assertEquals(
            listOf(fileName(ID_B), fileName(ID_C)),
            resumeEngine.calls.map { it.first.name },
        )
        assertEquals(3, complete.completedSessionCount)
        assertEquals(
            1,
            harness.transcriptionDao.readSessionTranscript(ID_A)!!.transcript.attemptCount,
        )
    }

    @Test
    fun explicitResumeAttemptsAPersistedFailureOnlyOncePerOperation() {
        val harness = harness(sessions = listOf(session(ID_A, captureOrder = 0)))
        assertTrue(harness.transcriptionDao.startSession(ID_A, provenance(), 300L))
        assertTrue(
            harness.transcriptionDao.markSessionFailed(
                sessionId = ID_A,
                failureDetail = "interrupted",
                completedAtEpochMillis = 301L,
            ),
        )
        val engine = FakeEngine().apply { failuresRemaining = 2 }

        val stillPaused = harness.coordinator(engine).resumeNight(NIGHT_ID)

        assertEquals(1, engine.calls.size)
        assertEquals(1, stillPaused.failedSessionCount)
        assertEquals(listOf(ID_A), stillPaused.retryableSessionIds)
        assertEquals(
            2,
            harness.transcriptionDao.readSessionTranscript(ID_A)!!.transcript.attemptCount,
        )
    }

    @Test
    fun progressReadFailureDoesNotAbortOrRewriteACompletedAttempt() {
        val harness = harness(sessions = listOf(session(ID_A, captureOrder = 0)))
        harness.nightDao.progressReadFailureStatesToArm = listOf(
            ProcessingState.RUNNING,
            ProcessingState.COMPLETE,
        )

        val final = harness.coordinator(FakeEngine()).processNight(NIGHT_ID)

        assertEquals(1, final.completedSessionCount)
        val transcript = harness.transcriptionDao.readSessionTranscript(ID_A)!!.transcript
        assertEquals(ProcessingState.COMPLETE, transcript.state)
        assertEquals(1, transcript.attemptCount)
        assertEquals(null, transcript.failureDetail)
    }

    @Test
    fun retranscriptionKeepsCompletedResultOnFailureAndReplacesItOnSuccess() {
        val harness = harness(sessions = listOf(session(ID_A, captureOrder = 0)))
        assertTrue(harness.transcriptionDao.startSession(ID_A, provenance(), 100L))
        assertTrue(
            harness.transcriptionDao.markSessionSucceeded(
                sessionId = ID_A,
                rawText = "prior result",
                segments = listOf(TranscriptSegmentDraft(100L, 200L, "prior result")),
                completedAtEpochMillis = 200L,
            ),
        )
        val failingEngine = FakeEngine().apply { failuresRemaining = 1 }

        val failure = runCatching {
            harness.coordinator(failingEngine).retranscribeSession(NIGHT_ID, ID_A)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        val preserved = harness.transcriptionDao.readSessionTranscript(ID_A)!!
        assertEquals("prior result", preserved.transcript.rawText)
        assertEquals(1, preserved.transcript.attemptCount)
        assertEquals(listOf("prior result"), preserved.segments.map { it.text })
        assertTrue(harness.audioFile(ID_A).isFile)

        val progress = mutableListOf<NightTranscriptionProgress>()
        val complete = harness.coordinator(FakeEngine()).retranscribeSession(
            NIGHT_ID,
            ID_A,
            progress::add,
        )

        assertEquals(1, complete.completedSessionCount)
        assertTrue(
            progress.any {
                it.activeSessionId == ID_A &&
                    it.completedSessionCount == 1 &&
                    it.runningSessionCount == 0
            },
        )
        val replaced = harness.transcriptionDao.readSessionTranscript(ID_A)!!
        assertEquals("text for ${fileName(ID_A)}", replaced.transcript.rawText)
        assertEquals(2, replaced.transcript.attemptCount)
        assertEquals(listOf(0, 1), replaced.segments.map { it.segmentIndex })
        assertTrue(harness.audioFile(ID_A).isFile)
    }

    @Test
    fun retranscribeNightReplacesEveryCompletedSessionAfterAllInferenceSucceeds() {
        val harness = harness(
            sessions = listOf(
                session(ID_A, captureOrder = 0),
                session(ID_B, captureOrder = 1),
            ),
        )
        seedCompletedTranscript(harness, ID_A, "prior A")
        seedCompletedTranscript(harness, ID_B, "prior B")
        val engine = FakeEngine()
        val progress = mutableListOf<NightTranscriptionProgress>()

        val complete = harness.coordinator(engine).retranscribeNight(NIGHT_ID, progress::add)

        assertEquals(listOf(fileName(ID_A), fileName(ID_B)), engine.calls.map { it.first.name })
        assertEquals(2, complete.completedSessionCount)
        assertTrue(progress.any { it.activeSessionId == ID_A })
        assertTrue(progress.any { it.activeSessionId == ID_B })
        listOf(ID_A, ID_B).forEach { sessionId ->
            val replaced = harness.transcriptionDao.readSessionTranscript(sessionId)!!
            assertEquals("text for ${fileName(sessionId)}", replaced.transcript.rawText)
            assertEquals(2, replaced.transcript.attemptCount)
            assertEquals(listOf(0, 1), replaced.segments.map { it.segmentIndex })
            assertTrue(harness.audioFile(sessionId).isFile)
        }
    }

    @Test
    fun postCommitProgressReadFailureDoesNotMisreportSuccessfulNightRetranscription() {
        val harness = harness(
            sessions = listOf(
                session(ID_A, captureOrder = 0),
                session(ID_B, captureOrder = 1),
            ),
        )
        seedCompletedTranscript(harness, ID_A, "prior A")
        seedCompletedTranscript(harness, ID_B, "prior B")
        harness.transcriptionDao.failProgressReadAfterNightReplacement = true

        val complete = harness.coordinator(FakeEngine()).retranscribeNight(NIGHT_ID)

        assertEquals(2, complete.completedSessionCount)
        assertEquals(null, complete.activeSessionId)
        listOf(ID_A, ID_B).forEach { sessionId ->
            val replaced = harness.transcriptionDao.readSessionTranscript(sessionId)!!
            assertEquals("text for ${fileName(sessionId)}", replaced.transcript.rawText)
            assertEquals(2, replaced.transcript.attemptCount)
        }
    }

    @Test
    fun failedLaterNightRetranscriptionPreservesEveryPriorCompletedResult() {
        val harness = harness(
            sessions = listOf(
                session(ID_A, captureOrder = 0),
                session(ID_B, captureOrder = 1),
            ),
        )
        seedCompletedTranscript(harness, ID_A, "prior A")
        seedCompletedTranscript(harness, ID_B, "prior B")
        val engine = FakeEngine().apply { failOnCallNumber = 2 }

        val failure = runCatching {
            harness.coordinator(engine).retranscribeNight(NIGHT_ID)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(fileName(ID_A), fileName(ID_B)), engine.calls.map { it.first.name })
        listOf(ID_A to "prior A", ID_B to "prior B").forEach { (sessionId, priorText) ->
            val preserved = harness.transcriptionDao.readSessionTranscript(sessionId)!!
            assertEquals(priorText, preserved.transcript.rawText)
            assertEquals(1, preserved.transcript.attemptCount)
            assertEquals(listOf(priorText), preserved.segments.map { it.text })
            assertTrue(harness.audioFile(sessionId).isFile)
        }
    }

    @Test
    fun startupRecoveryDoesNotRequireAnEngineAndMakesRunningAttemptRetryable() {
        val harness = harness(sessions = listOf(session(ID_A, captureOrder = 0)))
        assertTrue(
            harness.transcriptionDao.startSession(
                sessionId = ID_A,
                provenance = provenance(),
                startedAtEpochMillis = 300L,
            ),
        )

        val recovered = TranscriptionAttemptRecovery(
            transcriptionDao = harness.transcriptionDao,
            clock = { 200L },
        ).recover()

        assertEquals(1, recovered)
        val transcript = harness.transcriptionDao.readSessionTranscript(ID_A)!!.transcript
        assertEquals(ProcessingState.FAILED, transcript.state)
        assertEquals(TranscriptionAttemptRecovery.FAILURE_DETAIL, transcript.failureDetail)
        assertEquals(1, transcript.attemptCount)
        assertEquals(300L, transcript.completedAtEpochMillis)
    }

    private fun harness(
        night: NightEntity = night(),
        sessions: List<CaptureSessionEntity>,
        events: List<NightEventEntity> = emptyList(),
    ): Harness {
        val audioRoot = temporaryFolder.newFolder("audio-${System.nanoTime()}")
        val nightDirectory = File(audioRoot, NIGHT_ID).apply { mkdirs() }
        sessions.filter { it.audioState == AudioEvidenceState.RETAINED }.forEach { session ->
            File(nightDirectory, session.audioFileName).writeBytes(byteArrayOf(1))
        }
        val nightDao = FakeNightDao(night, sessions, events)
        val transcriptionDao = FakeTranscriptionDao(nightDao)
        nightDao.transcriptsForNight = transcriptionDao::readNightTranscripts
        return Harness(audioRoot, nightDao, transcriptionDao)
    }

    private fun seedCompletedTranscript(
        harness: Harness,
        sessionId: String,
        rawText: String,
    ) {
        assertTrue(harness.transcriptionDao.startSession(sessionId, provenance(), 100L))
        assertTrue(
            harness.transcriptionDao.markSessionSucceeded(
                sessionId = sessionId,
                rawText = rawText,
                segments = listOf(TranscriptSegmentDraft(100L, 200L, rawText)),
                completedAtEpochMillis = 200L,
            ),
        )
    }

    private data class Harness(
        val audioRoot: File,
        val nightDao: FakeNightDao,
        val transcriptionDao: FakeTranscriptionDao,
    ) {
        fun coordinator(
            engine: TranscriptionEngine,
            speechBoundaryAnalyzer: SessionSpeechBoundaryAnalyzer? = null,
            continuationGate: TranscriptionContinuationGate =
                TranscriptionContinuationGate.ALWAYS_CONTINUE,
        ) = NightTranscriptionCoordinator(
            nightDao = nightDao,
            transcriptionDao = transcriptionDao,
            audioRootDirectory = audioRoot,
            engine = engine,
            speechBoundaryAnalyzer = speechBoundaryAnalyzer,
            continuationGate = continuationGate,
            clock = object : () -> Long {
                private var value = 1_000L
                override fun invoke(): Long = value++
            },
        )

        fun audioFile(sessionId: String): File =
            File(File(audioRoot, NIGHT_ID), fileName(sessionId))
    }

    private class FakeEngine : TranscriptionEngine {
        override val metadata = TranscriptionEngineMetadata(
            localeTag = "en-US",
            engineId = "fake-engine",
            engineVersion = "1",
            runtimeId = "fake-runtime",
            runtimeVersion = "1",
            modelId = "fake-model",
            modelVersion = "1",
            modelSha256 = "a".repeat(64),
        )
        val calls = mutableListOf<Pair<File, TranscriptionInput>>()
        var decodeLimit: Long? = null
        var failuresRemaining = 0
        var failOnCallNumber: Int? = null

        override val maximumDecodeSampleCount: Long?
            get() = decodeLimit

        override fun transcribe(
            audioFile: File,
            input: TranscriptionInput?,
        ): TranscriptionResult {
            calls += audioFile to checkNotNull(input)
            if (calls.size == failOnCallNumber || failuresRemaining > 0) {
                if (failuresRemaining > 0) failuresRemaining -= 1
                error("forced local inference failure")
            }
            val text = "text for ${audioFile.name}"
            return TranscriptionResult(
                rawText = text,
                segments = listOf(
                    TranscriptionSegment(100L, 300L, "text"),
                    TranscriptionSegment(300L, 500L, "for ${audioFile.name}"),
                ),
            )
        }

        override fun close() = Unit
    }

    private class FakeSpeechBoundaryAnalyzer(
        private val result: SessionSpeechBoundaryResult,
    ) : SessionSpeechBoundaryAnalyzer {
        val analysisStarts = mutableListOf<Long>()
        var closed = false

        override fun analyze(
            audioFile: File,
            analysisStartSample: Long,
        ): SessionSpeechBoundaryResult {
            check(!closed)
            analysisStarts += analysisStartSample
            return result
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeNightDao(
        initialNight: NightEntity,
        initialSessions: List<CaptureSessionEntity>,
        private val events: List<NightEventEntity>,
    ) : NightDao() {
        private var night = initialNight
        private val sessions = initialSessions.associateByTo(linkedMapOf()) { it.sessionId }
        var transcriptsForNight: (String) -> List<SessionTranscriptWithSegments> = { emptyList() }
        var progressReadFailureStatesToArm: List<String> = emptyList()
        private var failNextRead = false

        override fun upsertNight(night: NightEntity) {
            this.night = night
        }

        override fun upsertSessions(sessions: List<CaptureSessionEntity>) {
            sessions.forEach { this.sessions[it.sessionId] = it }
        }

        override fun insertEvents(events: List<NightEventEntity>) = Unit

        override fun readHistory(): List<NightWithDetails> = listOfNotNull(readNight(night.nightId))

        override fun readNight(nightId: String): NightWithDetails? =
            night.takeIf { it.nightId == nightId }?.let {
                if (failNextRead) {
                    failNextRead = false
                    error("forced progress read failure")
                }
                NightWithDetails(
                    night = it,
                    sessions = sessions.values.toList(),
                    events = events,
                    transcripts = transcriptsForNight(nightId),
                )
            }

        override fun readUnfinishedNights(): List<NightEntity> =
            listOf(night).filter {
                it.captureState == NightCaptureState.STARTING ||
                    it.captureState == NightCaptureState.ACTIVE
            }

        override fun updateNightSessionAudioState(
            nightId: String,
            audioState: String,
        ): Int {
            val targets = sessions.values.filter { it.nightId == nightId }
            targets.forEach { session ->
                sessions[session.sessionId] = session.copy(audioState = audioState)
            }
            return targets.size
        }

        override fun updateNightRawAudioState(
            nightId: String,
            rawAudioState: String,
        ): Int {
            if (night.nightId != nightId) return 0
            night = night.copy(rawAudioState = rawAudioState)
            return 1
        }

        override fun deleteNight(nightId: String): Int =
            if (night.nightId == nightId) 1 else 0

        fun session(sessionId: String): CaptureSessionEntity? = sessions[sessionId]

        fun updateTranscriptionState(state: String, failureDetail: String?) {
            night = night.copy(
                transcriptionState = state,
                transcriptionFailure = failureDetail,
            )
            if (progressReadFailureStatesToArm.firstOrNull() == state) {
                progressReadFailureStatesToArm = progressReadFailureStatesToArm.drop(1)
                failNextRead = true
            }
        }

        fun armNextReadFailure() {
            failNextRead = true
        }
    }

    private class FakeTranscriptionDao(
        private val nightDao: FakeNightDao,
    ) : TranscriptionDao() {
        private val transcripts = linkedMapOf<String, SessionTranscriptEntity>()
        private val segmentsBySession = linkedMapOf<String, MutableList<TranscriptSegmentEntity>>()
        var failProgressReadAfterNightReplacement = false

        override fun replaceCompletedNight(
            nightId: String,
            provenance: TranscriptionProvenance,
            replacements: List<SessionTranscriptReplacementDraft>,
        ): Boolean {
            val replaced = super.replaceCompletedNight(nightId, provenance, replacements)
            if (replaced && failProgressReadAfterNightReplacement) {
                nightDao.armNextReadFailure()
            }
            return replaced
        }

        override fun readSessionTranscript(sessionId: String): SessionTranscriptWithSegments? =
            transcripts[sessionId]?.let { transcript ->
                SessionTranscriptWithSegments(
                    transcript = transcript,
                    segments = segmentsBySession[sessionId].orEmpty().sortedBy { it.segmentIndex },
                )
            }

        override fun readNightTranscripts(nightId: String): List<SessionTranscriptWithSegments> =
            transcripts.values
                .filter { it.nightId == nightId }
                .map { readSessionTranscript(it.sessionId)!! }

        override fun readSessionTarget(sessionId: String): TranscriptionSessionTarget? {
            val session = nightDao.session(sessionId) ?: return null
            val night = nightDao.readNight(session.nightId)?.night ?: return null
            return TranscriptionSessionTarget(
                sessionId = session.sessionId,
                nightId = session.nightId,
                audioState = session.audioState,
                finalizedAtEpochMillis = session.finalizedAtEpochMillis,
                captureState = night.captureState,
                endedAtEpochMillis = night.endedAtEpochMillis,
            )
        }

        override fun readTranscriptEntity(sessionId: String): SessionTranscriptEntity? =
            transcripts[sessionId]

        override fun readStaleRunningTranscripts(
            startedBeforeEpochMillis: Long,
        ): List<SessionTranscriptEntity> = transcripts.values.filter {
            it.state == ProcessingState.RUNNING &&
                it.startedAtEpochMillis <= startedBeforeEpochMillis
        }

        override fun upsertTranscript(transcript: SessionTranscriptEntity) {
            transcripts[transcript.sessionId] = transcript
        }

        override fun insertSegments(segments: List<TranscriptSegmentEntity>) {
            segments.groupBy { it.sessionId }.forEach { (sessionId, values) ->
                segmentsBySession.getOrPut(sessionId, ::mutableListOf).addAll(values)
            }
        }

        override fun deleteSegments(sessionId: String) {
            segmentsBySession.remove(sessionId)
        }

        override fun readOwnerEditedDreamCount(nightId: String): Int = 0

        override fun markCompletedEnrichmentRunsSuperseded(nightId: String): Int = 0

        override fun deleteDreamsAfterTranscriptReplacement(nightId: String): Int = 0

        override fun markEnrichmentStaleAfterTranscriptReplacement(
            nightId: String,
            failureDetail: String,
        ) = Unit

        override fun readNightCounts(nightId: String): NightTranscriptionCounts {
            val sessions = nightDao.readNight(nightId)?.sessions.orEmpty()
            val eligible = sessions.filter {
                it.audioState == AudioEvidenceState.RETAINED &&
                    it.finalizedAtEpochMillis != null
            }
            val states = eligible.mapNotNull { transcripts[it.sessionId]?.state }
            return NightTranscriptionCounts(
                eligibleSessionCount = eligible.size,
                unavailableSessionCount = sessions.size - eligible.size,
                runningSessionCount = states.count { it == ProcessingState.RUNNING },
                failedSessionCount = states.count { it == ProcessingState.FAILED },
                completeSessionCount = states.count { it == ProcessingState.COMPLETE },
            )
        }

        override fun readFirstNightFailure(nightId: String): String? =
            transcripts.values.firstOrNull {
                it.nightId == nightId && it.state == ProcessingState.FAILED
            }?.failureDetail

        override fun updateNightState(
            nightId: String,
            state: String,
            failureDetail: String?,
        ) {
            check(nightId == NIGHT_ID)
            nightDao.updateTranscriptionState(state, failureDetail)
        }
    }

    private companion object {
        const val NIGHT_ID = "night-1"
        const val ID_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ID_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val ID_C = "cccccccccccccccccccccccccccccccc"

        fun fileName(sessionId: String) = "a_$sessionId.wav"

        fun input(
            startSample: Long,
            endSampleExclusive: Long,
            contentStartSample: Long = startSample,
            triggeringWakePhrase: TriggeringWakePhrase? = null,
            openingRecoveryFloorSample: Long? = null,
            observedNonSpeechRanges: List<SessionNonSpeechRange> = emptyList(),
        ) = TranscriptionInput(
            acousticRange = Pcm16WavSource.RecognitionRange(
                startSample = startSample,
                endSampleExclusive = endSampleExclusive,
            ),
            contentStartSample = contentStartSample,
            triggeringWakePhrase = triggeringWakePhrase,
            openingRecoveryFloorSample = openingRecoveryFloorSample,
            observedNonSpeechRanges = observedNonSpeechRanges,
        )

        fun sessionStartedEvent(sessionId: String, phrase: String) = NightEventEntity(
            nightId = NIGHT_ID,
            eventId = "session-started-$sessionId",
            sessionId = sessionId,
            epochMillis = 200L,
            utcOffsetSeconds = 0,
            type = "session_started",
            encodedAttributes = mapOf(
                "phrase" to phrase,
                "session_id" to sessionId,
            ).toSortedMap().entries.joinToString(";") { (key, value) ->
                "$key=${
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                        value.toByteArray(StandardCharsets.UTF_8),
                    )
                }"
            },
        )

        fun provenance() = TranscriptionProvenance(
            localeTag = "en-US",
            engineId = "fake-engine",
            engineVersion = "1",
            runtimeId = "fake-runtime",
            runtimeVersion = "1",
            modelId = "fake-model",
            modelVersion = "1",
            modelSha256 = "a".repeat(64),
        )

        fun night(
            captureState: String = NightCaptureState.ENDED,
            endedAtEpochMillis: Long? = 900L,
        ) = NightEntity(
            nightId = NIGHT_ID,
            displayDate = "2026-07-31",
            startedAtEpochMillis = 100L,
            startedUtcOffsetSeconds = 0,
            endedAtEpochMillis = endedAtEpochMillis,
            endedUtcOffsetSeconds = endedAtEpochMillis?.let { 0 },
            captureState = captureState,
            endReason = null,
            interrupted = captureState == NightCaptureState.INTERRUPTED,
            lastHeartbeatEpochMillis = null,
            lastHeartbeatUtcOffsetSeconds = null,
            reportedSessionCount = 1,
            reportedIncompleteSessionCount = 0,
            hadMicrophoneSilencing = false,
            hadAudioGap = false,
            rawAudioState = "retained",
            transcriptionState = ProcessingState.NOT_STARTED,
            transcriptionFailure = null,
            enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION,
            enrichmentFailure = null,
            importWarning = null,
        )

        fun session(
            sessionId: String,
            captureOrder: Int,
            audioState: String = AudioEvidenceState.RETAINED,
            finalizedAt: Long? = 800L,
            incompleteReason: String? = "test_incomplete",
            sampleCount: Long? = 16_000L,
            preRollSampleCount: Long? = 0L,
            cueStartSample: Long? = null,
            cueEndSample: Long? = null,
            automaticSilenceTailSampleCount: Long? = null,
        ) = CaptureSessionEntity(
            sessionId = sessionId,
            nightId = NIGHT_ID,
            captureOrder = captureOrder,
            startedAtEpochMillis = 200L,
            startedUtcOffsetSeconds = 0,
            finalizedAtEpochMillis = finalizedAt,
            finalizedUtcOffsetSeconds = finalizedAt?.let { 0 },
            incompleteReason = incompleteReason,
            audioFileName = fileName(sessionId),
            audioState = audioState,
            sampleRateHz = 16_000,
            channelCount = 1,
            bitsPerSample = 16,
            sampleCount = sampleCount,
            preRollSampleCount = preRollSampleCount,
            cueStartSample = cueStartSample,
            cueEndSampleExclusive = cueEndSample,
            automaticSilenceTailSampleCount = automaticSilenceTailSampleCount,
        )
    }
}
