package com.wivy.dreamlog

import com.wivy.dreamlog.capture.CapturePhase
import com.wivy.dreamlog.capture.CaptureRuntimeSnapshot
import com.wivy.dreamlog.capture.CaptureSnapshot
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeSnapshot
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightEntity
import com.wivy.dreamlog.history.NightRecord
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioState
import com.wivy.dreamlog.history.SessionTranscriptEntity
import com.wivy.dreamlog.history.SessionTranscriptRecord
import com.wivy.dreamlog.history.TranscriptSegmentEntity
import com.wivy.dreamlog.transcription.TranscriptionModelPhase
import com.wivy.dreamlog.transcription.TranscriptionRuntimePhase
import com.wivy.dreamlog.transcription.TranscriptionRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityManualEnrichmentBatchEligibilityTest {
    @Test
    fun primaryActionIsAtLeastTwiceThePriorHeight() {
        assertTrue(HOME_PRIMARY_ACTION_HEIGHT_DP >= 152)
    }

    @Test
    fun failedRetainedSessionBecomesTheDominantResumeAction() {
        val base = record()
        val failedTranscript = base.transcripts.single().copy(
            transcript = base.transcripts.single().transcript.copy(
                state = ProcessingState.FAILED,
                failureDetail = "Transcription stopped before completion.",
                rawText = null,
                completedAtEpochMillis = null,
            ),
        )
        val failed = base.copy(
            night = base.night.copy(
                transcriptionState = ProcessingState.FAILED,
                transcriptionFailure = "Transcription stopped before completion.",
            ),
            transcripts = listOf(failedTranscript),
        )

        val action = homeMorningAction(
            latestResult = failed,
            transcriptionRuntime = TranscriptionRuntimeSnapshot(
                modelPhase = TranscriptionModelPhase.INSTALLED,
            ),
            enrichmentRuntime = EnrichmentRuntimeSnapshot(),
            readyEnrichmentRecords = emptyList(),
        )

        assertEquals(HomeNextActionKind.RESUME_TRANSCRIPTION, action?.kind)
        assertEquals("Transcription paused — 0 of 1 complete", action?.title)
        assertEquals("session-1", action?.sessionId)
        assertEquals("Resume transcription", action?.buttonLabel)
    }

    @Test
    fun morningSequenceMovesFromTranscribingToEnrichThenStart() {
        val ready = record()
        val running = homeMorningAction(
            latestResult = ready,
            transcriptionRuntime = TranscriptionRuntimeSnapshot(
                transcriptionPhase = TranscriptionRuntimePhase.RUNNING,
                nightId = NIGHT_ID,
                eligibleSessionCount = 1,
                completedSessionCount = 0,
            ),
            enrichmentRuntime = EnrichmentRuntimeSnapshot(),
            readyEnrichmentRecords = listOf(ready),
        )
        val enrich = homeMorningAction(
            latestResult = ready,
            transcriptionRuntime = TranscriptionRuntimeSnapshot(
                modelPhase = TranscriptionModelPhase.INSTALLED,
            ),
            enrichmentRuntime = EnrichmentRuntimeSnapshot(),
            readyEnrichmentRecords = listOf(ready),
        )
        val completed = homeMorningAction(
            latestResult = ready.copy(
                night = ready.night.copy(enrichmentState = ProcessingState.COMPLETE),
            ),
            transcriptionRuntime = TranscriptionRuntimeSnapshot(),
            enrichmentRuntime = EnrichmentRuntimeSnapshot(),
            readyEnrichmentRecords = emptyList(),
        )

        assertEquals("Transcribing 0/1", running?.title)
        assertEquals(HomeNextActionKind.ENRICH, enrich?.kind)
        assertEquals("Ready to enrich", enrich?.title)
        assertNull(completed)
    }

    @Test
    fun primaryStatusDescribesStateInsteadOfRepeatingTheAction() {
        assertEquals(
            "Ready to start",
            homePrimaryStatusTitle(
                runtime = CaptureRuntimeSnapshot(),
                morningAction = null,
                startEnabled = true,
                setupNeedsAttention = false,
            ),
        )
        assertEquals(
            "Setup required",
            homePrimaryStatusTitle(
                runtime = CaptureRuntimeSnapshot(),
                morningAction = null,
                startEnabled = false,
                setupNeedsAttention = true,
            ),
        )
        assertEquals(
            "Listening for wakewords",
            homePrimaryStatusTitle(
                runtime = CaptureRuntimeSnapshot(
                    capture = CaptureSnapshot(phase = CapturePhase.LISTENING),
                ),
                morningAction = null,
                startEnabled = false,
                setupNeedsAttention = false,
            ),
        )
    }

    @Test
    fun terminalEnrichmentFailureDoesNotBlockTheNextNight() {
        val failed = record(
            enrichmentState = ProcessingState.FAILED,
            enrichmentFailure =
                "The input is too large. [code=input_too_large; retryable=false]",
        )

        val action = homeMorningAction(
            latestResult = failed,
            transcriptionRuntime = TranscriptionRuntimeSnapshot(),
            enrichmentRuntime = EnrichmentRuntimeSnapshot(),
            readyEnrichmentRecords = emptyList(),
        )

        assertNull(action)
    }

    @Test
    fun endedAndInterruptedWaitingNightsAreReady() {
        listOf(NightCaptureState.ENDED, NightCaptureState.INTERRUPTED).forEach { captureState ->
            assertTrue(
                "$captureState night should be ready",
                record(captureState = captureState).isReadyForManualEnrichmentBatch(),
            )
        }
    }

    @Test
    fun retryableFailedEnrichmentIsReady() {
        assertTrue(
            record(
                enrichmentState = ProcessingState.FAILED,
                enrichmentFailure =
                    "Local enrichment inference stopped. " +
                        "[code=inference_failed; retryable=true]",
            ).isReadyForManualEnrichmentBatch(),
        )
    }

    @Test
    fun onlyGenuinelyEmptyZeroSessionNightIsReadyWithoutCompletedTranscription() {
        assertTrue(
            record(
                sessions = emptyList(),
                transcriptionState = ProcessingState.NOT_STARTED,
            ).isReadyForManualEnrichmentBatch(),
        )
        assertFalse(
            record(
                sessions = emptyList(),
                transcriptionState = ProcessingState.FAILED,
                transcriptionFailure = "Interrupted recovery did not establish an empty night.",
            ).isReadyForManualEnrichmentBatch(),
        )
        assertFalse(
            record(
                sessions = emptyList(),
                reportedSessionCount = 1,
                rawAudioState = RawAudioState.UNAVAILABLE,
            ).isReadyForManualEnrichmentBatch(),
        )
    }

    @Test
    fun activeCaptureIsNotReady() {
        assertFalse(
            record(captureState = NightCaptureState.ACTIVE)
                .isReadyForManualEnrichmentBatch(),
        )
    }

    @Test
    fun incompleteOrFailedTranscriptionIsNotReady() {
        assertFalse(
            record(transcriptionState = ProcessingState.RUNNING)
                .isReadyForManualEnrichmentBatch(),
        )
        assertFalse(
            record(
                transcriptionState = ProcessingState.FAILED,
                transcriptionFailure = "Transcription failed.",
            ).isReadyForManualEnrichmentBatch(),
        )
        assertFalse(
            record(
                transcriptionState = ProcessingState.COMPLETE,
                transcriptionFailure = "Persisted failure marker.",
            ).isReadyForManualEnrichmentBatch(),
        )
        assertFalse(
            record(transcripts = emptyList()).isReadyForManualEnrichmentBatch(),
        )
    }

    @Test
    fun completedOrNonretryableEnrichmentIsNotReady() {
        assertFalse(
            record(enrichmentState = ProcessingState.COMPLETE)
                .isReadyForManualEnrichmentBatch(),
        )
        assertFalse(
            record(
                enrichmentState = ProcessingState.FAILED,
                enrichmentFailure =
                    "The input is too large. [code=input_too_large; retryable=false]",
            ).isReadyForManualEnrichmentBatch(),
        )
    }

    private fun record(
        captureState: String = NightCaptureState.ENDED,
        transcriptionState: String = ProcessingState.COMPLETE,
        transcriptionFailure: String? = null,
        enrichmentState: String = ProcessingState.WAITING_FOR_TRANSCRIPTION,
        enrichmentFailure: String? = null,
        sessions: List<CaptureSessionEntity> = listOf(session()),
        transcripts: List<SessionTranscriptRecord> = sessions.map(::transcript),
        reportedSessionCount: Int = sessions.size,
        rawAudioState: String = if (sessions.isEmpty()) {
            RawAudioState.NONE
        } else {
            RawAudioState.RETAINED
        },
    ) = NightRecord(
        night = NightEntity(
            nightId = NIGHT_ID,
            displayDate = "2026-08-01",
            startedAtEpochMillis = 1_000L,
            startedUtcOffsetSeconds = 0,
            endedAtEpochMillis = if (captureState == NightCaptureState.ACTIVE) null else 2_000L,
            endedUtcOffsetSeconds = if (captureState == NightCaptureState.ACTIVE) null else 0,
            captureState = captureState,
            endReason = null,
            interrupted = captureState == NightCaptureState.INTERRUPTED,
            lastHeartbeatEpochMillis = null,
            lastHeartbeatUtcOffsetSeconds = null,
            reportedSessionCount = reportedSessionCount,
            reportedIncompleteSessionCount = 0,
            hadMicrophoneSilencing = false,
            hadAudioGap = false,
            rawAudioState = rawAudioState,
            transcriptionState = transcriptionState,
            transcriptionFailure = transcriptionFailure,
            enrichmentState = enrichmentState,
            enrichmentFailure = enrichmentFailure,
            importWarning = null,
        ),
        sessions = sessions,
        events = emptyList(),
        transcripts = transcripts,
    )

    private companion object {
        const val NIGHT_ID = "night-1"

        fun session() = CaptureSessionEntity(
            sessionId = "session-1",
            nightId = NIGHT_ID,
            captureOrder = 0,
            startedAtEpochMillis = 1_100L,
            startedUtcOffsetSeconds = 0,
            finalizedAtEpochMillis = 1_500L,
            finalizedUtcOffsetSeconds = 0,
            incompleteReason = null,
            audioFileName = "session-1.wav",
            audioState = AudioEvidenceState.RETAINED,
            sampleRateHz = 16_000,
            channelCount = 1,
            bitsPerSample = 16,
            sampleCount = 16_000L,
            preRollSampleCount = 0L,
            cueStartSample = null,
            cueEndSampleExclusive = null,
        )

        fun transcript(session: CaptureSessionEntity) = SessionTranscriptRecord(
            transcript = SessionTranscriptEntity(
                sessionId = session.sessionId,
                nightId = session.nightId,
                state = ProcessingState.COMPLETE,
                failureDetail = null,
                rawText = "blue door",
                localeTag = "en-US",
                engineId = "asr",
                engineVersion = "5",
                runtimeId = "sherpa",
                runtimeVersion = "1",
                modelId = "zipformer",
                modelVersion = "revision",
                modelSha256 = "a".repeat(64),
                attemptCount = 1,
                startedAtEpochMillis = 1_200L,
                completedAtEpochMillis = 1_400L,
            ),
            segments = listOf(
                TranscriptSegmentEntity(
                    sessionId = session.sessionId,
                    segmentIndex = 0,
                    sourceStartMillis = 0L,
                    sourceEndMillis = 500L,
                    text = "blue door",
                ),
            ),
        )
    }
}
