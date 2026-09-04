package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimestampGapDetectorTest {
    @Test
    fun `irregular healthy progression does not report a gap`() {
        val detector = AudioTimestampGapDetector(SAMPLE_RATE_HZ)

        assertNull(detector.observe(sample(0L, 0L)))
        assertNull(detector.observe(sample(800L, 50_000_000L)))
        assertNull(detector.observe(sample(1_760L, 110_000_000L)))
        assertNull(detector.observe(sample(2_400L, 151_000_000L)))
        assertNull(detector.observe(sample(3_744L, 230_000_000L)))
        assertFalse(detector.confirmationPending)
    }

    @Test
    fun `exact cached duplicates are ignored during warmup and confirmation`() {
        val detector = AudioTimestampGapDetector(SAMPLE_RATE_HZ)
        val anchor = sample(0L, 0L)

        assertNull(detector.observe(anchor))
        assertNull(detector.observe(anchor))
        assertNull(detector.observe(sample(512L, 32_000_000L)))
        assertNull(detector.observe(sample(512L, 32_000_000L)))
        assertNull(detector.observe(sample(1_024L, 64_000_000L)))

        val candidate = sample(1_536L, 160_000_000L)
        assertNull(detector.observe(candidate))
        assertTrue(detector.confirmationPending)
        assertNull(detector.observe(candidate))
        assertNull(detector.observe(null))
        assertTrue(detector.confirmationPending)

        assertEquals(
            TimestampGap(discrepancyFrames = 1_024L, estimatedGapMillis = 64L),
            detector.observe(sample(2_048L, 192_000_000L)),
        )
    }

    @Test
    fun `non-increasing time and decreasing position each reset warmup`() {
        val invalidSamples = listOf(
            sample(1_536L, 64_000_000L),
            sample(512L, 96_000_000L),
        )

        invalidSamples.forEach { invalid ->
            val detector = warmedDetector()

            assertNull(detector.observe(invalid))
            assertFalse(detector.confirmationPending)
            assertNull(
                detector.observe(
                    sample(
                        invalid.framePosition + 512L,
                        invalid.nanoTime + 32_000_000L,
                    ),
                ),
            )
            assertNull(
                detector.observe(
                    sample(
                        invalid.framePosition + 1_024L,
                        invalid.nanoTime + 128_000_000L,
                    ),
                ),
            )
            assertFalse(detector.confirmationPending)
            assertNull(
                detector.observe(
                    sample(
                        invalid.framePosition + 1_536L,
                        invalid.nanoTime + 160_000_000L,
                    ),
                ),
            )
        }
    }

    @Test
    fun `transient positive deficit is canceled by the next fresh sample`() {
        val detector = warmedDetector()

        assertNull(detector.observe(sample(1_536L, 160_000_000L)))
        assertTrue(detector.confirmationPending)

        assertNull(detector.observe(sample(2_049L, 192_000_000L)))
        assertFalse(detector.confirmationPending)
        assertNull(detector.observe(sample(2_561L, 224_000_000L)))
    }

    @Test
    fun `persistent 1024 frame positive deficit confirms on follow-up`() {
        val detector = warmedDetector()

        assertNull(detector.observe(sample(1_536L, 160_000_000L)))
        assertTrue(detector.confirmationPending)

        assertEquals(
            TimestampGap(discrepancyFrames = 1_024L, estimatedGapMillis = 64L),
            detector.observe(sample(2_048L, 192_000_000L)),
        )
        assertFalse(detector.confirmationPending)
    }

    @Test
    fun `1023 frame positive deficit stays below the candidate boundary`() {
        val detector = warmedDetector()

        assertNull(detector.observe(sample(1_536L, 159_937_500L)))
        assertFalse(detector.confirmationPending)
        assertNull(detector.observe(sample(2_048L, 191_937_500L)))
    }

    @Test
    fun `large reverse residual never reports a gap and resets warmup`() {
        val detector = warmedDetector()

        assertNull(detector.observe(sample(2_560L, 96_000_000L)))
        assertFalse(detector.confirmationPending)
        assertNull(detector.observe(sample(3_072L, 128_000_000L)))
        assertNull(detector.observe(sample(3_584L, 224_000_000L)))
        assertFalse(detector.confirmationPending)
        assertNull(detector.observe(sample(4_096L, 256_000_000L)))
    }

    @Test
    fun `start and explicit reset require two fresh warmup samples`() {
        val detector = AudioTimestampGapDetector(SAMPLE_RATE_HZ)

        assertWarmupDoesNotEvaluateLargeDeficit(detector, frameOffset = 0L)

        detector.reset()

        assertWarmupDoesNotEvaluateLargeDeficit(detector, frameOffset = 10_000L)
    }

    @Test
    fun `explicit reset discards a pending gap candidate`() {
        val detector = warmedDetector()

        assertNull(detector.observe(sample(1_536L, 160_000_000L)))
        assertTrue(detector.confirmationPending)

        detector.reset()

        assertNull(detector.observe(sample(2_048L, 192_000_000L)))
        assertFalse(detector.confirmationPending)
        assertNull(detector.observe(sample(2_560L, 224_000_000L)))
        assertNull(detector.observe(sample(3_072L, 256_000_000L)))
    }

    @Test
    fun `threshold is calculated from the configured sample rate and rounded up`() {
        val belowBoundary = AudioTimestampGapDetector(sampleRateHz = 11_025)
        assertNull(belowBoundary.observe(sample(0L, 0L)))
        assertNull(belowBoundary.observe(sample(11_025L, 1_000_000_000L)))
        assertNull(belowBoundary.observe(sample(22_050L, 2_000_000_000L)))
        assertNull(belowBoundary.observe(sample(32_370L, 3_000_000_000L)))
        assertFalse(belowBoundary.confirmationPending)

        val detector = AudioTimestampGapDetector(sampleRateHz = 11_025)

        assertNull(detector.observe(sample(0L, 0L)))
        assertNull(detector.observe(sample(11_025L, 1_000_000_000L)))
        assertNull(detector.observe(sample(22_050L, 2_000_000_000L)))
        assertNull(detector.observe(sample(32_369L, 3_000_000_000L)))
        assertTrue(detector.confirmationPending)

        assertEquals(
            TimestampGap(discrepancyFrames = 706L, estimatedGapMillis = 64L),
            detector.observe(sample(43_394L, 4_000_000_000L)),
        )
    }

    private fun warmedDetector(): AudioTimestampGapDetector =
        AudioTimestampGapDetector(SAMPLE_RATE_HZ).also { detector ->
            assertNull(detector.observe(sample(0L, 0L)))
            assertNull(detector.observe(sample(512L, 32_000_000L)))
            assertNull(detector.observe(sample(1_024L, 64_000_000L)))
        }

    private fun assertWarmupDoesNotEvaluateLargeDeficit(
        detector: AudioTimestampGapDetector,
        frameOffset: Long,
    ) {
        assertNull(detector.observe(sample(frameOffset, 0L)))
        assertNull(detector.observe(sample(frameOffset + 512L, 32_000_000L)))
        assertNull(detector.observe(sample(frameOffset + 1_024L, 128_000_000L)))
        assertFalse(detector.confirmationPending)
        assertNull(detector.observe(sample(frameOffset + 1_536L, 160_000_000L)))
        assertFalse(detector.confirmationPending)
    }

    private fun sample(
        framePosition: Long,
        nanoTime: Long,
    ): AudioTimestampSample =
        AudioTimestampSample(
            framePosition = framePosition,
            nanoTime = nanoTime,
        )

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}
