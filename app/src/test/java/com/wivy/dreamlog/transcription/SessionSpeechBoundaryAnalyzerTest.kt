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
        assertEquals(
            listOf(
                SessionNonSpeechRange(0L, 635L),
                SessionNonSpeechRange(1_147L, 1_347L),
            ),
            result.nonSpeechRanges,
        )
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
        assertEquals(
            listOf(
                SessionNonSpeechRange(512L, 1_031L),
                SessionNonSpeechRange(1_543L, 1_554L),
            ),
            result.nonSpeechRanges,
        )
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
        assertEquals(listOf(SessionNonSpeechRange(0L, 549L)), result.nonSpeechRanges)
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
        assertEquals(
            listOf(
                SessionNonSpeechRange(0L, 17L),
                SessionNonSpeechRange(46L, 69L),
            ),
            result.nonSpeechRanges,
        )
    }

    @Test
    fun rangesRetainTheAbsoluteAnalysisOffset() {
        val accumulator = SpeechBoundaryAccumulator(initialSourceSample = 10_000L)

        accumulator.acceptFrame(speech = false, actualSampleCount = 320)
        accumulator.acceptFrame(speech = true, actualSampleCount = 320)
        accumulator.acceptFrame(speech = false, actualSampleCount = 160)

        assertEquals(
            listOf(
                SessionNonSpeechRange(10_000L, 10_320L),
                SessionNonSpeechRange(10_640L, 10_800L),
            ),
            accumulator.result().nonSpeechRanges,
        )
    }
}
