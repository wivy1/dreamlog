package com.wivy.dreamlog.transcription

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.wivy.dreamlog.history.SessionTranscriptEntity
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

    override val maximumDecodeSampleCount: Long = MAX_DECODE_SAMPLE_COUNT.toLong()

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
        val primary = transcribeSerially(source, input)
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

        internal const val SAMPLE_RATE_HZ = 16_000
        internal const val MAX_DECODE_SAMPLE_COUNT = SAMPLE_RATE_HZ * 30
        private const val FEATURE_DIMENSION = 80
        private const val CPU_THREAD_COUNT = 2

        internal fun metadataFor(model: InstalledLocalAsrModel) = TranscriptionEngineMetadata(
            localeTag = CURRENT_LOCALE_TAG,
            engineId = CURRENT_ENGINE_ID,
            engineVersion = CURRENT_ENGINE_VERSION,
            runtimeId = CURRENT_RUNTIME_ID,
            runtimeVersion = SHERPA_RUNTIME_VERSION,
            modelId = LocalAsrModelManifest.ID,
            modelVersion = model.revision,
            modelSha256 = model.modelSha256,
        )

        /** True when a saved transcript already came from this exact pinned speech pipeline. */
        internal fun hasCurrentProvenance(transcript: SessionTranscriptEntity): Boolean =
            transcript.localeTag == CURRENT_LOCALE_TAG &&
                transcript.engineId == CURRENT_ENGINE_ID &&
                transcript.engineVersion == CURRENT_ENGINE_VERSION &&
                transcript.runtimeId == CURRENT_RUNTIME_ID &&
                transcript.runtimeVersion == SHERPA_RUNTIME_VERSION &&
                transcript.modelId == LocalAsrModelManifest.ID &&
                transcript.modelVersion == LocalAsrModelManifest.REVISION &&
                transcript.modelSha256.equals(LocalAsrModelManifest.MODEL_SHA256, ignoreCase = true)

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

        private const val CURRENT_LOCALE_TAG = "en-US"
        private const val CURRENT_ENGINE_ID = "sherpa-onnx-offline-transducer"
        private const val CURRENT_ENGINE_VERSION = "12"
        private const val CURRENT_RUNTIME_ID = "sherpa-onnx"
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

    /** Decode one complete stream for short input, or fresh bounded streams serially. */
    private fun transcribeSerially(
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

        val decodeRanges = SerialOfflineDecodePlanner.plan(
            recognitionStartSample = recognitionStartSample,
            recognitionEndSample = recognitionEndSample,
            observedNonSpeechRanges = input?.observedNonSpeechRanges.orEmpty(),
            maxDecodeSampleCount = MAX_DECODE_SAMPLE_COUNT.toLong(),
            sampleRateHz = source.sampleRateHz,
        )
        val results = decodeRanges.mapIndexed { index, range ->
            val windowContentStart = contentStartSample.coerceIn(
                range.startSample,
                range.endSampleExclusive,
            )
            decodeCompleteWindow(
                source = source,
                recognitionStartSample = range.startSample,
                recognitionEndSample = range.endSampleExclusive,
                contentStartSample = windowContentStart,
                triggeringWakePhrase = input?.triggeringWakePhrase.takeIf { index == 0 },
            )
        }
        return TranscriptionResult(
            rawText = results.asSequence()
                .map { it.rawText.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(separator = " "),
            segments = results.flatMap { it.segments },
        )
    }

    /** One call means one fresh sherpa offline stream and one bounded waveform allocation. */
    private fun decodeCompleteWindow(
        source: Pcm16WavSource,
        recognitionStartSample: Long,
        recognitionEndSample: Long,
        contentStartSample: Long,
        triggeringWakePhrase: TriggeringWakePhrase? = null,
    ): TranscriptionResult {
        check(recognitionEndSample - recognitionStartSample <= MAX_DECODE_SAMPLE_COUNT) {
            "A local transcription decode exceeded its fixed waveform limit."
        }
        val sourceStartMillis = samplesToMillis(recognitionStartSample, source.sampleRateHz)
        val sourceEndMillis = samplesToMillis(recognitionEndSample, source.sampleRateHz)
        check(sourceEndMillis > sourceStartMillis) {
            "The resolved narration is too short to map to source time."
        }
        val result = SherpaTokenSegmenter.segment(
            recognition = recognizer.recognizeCompleteWaveform(
                samples = source.readCompleteFloatSamples(
                    recognitionRange = Pcm16WavSource.RecognitionRange(
                        startSample = recognitionStartSample,
                        endSampleExclusive = recognitionEndSample,
                    ),
                    maxSampleCount = MAX_DECODE_SAMPLE_COUNT,
                ),
                sampleRateHz = source.sampleRateHz,
            ),
            sourceDurationMillis = sourceEndMillis - sourceStartMillis,
            contentStartMillis = samplesToMillis(
                contentStartSample - recognitionStartSample,
                source.sampleRateHz,
            ),
            triggeringWakePhrase = triggeringWakePhrase,
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

    /**
     * Recovers a short opening that a transducer can omit after a long leading pause.
     *
     * The ordinary serial pass remains authoritative. Only when its first narration word
     * begins well after the acknowledgement cue do we retry from shortly before that word. The
     * retry contributes only a small leading prefix when it ends with an exact overlap of the
     * primary opening; the complete primary body remains unchanged.
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

        val recognitionEndSample = input.acousticRange.endSampleExclusive ?: source.sampleCount
        val recoveryEndSample = minOf(
            recognitionEndSample,
            recoveryStartSample + minOf(
                MAX_DECODE_SAMPLE_COUNT.toLong(),
                recognitionEndSample - recoveryStartSample,
            ),
        )
        if (recoveryEndSample <= recoveryStartSample) return primary

        val recovery = try {
            decodeCompleteWindow(
                source = source,
                recognitionStartSample = recoveryStartSample,
                recognitionEndSample = recoveryEndSample,
                contentStartSample = recoveryStartSample,
            )
        } catch (_: Exception) {
            return primary
        }
        return recovery.mergeExactOpeningPrefixWith(primary) ?: primary
    }

    private fun samplesToMillis(sampleCount: Long, sampleRateHz: Int): Long =
        sampleCount * MILLIS_PER_SECOND / sampleRateHz

    private fun millisToSamples(durationMillis: Long, sampleRateHz: Int): Long =
        durationMillis * sampleRateHz / MILLIS_PER_SECOND

}

/**
 * Accepts only a small recovered prefix followed by an exact overlap with the primary opening.
 * The primary body remains byte-for-byte authoritative, so the bounded recovery never needs to
 * decode or compare the complete long tail and cannot duplicate the overlap.
 */
private fun TranscriptionResult.mergeExactOpeningPrefixWith(
    primary: TranscriptionResult,
): TranscriptionResult? {
    val recoveredStart = segments.firstOrNull()?.sourceStartMillis ?: return null
    val primaryStart = primary.segments.firstOrNull()?.sourceStartMillis ?: return null
    if (recoveredStart >= primaryStart) return null
    val primaryWords = primary.rawText.canonicalWords()
    val recoveredWords = rawText.canonicalWords()
    if (primaryWords.isEmpty() || recoveredWords.isEmpty()) return null
    val overlapWordCount = (minOf(primaryWords.size, recoveredWords.size) downTo 1)
        .firstOrNull { count ->
            recoveredWords.takeLast(count) == primaryWords.take(count)
        } ?: return null
    val addedWords = recoveredWords.dropLast(overlapWordCount)
    if (addedWords.size !in 1..MAX_RECOVERED_OPENING_WORDS) return null

    val addedSegments = segments.takeWhile { it.sourceStartMillis < primaryStart }
        .mapNotNull { segment ->
            val end = minOf(segment.sourceEndMillis, primaryStart)
            segment.copy(sourceEndMillis = end).takeIf { end > segment.sourceStartMillis }
        }
    if (
        addedSegments.flatMap { it.text.canonicalWords() } != addedWords ||
        addedSegments.lastOrNull()?.sourceEndMillis?.let { it > primaryStart } == true
    ) {
        return null
    }
    return TranscriptionResult(
        rawText = (addedSegments.joinToString(separator = " ") { it.text } +
            " " + primary.rawText).trim(),
        segments = addedSegments + primary.segments,
    )
}

internal data class OfflineDecodeRange(
    val startSample: Long,
    val endSampleExclusive: Long,
)

/** Plans contiguous serial streams without dropping or repeating any source sample. */
internal object SerialOfflineDecodePlanner {
    fun plan(
        recognitionStartSample: Long,
        recognitionEndSample: Long,
        observedNonSpeechRanges: List<SessionNonSpeechRange>,
        maxDecodeSampleCount: Long,
        sampleRateHz: Int,
    ): List<OfflineDecodeRange> {
        require(recognitionStartSample >= 0L)
        require(recognitionEndSample >= recognitionStartSample)
        require(maxDecodeSampleCount > 0L)
        require(sampleRateHz > 0)
        if (recognitionStartSample == recognitionEndSample) return emptyList()

        val minimumPreferredWindow = minOf(
            sampleRateHz * MIN_PREFERRED_WINDOW_SECONDS.toLong(),
            maxDecodeSampleCount,
        )
        val minimumBoundarySilence =
            sampleRateHz * MIN_BOUNDARY_SILENCE_MILLIS / MILLIS_PER_SECOND
        val minimumFinalWindow = minOf(
            sampleRateHz * MIN_FINAL_WINDOW_MILLIS / MILLIS_PER_SECOND,
            maxOf(1L, maxDecodeSampleCount / 2L),
        )
        val ranges = mutableListOf<OfflineDecodeRange>()
        var start = recognitionStartSample
        while (start < recognitionEndSample) {
            val remaining = recognitionEndSample - start
            if (remaining <= maxDecodeSampleCount) {
                ranges += OfflineDecodeRange(start, recognitionEndSample)
                break
            }

            val hardEnd = start + maxDecodeSampleCount
            val latestCut = minOf(hardEnd, recognitionEndSample - minimumFinalWindow)
            val earliestPreferredCut = minOf(
                latestCut,
                start + minimumPreferredWindow,
            )
            val observedCut = observedNonSpeechRanges.asSequence()
                .filter { range -> range.sampleCount >= minimumBoundarySilence }
                .mapNotNull { range ->
                    val usableStart = maxOf(range.startSample, earliestPreferredCut)
                    val usableEnd = minOf(range.endSampleExclusive, latestCut)
                    if (usableStart >= usableEnd) {
                        null
                    } else if (range.endSampleExclusive >= latestCut) {
                        latestCut
                    } else {
                        usableStart + (usableEnd - usableStart) / 2L
                    }
                }
                .maxOrNull()
            val cut = observedCut ?: if (
                recognitionEndSample - hardEnd in 1 until minimumFinalWindow
            ) {
                // With no usable silence, a balanced final pair avoids an unmappable tiny tail.
                start + remaining / 2L
            } else {
                // The hard cut is the last-resort coverage-preserving fallback for continuous
                // speech: every sample is still decoded once and the memory ceiling still holds.
                hardEnd
            }
            check(cut > start && cut < recognitionEndSample)
            ranges += OfflineDecodeRange(start, cut)
            start = cut
        }
        check(ranges.all { it.endSampleExclusive - it.startSample <= maxDecodeSampleCount })
        check(ranges.first().startSample == recognitionStartSample)
        check(ranges.last().endSampleExclusive == recognitionEndSample)
        check(ranges.zipWithNext().all { (left, right) ->
            left.endSampleExclusive == right.startSample
        })
        return ranges
    }

    private const val MIN_PREFERRED_WINDOW_SECONDS = 5
    private const val MIN_BOUNDARY_SILENCE_MILLIS = 250L
    private const val MIN_FINAL_WINDOW_MILLIS = 100L
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
private const val MILLIS_PER_SECOND = 1_000L
private val CANONICAL_WORD_PATTERN = Regex("[A-Z0-9']+")
