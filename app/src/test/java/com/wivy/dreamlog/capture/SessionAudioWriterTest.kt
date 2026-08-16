package com.wivy.dreamlog.capture

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionAudioWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun normalFinalizeWritesAtomicPcmWavAndCueMetadata() {
        var now = 1_000L
        val directory = temporaryFolder.newFolder("normal")
        val writer = writer(directory, NORMAL_ID) { now }
        val session = writer.startSession(
            preRoll = shortArrayOf(-32_768, -1, 0),
            startedAtEpochMillis = 900L,
        )

        assertEquals(3L, session.markCueStart())
        session.append(shortArrayOf(1, 32_767))
        assertEquals(5L, session.markCueEnd())
        session.append(shortArrayOf(10, 20))
        now = 2_000L
        val metadata = session.finalizeComplete(automaticSilenceTailSampleCount = 2L)

        assertTrue(metadata.isComplete)
        assertEquals(7L, metadata.sampleCount)
        assertEquals(3L, metadata.preRollSampleCount)
        assertEquals(3L, metadata.cueStartSample)
        assertEquals(5L, metadata.cueEndSampleExclusive)
        assertNull(metadata.incompleteReason)
        assertEquals(2L, metadata.automaticSilenceTailSampleCount)
        assertFalse(File(directory, "${metadata.audioFileName}.part").exists())

        val wav = File(directory, metadata.audioFileName)
        assertEquals(SessionAudioWriter.WAV_HEADER_BYTES + 14L, wav.length())
        RandomAccessFile(wav, "r").use { file ->
            val header = ByteArray(SessionAudioWriter.WAV_HEADER_BYTES)
            file.readFully(header)
            assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(50, littleEndianInt(header, 4))
            assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            assertEquals(16_000, littleEndianInt(header, 24))
            assertEquals(14, littleEndianInt(header, 40))
            val pcm = ByteArray(14)
            file.readFully(pcm)
            assertArrayEquals(
                byteArrayOf(
                    0, -128, -1, -1, 0, 0, 1, 0, -1, 127, 10, 0, 20, 0,
                ),
                pcm,
            )
        }
        assertEquals(metadata, writer.readMetadata(metadata.audioFileName))
    }

    @Test
    fun finalizedDiscoveryReturnsOnlyExactValidWavMetadataPairs() {
        val directory = temporaryFolder.newFolder("finalized-discovery")
        val validWriter = writer(directory, DISCOVERY_VALID_ID) { 2_000L }
        val validSession = validWriter.startSession(
            preRoll = shortArrayOf(1),
            startedAtEpochMillis = 1_000L,
        )
        validSession.markCueStart()
        validSession.append(shortArrayOf(2))
        validSession.markCueEnd()
        val valid = validSession.finalizeComplete(automaticSilenceTailSampleCount = 1L)

        val corruptWriter = writer(directory, DISCOVERY_CORRUPT_ID) { 4_000L }
        val corruptSession = corruptWriter.startSession(
            preRoll = shortArrayOf(3),
            startedAtEpochMillis = 3_000L,
        )
        corruptSession.markCueStart()
        corruptSession.append(shortArrayOf(4))
        corruptSession.markCueEnd()
        val corrupt = corruptSession.finalizeComplete(automaticSilenceTailSampleCount = 1L)
        RandomAccessFile(File(directory, corrupt.audioFileName), "rw").use { audio ->
            audio.seek(0L)
            audio.writeBytes("NOPE")
        }

        val missingMetadataWriter = writer(directory, DISCOVERY_UNPAIRED_ID) { 6_000L }
        val missingMetadataSession = missingMetadataWriter.startSession(
            preRoll = shortArrayOf(5),
            startedAtEpochMillis = 5_000L,
        )
        missingMetadataSession.markCueStart()
        missingMetadataSession.append(shortArrayOf(6))
        missingMetadataSession.markCueEnd()
        val missingMetadata = missingMetadataSession.finalizeComplete(
            automaticSilenceTailSampleCount = 1L,
        )
        assertTrue(metadataFile(directory, missingMetadata.audioFileName).delete())

        assertEquals(
            listOf(valid),
            writer(directory, UNUSED_ID).discoverFinalizedAudio(),
        )
    }

    @Test
    fun constructionCreatesNoIdleWriterAndSessionNameIsOpaque() {
        val directory = File(temporaryFolder.root, "not-created-yet")
        val writer = writer(directory, OPAQUE_ID)

        assertFalse(directory.exists())

        val session = writer.startSession(shortArrayOf(1, 2))
        val checkpoint = session.checkpoint()
        assertTrue(directory.isDirectory)
        assertTrue(checkpoint.audioFileName.matches(Regex("a_[0-9a-f]{32}\\.wav")))
        assertEquals("a_$OPAQUE_ID.wav.part", checkpoint.partialFileName)
        assertEquals(
            listOf(checkpoint.partialFileName),
            directory.listFiles().orEmpty().map(File::getName).sorted(),
        )
        session.finalizeIncomplete(SessionIncompleteReason.NIGHT_ENDED)
    }

    @Test
    fun exceptionalFinalizePreservesUsableAudioAndSpecificReason() {
        val directory = temporaryFolder.newFolder("exceptional")
        val writer = writer(directory, EXCEPTION_ID)
        val session = writer.startSession(shortArrayOf(3, 4))
        session.markCueStart()
        session.append(shortArrayOf(5, 6, 7))

        val metadata = session.finalizeIncomplete(SessionIncompleteReason.CAPTURE_FAILED)

        assertEquals(SessionIncompleteReason.CAPTURE_FAILED, metadata.incompleteReason)
        assertNull(metadata.automaticSilenceTailSampleCount)
        assertEquals(5L, metadata.sampleCount)
        assertEquals(2L, metadata.cueStartSample)
        assertNull(metadata.cueEndSampleExclusive)
        assertTrue(File(directory, metadata.audioFileName).isFile)
        assertEquals(metadata, writer.readMetadata(metadata.audioFileName))
    }

    @Test
    fun recoveryPatchesAndFinalizesReferencedPartialIdempotently() {
        val directory = temporaryFolder.newFolder("partial-recovery")
        val writer = writer(directory, PARTIAL_ID) { 1_000L }
        val session = writer.startSession(shortArrayOf(8, 9))
        session.markCueStart()
        session.append(shortArrayOf(10, 11, 12))
        val checkpoint = session.checkpoint()
        val finalized = session.finalizeIncomplete(SessionIncompleteReason.WRITE_FAILED)
        val finalFile = File(directory, finalized.audioFileName)
        val metadataFile = metadataFile(directory, finalized.audioFileName)
        assertTrue(metadataFile.delete())
        Files.move(
            finalFile.toPath(),
            File(directory, checkpoint.partialFileName).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
        RandomAccessFile(File(directory, checkpoint.partialFileName), "rw").use { partial ->
            partial.seek(4L)
            partial.writeInt(0)
            partial.seek(40L)
            partial.writeInt(0)
        }

        val recoveryWriter = writer(directory, UNUSED_ID) { 2_000L }
        val first = recoveryWriter.recoverInterrupted(listOf(checkpoint))
        val second = recoveryWriter.recoverInterrupted(listOf(checkpoint))

        assertEquals(1, first.size)
        assertEquals(first, second)
        assertEquals(SessionIncompleteReason.PROCESS_INTERRUPTED, first.single().incompleteReason)
        assertEquals(5L, first.single().sampleCount)
        assertFalse(File(directory, checkpoint.partialFileName).exists())
        assertTrue(File(directory, checkpoint.audioFileName).isFile)
        RandomAccessFile(File(directory, checkpoint.audioFileName), "r").use { wav ->
            wav.seek(40L)
            assertEquals(10, Integer.reverseBytes(wav.readInt()))
        }
    }

    @Test
    fun recoveryPreservesUnreferencedPartialFromPreCheckpointInterruption() {
        val directory = temporaryFolder.newFolder("orphan-partial-recovery")
        val writer = writer(directory, ORPHAN_ID) { 1_000L }
        val session = writer.startSession(shortArrayOf(1, 2, 3))
        session.append(shortArrayOf(4, 5))
        val checkpoint = session.checkpoint()
        val finalized = session.finalizeIncomplete(SessionIncompleteReason.WRITE_FAILED)
        assertTrue(metadataFile(directory, finalized.audioFileName).delete())
        Files.move(
            File(directory, finalized.audioFileName).toPath(),
            File(directory, checkpoint.partialFileName).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )

        val recoveryWriter = writer(directory, UNUSED_ID) { 2_000L }
        val recovered = recoveryWriter.recoverInterrupted()

        assertEquals(1, recovered.size)
        assertEquals(SessionIncompleteReason.PROCESS_INTERRUPTED, recovered.single().incompleteReason)
        assertEquals(5L, recovered.single().sampleCount)
        assertEquals(0L, recovered.single().preRollSampleCount)
        assertNull(recovered.single().cueStartSample)
        assertTrue(File(directory, checkpoint.audioFileName).isFile)
        assertFalse(File(directory, checkpoint.partialFileName).exists())
    }

    @Test
    fun referencedFinalAfterRenameIsMarkedIncompleteAndRecoveryIsIdempotent() {
        val directory = temporaryFolder.newFolder("rename-recovery")
        val writer = writer(directory, RENAMED_ID) { 1_000L }
        val session = writer.startSession(shortArrayOf(1, 2, 3))
        session.markCueStart()
        session.append(shortArrayOf(4))
        session.markCueEnd()
        val checkpoint = session.checkpoint()
        val complete = session.finalizeComplete(automaticSilenceTailSampleCount = 1L)
        assertTrue(metadataFile(directory, complete.audioFileName).delete())

        val recoveryWriter = writer(directory, UNUSED_ID) { 3_000L }
        val recovered = recoveryWriter.recoverInterrupted(listOf(checkpoint))
        val repeated = recoveryWriter.recoverInterrupted(listOf(checkpoint))

        assertEquals(1, recovered.size)
        assertEquals(recovered, repeated)
        assertEquals(
            SessionIncompleteReason.PROCESS_INTERRUPTED,
            recovered.single().incompleteReason,
        )
        assertEquals(4L, recovered.single().sampleCount)
        assertEquals(3L, recovered.single().cueStartSample)
        assertEquals(4L, recovered.single().cueEndSampleExclusive)
    }

    private fun writer(
        directory: File,
        id: String,
        clock: () -> Long = { 1_000L },
    ) = SessionAudioWriter(directory, clock) { id }

    private fun metadataFile(directory: File, audioFileName: String): File =
        File(directory, audioFileName.removeSuffix(".wav") + ".properties")

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    private companion object {
        const val NORMAL_ID = "00000000000000000000000000000001"
        const val OPAQUE_ID = "abcdefabcdefabcdefabcdefabcdefab"
        const val EXCEPTION_ID = "00000000000000000000000000000003"
        const val PARTIAL_ID = "00000000000000000000000000000004"
        const val RENAMED_ID = "00000000000000000000000000000005"
        const val ORPHAN_ID = "00000000000000000000000000000006"
        const val DISCOVERY_VALID_ID = "00000000000000000000000000000007"
        const val DISCOVERY_CORRUPT_ID = "00000000000000000000000000000008"
        const val DISCOVERY_UNPAIRED_ID = "00000000000000000000000000000009"
        const val UNUSED_ID = "ffffffffffffffffffffffffffffffff"
    }
}
