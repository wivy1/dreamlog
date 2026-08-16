package com.wivy.dreamlog.transcription

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Pcm16WavSourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recognitionStartSkipsControlPrefixAndPreservesSourceOffsets() {
        val file = wav(shortArrayOf(16_384, -16_384, 8_192, -8_192, 1, -1))
        val sourceBytes = file.readBytes()
        val source = Pcm16WavSource.open(file)
        val chunks = mutableListOf<Pcm16WavSource.Chunk>()

        source.forEachFloatChunk(
            recognitionRange = Pcm16WavSource.RecognitionRange(2, 6),
            chunkSampleCount = 2,
            consume = chunks::add,
        )

        assertEquals(16_000, source.sampleRateHz)
        assertEquals(6L, source.sampleCount)
        assertEquals(listOf(2L, 4L), chunks.map { it.startSample })
        val samples = chunks.flatMap { it.samples.asList() }
        assertEquals(0.25f, samples[0], 0.0001f)
        assertEquals(-0.25f, samples[1], 0.0001f)
        assertEquals(1f / 32_768f, samples[2], 0.000001f)
        assertEquals(-1f / 32_768f, samples[3], 0.000001f)
        assertArrayEquals(sourceBytes, file.readBytes())
    }

    @Test
    fun rejectsRecognitionStartOutsideSource() {
        val source = Pcm16WavSource.open(wav(shortArrayOf(1, 2)))

        assertThrows(IllegalArgumentException::class.java) {
            source.forEachFloatChunk(
                recognitionRange = Pcm16WavSource.RecognitionRange(3, null),
                consume = {},
            )
        }
    }

    @Test
    fun completeWaveformIsBoundedAfterRecognitionStart() {
        val source = Pcm16WavSource.open(
            wav(shortArrayOf(16_384, -16_384, 8_192, -8_192)),
        )

        val waveform = source.readCompleteFloatSamples(
            recognitionRange = Pcm16WavSource.RecognitionRange(1, 4),
            maxSampleCount = 3,
        )

        assertEquals(listOf(-0.5f, 0.25f, -0.25f), waveform.asList())
        assertThrows(IllegalArgumentException::class.java) {
            source.readCompleteFloatSamples(
                recognitionRange = Pcm16WavSource.RecognitionRange(1, 4),
                maxSampleCount = 2,
            )
        }
    }

    private fun wav(samples: ShortArray): File =
        temporaryFolder.newFile("fixture-${samples.size}.wav").also { file ->
            RandomAccessFile(file, "rw").use { output ->
                val dataBytes = samples.size * 2
                output.writeBytes("RIFF")
                output.writeIntLe(36 + dataBytes)
                output.writeBytes("WAVE")
                output.writeBytes("fmt ")
                output.writeIntLe(16)
                output.writeShortLe(1)
                output.writeShortLe(1)
                output.writeIntLe(16_000)
                output.writeIntLe(32_000)
                output.writeShortLe(2)
                output.writeShortLe(16)
                output.writeBytes("data")
                output.writeIntLe(dataBytes)
                samples.forEach { output.writeShortLe(it.toInt()) }
            }
        }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun RandomAccessFile.writeIntLe(value: Int) {
        writeShortLe(value and 0xffff)
        writeShortLe((value ushr 16) and 0xffff)
    }
}
