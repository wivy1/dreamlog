package com.wivy.dreamlog.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSpeechBoundaryAnalyzerTest {
    @Test
    fun countsNonSpeechBeforeFirstSpeechAndAfterLastSpeech() {
        val accumulator = SpeechBoundaryAccumulator()

        accumulator.acceptFrame(speech = false, actualSampleCount = 512)
        accumulator.acceptFrame(speech = false, actualSampleCount = 123)
        accumulator.acceptFrame(speech = true, actualSampleCount = 512)
        accumulator.acceptFrame(speech = false, actualSampleCount = 200)

        val result = accumulator.result()
        assertTrue(result.speechDetected)
        assertEquals(635L, result.leadingNonSpeechSamples)
        assertEquals(200L, result.trailingNonSpeechSamples)
    }

    @Test
    fun laterSpeechResetsAccumulatedTrailingNonSpeech() {
        val accumulator = SpeechBoundaryAccumulator()

        accumulator.acceptFrame(speech = true, actualSampleCount = 512)
        accumulator.acceptFrame(speech = false, actualSampleCount = 512)
        accumulator.acceptFrame(speech = false, actualSampleCount = 7)
        accumulator.acceptFrame(speech = true, actualSampleCount = 512)
        accumulator.acceptFrame(speech = false, actualSampleCount = 11)

        val result = accumulator.result()
        assertTrue(result.speechDetected)
        assertEquals(0L, result.leadingNonSpeechSamples)
        assertEquals(11L, result.trailingNonSpeechSamples)
    }

    @Test
    fun allNonSpeechIsReportedAtBothEdges() {
        val accumulator = SpeechBoundaryAccumulator()

        accumulator.acceptFrame(speech = false, actualSampleCount = 512)
        accumulator.acceptFrame(speech = false, actualSampleCount = 37)

        val result = accumulator.result()
        assertFalse(result.speechDetected)
        assertEquals(549L, result.leadingNonSpeechSamples)
        assertEquals(549L, result.trailingNonSpeechSamples)
    }

    @Test
    fun partialFramesContributeOnlyTheirActualSampleCounts() {
        val accumulator = SpeechBoundaryAccumulator()

        accumulator.acceptFrame(speech = false, actualSampleCount = 17)
        accumulator.acceptFrame(speech = true, actualSampleCount = 29)
        accumulator.acceptFrame(speech = false, actualSampleCount = 23)

        val result = accumulator.result()
        assertTrue(result.speechDetected)
        assertEquals(17L, result.leadingNonSpeechSamples)
        assertEquals(23L, result.trailingNonSpeechSamples)
    }
}
