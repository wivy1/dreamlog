package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioGapEvidenceTest {
    @Test
    fun `confirmed gap journal attributes carry durable evidence and session link`() {
        val attributes = confirmedAudioGapAttributes(
            AudioCaptureEvent.AudioGap(
                discrepancyFrames = 1_024L,
                estimatedGapMillis = 64L,
                activeSessionId = "session-1",
                sessionSampleOffset = 32_000L,
            ),
        )

        assertEquals("1024", attributes["discrepancy_frames"])
        assertEquals("64", attributes["estimated_gap_millis"])
        assertEquals(
            AudioGapEvidence.CONFIRMED_PERSISTENT_TIMESTAMP_DEFICIT,
            attributes[AudioGapEvidence.ATTRIBUTE_KEY],
        )
        assertEquals("session-1", attributes["session_id"])
        assertEquals("32000", attributes["session_sample_offset"])
    }
}
