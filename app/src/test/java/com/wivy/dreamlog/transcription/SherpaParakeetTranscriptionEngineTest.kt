package com.wivy.dreamlog.transcription

import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.SessionTranscriptEntity
import com.wivy.dreamlog.transcription.model.InstalledLocalAsrModel
import com.wivy.dreamlog.transcription.model.LocalAsrModelManifest
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SherpaParakeetTranscriptionEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun productionConfigurationPinsVersionTwelveParakeetGreedyDecoder() {
        val model = InstalledLocalAsrModel(
            directory = temporaryFolder.newFolder("model"),
            revision = "model-revision",
            modelSha256 = "a".repeat(64),
            totalModelBytes = 1L,
        )

        val config = SherpaParakeetTranscriptionEngine.recognizerConfig(model)
        val metadata = SherpaParakeetTranscriptionEngine.metadataFor(model)

        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(0f, config.featConfig.dither)
        assertEquals(2, config.modelConfig.numThreads)
        assertEquals("cpu", config.modelConfig.provider)
        assertEquals("nemo_transducer", config.modelConfig.modelType)
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals(4, config.maxActivePaths)
        assertEquals(0f, config.blankPenalty)
        assertEquals("12", metadata.engineVersion)
        assertEquals("model-revision", metadata.modelVersion)
        assertEquals("a".repeat(64), metadata.modelSha256)
    }

    @Test
    fun currentProvenanceRecognizesOnlyTheExactPinnedSpeechPipeline() {
        val model = InstalledLocalAsrModel(
            directory = temporaryFolder.newFolder("current-model"),
            revision = LocalAsrModelManifest.REVISION,
            modelSha256 = LocalAsrModelManifest.MODEL_SHA256,
            totalModelBytes = 1L,
        )
        val metadata = SherpaParakeetTranscriptionEngine.metadataFor(model)
        val transcript = SessionTranscriptEntity(
            sessionId = "session-1",
            nightId = "night-1",
            state = ProcessingState.COMPLETE,
            failureDetail = null,
            rawText = "saved transcript",
            localeTag = metadata.localeTag,
            engineId = metadata.engineId,
            engineVersion = metadata.engineVersion,
            runtimeId = metadata.runtimeId,
            runtimeVersion = metadata.runtimeVersion,
            modelId = metadata.modelId,
            modelVersion = metadata.modelVersion,
            modelSha256 = metadata.modelSha256.uppercase(),
            attemptCount = 1,
            startedAtEpochMillis = 1L,
            completedAtEpochMillis = 2L,
        )

        assertTrue(SherpaParakeetTranscriptionEngine.hasCurrentProvenance(transcript))
        assertFalse(
            SherpaParakeetTranscriptionEngine.hasCurrentProvenance(
                transcript.copy(engineVersion = "11"),
            ),
        )
        assertFalse(
            SherpaParakeetTranscriptionEngine.hasCurrentProvenance(
                transcript.copy(modelSha256 = "0".repeat(64)),
            ),
        )
    }

    @Test
    fun startsAfterControlPrefixAndKeepsSourceTimestampOffset() {
        val sourceSamples = ShortArray(16_016).also { samples ->
            samples[16_000] = 16_384
            samples[16_001] = -16_384
            samples[16_002] = 8_192
            samples[16_003] = -8_192
        }
        val recognizer = RecordingRecognizer(
            recognition = SherpaRecognition(
                text = "ONE TWO",
                tokens = listOf(" ONE", " TWO"),
                timestampsSeconds = listOf(0f, 0.0002f),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(
            recognizer = recognizer,
        )

        val result = engine.transcribe(
            audioFile = wav(sourceSamples),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(16_000, 16_016),
            ),
        )

        assertEquals(1, recognizer.calls.size)
        assertEquals(16_000, recognizer.calls.single().sampleRateHz)
        assertArrayEquals(
            FloatArray(16).also { samples ->
                samples[0] = 0.5f
                samples[1] = -0.5f
                samples[2] = 0.25f
                samples[3] = -0.25f
            },
            recognizer.calls.single().samples,
            0.0001f,
        )
        assertEquals("ONE TWO", result.rawText)
        assertEquals(
            listOf(TranscriptionSegment(1_000L, 1_001L, "ONE TWO")),
            result.segments,
        )
    }

    @Test
    fun fullPreRollContextDropsExactWakePhraseAndKeepsImmediateNarration() {
        val sourceSamples = ShortArray(48_000)
        val recognizer = RecordingRecognizer(
            recognition = SherpaRecognition(
                text = "DREAM LOG SO I WAS WITH STEVE DREAM LOG",
                tokens = listOf(
                    " DREAM",
                    " LOG",
                    " SO",
                    " I",
                    " WAS",
                    " WITH",
                    " STEVE",
                    " DREAM",
                    " LOG",
                ),
                timestampsSeconds = listOf(1.5f, 1.8f, 1.8f, 1.9f, 2.0f, 2.1f, 2.2f, 2.4f, 2.6f),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)

        val result = engine.transcribe(
            audioFile = wav(sourceSamples),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(0L, 48_000L),
                contentStartSample = 32_000L,
                triggeringWakePhrase = TriggeringWakePhrase.DREAM_LOG,
            ),
        )

        assertEquals(48_000, recognizer.calls.single().samples.size)
        assertEquals("SO I WAS WITH STEVE DREAM LOG", result.rawText)
        assertEquals(1_800L, result.segments.first().sourceStartMillis)
        assertEquals("SO", result.segments.first().text)
        assertEquals(listOf("DREAM", "LOG"), result.segments.takeLast(2).map { it.text })
        assertEquals(3_000L, result.segments.last().sourceEndMillis)
    }

    @Test
    fun unmatchedWakePhraseFallsBackToStrictContentBoundary() {
        val recognizer = RecordingRecognizer(
            recognition = SherpaRecognition(
                text = "DREAMLOCK SO I WAS",
                tokens = listOf(" DREAMLOCK", " SO", " I", " WAS"),
                timestampsSeconds = listOf(1.0f, 1.8f, 2.1f, 2.3f),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)

        val result = engine.transcribe(
            audioFile = wav(ShortArray(48_000)),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(0L, 48_000L),
                contentStartSample = 32_000L,
                triggeringWakePhrase = TriggeringWakePhrase.DREAM_LOG,
            ),
        )

        assertEquals("I WAS", result.rawText)
        assertEquals(listOf("I", "WAS"), result.segments.map { it.text })
        assertEquals(2_100L, result.segments.first().sourceStartMillis)
    }

    @Test
    fun earlierAcousticContextStillRequiresAValidContentBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(16_000L, 48_000L),
                contentStartSample = 8_000L,
                triggeringWakePhrase = TriggeringWakePhrase.DREAM_LOG,
            )
        }
    }

    @Test
    fun cueTailContextCannotBecomeTranscriptTextAndKeepsTheFirstNarrationWord() {
        val recognizer = RecordingRecognizer(
            recognition = SherpaRecognition(
                text = "CUE THE BEGINNING",
                tokens = listOf(" CUE", " THE", " BEGINNING"),
                timestampsSeconds = listOf(0.1f, 0.52f, 0.8f),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)

        val result = engine.transcribe(
            audioFile = wav(ShortArray(48_000)),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(8_000L, 48_000L),
                contentStartSample = 16_000L,
            ),
        )

        assertEquals("THE BEGINNING", result.rawText)
        assertEquals(listOf("THE", "BEGINNING"), result.segments.map { it.text })
        assertEquals(1_020L, result.segments.first().sourceStartMillis)
    }

    @Test
    fun partialWakeSuffixBeforeCueStartIsDroppedWithoutCroppingFirstNarrationWord() {
        val recognizer = RecordingRecognizer(
            recognition = SherpaRecognition(
                text = "LOG THE BEGINNING",
                tokens = listOf(" LOG", " THE", " BEGINNING"),
                timestampsSeconds = listOf(1.8f, 2.3f, 2.8f),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)

        val result = engine.transcribe(
            audioFile = wav(ShortArray(64_000)),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(0L, 64_000L),
                contentStartSample = 32_000L,
                triggeringWakePhrase = TriggeringWakePhrase.DREAM_LOG,
            ),
        )

        assertEquals("THE BEGINNING", result.rawText)
        assertEquals(listOf("THE", "BEGINNING"), result.segments.map { it.text })
        assertEquals(2_300L, result.segments.first().sourceStartMillis)
    }

    @Test
    fun longOpeningGapUsesOnlyAnExactPrefixExtensionFromTheRecoveryPass() {
        val recognizer = RecordingRecognizer(
            recognitions = listOf(
                SherpaRecognition(
                    text = "MAKING IT HARD TO FIND",
                    tokens = listOf(" MAKING", " IT", " HARD", " TO", " FIND"),
                    timestampsSeconds = listOf(6.7f, 6.8f, 6.9f, 7.0f, 7.1f),
                ),
                SherpaRecognition(
                    text = "THIS MAKING IT HARD TO FIND",
                    tokens = listOf(" THIS", " MAKING", " IT", " HARD", " TO", " FIND"),
                    timestampsSeconds = listOf(0.35f, 1.5f, 1.6f, 1.7f, 1.8f, 1.9f),
                ),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)

        val result = engine.transcribe(
            audioFile = wav(ShortArray(160_000)),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(0L, 160_000L),
                contentStartSample = 32_000L,
                openingRecoveryFloorSample = 64_000L,
            ),
        )

        assertEquals(listOf(160_000, 76_800), recognizer.calls.map { it.samples.size })
        assertEquals("THIS MAKING IT HARD TO FIND", result.rawText)
        assertEquals(5_550L, result.segments.first().sourceStartMillis)
        assertEquals("THIS", result.segments.first().text)
        assertEquals(10_000L, result.segments.last().sourceEndMillis)
    }

    @Test
    fun openingRecoveryCannotReplaceAChangedPrimaryTranscript() {
        val recognizer = RecordingRecognizer(
            recognitions = listOf(
                SherpaRecognition(
                    text = "PRIMARY BODY",
                    tokens = listOf(" PRIMARY", " BODY"),
                    timestampsSeconds = listOf(6.7f, 7.0f),
                ),
                SherpaRecognition(
                    text = "EXTRA DIFFERENT BODY",
                    tokens = listOf(" EXTRA", " DIFFERENT", " BODY"),
                    timestampsSeconds = listOf(0.35f, 1.5f, 1.9f),
                ),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)

        val result = engine.transcribe(
            audioFile = wav(ShortArray(160_000)),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(0L, 160_000L),
                contentStartSample = 32_000L,
                openingRecoveryFloorSample = 64_000L,
            ),
        )

        assertEquals(2, recognizer.calls.size)
        assertEquals("PRIMARY BODY", result.rawText)
        assertEquals(6_700L, result.segments.first().sourceStartMillis)
    }

    @Test
    fun shortNarrationUsesOneCompleteRecognitionStream() {
        val sourceSamples = ShortArray(40_000)
        val recognizer = RecordingRecognizer(
            recognition = SherpaRecognition(
                text = "FIRST SECOND",
                tokens = listOf(" FIRST", " SECOND"),
                timestampsSeconds = listOf(0f, 1.25f),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(
            recognizer = recognizer,
        )

        val result = engine.transcribe(
            audioFile = wav(sourceSamples),
            input = null,
        )

        assertEquals(listOf(40_000), recognizer.calls.map { it.samples.size })
        assertEquals(listOf(16_000), recognizer.calls.map { it.sampleRateHz })
        assertEquals("FIRST SECOND", result.rawText)
        assertEquals(
            listOf(
                TranscriptionSegment(0L, 1_250L, "FIRST"),
                TranscriptionSegment(1_250L, 2_500L, "SECOND"),
            ),
            result.segments,
        )
    }

    @Test
    fun longNarrationUsesObservedSilenceBoundariesAndAbsoluteOffsets() {
        val sourceSamples = ShortArray(1_040_000)
        val sourceFile = wav(sourceSamples)
        val sourceBytes = sourceFile.readBytes()
        val recognizer = RecordingRecognizer(
            recognitions = listOf(
                SherpaRecognition(
                    text = "DREAM LOG FIRST",
                    tokens = listOf(" DREAM", " LOG", " FIRST"),
                    timestampsSeconds = listOf(1f, 1.5f, 2.5f),
                ),
                SherpaRecognition(
                    text = "SECOND",
                    tokens = listOf(" SECOND"),
                    timestampsSeconds = listOf(2f),
                ),
                SherpaRecognition(
                    text = "THIRD",
                    tokens = listOf(" THIRD"),
                    timestampsSeconds = listOf(1f),
                ),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)

        val result = engine.transcribe(
            audioFile = sourceFile,
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(0L, 1_040_000L),
                contentStartSample = 32_000L,
                triggeringWakePhrase = TriggeringWakePhrase.DREAM_LOG,
                observedNonSpeechRanges = listOf(
                    SessionNonSpeechRange(400_000L, 416_000L),
                    SessionNonSpeechRange(800_000L, 816_000L),
                ),
            ),
        )

        assertEquals(listOf(408_000, 400_000, 232_000), recognizer.calls.map { it.samples.size })
        assertTrue(
            recognizer.calls.all {
                it.samples.size <= SherpaParakeetTranscriptionEngine.MAX_DECODE_SAMPLE_COUNT
            },
        )
        assertEquals("FIRST SECOND THIRD", result.rawText)
        assertEquals(listOf("FIRST", "SECOND", "THIRD"), result.segments.map { it.text })
        assertEquals(listOf(2_500L, 27_500L, 51_500L), result.segments.map { it.sourceStartMillis })
        assertEquals(65_000L, result.segments.last().sourceEndMillis)
        assertArrayEquals(sourceBytes, sourceFile.readBytes())
    }

    @Test
    fun continuousSpeechUsesContiguousHardBoundedFallback() {
        val ranges = SerialOfflineDecodePlanner.plan(
            recognitionStartSample = 0L,
            recognitionEndSample = 640_000L,
            observedNonSpeechRanges = emptyList(),
            maxDecodeSampleCount = SherpaParakeetTranscriptionEngine.MAX_DECODE_SAMPLE_COUNT.toLong(),
            sampleRateHz = 16_000,
        )

        assertEquals(
            listOf(
                OfflineDecodeRange(0L, 480_000L),
                OfflineDecodeRange(480_000L, 640_000L),
            ),
            ranges,
        )
    }

    @Test
    fun openingRecoveryNeverMaterializesTheCompleteLongTail() {
        val recognizer = RecordingRecognizer(
            recognitions = listOf(
                SherpaRecognition(
                    text = "PRIMARY BODY",
                    tokens = listOf(" PRIMARY", " BODY"),
                    timestampsSeconds = listOf(6.7f, 7.0f),
                ),
                EMPTY_RECOGNITION,
                EMPTY_RECOGNITION,
                SherpaRecognition(
                    text = "EXTRA PRIMARY",
                    tokens = listOf(" EXTRA", " PRIMARY"),
                    timestampsSeconds = listOf(0.35f, 1.5f),
                ),
            ),
        )
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)

        val result = engine.transcribe(
            audioFile = wav(ShortArray(1_200_000)),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(0L, 1_200_000L),
                contentStartSample = 32_000L,
                openingRecoveryFloorSample = 64_000L,
            ),
        )

        assertEquals(listOf(480_000, 480_000, 240_000, 480_000), recognizer.calls.map { it.samples.size })
        assertTrue(
            recognizer.calls.all {
                it.samples.size <= SherpaParakeetTranscriptionEngine.MAX_DECODE_SAMPLE_COUNT
            },
        )
        assertEquals("EXTRA PRIMARY BODY", result.rawText)
        assertEquals(listOf("EXTRA", "PRIMARY", "BODY"), result.segments.map { it.text })
        assertEquals(5_550L, result.segments.first().sourceStartMillis)
    }

    @Test
    fun readsTheExactResolvedNarrationRangeIntoOneWaveform() {
        val sourceSamples = ShortArray(55) { index -> (index + 1).toShort() }
        val recognizer = RecordingRecognizer()
        val engine = SherpaParakeetTranscriptionEngine.forTesting(
            recognizer = recognizer,
        )

        engine.transcribe(
            audioFile = wav(sourceSamples),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(7, 55),
            ),
        )

        assertEquals(1, recognizer.calls.size)
        assertEquals(48, recognizer.calls.single().samples.size)
        assertEquals(8f / 32_768f, recognizer.calls.single().samples.first(), 0.000001f)
        assertEquals(55f / 32_768f, recognizer.calls.single().samples.last(), 0.000001f)
    }

    @Test
    fun emptyRecognitionRangeReturnsEmptyTranscriptWithoutCallingRecognizer() {
        val recognizer = RecordingRecognizer()
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)

        val result = engine.transcribe(
            audioFile = wav(shortArrayOf(1, 2, 3)),
            input = TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(2, 2),
            ),
        )

        assertEquals("", result.rawText)
        assertTrue(result.segments.isEmpty())
        assertTrue(recognizer.calls.isEmpty())
    }

    @Test
    fun closeIsIdempotentAndPreventsLaterRecognition() {
        val recognizer = RecordingRecognizer()
        val engine = SherpaParakeetTranscriptionEngine.forTesting(recognizer)
        val audioFile = wav(shortArrayOf(1))

        engine.close()
        engine.close()

        assertEquals(1, recognizer.closeCount)
        assertThrows(IllegalStateException::class.java) {
            engine.transcribe(audioFile = audioFile, input = null)
        }
        assertEquals(0, recognizer.calls.size)
    }

    private fun wav(samples: ShortArray): File =
        temporaryFolder.newFile("fixture-${samples.size}-${System.nanoTime()}.wav").also { file ->
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

    private class RecordingRecognizer private constructor(
        private val recognitions: ArrayDeque<SherpaRecognition>,
    ) : CompleteWaveformRecognizer {
        constructor(
            recognition: SherpaRecognition = EMPTY_RECOGNITION,
        ) : this(ArrayDeque(listOf(recognition)))

        constructor(
            recognitions: List<SherpaRecognition>,
        ) : this(ArrayDeque(recognitions))

        val calls = mutableListOf<Call>()
        var closeCount = 0
            private set

        override fun recognizeCompleteWaveform(
            samples: FloatArray,
            sampleRateHz: Int,
        ): SherpaRecognition {
            calls += Call(samples.copyOf(), sampleRateHz)
            return recognitions.removeFirst()
        }

        override fun close() {
            closeCount += 1
        }

        data class Call(
            val samples: FloatArray,
            val sampleRateHz: Int,
        )

    }

    private companion object {
        val EMPTY_RECOGNITION = SherpaRecognition(
            text = "",
            tokens = emptyList(),
            timestampsSeconds = emptyList(),
        )
    }

}
