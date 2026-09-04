package com.wivy.dreamlog.capture

import kotlin.math.max
import kotlin.math.min

/** Bounded, memory-only audio retained while waiting for an accepted wake phrase. */
internal object WakePreRollPolicy {
    const val SAMPLE_RATE_HZ = 16_000
    const val MAX_RETENTION_SECONDS = 45
    const val MAX_RETENTION_SAMPLES = SAMPLE_RATE_HZ * MAX_RETENTION_SECONDS
    const val MAX_RETENTION_PCM16_BYTES = MAX_RETENTION_SAMPLES * 2

    const val MINIMUM_RETENTION_SECONDS = 2
    const val MINIMUM_RETENTION_SAMPLES = SAMPLE_RATE_HZ * MINIMUM_RETENTION_SECONDS
    const val SPEECH_RUN_GAP_SECONDS = 3
    const val SPEECH_RUN_GAP_SAMPLES = SAMPLE_RATE_HZ * SPEECH_RUN_GAP_SECONDS
    const val SPEECH_LEAD_SECONDS = 1
    const val SPEECH_LEAD_SAMPLES = SAMPLE_RATE_HZ * SPEECH_LEAD_SECONDS
    const val ACTIVITY_RMS_THRESHOLD = LiveKitWakeWordPolicy.ACOUSTIC_GUARD_MIN_HOP_RMS.toInt()
}

/**
 * Keeps at most 45 seconds in RAM and snapshots from the current speech run when one is known.
 *
 * Activity tracking is deliberately cheaper and more conservative than another live VAD pass.
 * The accepted wake detector already requires this PCM16 RMS floor. A one-second lead and the
 * original two-second minimum protect word onsets and preserve the previous capture guarantee.
 * When activity is uncertain, the complete bounded buffer is returned rather than risking a cut.
 */
internal class WakePreRollBuffer(
    private val capacitySamples: Int = WakePreRollPolicy.MAX_RETENTION_SAMPLES,
    private val activityRmsThreshold: Int = WakePreRollPolicy.ACTIVITY_RMS_THRESHOLD,
    private val speechRunGapSamples: Int = WakePreRollPolicy.SPEECH_RUN_GAP_SAMPLES,
    private val speechLeadSamples: Int = WakePreRollPolicy.SPEECH_LEAD_SAMPLES,
    private val minimumSnapshotSamples: Int = WakePreRollPolicy.MINIMUM_RETENTION_SAMPLES,
) {
    private val samples: ShortArray
    private var writeIndex = 0
    private var available = 0
    private var totalSamples = 0L
    private var currentSpeechRunStart: Long? = null
    private var lastActiveSampleExclusive: Long? = null

    init {
        require(capacitySamples > 0) { "Pre-roll capacity must be positive." }
        require(activityRmsThreshold > 0) { "Pre-roll activity threshold must be positive." }
        require(speechRunGapSamples >= 0) { "Pre-roll speech gap cannot be negative." }
        require(speechLeadSamples >= 0) { "Pre-roll speech lead cannot be negative." }
        require(minimumSnapshotSamples in 0..capacitySamples) {
            "Pre-roll minimum must fit within its capacity."
        }
        samples = ShortArray(capacitySamples)
    }

    fun append(source: ShortArray) {
        if (source.isEmpty()) return
        val sourceStart = totalSamples
        if (hasActivity(source)) {
            val priorActiveEnd = lastActiveSampleExclusive
            if (
                priorActiveEnd == null ||
                sourceStart - priorActiveEnd > speechRunGapSamples
            ) {
                currentSpeechRunStart = sourceStart
            }
            lastActiveSampleExclusive = Math.addExact(sourceStart, source.size.toLong())
        }

        var sourceOffset = 0
        var remaining = source.size
        while (remaining > 0) {
            val copyCount = min(remaining, samples.size - writeIndex)
            source.copyInto(
                destination = samples,
                destinationOffset = writeIndex,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copyCount,
            )
            writeIndex = (writeIndex + copyCount) % samples.size
            sourceOffset += copyCount
            remaining -= copyCount
            available = min(samples.size, available + copyCount)
        }
        totalSamples = Math.addExact(totalSamples, source.size.toLong())
    }

    fun snapshotForAcceptedWake(): ShortArray {
        if (available == 0) return ShortArray(0)
        val earliestRetainedSample = totalSamples - available
        val activityStart = currentSpeechRunStart?.let { runStart ->
            max(earliestRetainedSample, runStart - speechLeadSamples)
        } ?: earliestRetainedSample
        val minimumStart = max(
            earliestRetainedSample,
            totalSamples - minimumSnapshotSamples,
        )
        val snapshotStart = min(activityStart, minimumStart)
        return snapshotLast((totalSamples - snapshotStart).toInt())
    }

    /** Removes logical access and overwrites retained PCM on every reset or stop. */
    fun clear() {
        if (available > 0) samples.fill(0)
        writeIndex = 0
        available = 0
        totalSamples = 0L
        currentSpeechRunStart = null
        lastActiveSampleExclusive = null
    }

    private fun hasActivity(source: ShortArray): Boolean {
        var sumSquares = 0L
        source.forEach { value ->
            val sample = value.toLong()
            sumSquares += sample * sample
        }
        val threshold = activityRmsThreshold.toLong()
        return sumSquares >= threshold * threshold * source.size
    }

    private fun snapshotLast(sampleCount: Int): ShortArray {
        require(sampleCount in 0..available) { "Pre-roll snapshot is outside retained audio." }
        if (sampleCount == 0) return ShortArray(0)
        val result = ShortArray(sampleCount)
        val start = (writeIndex - sampleCount + samples.size) % samples.size
        val firstCopy = min(sampleCount, samples.size - start)
        samples.copyInto(
            destination = result,
            destinationOffset = 0,
            startIndex = start,
            endIndex = start + firstCopy,
        )
        if (firstCopy < sampleCount) {
            samples.copyInto(
                destination = result,
                destinationOffset = firstCopy,
                startIndex = 0,
                endIndex = sampleCount - firstCopy,
            )
        }
        return result
    }
}
