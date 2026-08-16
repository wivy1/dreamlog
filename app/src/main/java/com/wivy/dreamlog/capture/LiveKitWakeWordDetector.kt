package com.wivy.dreamlog.capture

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlin.math.min

internal data class LiveKitWakeWordAssetSpec(
    val assetPath: String,
    val expectedBytes: Long,
    val expectedSha256: String,
) {
    init {
        require(assetPath.isNotBlank() && !assetPath.startsWith('/') && '\\' !in assetPath) {
            "The wake-word model asset path is malformed."
        }
        require(assetPath.split('/').none { component -> component.isEmpty() || component == "." || component == ".." }) {
            "The wake-word model asset path is malformed."
        }
        require(expectedBytes in 1..Int.MAX_VALUE.toLong()) {
            "The wake-word model byte count is invalid."
        }
        require(SHA256_PATTERN.matches(expectedSha256)) {
            "The wake-word model SHA-256 is malformed."
        }
    }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal data class LiveKitWakeWordModelSpecs(
    val melSpectrogram: LiveKitWakeWordAssetSpec,
    val embedding: LiveKitWakeWordAssetSpec,
    val dreamLogHead: LiveKitWakeWordAssetSpec,
    val heyDreamLogHead: LiveKitWakeWordAssetSpec,
) {
    init {
        val models = listOf(melSpectrogram, embedding, dreamLogHead, heyDreamLogHead)
        require(models.map(LiveKitWakeWordAssetSpec::assetPath).distinct().size == models.size) {
            "Wake-word model asset paths must be distinct."
        }
        require(dreamLogHead.expectedSha256 != heyDreamLogHead.expectedSha256) {
            "The two wake-word heads must be distinct models."
        }
    }
}

internal data class LiveKitWakeWordThresholds(
    val dreamLog: Float,
    val heyDreamLog: Float,
) {
    init {
        require(dreamLog.isFinite() && dreamLog in 0f..1f) {
            "The DreamLog threshold must be finite and between zero and one."
        }
        require(heyDreamLog.isFinite() && heyDreamLog in 0f..1f) {
            "The Hey DreamLog threshold must be finite and between zero and one."
        }
    }
}

internal data class LiveKitWakeWordScores(
    val dreamLog: Float,
    val heyDreamLog: Float,
) {
    init {
        require(dreamLog.isFinite() && dreamLog in 0f..1f) {
            "The DreamLog score is outside the probability range."
        }
        require(heyDreamLog.isFinite() && heyDreamLog in 0f..1f) {
            "The Hey DreamLog score is outside the probability range."
        }
    }
}

internal data class LiveKitWakeWordScoreMeasurement(
    val scores: LiveKitWakeWordScores,
    val latencyNanos: Long,
)

internal fun interface LiveKitWakeWordWindowScorer {
    fun score(window: ShortArray): LiveKitWakeWordScoreMeasurement
}

internal data class LiveKitWakeWordEvaluation(
    val evaluationIndex: Long,
    val sampleExclusive: Long,
    val scores: LiveKitWakeWordScores,
    val latencyNanos: Long,
    val detectedPhrase: ApprovedWakePhrase?,
    val candidateTelemetry: LiveKitWakeCandidateTelemetry? = null,
)

enum class LiveKitWakeCandidateGuardOutcome {
    NOT_EVALUATED,
    PASSED,
    FAILED,
}

enum class LiveKitWakeCandidateDecisionReason {
    STRONG_CANDIDATE,
    PERSISTENT_CANDIDATE,
    TRANSIENT_CANDIDATE,
    ACOUSTIC_GUARD_REJECTED,
    PHRASE_AGREEMENT_REJECTED,
    HEY_DREAM_LOG_CONFIDENCE_REJECTED,
    BELOW_THRESHOLD,
    EPISODE_LIMIT_REACHED,
}

/** Content-free evidence emitted once when a near-threshold episode closes. */
data class LiveKitWakeCandidateTelemetry(
    val maxDreamLogScore: Float,
    val maxHeyDreamLogScore: Float,
    val maxDreamLogThresholdRatio: Float,
    val maxHeyDreamLogThresholdRatio: Float,
    val dreamLogThresholdMargin: Float,
    val heyDreamLogThresholdMargin: Float,
    val observedHopCount: Int,
    val maxAdjacentQualifyingHopCount: Int,
    val guardOutcome: LiveKitWakeCandidateGuardOutcome,
    val accepted: Boolean,
    val reason: LiveKitWakeCandidateDecisionReason,
    val acceptedPhrase: ApprovedWakePhrase?,
)

internal data class LiveKitWakeWordTensorMetadata(
    val name: String,
    val shape: List<Long>,
)

internal data class LiveKitWakeWordModelVerification(
    val role: String,
    val assetPath: String,
    val bytes: Long,
    val sha256: String,
    val input: LiveKitWakeWordTensorMetadata,
    val output: LiveKitWakeWordTensorMetadata,
)

internal object LiveKitWakeWordPolicy {
    const val SAMPLE_RATE_HZ = 16_000
    const val WINDOW_SAMPLES = 32_000
    const val HOP_SAMPLES = 1_280
    const val CONFIRMATION_HOPS = 2
    const val MAX_CANDIDATE_EPISODE_HOPS = 6
    const val NEAR_THRESHOLD_RATIO = 0.75f
    const val CONFIRMATION_SUPPORT_RATIO = 0.75f
    const val STRONG_SINGLE_RATIO = 2.5f
    const val HEY_DREAM_LOG_MIN_DREAM_LOG_SUPPORT_RATIO = 0.1f
    const val HEY_DREAM_LOG_PERSISTENT_MIN_RATIO = 1.65f
    const val ACOUSTIC_GUARD_LOOKBACK_HOPS = 12
    const val ACOUSTIC_GUARD_MIN_ADJACENT_ACTIVE_HOPS = 2
    const val ACOUSTIC_GUARD_MIN_HOP_RMS = 180f

    fun detectedPhrase(
        scores: LiveKitWakeWordScores,
        thresholds: LiveKitWakeWordThresholds,
    ): ApprovedWakePhrase? = when {
        scores.heyDreamLog >= thresholds.heyDreamLog -> ApprovedWakePhrase.HEY_DREAM_LOG
        scores.dreamLog >= thresholds.dreamLog -> ApprovedWakePhrase.DREAM_LOG
        else -> null
    }

    /**
     * Candidate-only, allocation-free acoustic activity check. It avoids sharing the stateful
     * narrative Silero instance and runs only after a model threshold crossing. Requiring two
     * adjacent active 80-ms blocks rejects quiet windows and isolated impulses; it is not a
     * phonetic speech classifier and remains subject to Pixel replay tuning.
     */
    fun passesCandidateAcousticGuard(window: ShortArray): Boolean {
        require(window.size == WINDOW_SAMPLES) { "The acoustic guard requires one scoring window." }
        val start = window.size - ACOUSTIC_GUARD_LOOKBACK_HOPS * HOP_SAMPLES
        var adjacentActiveHops = 0
        repeat(ACOUSTIC_GUARD_LOOKBACK_HOPS) { hop ->
            val hopStart = start + hop * HOP_SAMPLES
            var sumSquares = 0L
            for (index in hopStart until hopStart + HOP_SAMPLES) {
                val sample = window[index].toLong()
                sumSquares += sample * sample
            }
            val rms = sqrt(sumSquares.toDouble() / HOP_SAMPLES).toFloat()
            if (rms >= ACOUSTIC_GUARD_MIN_HOP_RMS) {
                adjacentActiveHops += 1
                if (adjacentActiveHops >= ACOUSTIC_GUARD_MIN_ADJACENT_ACTIVE_HOPS) return true
            } else {
                adjacentActiveHops = 0
            }
        }
        return false
    }
}

/**
 * Applies the production 32,000-sample window and 1,280-sample cadence to PCM16 chunks.
 * A union detection clears the window, so another evaluation requires two seconds of fresh audio.
 */
internal class LiveKitWakeWordStream(
    private val scorer: LiveKitWakeWordWindowScorer,
    private val thresholds: LiveKitWakeWordThresholds,
) {
    private val ring = ShortArray(LiveKitWakeWordPolicy.WINDOW_SAMPLES)
    private val chronologicalWindow = ShortArray(LiveKitWakeWordPolicy.WINDOW_SAMPLES)
    private var writeIndex = 0
    private var bufferedSamples = 0
    private var samplesSinceEvaluation = 0
    private var totalSamples = 0L
    private var candidateEpisode: CandidateEpisode? = null

    var evaluationCount: Long = 0L
        private set

    fun accept(
        samples: ShortArray,
        offset: Int = 0,
        count: Int = samples.size - offset,
    ): List<LiveKitWakeWordEvaluation> {
        require(offset >= 0 && count >= 0 && offset <= samples.size - count) {
            "The PCM16 chunk range is outside its source."
        }
        if (count == 0) return emptyList()

        val evaluations = mutableListOf<LiveKitWakeWordEvaluation>()
        var sourceOffset = offset
        var remaining = count
        while (remaining > 0) {
            val untilEvaluation = if (bufferedSamples < ring.size) {
                ring.size - bufferedSamples
            } else {
                LiveKitWakeWordPolicy.HOP_SAMPLES - samplesSinceEvaluation
            }
            val accepted = min(remaining, untilEvaluation)
            appendToRing(samples, sourceOffset, accepted)
            sourceOffset += accepted
            remaining -= accepted
            totalSamples = Math.addExact(totalSamples, accepted.toLong())

            if (bufferedSamples < ring.size) {
                bufferedSamples += accepted
            } else {
                samplesSinceEvaluation += accepted
            }

            val shouldEvaluate = bufferedSamples == ring.size &&
                (samplesSinceEvaluation == 0 ||
                    samplesSinceEvaluation == LiveKitWakeWordPolicy.HOP_SAMPLES)
            if (!shouldEvaluate) continue

            copyChronologicalWindow()
            val measurement = scorer.score(chronologicalWindow)
            require(measurement.latencyNanos >= 0L) { "Inference latency must not be negative." }
            val rawPhrase = LiveKitWakeWordPolicy.detectedPhrase(measurement.scores, thresholds)
            val decision = evaluateCandidate(measurement.scores, rawPhrase, chronologicalWindow)
            evaluationCount = Math.addExact(evaluationCount, 1L)
            evaluations += LiveKitWakeWordEvaluation(
                evaluationIndex = evaluationCount,
                sampleExclusive = totalSamples,
                scores = measurement.scores,
                latencyNanos = measurement.latencyNanos,
                detectedPhrase = decision.acceptedPhrase,
                candidateTelemetry = decision.telemetry,
            )
            if (decision.acceptedPhrase == null) {
                samplesSinceEvaluation = 0
            } else {
                clearWindow()
            }
        }
        return evaluations
    }

    fun reset() {
        clearWindow()
        totalSamples = 0L
        evaluationCount = 0L
        candidateEpisode = null
        ring.fill(0)
        chronologicalWindow.fill(0)
    }

    private fun appendToRing(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ) {
        var sourceOffset = offset
        var remaining = count
        while (remaining > 0) {
            val copied = min(remaining, ring.size - writeIndex)
            samples.copyInto(
                destination = ring,
                destinationOffset = writeIndex,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copied,
            )
            writeIndex = (writeIndex + copied) % ring.size
            sourceOffset += copied
            remaining -= copied
        }
    }

    private fun copyChronologicalWindow() {
        val tail = ring.size - writeIndex
        ring.copyInto(chronologicalWindow, 0, writeIndex, ring.size)
        if (writeIndex > 0) ring.copyInto(chronologicalWindow, tail, 0, writeIndex)
    }

    private fun clearWindow() {
        writeIndex = 0
        bufferedSamples = 0
        samplesSinceEvaluation = 0
    }

    private fun evaluateCandidate(
        scores: LiveKitWakeWordScores,
        rawPhrase: ApprovedWakePhrase?,
        window: ShortArray,
    ): CandidateDecision {
        val dreamRatio = scores.dreamLog / thresholds.dreamLog
        val heyRatio = scores.heyDreamLog / thresholds.heyDreamLog
        val nearThreshold = maxOf(dreamRatio, heyRatio) >= LiveKitWakeWordPolicy.NEAR_THRESHOLD_RATIO

        if (!nearThreshold) {
            val completed = candidateEpisode?.telemetry(
                accepted = false,
                reason = when {
                    candidateEpisode?.phraseAgreementRejected == true ->
                        LiveKitWakeCandidateDecisionReason.PHRASE_AGREEMENT_REJECTED
                    candidateEpisode?.heyDreamLogConfidenceRejected == true ->
                        LiveKitWakeCandidateDecisionReason.HEY_DREAM_LOG_CONFIDENCE_REJECTED
                    candidateEpisode?.guardFailed == true ->
                        LiveKitWakeCandidateDecisionReason.ACOUSTIC_GUARD_REJECTED
                    candidateEpisode?.maxAdjacentQualifyingHops == 0 ->
                        LiveKitWakeCandidateDecisionReason.BELOW_THRESHOLD
                    else -> LiveKitWakeCandidateDecisionReason.TRANSIENT_CANDIDATE
                },
                acceptedPhrase = null,
            )
            candidateEpisode = null
            return CandidateDecision(acceptedPhrase = null, telemetry = completed)
        }

        val episode = candidateEpisode ?: CandidateEpisode(thresholds).also {
            candidateEpisode = it
        }
        episode.observe(scores)
        val strongestRatio = maxOf(dreamRatio, heyRatio)
        val supporting = strongestRatio >= LiveKitWakeWordPolicy.CONFIRMATION_SUPPORT_RATIO
        val shouldEvaluateGuard = rawPhrase != null || (episode.armed && supporting)
        val guardPassed = shouldEvaluateGuard &&
            LiveKitWakeWordPolicy.passesCandidateAcousticGuard(window)
        if (shouldEvaluateGuard) {
            if (guardPassed) episode.guardPassed = true else episode.guardFailed = true
        }

        if (rawPhrase != null && guardPassed) {
            if (!episode.armed) {
                episode.armed = true
                episode.firstFullSampleExclusive = totalSamples
                episode.adjacentQualifyingHops = 1
            } else {
                episode.adjacentQualifyingHops += 1
            }
        } else if (episode.armed && supporting && guardPassed) {
            episode.adjacentQualifyingHops += 1
        } else {
            episode.adjacentQualifyingHops = 0
            episode.armed = false
            episode.firstFullSampleExclusive = null
        }
        episode.maxAdjacentQualifyingHops = maxOf(
            episode.maxAdjacentQualifyingHops,
            episode.adjacentQualifyingHops,
        )

        val strongSingle = rawPhrase != null &&
            guardPassed &&
            strongestRatio >= LiveKitWakeWordPolicy.STRONG_SINGLE_RATIO
        val confirmed = strongSingle ||
            episode.adjacentQualifyingHops >= LiveKitWakeWordPolicy.CONFIRMATION_HOPS
        if (confirmed) {
            val phrase = episode.selectedPhrase()
            if (
                phrase == ApprovedWakePhrase.HEY_DREAM_LOG &&
                episode.maxDreamLogThresholdRatio() <
                LiveKitWakeWordPolicy.HEY_DREAM_LOG_MIN_DREAM_LOG_SUPPORT_RATIO
            ) {
                episode.phraseAgreementRejected = true
            } else if (
                !strongSingle &&
                phrase == ApprovedWakePhrase.HEY_DREAM_LOG &&
                episode.maxHeyDreamLogThresholdRatio() <
                LiveKitWakeWordPolicy.HEY_DREAM_LOG_PERSISTENT_MIN_RATIO
            ) {
                episode.heyDreamLogConfidenceRejected = true
            } else {
                val telemetry = episode.telemetry(
                    accepted = true,
                    reason = if (strongSingle) {
                        LiveKitWakeCandidateDecisionReason.STRONG_CANDIDATE
                    } else {
                        LiveKitWakeCandidateDecisionReason.PERSISTENT_CANDIDATE
                    },
                    acceptedPhrase = phrase,
                )
                candidateEpisode = null
                return CandidateDecision(acceptedPhrase = phrase, telemetry = telemetry)
            }
        }
        if (episode.observedHops >= LiveKitWakeWordPolicy.MAX_CANDIDATE_EPISODE_HOPS) {
            val telemetry = episode.telemetry(
                accepted = false,
                reason = when {
                    episode.phraseAgreementRejected ->
                        LiveKitWakeCandidateDecisionReason.PHRASE_AGREEMENT_REJECTED
                    episode.heyDreamLogConfidenceRejected ->
                        LiveKitWakeCandidateDecisionReason.HEY_DREAM_LOG_CONFIDENCE_REJECTED
                    episode.guardFailed ->
                        LiveKitWakeCandidateDecisionReason.ACOUSTIC_GUARD_REJECTED
                    else -> LiveKitWakeCandidateDecisionReason.EPISODE_LIMIT_REACHED
                },
                acceptedPhrase = null,
            )
            candidateEpisode = null
            return CandidateDecision(acceptedPhrase = null, telemetry = telemetry)
        }
        return CandidateDecision(acceptedPhrase = null, telemetry = null)
    }

    private data class CandidateDecision(
        val acceptedPhrase: ApprovedWakePhrase?,
        val telemetry: LiveKitWakeCandidateTelemetry?,
    )

    private class CandidateEpisode(
        private val thresholds: LiveKitWakeWordThresholds,
    ) {
        var maxDreamLogScore = 0f
        var maxHeyDreamLogScore = 0f
        var observedHops = 0
        var adjacentQualifyingHops = 0
        var maxAdjacentQualifyingHops = 0
        var guardPassed = false
        var guardFailed = false
        var armed = false
        var firstFullSampleExclusive: Long? = null
        var phraseAgreementRejected = false
        var heyDreamLogConfidenceRejected = false

        fun observe(scores: LiveKitWakeWordScores) {
            maxDreamLogScore = maxOf(maxDreamLogScore, scores.dreamLog)
            maxHeyDreamLogScore = maxOf(maxHeyDreamLogScore, scores.heyDreamLog)
            observedHops += 1
        }

        fun maxDreamLogThresholdRatio() = maxDreamLogScore / thresholds.dreamLog

        fun maxHeyDreamLogThresholdRatio() = maxHeyDreamLogScore / thresholds.heyDreamLog

        fun selectedPhrase(): ApprovedWakePhrase =
            if (maxHeyDreamLogThresholdRatio() >= maxDreamLogThresholdRatio()) {
                ApprovedWakePhrase.HEY_DREAM_LOG
            } else {
                ApprovedWakePhrase.DREAM_LOG
            }

        fun telemetry(
            accepted: Boolean,
            reason: LiveKitWakeCandidateDecisionReason,
            acceptedPhrase: ApprovedWakePhrase?,
        ) = LiveKitWakeCandidateTelemetry(
            maxDreamLogScore = maxDreamLogScore,
            maxHeyDreamLogScore = maxHeyDreamLogScore,
            maxDreamLogThresholdRatio = maxDreamLogThresholdRatio(),
            maxHeyDreamLogThresholdRatio = maxHeyDreamLogThresholdRatio(),
            dreamLogThresholdMargin = maxDreamLogScore - thresholds.dreamLog,
            heyDreamLogThresholdMargin = maxHeyDreamLogScore - thresholds.heyDreamLog,
            observedHopCount = observedHops,
            maxAdjacentQualifyingHopCount = maxAdjacentQualifyingHops,
            guardOutcome = when {
                guardPassed -> LiveKitWakeCandidateGuardOutcome.PASSED
                guardFailed -> LiveKitWakeCandidateGuardOutcome.FAILED
                else -> LiveKitWakeCandidateGuardOutcome.NOT_EVALUATED
            },
            accepted = accepted,
            reason = reason,
            acceptedPhrase = acceptedPhrase,
        )
    }
}

/** Synchronous production detector. The capture worker remains the sole caller. */
internal class LiveKitWakeWordDetector private constructor(
    private val runtime: LiveKitWakeWordOrtRuntime,
    thresholds: LiveKitWakeWordThresholds,
) : AutoCloseable {
    private val stream = LiveKitWakeWordStream(runtime, thresholds)
    private var closed = false

    val evaluationCount: Long
        get() = stream.evaluationCount

    val ortVersion: String
        get() = runtime.ortVersion

    val modelVerification: List<LiveKitWakeWordModelVerification>
        get() = runtime.modelVerification

    @Synchronized
    fun accept(
        samples: ShortArray,
        offset: Int = 0,
        count: Int = samples.size - offset,
    ): List<LiveKitWakeWordEvaluation> {
        check(!closed) { "The LiveKit wake-word detector is closed." }
        return stream.accept(samples, offset, count)
    }

    @Synchronized
    fun reset() {
        check(!closed) { "The LiveKit wake-word detector is closed." }
        stream.reset()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stream.reset()
        runtime.close()
    }

    companion object {
        val PRODUCTION_MODEL_SPECS = LiveKitWakeWordModelSpecs(
            melSpectrogram = LiveKitWakeWordAssetSpec(
                assetPath = "livekit-wakeword/melspectrogram.onnx",
                expectedBytes = 1_087_958,
                expectedSha256 = "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f",
            ),
            embedding = LiveKitWakeWordAssetSpec(
                assetPath = "livekit-wakeword/embedding_model.onnx",
                expectedBytes = 1_326_578,
                expectedSha256 = "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f",
            ),
            dreamLogHead = LiveKitWakeWordAssetSpec(
                assetPath = "livekit-wakeword/dreamlog.onnx",
                expectedBytes = 174_843,
                expectedSha256 = "8da21a475eda39aa3b315d70238ae7eae447447daec342a9c1404af40b281f48",
            ),
            heyDreamLogHead = LiveKitWakeWordAssetSpec(
                assetPath = "livekit-wakeword/hey_dreamlog.onnx",
                expectedBytes = 174_843,
                expectedSha256 = "5866e2f201133545929a2a92ec9d4e6c71b673f3a6dee9d065b9a3eac23856c0",
            ),
        )

        val PRODUCTION_THRESHOLDS = LiveKitWakeWordThresholds(
            dreamLog = 0.24f,
            heyDreamLog = 0.15f,
        )

        fun createDefault(context: Context): LiveKitWakeWordDetector = create(
            assetManager = context.assets,
            modelSpecs = PRODUCTION_MODEL_SPECS,
            thresholds = PRODUCTION_THRESHOLDS,
        )

        fun create(
            assetManager: AssetManager,
            modelSpecs: LiveKitWakeWordModelSpecs,
            thresholds: LiveKitWakeWordThresholds,
        ): LiveKitWakeWordDetector = LiveKitWakeWordDetector(
            runtime = LiveKitWakeWordOrtRuntime(assetManager, modelSpecs),
            thresholds = thresholds,
        )
    }
}

private class LiveKitWakeWordOrtRuntime(
    assetManager: AssetManager,
    modelSpecs: LiveKitWakeWordModelSpecs,
) : LiveKitWakeWordWindowScorer, AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    val ortVersion: String = environment.version
    private val resources: Resources
    val modelVerification: List<LiveKitWakeWordModelVerification>
    private var closed = false

    init {
        require(ortVersion == REQUIRED_ORT_VERSION) {
            "The LiveKit wake-word detector requires ORT $REQUIRED_ORT_VERSION, observed $ortVersion."
        }
        resources = Resources.open(environment, assetManager, modelSpecs)
        modelVerification = resources.modelVerification
    }

    override fun score(window: ShortArray): LiveKitWakeWordScoreMeasurement {
        check(!closed) { "The LiveKit wake-word runtime is closed." }
        require(window.size == LiveKitWakeWordPolicy.WINDOW_SAMPLES) {
            "The LiveKit frontend requires exactly 32,000 PCM16 samples."
        }

        val startedNanos = System.nanoTime()
        for (index in window.indices) {
            resources.melInputBuffer.put(index, window[index] / PCM16_SCALE)
        }
        resources.melSession.run(
            mapOf(MEL_INPUT_NAME to resources.melInputTensor),
            mapOf(MEL_OUTPUT_NAME to resources.melOutputTensor),
        ).use { }

        for (embeddingIndex in 0 until EMBEDDING_COUNT) {
            val startFrame = embeddingIndex * EMBEDDING_STRIDE_FRAMES
            val destinationBase = embeddingIndex * EMBEDDING_WINDOW_FRAMES * MEL_BIN_COUNT
            for (frameOffset in 0 until EMBEDDING_WINDOW_FRAMES) {
                val sourceBase = (startFrame + frameOffset) * MEL_BIN_COUNT
                val destinationFrameBase = destinationBase + frameOffset * MEL_BIN_COUNT
                for (melBin in 0 until MEL_BIN_COUNT) {
                    val raw = resources.melOutputBuffer.get(sourceBase + melBin)
                    resources.embeddingInputBuffer.put(destinationFrameBase + melBin, raw * 0.1f + 2f)
                }
            }
        }
        resources.embeddingSession.run(
            mapOf(EMBEDDING_INPUT_NAME to resources.embeddingInputTensor),
            mapOf(EMBEDDING_OUTPUT_NAME to resources.embeddingOutputTensor),
        ).use { }
        resources.dreamLogSession.run(
            mapOf(HEAD_INPUT_NAME to resources.headInputTensor),
            mapOf(HEAD_OUTPUT_NAME to resources.dreamLogOutputTensor),
        ).use { }
        resources.heyDreamLogSession.run(
            mapOf(HEAD_INPUT_NAME to resources.headInputTensor),
            mapOf(HEAD_OUTPUT_NAME to resources.heyDreamLogOutputTensor),
        ).use { }

        return LiveKitWakeWordScoreMeasurement(
            scores = LiveKitWakeWordScores(
                dreamLog = resources.dreamLogOutputBuffer.get(0),
                heyDreamLog = resources.heyDreamLogOutputBuffer.get(0),
            ),
            latencyNanos = System.nanoTime() - startedNanos,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        resources.close()
    }

    private class Resources private constructor(
        val melSession: OrtSession,
        val embeddingSession: OrtSession,
        val dreamLogSession: OrtSession,
        val heyDreamLogSession: OrtSession,
        val melInputBuffer: FloatBuffer,
        val melOutputBuffer: FloatBuffer,
        val embeddingInputBuffer: FloatBuffer,
        val dreamLogOutputBuffer: FloatBuffer,
        val heyDreamLogOutputBuffer: FloatBuffer,
        val melInputTensor: OnnxTensor,
        val melOutputTensor: OnnxTensor,
        val embeddingInputTensor: OnnxTensor,
        val embeddingOutputTensor: OnnxTensor,
        val headInputTensor: OnnxTensor,
        val dreamLogOutputTensor: OnnxTensor,
        val heyDreamLogOutputTensor: OnnxTensor,
        val modelVerification: List<LiveKitWakeWordModelVerification>,
    ) : AutoCloseable {
        override fun close() {
            val failures = mutableListOf<Throwable>()
            listOf(
                heyDreamLogOutputTensor,
                dreamLogOutputTensor,
                headInputTensor,
                embeddingOutputTensor,
                embeddingInputTensor,
                melOutputTensor,
                melInputTensor,
            ).forEach { tensor -> runCatching(tensor::close).onFailure(failures::add) }
            listOf(
                heyDreamLogSession,
                dreamLogSession,
                embeddingSession,
                melSession,
            ).forEach { session -> runCatching(session::close).onFailure(failures::add) }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }

        companion object {
            fun open(
                environment: OrtEnvironment,
                assetManager: AssetManager,
                modelSpecs: LiveKitWakeWordModelSpecs,
            ): Resources {
                val openedSessions = mutableListOf<OrtSession>()
                val openedTensors = mutableListOf<OnnxTensor>()
                try {
                    val melSession = createCpuSession(
                        environment,
                        loadVerifiedAsset(assetManager, "melspectrogram", modelSpecs.melSpectrogram),
                    ).also(openedSessions::add)
                    val embeddingSession = createCpuSession(
                        environment,
                        loadVerifiedAsset(assetManager, "embedding", modelSpecs.embedding),
                    ).also(openedSessions::add)
                    val dreamLogSession = createCpuSession(
                        environment,
                        loadVerifiedAsset(assetManager, "dreamlog_head", modelSpecs.dreamLogHead),
                    ).also(openedSessions::add)
                    val heyDreamLogSession = createCpuSession(
                        environment,
                        loadVerifiedAsset(assetManager, "hey_dreamlog_head", modelSpecs.heyDreamLogHead),
                    ).also(openedSessions::add)

                    val verification = listOf(
                        verifySession(
                            "melspectrogram",
                            modelSpecs.melSpectrogram,
                            melSession,
                            MEL_INPUT_NAME,
                            longArrayOf(DYNAMIC_DIMENSION, DYNAMIC_DIMENSION),
                            MEL_OUTPUT_NAME,
                            longArrayOf(DYNAMIC_DIMENSION, 1, DYNAMIC_DIMENSION, MEL_BIN_COUNT.toLong()),
                        ),
                        verifySession(
                            "embedding",
                            modelSpecs.embedding,
                            embeddingSession,
                            EMBEDDING_INPUT_NAME,
                            longArrayOf(
                                DYNAMIC_DIMENSION,
                                EMBEDDING_WINDOW_FRAMES.toLong(),
                                MEL_BIN_COUNT.toLong(),
                                1,
                            ),
                            EMBEDDING_OUTPUT_NAME,
                            longArrayOf(DYNAMIC_DIMENSION, 1, 1, EMBEDDING_DIMENSION.toLong()),
                        ),
                        verifySession(
                            "dreamlog_head",
                            modelSpecs.dreamLogHead,
                            dreamLogSession,
                            HEAD_INPUT_NAME,
                            longArrayOf(
                                DYNAMIC_DIMENSION,
                                EMBEDDING_COUNT.toLong(),
                                EMBEDDING_DIMENSION.toLong(),
                            ),
                            HEAD_OUTPUT_NAME,
                            longArrayOf(DYNAMIC_DIMENSION, 1),
                        ),
                        verifySession(
                            "hey_dreamlog_head",
                            modelSpecs.heyDreamLogHead,
                            heyDreamLogSession,
                            HEAD_INPUT_NAME,
                            longArrayOf(
                                DYNAMIC_DIMENSION,
                                EMBEDDING_COUNT.toLong(),
                                EMBEDDING_DIMENSION.toLong(),
                            ),
                            HEAD_OUTPUT_NAME,
                            longArrayOf(DYNAMIC_DIMENSION, 1),
                        ),
                    )

                    val melInputBuffer = directFloatBuffer(LiveKitWakeWordPolicy.WINDOW_SAMPLES)
                    val melOutputBuffer = directFloatBuffer(MEL_FRAME_COUNT * MEL_BIN_COUNT)
                    val embeddingInputBuffer = directFloatBuffer(
                        EMBEDDING_COUNT * EMBEDDING_WINDOW_FRAMES * MEL_BIN_COUNT,
                    )
                    val embeddingOutputBuffer = directFloatBuffer(EMBEDDING_COUNT * EMBEDDING_DIMENSION)
                    val dreamLogOutputBuffer = directFloatBuffer(1)
                    val heyDreamLogOutputBuffer = directFloatBuffer(1)

                    val melInputTensor = OnnxTensor.createTensor(
                        environment,
                        melInputBuffer,
                        longArrayOf(1, LiveKitWakeWordPolicy.WINDOW_SAMPLES.toLong()),
                    ).also(openedTensors::add)
                    val melOutputTensor = OnnxTensor.createTensor(
                        environment,
                        melOutputBuffer,
                        longArrayOf(1, 1, MEL_FRAME_COUNT.toLong(), MEL_BIN_COUNT.toLong()),
                    ).also(openedTensors::add)
                    val embeddingInputTensor = OnnxTensor.createTensor(
                        environment,
                        embeddingInputBuffer,
                        longArrayOf(
                            EMBEDDING_COUNT.toLong(),
                            EMBEDDING_WINDOW_FRAMES.toLong(),
                            MEL_BIN_COUNT.toLong(),
                            1,
                        ),
                    ).also(openedTensors::add)
                    val embeddingOutputTensor = OnnxTensor.createTensor(
                        environment,
                        embeddingOutputBuffer.duplicate().apply { clear() },
                        longArrayOf(EMBEDDING_COUNT.toLong(), 1, 1, EMBEDDING_DIMENSION.toLong()),
                    ).also(openedTensors::add)
                    val headInputTensor = OnnxTensor.createTensor(
                        environment,
                        embeddingOutputBuffer.duplicate().apply { clear() },
                        longArrayOf(1, EMBEDDING_COUNT.toLong(), EMBEDDING_DIMENSION.toLong()),
                    ).also(openedTensors::add)
                    val dreamLogOutputTensor = OnnxTensor.createTensor(
                        environment,
                        dreamLogOutputBuffer,
                        longArrayOf(1, 1),
                    ).also(openedTensors::add)
                    val heyDreamLogOutputTensor = OnnxTensor.createTensor(
                        environment,
                        heyDreamLogOutputBuffer,
                        longArrayOf(1, 1),
                    ).also(openedTensors::add)

                    return Resources(
                        melSession = melSession,
                        embeddingSession = embeddingSession,
                        dreamLogSession = dreamLogSession,
                        heyDreamLogSession = heyDreamLogSession,
                        melInputBuffer = melInputBuffer,
                        melOutputBuffer = melOutputBuffer,
                        embeddingInputBuffer = embeddingInputBuffer,
                        dreamLogOutputBuffer = dreamLogOutputBuffer,
                        heyDreamLogOutputBuffer = heyDreamLogOutputBuffer,
                        melInputTensor = melInputTensor,
                        melOutputTensor = melOutputTensor,
                        embeddingInputTensor = embeddingInputTensor,
                        embeddingOutputTensor = embeddingOutputTensor,
                        headInputTensor = headInputTensor,
                        dreamLogOutputTensor = dreamLogOutputTensor,
                        heyDreamLogOutputTensor = heyDreamLogOutputTensor,
                        modelVerification = verification,
                    )
                } catch (failure: Throwable) {
                    openedTensors.asReversed().forEach { tensor ->
                        runCatching(tensor::close).onFailure(failure::addSuppressed)
                    }
                    openedSessions.asReversed().forEach { session ->
                        runCatching(session::close).onFailure(failure::addSuppressed)
                    }
                    throw failure
                }
            }
        }
    }

    private companion object {
        const val REQUIRED_ORT_VERSION = "1.27.0"
        const val DYNAMIC_DIMENSION = -1L
        const val MEL_FRAME_COUNT = 197
        const val MEL_BIN_COUNT = 32
        const val EMBEDDING_COUNT = 16
        const val EMBEDDING_WINDOW_FRAMES = 76
        const val EMBEDDING_STRIDE_FRAMES = 8
        const val EMBEDDING_DIMENSION = 96
        const val PCM16_SCALE = 32_768f

        const val MEL_INPUT_NAME = "input"
        const val MEL_OUTPUT_NAME = "output"
        const val EMBEDDING_INPUT_NAME = "input_1"
        const val EMBEDDING_OUTPUT_NAME = "conv2d_19"
        const val HEAD_INPUT_NAME = "embeddings"
        const val HEAD_OUTPUT_NAME = "score"

        fun createCpuSession(
            environment: OrtEnvironment,
            model: ByteBuffer,
        ): OrtSession {
            val options = OrtSession.SessionOptions()
            return try {
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setInterOpNumThreads(1)
                options.setIntraOpNumThreads(1)
                environment.createSession(model, options)
            } finally {
                options.close()
            }
        }

        fun loadVerifiedAsset(
            assetManager: AssetManager,
            role: String,
            spec: LiveKitWakeWordAssetSpec,
        ): ByteBuffer {
            val output = ByteBuffer.allocateDirect(spec.expectedBytes.toInt()).order(ByteOrder.nativeOrder())
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            assetManager.open(spec.assetPath, AssetManager.ACCESS_STREAMING).use { input ->
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count == 0) continue
                    totalBytes += count
                    require(totalBytes <= spec.expectedBytes) {
                        "The $role model byte count differs from its pinned identity."
                    }
                    output.put(chunk, 0, count)
                    digest.update(chunk, 0, count)
                }
            }
            require(totalBytes == spec.expectedBytes) {
                "The $role model byte count differs from its pinned identity."
            }
            val observedSha256 = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            require(observedSha256 == spec.expectedSha256) {
                "The $role model SHA-256 differs from its pinned identity."
            }
            return output.apply { flip() }
        }

        fun verifySession(
            role: String,
            model: LiveKitWakeWordAssetSpec,
            session: OrtSession,
            inputName: String,
            inputShape: LongArray,
            outputName: String,
            outputShape: LongArray,
        ): LiveKitWakeWordModelVerification {
            require(session.inputNames == setOf(inputName)) {
                "The $role model has unexpected inputs: ${session.inputNames}."
            }
            require(session.outputNames == setOf(outputName)) {
                "The $role model has unexpected outputs: ${session.outputNames}."
            }
            val observedInput = requireTensor(session.inputInfo.getValue(inputName), inputShape)
            val observedOutput = requireTensor(session.outputInfo.getValue(outputName), outputShape)
            return LiveKitWakeWordModelVerification(
                role = role,
                assetPath = model.assetPath,
                bytes = model.expectedBytes,
                sha256 = model.expectedSha256,
                input = LiveKitWakeWordTensorMetadata(inputName, observedInput.toList()),
                output = LiveKitWakeWordTensorMetadata(outputName, observedOutput.toList()),
            )
        }

        fun requireTensor(
            node: NodeInfo,
            expectedShape: LongArray,
        ): LongArray {
            val tensor = node.info as? TensorInfo
                ?: throw IllegalArgumentException("${node.name} is not a tensor.")
            require(tensor.type == OnnxJavaType.FLOAT) { "${node.name} is not float32." }
            val observed = tensor.shape
            require(observed.contentEquals(expectedShape)) {
                "${node.name} has shape ${observed.contentToString()}, " +
                    "expected ${expectedShape.contentToString()}."
            }
            return observed
        }

        fun directFloatBuffer(elementCount: Int): FloatBuffer = ByteBuffer
            .allocateDirect(elementCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }
}
