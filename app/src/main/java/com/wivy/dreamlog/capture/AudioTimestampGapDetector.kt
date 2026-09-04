package com.wivy.dreamlog.capture

internal data class AudioTimestampSample(
    val framePosition: Long,
    val nanoTime: Long,
)

internal data class TimestampGap(
    val discrepancyFrames: Long,
    val estimatedGapMillis: Long,
)

internal class AudioTimestampGapDetector(
    private val sampleRateHz: Int,
) {
    private val gapThresholdFrames =
        (sampleRateHz.toLong() * GAP_THRESHOLD_MILLIS + MILLIS_PER_SECOND - 1L) /
            MILLIS_PER_SECOND

    private var baseline: AudioTimestampSample? = null
    private var latestFreshSample: AudioTimestampSample? = null
    private var warmupAdvanceCount = 0
    private var candidateAnchor: AudioTimestampSample? = null

    init {
        require(sampleRateHz > 0) { "Sample rate must be positive." }
    }

    val confirmationPending: Boolean
        get() = candidateAnchor != null

    fun observe(sample: AudioTimestampSample?): TimestampGap? {
        if (sample == null) return null
        val latest = latestFreshSample
        if (latest == null) {
            startGeneration(sample)
            return null
        }
        if (sample == latest) return null
        if (
            sample.nanoTime <= latest.nanoTime ||
            sample.framePosition < latest.framePosition
        ) {
            startGeneration(sample)
            return null
        }

        val strictlyAdvancing = sample.framePosition > latest.framePosition
        val localDeficit = frameDeficit(latest, sample)
        if (localDeficit <= -gapThresholdFrames) {
            startGeneration(sample)
            return null
        }
        latestFreshSample = sample

        if (warmupAdvanceCount < REQUIRED_WARMUP_ADVANCES) {
            if (!strictlyAdvancing) {
                startGeneration(sample)
                return null
            }
            warmupAdvanceCount += 1
            baseline = sample
            return null
        }

        val pendingAnchor = candidateAnchor
        if (pendingAnchor != null) {
            if (!strictlyAdvancing) return null
            val cumulativeDeficit = frameDeficit(pendingAnchor, sample)
            candidateAnchor = null
            baseline = sample
            if (cumulativeDeficit <= -gapThresholdFrames) {
                startGeneration(sample)
                return null
            }
            return if (cumulativeDeficit >= gapThresholdFrames) {
                TimestampGap(
                    discrepancyFrames = cumulativeDeficit,
                    estimatedGapMillis = (
                        cumulativeDeficit * MILLIS_PER_SECOND / sampleRateHz
                        ).coerceAtLeast(GAP_THRESHOLD_MILLIS),
                )
            } else {
                null
            }
        }

        val nearbyBaseline = checkNotNull(baseline)
        val deficit = frameDeficit(nearbyBaseline, sample)
        return when {
            deficit >= gapThresholdFrames -> {
                candidateAnchor = nearbyBaseline
                null
            }

            deficit <= -gapThresholdFrames -> {
                startGeneration(sample)
                null
            }

            else -> {
                baseline = sample
                null
            }
        }
    }

    fun reset() {
        baseline = null
        latestFreshSample = null
        warmupAdvanceCount = 0
        candidateAnchor = null
    }

    private fun startGeneration(anchor: AudioTimestampSample) {
        baseline = anchor
        latestFreshSample = anchor
        warmupAdvanceCount = 0
        candidateAnchor = null
    }

    private fun frameDeficit(
        from: AudioTimestampSample,
        to: AudioTimestampSample,
    ): Long {
        val nanosDelta = to.nanoTime - from.nanoTime
        val expectedFrames = nanosDelta * sampleRateHz / NANOS_PER_SECOND
        return expectedFrames - (to.framePosition - from.framePosition)
    }

    private companion object {
        const val REQUIRED_WARMUP_ADVANCES = 2
        const val GAP_THRESHOLD_MILLIS = 64L
        const val MILLIS_PER_SECOND = 1_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
