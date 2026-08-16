package com.wivy.dreamlog.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueAudioPreflightTest {
    @Test
    fun `volume at warning threshold may be too low`() {
        assertTrue(status(volumePercent = 25).volumeMayBeTooLow)
    }

    @Test
    fun `volume above warning threshold is not flagged`() {
        assertFalse(status(volumePercent = 26).volumeMayBeTooLow)
    }

    @Test
    fun `muted stream is flagged above warning threshold`() {
        assertTrue(status(volumePercent = 80, streamMuted = true).volumeMayBeTooLow)
    }

    private fun status(
        volumePercent: Int,
        streamMuted: Boolean = false,
    ): CueAudioStatus =
        CueAudioStatus(
            streamType = 0,
            streamName = "Assistant",
            volumeIndex = volumePercent,
            minVolumeIndex = 0,
            maxVolumeIndex = 100,
            streamMuted = streamMuted,
            interruptionFilter = 0,
            mediaAllowedByActiveFilter = true,
        )
}
