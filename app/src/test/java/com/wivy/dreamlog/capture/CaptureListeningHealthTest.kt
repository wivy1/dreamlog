package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureListeningHealthTest {
    @Test
    fun keywordProgressTracksOnlyActualCaptureWorkAndTypedResets() {
        val tracker = KeywordStreamHealthTracker()

        tracker.recordAcceptedFrame()
        tracker.recordAcceptedFrame()
        tracker.recordDecode()
        tracker.recordReset(KeywordStreamResetReason.AUDIO_DISCONTINUITY)

        assertEquals(
            KeywordStreamProgress(
                acceptedFrameCount = 2L,
                decodeCount = 1L,
                resetCount = 1L,
                lastResetReason = "audio_discontinuity",
            ),
            tracker.snapshot(),
        )
        assertFalse(
            KeywordStreamResetReason.entries.any {
                it.journalCode.contains("screen") || it.journalCode.contains("unlock")
            },
        )
    }

    @Test
    fun unrelatedScreenEventDoesNotChangeKeywordHealthSummary() {
        val events = listOf(
            heartbeat(
                epochMillis = 60_000L,
                framesRead = 960_000L,
                acceptedFrames = 1_875L,
                decodes = 230L,
                resets = 1L,
            ),
            CaptureJournalEvent(
                eventId = "event_screen",
                epochMillis = 90_000L,
                type = "screen_state_changed",
                attributes = mapOf("screen_on" to "true"),
            ),
            heartbeat(
                epochMillis = 120_000L,
                framesRead = 1_920_000L,
                acceptedFrames = 3_750L,
                decodes = 460L,
                resets = 1L,
            ),
        )

        val summary = summarizeCaptureListeningHealth(events)

        assertEquals(1L, summary.latestHeartbeat?.keywordResetCount)
        assertEquals("audio_discontinuity", summary.latestHeartbeat?.lastKeywordResetReason)
        assertTrue(summary.issues.isEmpty())
    }

    @Test
    fun summaryFlagsKeywordInputThatStopsWhileIdleCaptureAdvances() {
        val summary = summarizeCaptureListeningHealth(
            listOf(
                heartbeat(
                    epochMillis = 60_000L,
                    framesRead = 960_000L,
                    acceptedFrames = 1_875L,
                    decodes = 230L,
                    resets = 1L,
                ),
                heartbeat(
                    epochMillis = 120_000L,
                    framesRead = 1_920_000L,
                    acceptedFrames = 1_875L,
                    decodes = 230L,
                    resets = 1L,
                ),
            ),
        )

        assertEquals(
            setOf(CaptureListeningHealthIssue.KEYWORD_INPUT_NOT_ADVANCING),
            summary.issues,
        )
    }

    @Test
    fun summaryRetainsAnEarlierStallAfterLaterProgressResumes() {
        val summary = summarizeCaptureListeningHealth(
            listOf(
                heartbeat(60_000L, 960_000L, 1_875L, 230L, 1L),
                heartbeat(120_000L, 1_920_000L, 1_875L, 230L, 1L),
                heartbeat(180_000L, 2_880_000L, 5_625L, 690L, 1L),
            ),
        )

        assertTrue(
            CaptureListeningHealthIssue.KEYWORD_INPUT_NOT_ADVANCING in summary.issues,
        )
    }

    @Test
    fun explicitEffectiveMicrophoneStateOverridesLegacySilencingFields() {
        assertEquals(
            false,
            captureMicrophoneSilencedState(
                eventType = "microphone_state",
                attributes = mapOf(
                    "effective_silenced" to "false",
                    "client_silenced" to "true",
                    "own_configuration" to "missing",
                ),
            ),
        )
        assertEquals(
            true,
            captureMicrophoneSilencedState(
                eventType = "microphone_state",
                attributes = mapOf(
                    "client_silenced" to "true",
                    "own_configuration" to "observed",
                ),
            ),
        )
        assertEquals(
            true,
            captureMicrophoneSilencedState(
                eventType = "microphone_state",
                attributes = mapOf(
                    "client_silenced" to "false",
                    "own_configuration" to "missing",
                ),
            ),
        )
    }

    private fun heartbeat(
        epochMillis: Long,
        framesRead: Long,
        acceptedFrames: Long,
        decodes: Long,
        resets: Long,
    ): CaptureJournalEvent = CaptureJournalEvent(
        eventId = "event_$epochMillis",
        epochMillis = epochMillis,
        type = "heartbeat",
        attributes = mapOf(
            "frames_read" to framesRead.toString(),
            "gap_count" to "0",
            "microphone_silenced" to "false",
            "readiness" to "ready",
            "charging" to "true",
            "session_active" to "false",
            "kws_accepted_frame_count" to acceptedFrames.toString(),
            "kws_decode_count" to decodes.toString(),
            "kws_reset_count" to resets.toString(),
            "kws_last_reset_reason" to "audio_discontinuity",
        ),
    )
}
