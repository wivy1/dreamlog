package com.wivy.dreamlog.transcription

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * A narrow reader for DreamLog's 16 kHz mono PCM16 session files.
 *
 * The source file is never rewritten. Recognition can exclude known non-narrative source edges;
 * streamed chunks retain their original source positions for timestamp mapping.
 */
class Pcm16WavSource private constructor(
    private val file: File,
    val sampleRateHz: Int,
    val sampleCount: Long,
    private val dataOffsetBytes: Long,
) {
    data class RecognitionRange(
        val startSample: Long,
        val endSampleExclusive: Long? = null,
    )

    data class Chunk(
        val startSample: Long,
        val samples: FloatArray,
    )

    val durationMillis: Long
        get() = sampleCount * 1_000L / sampleRateHz

    /**
     * Reads this complete utterance into one normalized waveform for offline recognizers.
     *
     * [maxSampleCount] is an explicit allocation boundary. The source audio is never shortened;
     * input beyond the boundary is rejected so callers can preserve it and report a retryable
     * transcription failure instead of risking an unbounded heap allocation.
     */
    fun readCompleteFloatSamples(
        recognitionRange: RecognitionRange? = null,
        maxSampleCount: Int,
    ): FloatArray {
        require(maxSampleCount > 0) { "Maximum sample count must be positive." }
        val resolvedRange = resolveRecognitionRange(recognitionRange)
        val recognitionSampleCount =
            resolvedRange.endSampleExclusive - resolvedRange.startSample
        require(recognitionSampleCount <= maxSampleCount.toLong()) {
            "Session audio exceeds the local transcription input limit."
        }

        val result = FloatArray(recognitionSampleCount.toInt())
        forEachFloatChunk(
            recognitionRange = RecognitionRange(
                startSample = resolvedRange.startSample,
                endSampleExclusive = resolvedRange.endSampleExclusive,
            ),
        ) { chunk ->
            chunk.samples.copyInto(
                result,
                destinationOffset = (chunk.startSample - resolvedRange.startSample).toInt(),
            )
        }
        return result
    }

    fun forEachFloatChunk(
        recognitionRange: RecognitionRange? = null,
        chunkSampleCount: Int = DEFAULT_CHUNK_SAMPLE_COUNT,
        consume: (Chunk) -> Unit,
    ) {
        require(chunkSampleCount > 0) { "Chunk size must be positive." }
        val resolvedRange = resolveRecognitionRange(recognitionRange)

        RandomAccessFile(file, "r").use { input ->
            input.seek(dataOffsetBytes + resolvedRange.startSample * BYTES_PER_SAMPLE)
            var position = resolvedRange.startSample
            while (position < resolvedRange.endSampleExclusive) {
                val count = minOf(
                    chunkSampleCount.toLong(),
                    resolvedRange.endSampleExclusive - position,
                ).toInt()
                val bytes = ByteArray(count * BYTES_PER_SAMPLE)
                input.readFully(bytes)
                val samples = FloatArray(count)
                for (index in 0 until count) {
                    val low = bytes[index * 2].toInt() and 0xff
                    val high = bytes[index * 2 + 1].toInt()
                    val pcm = ((high shl 8) or low).toShort()
                    samples[index] = pcm.toFloat() / PCM_SCALE
                }
                consume(Chunk(startSample = position, samples = samples))
                position += count
            }
        }
    }

    private fun resolveRecognitionRange(
        requested: RecognitionRange?,
    ): ResolvedRecognitionRange {
        val start = requested?.startSample ?: 0L
        val end = requested?.endSampleExclusive ?: sampleCount
        require(start in 0L..sampleCount) {
            "Recognition start is outside the source audio."
        }
        require(end in start..sampleCount) {
            "Recognition end is outside the source audio."
        }
        return ResolvedRecognitionRange(startSample = start, endSampleExclusive = end)
    }

    private data class ResolvedRecognitionRange(
        val startSample: Long,
        val endSampleExclusive: Long,
    )

    companion object {
        private const val PCM_FORMAT = 1
        private const val MONO_CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = 2
        private const val PCM_SCALE = 32_768f
        private const val DEFAULT_CHUNK_SAMPLE_COUNT = 16_384

        fun open(file: File): Pcm16WavSource {
            require(file.isFile) { "Session audio is unavailable." }
            return RandomAccessFile(file, "r").use { input ->
                require(input.length() >= 12L) { "Session audio is not a complete WAV file." }
                require(input.readFourCc() == "RIFF") { "Session audio is not RIFF." }
                input.readUnsignedIntLe()
                require(input.readFourCc() == "WAVE") { "Session audio is not WAVE." }

                var sampleRateHz: Int? = null
                var dataOffsetBytes: Long? = null
                var dataSizeBytes: Long? = null
                while (input.filePointer + 8L <= input.length()) {
                    val chunkId = input.readFourCc()
                    val chunkSize = input.readUnsignedIntLe()
                    val chunkStart = input.filePointer
                    val chunkEnd = runCatching { Math.addExact(chunkStart, chunkSize) }
                        .getOrElse { throw IllegalArgumentException("WAV chunk size overflow.") }
                    require(chunkEnd <= input.length()) { "WAV chunk extends beyond the file." }

                    when (chunkId) {
                        "fmt " -> {
                            require(chunkSize >= 16L) { "WAV format chunk is incomplete." }
                            val audioFormat = input.readUnsignedShortLe()
                            val channelCount = input.readUnsignedShortLe()
                            val rate = input.readUnsignedIntLe()
                            input.readUnsignedIntLe()
                            val blockAlign = input.readUnsignedShortLe()
                            val bitsPerSample = input.readUnsignedShortLe()
                            require(audioFormat == PCM_FORMAT) { "WAV audio is not PCM." }
                            require(channelCount == MONO_CHANNEL_COUNT) {
                                "WAV audio must be mono."
                            }
                            require(rate in 1L..Int.MAX_VALUE.toLong()) {
                                "WAV sample rate is invalid."
                            }
                            require(blockAlign == BYTES_PER_SAMPLE) {
                                "WAV block alignment is invalid."
                            }
                            require(bitsPerSample == BITS_PER_SAMPLE) {
                                "WAV audio must be PCM16."
                            }
                            sampleRateHz = rate.toInt()
                        }

                        "data" -> {
                            require(chunkSize % BYTES_PER_SAMPLE == 0L) {
                                "WAV data is not aligned to PCM16 samples."
                            }
                            if (dataOffsetBytes == null) {
                                dataOffsetBytes = chunkStart
                                dataSizeBytes = chunkSize
                            }
                        }
                    }

                    input.seek(chunkEnd + (chunkSize and 1L))
                }

                val rate = requireNotNull(sampleRateHz) { "WAV format chunk is missing." }
                val offset = requireNotNull(dataOffsetBytes) { "WAV data chunk is missing." }
                val dataSize = requireNotNull(dataSizeBytes) { "WAV data chunk is missing." }
                Pcm16WavSource(
                    file = file,
                    sampleRateHz = rate,
                    sampleCount = dataSize / BYTES_PER_SAMPLE,
                    dataOffsetBytes = offset,
                )
            }
        }

        private fun RandomAccessFile.readFourCc(): String {
            val bytes = ByteArray(4)
            readFully(bytes)
            return String(bytes, StandardCharsets.US_ASCII)
        }

        private fun RandomAccessFile.readUnsignedShortLe(): Int {
            val low = readUnsignedByte()
            val high = readUnsignedByte()
            return low or (high shl 8)
        }

        private fun RandomAccessFile.readUnsignedIntLe(): Long {
            val low = readUnsignedShortLe().toLong()
            val high = readUnsignedShortLe().toLong()
            return low or (high shl 16)
        }
    }
}
