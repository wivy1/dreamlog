package com.wivy.dreamlog.capture

import kotlin.math.abs
import kotlin.math.max

enum class WakeReplayPhrase(
    val manifestValue: String,
) {
    DREAM_LOG("dreamlog"),
    HEY_DREAM_LOG("hey_dreamlog"),
    ;

    companion object {
        fun fromManifest(value: String): WakeReplayPhrase =
            entries.singleOrNull { phrase -> phrase.manifestValue == value }
                ?: throw IllegalArgumentException("Unknown wake phrase class: $value")
    }
}

enum class WakeReplayInvocationRole(
    val manifestValue: String,
) {
    CONTROL("control"),
    SCORED("scored"),
    ;

    companion object {
        fun fromManifest(value: String): WakeReplayInvocationRole =
            entries.singleOrNull { role -> role.manifestValue == value }
                ?: throw IllegalArgumentException("Unknown wake invocation role: $value")
    }
}

data class WakeReplayInvocation(
    val id: String,
    val phrase: WakeReplayPhrase,
    val role: WakeReplayInvocationRole,
    val spokenStartSample: Long,
    val spokenEndSampleExclusive: Long,
    val scoreStartSample: Long,
    val scoreEndSampleExclusive: Long,
)

data class WakeReplaySessionSpec(
    val alias: String,
    val sampleCount: Long,
    val triggerPhrase: WakeReplayPhrase,
    val preRollSampleCount: Long,
    val cueStartSample: Long?,
    val cueEndSampleExclusive: Long?,
    val invocations: List<WakeReplayInvocation>,
)

data class ValidatedWakeReplaySession(
    val spec: WakeReplaySessionSpec,
    val scoredStartSample: Long,
    val controlInvocations: List<WakeReplayInvocation>,
    val scoredInvocations: List<WakeReplayInvocation>,
    val allCounts: Map<WakeReplayPhrase, Int>,
    val controlCounts: Map<WakeReplayPhrase, Int>,
    val scoredCounts: Map<WakeReplayPhrase, Int>,
    val negativeSampleCount: Long,
)

data class WakeReplayDetection(
    val sampleExclusive: Long,
    val phrase: WakeReplayPhrase,
)

data class WakeReplayInvocationScore(
    val invocation: WakeReplayInvocation,
    val matchedDetection: WakeReplayDetection?,
    val duplicateDetections: List<WakeReplayDetection>,
) {
    val exactLabelHit: Boolean
        get() = matchedDetection?.phrase == invocation.phrase

    val wrongLabelHit: Boolean
        get() = matchedDetection != null && !exactLabelHit
}

data class WakeReplayContinuousScore(
    val invocationScores: List<WakeReplayInvocationScore>,
    val falsePositiveDetections: List<WakeReplayDetection>,
    val negativeSampleCount: Long,
) {
    val invocationCount: Int
        get() = invocationScores.size

    val anyLabelHits: Int
        get() = invocationScores.count { score -> score.matchedDetection != null }

    val exactLabelHits: Int
        get() = invocationScores.count(WakeReplayInvocationScore::exactLabelHit)

    val wrongLabelHits: Int
        get() = invocationScores.count(WakeReplayInvocationScore::wrongLabelHit)

    val misses: Int
        get() = invocationCount - anyLabelHits

    val duplicateDetectionCount: Int
        get() = invocationScores.sumOf { score -> score.duplicateDetections.size }
}

data class WakeReplayGainStatistics(
    val sampleCount: Long,
    val clippedSampleCount: Long,
    val sourcePeak: Float,
    val outputPeak: Float,
) {
    val clippedFraction: Double
        get() = if (sampleCount == 0L) 0.0 else clippedSampleCount.toDouble() / sampleCount
}

object WakeSessionReplayScoring {
    private val SAFE_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")

    fun validateSession(spec: WakeReplaySessionSpec): ValidatedWakeReplaySession {
        require(SAFE_ID.matches(spec.alias)) { "The session alias is malformed." }
        require(spec.sampleCount > 0L) { "The session must contain samples." }
        require(spec.preRollSampleCount in 0L..spec.sampleCount) {
            "The pre-roll boundary is outside the session."
        }
        spec.cueStartSample?.let { cueStart ->
            require(cueStart in 0L..spec.sampleCount) {
                "The cue start boundary is outside the session."
            }
        }
        spec.cueEndSampleExclusive?.let { cueEnd ->
            require(cueEnd in 0L..spec.sampleCount) {
                "The cue end boundary is outside the session."
            }
        }
        if (spec.cueStartSample != null && spec.cueEndSampleExclusive != null) {
            require(spec.cueStartSample < spec.cueEndSampleExclusive) {
                "The cue interval is empty or reversed."
            }
        }

        val scoredStartSample = max(
            spec.preRollSampleCount,
            spec.cueEndSampleExclusive ?: spec.cueStartSample ?: spec.preRollSampleCount,
        )
        require(scoredStartSample < spec.sampleCount) {
            "The scored portion of the session is empty."
        }
        require(spec.invocations.isNotEmpty()) { "The invocation manifest is empty." }
        require(spec.invocations.map(WakeReplayInvocation::id).distinct().size == spec.invocations.size) {
            "Wake invocation IDs must be unique within a session."
        }

        var previousScoreEnd = -1L
        spec.invocations.forEach { invocation ->
            require(SAFE_ID.matches(invocation.id)) { "A wake invocation ID is malformed." }
            require(
                invocation.spokenStartSample >= 0L &&
                    invocation.spokenStartSample < invocation.spokenEndSampleExclusive &&
                    invocation.spokenEndSampleExclusive <= spec.sampleCount,
            ) { "A spoken invocation interval is outside the session or reversed." }
            require(
                invocation.scoreStartSample >= 0L &&
                    invocation.scoreStartSample < invocation.scoreEndSampleExclusive &&
                    invocation.scoreEndSampleExclusive <= spec.sampleCount,
            ) { "A wake score window is outside the session or reversed." }
            require(
                invocation.scoreStartSample <= invocation.spokenStartSample &&
                    invocation.spokenEndSampleExclusive <= invocation.scoreEndSampleExclusive,
            ) { "A wake score window must contain its spoken interval." }
            require(invocation.scoreStartSample >= previousScoreEnd) {
                "Wake score windows must be ordered and non-overlapping."
            }
            when (invocation.role) {
                WakeReplayInvocationRole.CONTROL -> require(
                    invocation.scoreEndSampleExclusive <= scoredStartSample,
                ) { "A control invocation crosses the scored-session boundary." }

                WakeReplayInvocationRole.SCORED -> require(
                    invocation.scoreStartSample >= scoredStartSample,
                ) { "A scored invocation begins before the scored-session boundary." }
            }
            previousScoreEnd = invocation.scoreEndSampleExclusive
        }

        val controlInvocations = spec.invocations.filter {
            invocation -> invocation.role == WakeReplayInvocationRole.CONTROL
        }
        val scoredInvocations = spec.invocations.filter {
            invocation -> invocation.role == WakeReplayInvocationRole.SCORED
        }
        require(controlInvocations.size == 1) {
            "The session must identify exactly one initial control invocation."
        }
        require(controlInvocations.single().phrase == spec.triggerPhrase) {
            "The initial control invocation must match the session trigger phrase."
        }
        require(scoredInvocations.isNotEmpty()) { "The session has no scored wake invocations." }

        val positiveSamples = unionLength(
            scoredInvocations.map { invocation ->
                invocation.scoreStartSample until invocation.scoreEndSampleExclusive
            },
        )
        val scoredSamples = spec.sampleCount - scoredStartSample
        require(positiveSamples <= scoredSamples) {
            "Positive wake windows exceed the scored session."
        }

        return ValidatedWakeReplaySession(
            spec = spec,
            scoredStartSample = scoredStartSample,
            controlInvocations = controlInvocations,
            scoredInvocations = scoredInvocations,
            allCounts = countsByPhrase(spec.invocations),
            controlCounts = countsByPhrase(controlInvocations),
            scoredCounts = countsByPhrase(scoredInvocations),
            negativeSampleCount = scoredSamples - positiveSamples,
        )
    }

    fun scoreContinuous(
        session: ValidatedWakeReplaySession,
        detections: List<WakeReplayDetection>,
    ): WakeReplayContinuousScore {
        detections.forEachIndexed { index, detection ->
            require(
                detection.sampleExclusive in session.scoredStartSample..session.spec.sampleCount,
            ) { "A continuous detection is outside the scored session." }
            if (index > 0) {
                require(detections[index - 1].sampleExclusive <= detection.sampleExclusive) {
                    "Continuous detections must be in timestamp order."
                }
            }
        }

        val invocationScores = session.scoredInvocations.map { invocation ->
            val inWindow = detections.filter { detection ->
                detection.sampleExclusive >= invocation.scoreStartSample &&
                    detection.sampleExclusive < invocation.scoreEndSampleExclusive
            }
            WakeReplayInvocationScore(
                invocation = invocation,
                matchedDetection = inWindow.firstOrNull(),
                duplicateDetections = inWindow.drop(1),
            )
        }
        val falsePositives = detections.filter { detection ->
            session.scoredInvocations.none { invocation ->
                detection.sampleExclusive >= invocation.scoreStartSample &&
                    detection.sampleExclusive < invocation.scoreEndSampleExclusive
            }
        }
        return WakeReplayContinuousScore(
            invocationScores = invocationScores,
            falsePositiveDetections = falsePositives,
            negativeSampleCount = session.negativeSampleCount,
        )
    }

    fun measureGain(
        samples: ShortArray,
        startSample: Int,
        endSampleExclusive: Int,
        gain: Float,
    ): WakeReplayGainStatistics {
        require(gain.isFinite() && gain > 0f) { "Replay input gain must be positive and finite." }
        require(startSample in 0..samples.size && endSampleExclusive in startSample..samples.size) {
            "The gain-measurement range is outside the source samples."
        }
        var clipped = 0L
        var sourcePeak = 0f
        var outputPeak = 0f
        for (index in startSample until endSampleExclusive) {
            val source = samples[index] / PCM16_SCALE
            val scaled = source * gain
            if (scaled < -1f || scaled > 1f) clipped += 1L
            sourcePeak = max(sourcePeak, abs(source))
            outputPeak = max(outputPeak, abs(scaled.coerceIn(-1f, 1f)))
        }
        return WakeReplayGainStatistics(
            sampleCount = (endSampleExclusive - startSample).toLong(),
            clippedSampleCount = clipped,
            sourcePeak = sourcePeak,
            outputPeak = outputPeak,
        )
    }

    fun scaledPcm16Frame(
        samples: ShortArray,
        offset: Int,
        count: Int,
        gain: Float,
    ): FloatArray {
        require(gain.isFinite() && gain > 0f) { "Replay input gain must be positive and finite." }
        require(offset >= 0 && count >= 0 && offset + count <= samples.size) {
            "The replay frame is outside the source samples."
        }
        return FloatArray(count) { frameIndex ->
            (samples[offset + frameIndex] / PCM16_SCALE * gain).coerceIn(-1f, 1f)
        }
    }

    private fun countsByPhrase(
        invocations: List<WakeReplayInvocation>,
    ): Map<WakeReplayPhrase, Int> = WakeReplayPhrase.entries.associateWith { phrase ->
        invocations.count { invocation -> invocation.phrase == phrase }
    }

    private fun unionLength(ranges: List<LongRange>): Long {
        if (ranges.isEmpty()) return 0L
        val ordered = ranges.sortedBy(LongRange::first)
        var total = 0L
        var start = ordered.first().first
        var endExclusive = ordered.first().last + 1L
        ordered.drop(1).forEach { range ->
            val nextEndExclusive = range.last + 1L
            if (range.first <= endExclusive) {
                endExclusive = max(endExclusive, nextEndExclusive)
            } else {
                total += endExclusive - start
                start = range.first
                endExclusive = nextEndExclusive
            }
        }
        return total + endExclusive - start
    }

    private const val PCM16_SCALE = 32_768f
}
