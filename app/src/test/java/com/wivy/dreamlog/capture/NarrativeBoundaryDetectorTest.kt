package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrativeBoundaryDetectorTest {
    @Test
    fun `default boundary is reached at exactly 160000 consecutive non-speech samples`() {
        val detector = NarrativeBoundaryDetector()

        assertFalse(detector.onNonSpeech(159_999))
        assertEquals(159_999L, detector.consecutiveNonSpeechSamples)

        assertTrue(detector.onNonSpeech(1))
        assertEquals(
            NarrativeBoundaryDetector.DEFAULT_REQUIRED_NON_SPEECH_SAMPLES,
            detector.consecutiveNonSpeechSamples,
        )
    }

    @Test
    fun `the complete frame that crosses the threshold is retained as evidence`() {
        val detector = NarrativeBoundaryDetector()

        assertFalse(detector.onNonSpeech(159_744))
        assertTrue(detector.onNonSpeech(512))

        assertEquals(160_256L, detector.consecutiveNonSpeechSamples)
    }

    @Test
    fun `speech cue silencing and gaps each reset continuous non-speech`() {
        val resetSignals =
            listOf<(NarrativeBoundaryDetector) -> Unit>(
                { it.onSpeech() },
                { it.onCue() },
                { it.onSilencing() },
                { it.onGap() },
            )

        resetSignals.forEach { resetSignal ->
            val detector = NarrativeBoundaryDetector()
            detector.onNonSpeech(159_999)

            resetSignal(detector)

            assertEquals(0L, detector.consecutiveNonSpeechSamples)
            assertFalse(detector.onNonSpeech(1))
            assertEquals(1L, detector.consecutiveNonSpeechSamples)
        }
    }

    @Test
    fun `an alternate positive duration changes the exact sample boundary`() {
        val detector = NarrativeBoundaryDetector(continuousNonSpeechSeconds = 20)

        assertEquals(320_000L, detector.requiredNonSpeechSamples)
        assertFalse(detector.onNonSpeech(319_999))
        assertTrue(detector.onNonSpeech(1))
    }

    @Test
    fun `nonpositive duration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            NarrativeBoundaryDetector(continuousNonSpeechSeconds = 0)
        }
    }

    @Test
    fun `negative sample count is rejected without changing progress`() {
        val detector = NarrativeBoundaryDetector()
        detector.onNonSpeech(512)

        assertThrows(IllegalArgumentException::class.java) {
            detector.onNonSpeech(-1)
        }
        assertEquals(512L, detector.consecutiveNonSpeechSamples)
    }
}
