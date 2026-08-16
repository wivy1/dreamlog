package com.wivy.dreamlog.capture

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.sqrt

data class LiveKitFixtureModelSpec(
    val role: String,
    val file: File,
    val expectedBytes: Long,
    val expectedSha256: String,
)

data class LiveKitFixtureModelSet(
    val melSpectrogram: LiveKitFixtureModelSpec,
    val embedding: LiveKitFixtureModelSpec,
    val dreamLogHead: LiveKitFixtureModelSpec,
    val heyDreamLogHead: LiveKitFixtureModelSpec,
)

data class LiveKitFixtureThresholds(
    val dreamLog: Float,
    val heyDreamLog: Float,
) {
    init {
        require(dreamLog.isFinite() && dreamLog in 0f..1f) {
            "The dreamlog threshold must be finite and between zero and one."
        }
        require(heyDreamLog.isFinite() && heyDreamLog in 0f..1f) {
            "The hey-dreamlog threshold must be finite and between zero and one."
        }
    }
}

data class LiveKitFixtureScores(
    val dreamLog: Float,
    val heyDreamLog: Float,
) {
    init {
        require(dreamLog.isFinite() && dreamLog in 0f..1f) {
            "The dreamlog score is outside the probability range."
        }
        require(heyDreamLog.isFinite() && heyDreamLog in 0f..1f) {
            "The hey-dreamlog score is outside the probability range."
        }
    }
}

data class LiveKitFixtureScoreMeasurement(
    val scores: LiveKitFixtureScores,
    val latencyNanos: Long,
)

fun interface LiveKitFixtureWindowScorer {
    fun score(window: ShortArray): LiveKitFixtureScoreMeasurement
}

data class LiveKitFixtureEvaluation(
    val sampleExclusive: Long,
    val triggerSampleExclusive: Long? = null,
    val scores: LiveKitFixtureScores,
    val latencyNanos: Long,
    val detectedPhrase: WakeReplayPhrase?,
    val candidateTelemetry: LiveKitFixtureCandidateTelemetry? = null,
)

data class LiveKitFixtureCandidateTelemetry(
    val maxDreamLogScore: Float,
    val maxHeyDreamLogScore: Float,
    val maxDreamLogThresholdRatio: Float,
    val maxHeyDreamLogThresholdRatio: Float,
    val dreamLogThresholdMargin: Float,
    val heyDreamLogThresholdMargin: Float,
    val observedHopCount: Int,
    val maxAdjacentQualifyingHopCount: Int,
    val guardOutcome: String,
    val accepted: Boolean,
    val reason: String,
    val acceptedPhrase: WakeReplayPhrase?,
)

data class LiveKitFixtureTensorMetadata(
    val name: String,
    val shape: List<Long>,
)

data class LiveKitFixtureModelVerification(
    val role: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val input: LiveKitFixtureTensorMetadata,
    val output: LiveKitFixtureTensorMetadata,
)

/** Pure joint-head decision and 32k/1280 replay state used by device and JVM fixtures. */
object LiveKitWakeWordFixturePolicy {
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
        scores: LiveKitFixtureScores,
        thresholds: LiveKitFixtureThresholds,
    ): WakeReplayPhrase? = when {
        scores.heyDreamLog >= thresholds.heyDreamLog -> WakeReplayPhrase.HEY_DREAM_LOG
        scores.dreamLog >= thresholds.dreamLog -> WakeReplayPhrase.DREAM_LOG
        else -> null
    }

    fun passesCandidateAcousticGuard(window: ShortArray): Boolean {
        require(window.size == WINDOW_SAMPLES)
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
 * Replays arbitrary PCM16 chunks against the exact LiveKit sliding-window contract.
 *
 * One union detection clears the complete 32k window. This mirrors the upstream
 * listener: the next possible evaluation occurs only after two seconds of fresh audio.
 */
class LiveKitWakeWordFixtureStream(
    private val scorer: LiveKitFixtureWindowScorer,
    private val thresholds: LiveKitFixtureThresholds,
) {
    private val ring = ShortArray(LiveKitWakeWordFixturePolicy.WINDOW_SAMPLES)
    private val chronologicalWindow = ShortArray(LiveKitWakeWordFixturePolicy.WINDOW_SAMPLES)
    private var writeIndex = 0
    private var bufferedSamples = 0
    private var samplesSinceEvaluation = 0
    private var totalSamples = 0L
    private var candidateEpisode: CandidateEpisode? = null

    fun accept(
        samples: ShortArray,
        offset: Int = 0,
        count: Int = samples.size - offset,
    ): List<LiveKitFixtureEvaluation> {
        require(offset >= 0 && count >= 0 && offset + count <= samples.size) {
            "The replay chunk range is outside its PCM source."
        }
        if (count == 0) return emptyList()

        val evaluations = mutableListOf<LiveKitFixtureEvaluation>()
        var sourceOffset = offset
        var remaining = count
        while (remaining > 0) {
            val untilEvaluation = if (bufferedSamples < ring.size) {
                ring.size - bufferedSamples
            } else {
                LiveKitWakeWordFixturePolicy.HOP_SAMPLES - samplesSinceEvaluation
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
                    samplesSinceEvaluation == LiveKitWakeWordFixturePolicy.HOP_SAMPLES)
            if (!shouldEvaluate) continue

            copyChronologicalWindow()
            val measurement = scorer.score(chronologicalWindow)
            require(measurement.latencyNanos >= 0L) { "Inference latency must not be negative." }
            val rawPhrase = LiveKitWakeWordFixturePolicy.detectedPhrase(
                scores = measurement.scores,
                thresholds = thresholds,
            )
            val decision = evaluateCandidate(measurement.scores, rawPhrase, chronologicalWindow)
            evaluations += LiveKitFixtureEvaluation(
                sampleExclusive = totalSamples,
                triggerSampleExclusive = decision.triggerSampleExclusive,
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
        candidateEpisode = null
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
        scores: LiveKitFixtureScores,
        rawPhrase: WakeReplayPhrase?,
        window: ShortArray,
    ): CandidateDecision {
        val dreamRatio = scores.dreamLog / thresholds.dreamLog
        val heyRatio = scores.heyDreamLog / thresholds.heyDreamLog
        val near = maxOf(dreamRatio, heyRatio) >= LiveKitWakeWordFixturePolicy.NEAR_THRESHOLD_RATIO
        if (!near) {
            val telemetry = candidateEpisode?.telemetry(
                accepted = false,
                reason = when {
                    candidateEpisode?.phraseAgreementRejected == true ->
                        "phrase_agreement_rejected"
                    candidateEpisode?.heyDreamLogConfidenceRejected == true ->
                        "hey_dream_log_confidence_rejected"
                    candidateEpisode?.guardFailed == true -> "acoustic_guard_rejected"
                    candidateEpisode?.maxAdjacentQualifyingHops == 0 -> "below_threshold"
                    else -> "transient_candidate"
                },
                acceptedPhrase = null,
            )
            candidateEpisode = null
            return CandidateDecision(null, telemetry, null)
        }

        val episode = candidateEpisode ?: CandidateEpisode(thresholds).also { candidateEpisode = it }
        episode.observe(scores)
        val strongestRatio = maxOf(dreamRatio, heyRatio)
        val supporting = strongestRatio >= LiveKitWakeWordFixturePolicy.CONFIRMATION_SUPPORT_RATIO
        val shouldEvaluateGuard = rawPhrase != null || (episode.armed && supporting)
        val guardPassed = shouldEvaluateGuard &&
            LiveKitWakeWordFixturePolicy.passesCandidateAcousticGuard(window)
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
            strongestRatio >= LiveKitWakeWordFixturePolicy.STRONG_SINGLE_RATIO
        val confirmed = strongSingle ||
            episode.adjacentQualifyingHops >= LiveKitWakeWordFixturePolicy.CONFIRMATION_HOPS
        if (confirmed) {
            val phrase = episode.selectedPhrase()
            if (
                phrase == WakeReplayPhrase.HEY_DREAM_LOG &&
                episode.maxDreamLogThresholdRatio() <
                LiveKitWakeWordFixturePolicy.HEY_DREAM_LOG_MIN_DREAM_LOG_SUPPORT_RATIO
            ) {
                episode.phraseAgreementRejected = true
            } else if (
                !strongSingle &&
                phrase == WakeReplayPhrase.HEY_DREAM_LOG &&
                episode.maxHeyDreamLogThresholdRatio() <
                LiveKitWakeWordFixturePolicy.HEY_DREAM_LOG_PERSISTENT_MIN_RATIO
            ) {
                episode.heyDreamLogConfidenceRejected = true
            } else {
                val telemetry = episode.telemetry(
                    true,
                    if (strongSingle) "strong_candidate" else "persistent_candidate",
                    phrase,
                )
                val triggerSampleExclusive = checkNotNull(episode.firstFullSampleExclusive)
                candidateEpisode = null
                return CandidateDecision(phrase, telemetry, triggerSampleExclusive)
            }
        }
        if (episode.observedHops >= LiveKitWakeWordFixturePolicy.MAX_CANDIDATE_EPISODE_HOPS) {
            val telemetry = episode.telemetry(
                accepted = false,
                reason = when {
                    episode.phraseAgreementRejected -> "phrase_agreement_rejected"
                    episode.heyDreamLogConfidenceRejected ->
                        "hey_dream_log_confidence_rejected"
                    episode.guardFailed -> "acoustic_guard_rejected"
                    else -> "episode_limit_reached"
                },
                acceptedPhrase = null,
            )
            candidateEpisode = null
            return CandidateDecision(null, telemetry, null)
        }
        return CandidateDecision(null, null, null)
    }

    private data class CandidateDecision(
        val acceptedPhrase: WakeReplayPhrase?,
        val telemetry: LiveKitFixtureCandidateTelemetry?,
        val triggerSampleExclusive: Long?,
    )

    private class CandidateEpisode(private val thresholds: LiveKitFixtureThresholds) {
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

        fun observe(scores: LiveKitFixtureScores) {
            maxDreamLogScore = maxOf(maxDreamLogScore, scores.dreamLog)
            maxHeyDreamLogScore = maxOf(maxHeyDreamLogScore, scores.heyDreamLog)
            observedHops += 1
        }

        fun maxDreamLogThresholdRatio() = maxDreamLogScore / thresholds.dreamLog

        fun maxHeyDreamLogThresholdRatio() = maxHeyDreamLogScore / thresholds.heyDreamLog

        fun selectedPhrase(): WakeReplayPhrase =
            if (maxHeyDreamLogThresholdRatio() >= maxDreamLogThresholdRatio()) {
                WakeReplayPhrase.HEY_DREAM_LOG
            } else {
                WakeReplayPhrase.DREAM_LOG
            }

        fun telemetry(
            accepted: Boolean,
            reason: String,
            acceptedPhrase: WakeReplayPhrase?,
        ) = LiveKitFixtureCandidateTelemetry(
            maxDreamLogScore = maxDreamLogScore,
            maxHeyDreamLogScore = maxHeyDreamLogScore,
            maxDreamLogThresholdRatio = maxDreamLogThresholdRatio(),
            maxHeyDreamLogThresholdRatio = maxHeyDreamLogThresholdRatio(),
            dreamLogThresholdMargin = maxDreamLogScore - thresholds.dreamLog,
            heyDreamLogThresholdMargin = maxHeyDreamLogScore - thresholds.heyDreamLog,
            observedHopCount = observedHops,
            maxAdjacentQualifyingHopCount = maxAdjacentQualifyingHops,
            guardOutcome = when {
                guardPassed -> "passed"
                guardFailed -> "failed"
                else -> "not_evaluated"
            },
            accepted = accepted,
            reason = reason,
            acceptedPhrase = acceptedPhrase,
        )
    }
}

/** Four-session ORT 1.27 CPU runtime used only by the isolated deviceTest fixture. */
class LiveKitWakeWordFixtureRuntime(
    privateRoot: File,
    models: LiveKitFixtureModelSet,
) : LiveKitFixtureWindowScorer, AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    val ortVersion: String = environment.version

    private val melSession: OrtSession
    private val embeddingSession: OrtSession
    private val dreamLogSession: OrtSession
    private val heyDreamLogSession: OrtSession

    private val melInputBuffer = directFloatBuffer(LiveKitWakeWordFixturePolicy.WINDOW_SAMPLES)
    private val melOutputBuffer = directFloatBuffer(MEL_FRAME_COUNT * MEL_BIN_COUNT)
    private val embeddingInputBuffer = directFloatBuffer(
        EMBEDDING_COUNT * EMBEDDING_WINDOW_FRAMES * MEL_BIN_COUNT,
    )
    private val embeddingOutputBuffer = directFloatBuffer(EMBEDDING_COUNT * EMBEDDING_DIMENSION)
    private val dreamLogOutputBuffer = directFloatBuffer(1)
    private val heyDreamLogOutputBuffer = directFloatBuffer(1)

    private val melInputTensor: OnnxTensor
    private val melOutputTensor: OnnxTensor
    private val embeddingInputTensor: OnnxTensor
    private val embeddingOutputTensor: OnnxTensor
    private val headInputTensor: OnnxTensor
    private val dreamLogOutputTensor: OnnxTensor
    private val heyDreamLogOutputTensor: OnnxTensor

    val modelVerification: List<LiveKitFixtureModelVerification>
    private var closed = false

    init {
        require(ortVersion == REQUIRED_ORT_VERSION) {
            "The LiveKit fixture requires ORT $REQUIRED_ORT_VERSION, observed $ortVersion."
        }
        val orderedModels = listOf(
            models.melSpectrogram,
            models.embedding,
            models.dreamLogHead,
            models.heyDreamLogHead,
        )
        require(orderedModels.map(LiveKitFixtureModelSpec::role) == EXPECTED_MODEL_ROLES) {
            "The four staged ONNX roles do not match the fixture contract."
        }
        orderedModels.forEach { model -> verifyPrivateModel(privateRoot, model) }

        melSession = createCpuSession(models.melSpectrogram.file)
        embeddingSession = createCpuSession(models.embedding.file)
        dreamLogSession = createCpuSession(models.dreamLogHead.file)
        heyDreamLogSession = createCpuSession(models.heyDreamLogHead.file)

        modelVerification = listOf(
            verifySession(
                model = models.melSpectrogram,
                session = melSession,
                inputName = MEL_INPUT_NAME,
                inputShape = longArrayOf(DYNAMIC_DIMENSION, DYNAMIC_DIMENSION),
                outputName = MEL_OUTPUT_NAME,
                outputShape = longArrayOf(DYNAMIC_DIMENSION, 1, DYNAMIC_DIMENSION, MEL_BIN_COUNT.toLong()),
            ),
            verifySession(
                model = models.embedding,
                session = embeddingSession,
                inputName = EMBEDDING_INPUT_NAME,
                inputShape = longArrayOf(
                    DYNAMIC_DIMENSION,
                    EMBEDDING_WINDOW_FRAMES.toLong(),
                    MEL_BIN_COUNT.toLong(),
                    1,
                ),
                outputName = EMBEDDING_OUTPUT_NAME,
                outputShape = longArrayOf(DYNAMIC_DIMENSION, 1, 1, EMBEDDING_DIMENSION.toLong()),
            ),
            verifySession(
                model = models.dreamLogHead,
                session = dreamLogSession,
                inputName = HEAD_INPUT_NAME,
                inputShape = longArrayOf(
                    DYNAMIC_DIMENSION,
                    EMBEDDING_COUNT.toLong(),
                    EMBEDDING_DIMENSION.toLong(),
                ),
                outputName = HEAD_OUTPUT_NAME,
                outputShape = longArrayOf(DYNAMIC_DIMENSION, 1),
            ),
            verifySession(
                model = models.heyDreamLogHead,
                session = heyDreamLogSession,
                inputName = HEAD_INPUT_NAME,
                inputShape = longArrayOf(
                    DYNAMIC_DIMENSION,
                    EMBEDDING_COUNT.toLong(),
                    EMBEDDING_DIMENSION.toLong(),
                ),
                outputName = HEAD_OUTPUT_NAME,
                outputShape = longArrayOf(DYNAMIC_DIMENSION, 1),
            ),
        )

        melInputTensor = OnnxTensor.createTensor(
            environment,
            melInputBuffer,
            longArrayOf(1, LiveKitWakeWordFixturePolicy.WINDOW_SAMPLES.toLong()),
        )
        melOutputTensor = OnnxTensor.createTensor(
            environment,
            melOutputBuffer,
            longArrayOf(1, 1, MEL_FRAME_COUNT.toLong(), MEL_BIN_COUNT.toLong()),
        )
        embeddingInputTensor = OnnxTensor.createTensor(
            environment,
            embeddingInputBuffer,
            longArrayOf(
                EMBEDDING_COUNT.toLong(),
                EMBEDDING_WINDOW_FRAMES.toLong(),
                MEL_BIN_COUNT.toLong(),
                1,
            ),
        )
        embeddingOutputTensor = OnnxTensor.createTensor(
            environment,
            embeddingOutputBuffer.duplicate().apply { clear() },
            longArrayOf(EMBEDDING_COUNT.toLong(), 1, 1, EMBEDDING_DIMENSION.toLong()),
        )
        headInputTensor = OnnxTensor.createTensor(
            environment,
            embeddingOutputBuffer.duplicate().apply { clear() },
            longArrayOf(1, EMBEDDING_COUNT.toLong(), EMBEDDING_DIMENSION.toLong()),
        )
        dreamLogOutputTensor = OnnxTensor.createTensor(
            environment,
            dreamLogOutputBuffer,
            longArrayOf(1, 1),
        )
        heyDreamLogOutputTensor = OnnxTensor.createTensor(
            environment,
            heyDreamLogOutputBuffer,
            longArrayOf(1, 1),
        )
    }

    @Synchronized
    override fun score(window: ShortArray): LiveKitFixtureScoreMeasurement {
        check(!closed) { "The LiveKit fixture runtime is closed." }
        require(window.size == LiveKitWakeWordFixturePolicy.WINDOW_SAMPLES) {
            "The LiveKit frontend requires exactly 32,000 PCM16 samples."
        }
        val startedNanos = System.nanoTime()
        for (index in window.indices) {
            melInputBuffer.put(index, window[index] / PCM16_SCALE)
        }
        melSession.run(
            mapOf(MEL_INPUT_NAME to melInputTensor),
            mapOf(MEL_OUTPUT_NAME to melOutputTensor),
        ).use { }

        for (embeddingIndex in 0 until EMBEDDING_COUNT) {
            val startFrame = embeddingIndex * EMBEDDING_STRIDE_FRAMES
            val destinationBase = embeddingIndex * EMBEDDING_WINDOW_FRAMES * MEL_BIN_COUNT
            for (frameOffset in 0 until EMBEDDING_WINDOW_FRAMES) {
                val sourceBase = (startFrame + frameOffset) * MEL_BIN_COUNT
                val destinationFrameBase = destinationBase + frameOffset * MEL_BIN_COUNT
                for (melBin in 0 until MEL_BIN_COUNT) {
                    val raw = melOutputBuffer.get(sourceBase + melBin)
                    embeddingInputBuffer.put(destinationFrameBase + melBin, raw * 0.1f + 2f)
                }
            }
        }
        embeddingSession.run(
            mapOf(EMBEDDING_INPUT_NAME to embeddingInputTensor),
            mapOf(EMBEDDING_OUTPUT_NAME to embeddingOutputTensor),
        ).use { }
        dreamLogSession.run(
            mapOf(HEAD_INPUT_NAME to headInputTensor),
            mapOf(HEAD_OUTPUT_NAME to dreamLogOutputTensor),
        ).use { }
        heyDreamLogSession.run(
            mapOf(HEAD_INPUT_NAME to headInputTensor),
            mapOf(HEAD_OUTPUT_NAME to heyDreamLogOutputTensor),
        ).use { }

        return LiveKitFixtureScoreMeasurement(
            scores = LiveKitFixtureScores(
                dreamLog = dreamLogOutputBuffer.get(0),
                heyDreamLog = heyDreamLogOutputBuffer.get(0),
            ),
            latencyNanos = System.nanoTime() - startedNanos,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        listOf(
            heyDreamLogOutputTensor,
            dreamLogOutputTensor,
            headInputTensor,
            embeddingOutputTensor,
            embeddingInputTensor,
            melOutputTensor,
            melInputTensor,
        ).forEach(OnnxTensor::close)
        listOf(
            heyDreamLogSession,
            dreamLogSession,
            embeddingSession,
            melSession,
        ).forEach(OrtSession::close)
    }

    private fun createCpuSession(model: File): OrtSession {
        val options = OrtSession.SessionOptions()
        return try {
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(1)
            environment.createSession(model.absolutePath, options)
        } finally {
            options.close()
        }
    }

    private fun verifyPrivateModel(
        privateRoot: File,
        model: LiveKitFixtureModelSpec,
    ) {
        require(MODEL_ROLE_PATTERN.matches(model.role)) { "A staged model role is malformed." }
        require(model.file.isFile) { "The staged ${model.role} model is missing." }
        val privatePrefix = privateRoot.canonicalPath + File.separator
        require(model.file.canonicalPath.startsWith(privatePrefix)) {
            "The staged ${model.role} model escaped the device-test private directory."
        }
        require(model.expectedBytes > 0L && model.file.length() == model.expectedBytes) {
            "The staged ${model.role} model byte count differs from its manifest."
        }
        require(SHA256_PATTERN.matches(model.expectedSha256)) {
            "The staged ${model.role} model SHA-256 is malformed."
        }
        require(sha256(model.file) == model.expectedSha256) {
            "The staged ${model.role} model SHA-256 differs from its manifest."
        }
    }

    private fun verifySession(
        model: LiveKitFixtureModelSpec,
        session: OrtSession,
        inputName: String,
        inputShape: LongArray,
        outputName: String,
        outputShape: LongArray,
    ): LiveKitFixtureModelVerification {
        require(session.inputNames == setOf(inputName)) {
            "The ${model.role} model has unexpected inputs: ${session.inputNames}."
        }
        require(session.outputNames == setOf(outputName)) {
            "The ${model.role} model has unexpected outputs: ${session.outputNames}."
        }
        val observedInput = requireTensor(session.inputInfo.getValue(inputName), inputShape)
        val observedOutput = requireTensor(session.outputInfo.getValue(outputName), outputShape)
        return LiveKitFixtureModelVerification(
            role = model.role,
            fileName = model.file.name,
            bytes = model.expectedBytes,
            sha256 = model.expectedSha256,
            input = LiveKitFixtureTensorMetadata(inputName, observedInput.toList()),
            output = LiveKitFixtureTensorMetadata(outputName, observedOutput.toList()),
        )
    }

    private fun requireTensor(
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
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

        val EXPECTED_MODEL_ROLES = listOf(
            "melspectrogram",
            "embedding",
            "dreamlog_head",
            "hey_dreamlog_head",
        )
        val MODEL_ROLE_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun directFloatBuffer(elementCount: Int): FloatBuffer = ByteBuffer
            .allocateDirect(elementCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }
}
