package com.wivy.dreamlog.feasibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class WakeCalibrationClipStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun explicitAttemptWritesExactMonoPcm16Wav() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val store = store(filesDirectory, sampleRateHz = 1_000, windowMillis = 5)

        val started = store.start(
            attemptNumber = 1,
            target = "dreamlog",
            delivery = "quiet",
        )
        assertEquals(
            "wake-calibration/$RUN_ID/attempt-001_dreamlog_quiet.wav",
            started.relativePath,
        )
        assertEquals(5, started.intendedSamples)
        store.append(shortArrayOf(99, 99))
        store.append(shortArrayOf(0, 1))
        store.append(shortArrayOf(-1, Short.MIN_VALUE, Short.MAX_VALUE))

        val closed = store.closeAndCollect("run_finished")
        assertNull(closed.failure)
        assertNull(closed.discarded)
        val result = closed.completed.singleOrNull()
        assertNotNull(result)
        requireNotNull(result)
        assertEquals(1, result.attemptNumber)
        assertEquals("dreamlog", result.target)
        assertEquals("quiet", result.delivery)
        assertEquals(5, result.samples)
        assertEquals(5L, result.durationMillis)
        assertEquals(54L, result.wavBytes)

        val wav = filesDirectory.resolve(result.relativePath).readBytes()
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", wav.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(46, header.getInt(4))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(1_000, header.getInt(24))
        assertEquals(2_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(10, header.getInt(40))
        assertEquals(
            listOf<Byte>(0, 0, 1, 0, -1, -1, 0, -128, -1, 127),
            wav.copyOfRange(44, wav.size).toList(),
        )
        assertEquals(sha256(wav), result.sha256)
        assertEquals(1, store.completedClipCount)
        assertEquals(54L, store.completedWavBytes)
        store.requeueCompleted(listOf(result))
        assertEquals(result, store.drainCompleted().single())
    }

    @Test
    fun stoppedAttemptFinalizesOnlyCapturedSamples() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val store = store(filesDirectory, sampleRateHz = 1_000, windowMillis = 6)
        store.start(2, "hey_dreamlog", "slow_continuous")
        store.append(shortArrayOf(99))
        store.append(shortArrayOf(10, 20, 30))

        val closed = store.closeAndCollect("run_finished")

        assertNull(closed.failure)
        assertTrue(closed.completed.isEmpty())
        val discarded = closed.discarded
        assertNotNull(discarded)
        requireNotNull(discarded)
        assertEquals(2, discarded.attemptNumber)
        assertEquals("hey_dreamlog", discarded.target)
        assertEquals("slow_continuous", discarded.delivery)
        assertEquals(3, discarded.bufferedSamples)
        assertEquals("run_finished", discarded.reason)
        assertFalse(filesDirectory.resolve("wake-calibration/$RUN_ID").exists())
    }

    @Test
    fun unmarkedSamplesAndDiscardedAttemptLeaveNoAudio() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val store = store(filesDirectory, sampleRateHz = 1_000, windowMillis = 5)
        store.append(shortArrayOf(1, 2, 3))
        assertFalse(filesDirectory.resolve("wake-calibration/$RUN_ID").exists())

        store.start(1, "dreamlog", "normal_sleepy")
        val discarded = store.cancelActive("report_marker_failed")
        val closed = store.closeAndCollect("run_finished")

        assertNotNull(discarded)
        assertNull(closed.failure)
        assertFalse(filesDirectory.resolve("wake-calibration/$RUN_ID").exists())
        assertEquals(0, store.completedClipCount)
    }

    @Test
    fun constructorRemovesOnlyIncompleteTaskOwnedStagingFiles() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val oldRun = filesDirectory.resolve("wake-calibration/old-run").apply { mkdirs() }
        val stale = oldRun.resolve(".attempt-001_dreamlog_quiet.wav.part").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val completed = oldRun.resolve("attempt-001_dreamlog_quiet.wav").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }

        val store = store(filesDirectory, sampleRateHz = 1_000, windowMillis = 5)

        assertEquals(1, store.staleStagingFilesRemoved)
        assertFalse(stale.exists())
        assertTrue(completed.exists())
        assertNull(store.closeAndCollect("run_finished").failure)
    }

    @Test
    fun unsafeLabelsCannotEscapePrivateRunDirectory() {
        val filesDirectory = temporaryFolder.newFolder("files")
        val store = store(filesDirectory, sampleRateHz = 1_000, windowMillis = 5)

        assertThrows(IllegalArgumentException::class.java) {
            store.start(1, "../dreamlog", "quiet")
        }
        assertNull(store.closeAndCollect("run_finished").failure)
        assertFalse(filesDirectory.resolve("wake-calibration/$RUN_ID").exists())
    }

    private fun store(
        filesDirectory: java.io.File,
        sampleRateHz: Int,
        windowMillis: Long,
    ) = WakeCalibrationClipStore(
        filesDirectory = filesDirectory,
        runId = RUN_ID,
        storageReserveBytes = 0L,
        sampleRateHz = sampleRateHz,
        windowMillis = windowMillis,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val RUN_ID = "11111111-2222-3333-4444-555555555555"
    }
}
