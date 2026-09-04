package com.wivy.dreamlog.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePreRollBufferTest {
    @Test
    fun `production retention is capped at forty five seconds of pcm16`() {
        assertEquals(720_000, WakePreRollPolicy.MAX_RETENTION_SAMPLES)
        assertEquals(1_440_000, WakePreRollPolicy.MAX_RETENTION_PCM16_BYTES)
    }

    @Test
    fun `production buffer keeps the newest forty five seconds across frame wrap`() {
        val buffer = WakePreRollBuffer(activityRmsThreshold = Short.MAX_VALUE.toInt())
        val totalSamples = WakePreRollPolicy.MAX_RETENTION_SAMPLES + 512
        var samplePosition = 0
        while (samplePosition < totalSamples) {
            val frameSize = minOf(512, totalSamples - samplePosition)
            buffer.append(
                ShortArray(frameSize) { offset ->
                    ((samplePosition + offset) % 1_000).toShort()
                },
            )
            samplePosition += frameSize
        }

        assertArrayEquals(
            ShortArray(WakePreRollPolicy.MAX_RETENTION_SAMPLES) { offset ->
                ((offset + 512) % 1_000).toShort()
            },
            buffer.snapshotForAcceptedWake(),
        )
    }

    @Test
    fun `delayed wake keeps the beginning of the current speech run`() {
        val buffer = WakePreRollBuffer(
            capacitySamples = 30,
            activityRmsThreshold = 100,
            speechRunGapSamples = 6,
            speechLeadSamples = 2,
            minimumSnapshotSamples = 4,
        )
        buffer.append(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        buffer.append(shortArrayOf(500, 501, 502))
        buffer.append(shortArrayOf(0, 0, 0, 0))
        buffer.append(shortArrayOf(503, 504, 505))

        assertArrayEquals(
            shortArrayOf(9, 10, 500, 501, 502, 0, 0, 0, 0, 503, 504, 505),
            buffer.snapshotForAcceptedWake(),
        )
    }

    @Test
    fun `long silence starts a new speech run without dropping the existing minimum`() {
        val buffer = WakePreRollBuffer(
            capacitySamples = 30,
            activityRmsThreshold = 100,
            speechRunGapSamples = 4,
            speechLeadSamples = 1,
            minimumSnapshotSamples = 6,
        )
        buffer.append(shortArrayOf(400, 401))
        buffer.append(shortArrayOf(0, 0, 0, 0, 0))
        buffer.append(shortArrayOf(500, 501))

        assertArrayEquals(
            shortArrayOf(0, 0, 0, 0, 500, 501),
            buffer.snapshotForAcceptedWake(),
        )
    }

    @Test
    fun `uncertain activity falls back to the complete capped buffer and clear removes it`() {
        val buffer = WakePreRollBuffer(
            capacitySamples = 6,
            activityRmsThreshold = 100,
            speechRunGapSamples = 2,
            speechLeadSamples = 1,
            minimumSnapshotSamples = 2,
        )
        buffer.append(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7, 8), buffer.snapshotForAcceptedWake())

        buffer.clear()

        assertTrue(buffer.snapshotForAcceptedWake().isEmpty())
    }
}
