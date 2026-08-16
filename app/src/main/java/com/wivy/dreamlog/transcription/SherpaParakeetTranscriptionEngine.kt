package com.wivy.dreamlog.transcription

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.wivy.dreamlog.transcription.model.InstalledLocalAsrModel
import com.wivy.dreamlog.transcription.model.LocalAsrModelManifest
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the selected, verified Parakeet transducer entirely on-device. */
internal class SherpaParakeetTranscriptionEngine private constructor(
    private val recognizer: CompleteWaveformRecognizer,
    override val metadata: TranscriptionEngineMetadata,
) : TranscriptionEngine {
    private val closed = AtomicBoolean(false)

    constructor(model: InstalledLocalAsrModel) : this(
        recognizer = NativeSherpaRecognizer(model),
        metadata = metadataFor(model),
    )

    override fun transcribe(
        audioFile: File,
        input: TranscriptionInput?,
    ): TranscriptionResult {
        check(!closed.get()) { "The local transcription engine is closed." }
        val source = Pcm16WavSource.open(audioFile)
        require(source.sampleRateHz == SAMPLE_RATE_HZ) {
            "Session audio must use a 16 kHz sample rate."
        }
        val primary = transcribeCompleteWaveform(source, input)
        return recoverMissingOpening(source, input, primary)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) recognizer.close()
    }

    internal companion object {
        fun forTesting(
            recognizer: CompleteWaveformRecognizer,
            metadata: TranscriptionEngineMetadata = TEST_METADATA,
        ): SherpaParakeetTranscriptionEngine {
            return SherpaParakeetTranscriptionEngine(
                recognizer = recognizer,
                metadata = metadata,
            )
        }

        private const val SAMPLE_RATE_HZ = 16_000
        private const val FEATURE_DIMENSION = 80
        private const val CPU_THREAD_COUNT = 2

        internal fun metadataFor(model: InstalledLocalAsrModel) = TranscriptionEngineMetadata(
            localeTag = "en-US",
            engineId = "sherpa-onnx-offline-transducer",
            engineVersion = "11",
            runtimeId = "sherpa-onnx",
            runtimeVersion = SHERPA_RUNTIME_VERSION,
            modelId = LocalAsrModelManifest.ID,
            modelVersion = model.revision,
            modelSha256 = model.modelSha256,
        )

        internal fun recognizerConfig(model: InstalledLocalAsrModel) = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE_HZ,
                featureDim = FEATURE_DIMENSION,
                dither = 0f,
            ),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = model.encoderFile.absolutePath,
                    decoder = model.decoderFile.absolutePath,
                    joiner = model.joinerFile.absolutePath,
                ),
                numThreads = CPU_THREAD_COUNT,
                debug = false,
                provider = "cpu",
                modelType = "nemo_transducer",
                tokens = model.tokensFile.absolutePath,
            ),
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
            blankPenalty = 0f,
        )

        private const val SHERPA_RUNTIME_VERSION = "1.13.4"
        private const val MILLIS_PER_SECOND = 1_000L
        private val TEST_METADATA = TranscriptionEngineMetadata(
            localeTag = "en-US",
            engineId = "test-engine",
            engineVersion = "1",
            runtimeId = "test-runtime",
            runtimeVersion = "1",
            modelId = "test-model",
            modelVersion = "1",
            modelSha256 = "0".repeat(64),
        )
    }

    /** Decode the complete resolved narration range as one utterance. */
    private fun transcribeCompleteWaveform(
        source: Pcm16WavSource,
        input: TranscriptionInput?,
    ): TranscriptionResult {
        val recognitionRange = input?.acousticRange
        val recognitionStartSample = recognitionRange?.startSample ?: 0L
        val recognitionEndSample = recognitionRange?.endSampleExclusive ?: source.sampleCount
        val contentStartSample = input?.contentStartSample ?: recognitionStartSample
        require(recognitionStartSample in 0L..source.sampleCount) {
            "Recognition start is outside the source audio."
        }
        require(recognitionEndSample in recognitionStartSample..source.sampleCount) {
            "Recognition end is outside the source audio."
        }
        require(contentStartSample in recognitionStartSample..recognitionEndSample) {
            "Recognition content start is outside the resolved audio."
        }
        if (recognitionEndSample == recognitionStartSample) {
            return TranscriptionResult(rawText = "", segments = emptyList())
        }

        val sourceStartMillis = samplesToMillis(recognitionStartSample, source.sampleRateHz)
        val sourceEndMillis = samplesToMillis(recognitionEndSample, source.sampleRateHz)
        check(sourceEndMillis > sourceStartMillis) {
            "The resolved narration is too short to map to source time."
        }
        val result = SherpaTokenSegmenter.segment(
            // Keep the large PCM FloatArray inside a narrow call frame. It becomes unreachable
            // as soon as native decoding returns, before timestamp segmentation and before the
            // next session is claimed. The source audio is not shortened or arbitrarily cut.
            recognition = recognizeResolvedWaveform(
                source = source,
                recognitionStartSample = recognitionStartSample,
                recognitionEndSample = recognitionEndSample,
            ),
            sourceDurationMillis = sourceEndMillis - sourceStartMillis,
            contentStartMillis = samplesToMillis(
                contentStartSample - recognitionStartSample,
                source.sampleRateHz,
            ),
            triggeringWakePhrase = input?.triggeringWakePhrase,
        )
        return result.copy(
            segments = result.segments.map { segment ->
                TranscriptionSegment(
                    sourceStartMillis = sourceStartMillis + segment.sourceStartMillis,
                    sourceEndMillis = sourceStartMillis + segment.sourceEndMillis,
                    text = segment.text,
                )
            },
        )
    }

    private fun recognizeResolvedWaveform(
        source: Pcm16WavSource,
        recognitionStartSample: Long,
        recognitionEndSample: Long,
    ): SherpaRecognition = recognizer.recognizeCompleteWaveform(
        samples = source.readCompleteFloatSamples(
            recognitionRange = Pcm16WavSource.RecognitionRange(
                startSample = recognitionStartSample,
                endSampleExclusive = recognitionEndSample,
            ),
            maxSampleCount = Int.MAX_VALUE,
        ),
        sampleRateHz = source.sampleRateHz,
    )

    /**
     * Recovers a short opening that a transducer can omit after a long leading pause.
     *
     * The ordinary whole-session pass remains authoritative. Only when its first narration word
     * begins well after the acknowledgement cue do we retry from shortly before that word. The
     * retry replaces the primary result only when it adds a small leading prefix and every primary
     * word remains an exact suffix, so a changed or speculative body is never accepted.
     */
    private fun recoverMissingOpening(
        source: Pcm16WavSource,
        input: TranscriptionInput?,
        primary: TranscriptionResult,
    ): TranscriptionResult {
        val recoveryFloorSample = input?.openingRecoveryFloorSample ?: return primary
        val firstPrimarySegment = primary.segments.firstOrNull() ?: return primary
        val recoveryFloorMillis = samplesToMillis(recoveryFloorSample, source.sampleRateHz)
        if (
            firstPrimarySegment.sourceStartMillis - recoveryFloorMillis <
            MIN_OPENING_GAP_FOR_RECOVERY_MILLIS
        ) {
            return primary
        }

        val firstPrimarySample = millisToSamples(
            firstPrimarySegment.sourceStartMillis,
            source.sampleRateHz,
        ).coerceAtMost(source.sampleCount)
        val recoveryLookbackSamples = millisToSamples(
            OPENING_RECOVERY_LOOKBACK_MILLIS,
            source.sampleRateHz,
        )
        val recoveryStartSample = maxOf(
            recoveryFloorSample,
            firstPrimarySample - recoveryLookbackSamples,
        )
        if (recoveryStartSample >= firstPrimarySample) return primary

        val recovery = try {
            transcribeCompleteWaveform(
                source = source,
                input = TranscriptionInput(
                    acousticRange = Pcm16WavSource.RecognitionRange(
                        startSample = recoveryStartSample,
                        endSampleExclusive = input.acousticRange.endSampleExclusive,
                    ),
                ),
            )
        } catch (_: Exception) {
            return primary
        }
        return if (recovery.isExactPrefixExtensionOf(primary)) recovery else primary
    }

    private fun samplesToMillis(sampleCount: Long, sampleRateHz: Int): Long =
        sampleCount * MILLIS_PER_SECOND / sampleRateHz

    private fun millisToSamples(durationMillis: Long, sampleRateHz: Int): Long =
        durationMillis * sampleRateHz / MILLIS_PER_SECOND

}

private fun TranscriptionResult.isExactPrefixExtensionOf(
    primary: TranscriptionResult,
): Boolean {
    val recoveredStart = segments.firstOrNull()?.sourceStartMillis ?: return false
    val primaryStart = primary.segments.firstOrNull()?.sourceStartMillis ?: return false
    if (recoveredStart >= primaryStart) return false
    val primaryWords = primary.rawText.canonicalWords()
    val recoveredWords = rawText.canonicalWords()
    val addedWordCount = recoveredWords.size - primaryWords.size
    return primaryWords.isNotEmpty() &&
        addedWordCount in 1..MAX_RECOVERED_OPENING_WORDS &&
        recoveredWords.takeLast(primaryWords.size) == primaryWords
}

private fun String.canonicalWords(): List<String> =
    CANONICAL_WORD_PATTERN.findAll(uppercase()).map { it.value }.toList()

/** Test seam that also makes the one-complete-waveform contract explicit. */
internal interface CompleteWaveformRecognizer : AutoCloseable {
    fun recognizeCompleteWaveform(
        samples: FloatArray,
        sampleRateHz: Int,
    ): SherpaRecognition
}

private class NativeSherpaRecognizer(
    model: InstalledLocalAsrModel,
) : CompleteWaveformRecognizer {
    private val closed = AtomicBoolean(false)
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = SherpaParakeetTranscriptionEngine.recognizerConfig(model),
    )

    override fun recognizeCompleteWaveform(
        samples: FloatArray,
        sampleRateHz: Int,
    ): SherpaRecognition {
        check(!closed.get()) { "The local recognizer is closed." }
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRateHz)
            recognizer.decode(stream)
            recognizer.getResult(stream).let { result ->
                SherpaRecognition(
                    text = result.text,
                    tokens = result.tokens.toList(),
                    timestampsSeconds = result.timestamps.toList(),
                )
            }
        } finally {
            stream.release()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) recognizer.release()
    }
}

private const val MIN_OPENING_GAP_FOR_RECOVERY_MILLIS = 2_000L
private const val OPENING_RECOVERY_LOOKBACK_MILLIS = 1_500L
private const val MAX_RECOVERED_OPENING_WORDS = 12
private val CANONICAL_WORD_PATTERN = Regex("[A-Z0-9']+")
