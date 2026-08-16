package com.wivy.dreamlog.transcription

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.wivy.dreamlog.capture.CaptureAssets
import java.io.File

/** Source-relative non-speech edges observed while analyzing a retained session. */
data class SessionSpeechBoundaryResult(
    val speechDetected: Boolean,
    val leadingNonSpeechSamples: Long,
    val trailingNonSpeechSamples: Long,
)

/** Locates speech boundaries without modifying the retained source audio. */
interface SessionSpeechBoundaryAnalyzer : AutoCloseable {
    fun analyze(
        audioFile: File,
        analysisStartSample: Long,
    ): SessionSpeechBoundaryResult
}

/** Runs the already-bundled Silero model sequentially over one retained session at a time. */
class SileroSessionSpeechBoundaryAnalyzer(
    assetManager: AssetManager,
) : SessionSpeechBoundaryAnalyzer {
    private val vad = Vad(
        assetManager,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = CaptureAssets.VAD_MODEL,
                threshold = SPEECH_THRESHOLD,
                minSilenceDuration = 0f,
                minSpeechDuration = 0f,
                windowSize = FRAME_SAMPLE_COUNT,
                maxSpeechDuration = 0f,
            ),
            sampleRate = SAMPLE_RATE_HZ,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    )
    private var closed = false

    @Synchronized
    override fun analyze(
        audioFile: File,
        analysisStartSample: Long,
    ): SessionSpeechBoundaryResult {
        check(!closed) { "The session speech-boundary analyzer is closed." }
        val source = Pcm16WavSource.open(audioFile)
        require(source.sampleRateHz == SAMPLE_RATE_HZ) {
            "Speech-boundary analysis requires 16 kHz session audio."
        }

        val accumulator = SpeechBoundaryAccumulator()
        vad.reset()
        source.forEachFloatChunk(
            recognitionRange = Pcm16WavSource.RecognitionRange(
                startSample = analysisStartSample,
                endSampleExclusive = source.sampleCount,
            ),
            chunkSampleCount = FRAME_SAMPLE_COUNT,
        ) { chunk ->
            val frame = if (chunk.samples.size == FRAME_SAMPLE_COUNT) {
                chunk.samples
            } else {
                FloatArray(FRAME_SAMPLE_COUNT).also { padded ->
                    chunk.samples.copyInto(padded)
                }
            }
            accumulator.acceptFrame(
                speech = vad.compute(frame) >= SPEECH_THRESHOLD,
                actualSampleCount = chunk.samples.size,
            )
        }
        return accumulator.result()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        vad.release()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLE_COUNT = 512
        const val SPEECH_THRESHOLD = 0.25f
    }
}

/** Pure frame accumulator kept separate from native VAD inference for deterministic JVM tests. */
internal class SpeechBoundaryAccumulator {
    private var speechDetected = false
    private var leadingNonSpeechSamples = 0L
    private var trailingNonSpeechSamples = 0L

    fun acceptFrame(
        speech: Boolean,
        actualSampleCount: Int,
    ) {
        require(actualSampleCount > 0) { "A speech-analysis frame must contain samples." }
        if (speech) {
            speechDetected = true
            trailingNonSpeechSamples = 0L
            return
        }

        val count = actualSampleCount.toLong()
        trailingNonSpeechSamples = Math.addExact(trailingNonSpeechSamples, count)
        if (!speechDetected) {
            leadingNonSpeechSamples = Math.addExact(leadingNonSpeechSamples, count)
        }
    }

    fun result() = SessionSpeechBoundaryResult(
        speechDetected = speechDetected,
        leadingNonSpeechSamples = leadingNonSpeechSamples,
        trailingNonSpeechSamples = trailingNonSpeechSamples,
    )
}
