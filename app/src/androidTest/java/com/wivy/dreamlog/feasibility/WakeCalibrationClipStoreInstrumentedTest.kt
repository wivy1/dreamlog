package com.wivy.dreamlog.feasibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class WakeCalibrationClipStoreInstrumentedTest {
    @Test
    fun completeClipAtomicallyPersistsInIsolatedPrivateStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runDirectory = File(
            context.filesDir,
            "${WakeCalibrationClipStore.ROOT_DIRECTORY}/$RUN_ID",
        )
        check(runDirectory.canonicalPath.startsWith(context.filesDir.canonicalPath))
        runDirectory.deleteRecursively()

        try {
            val store = WakeCalibrationClipStore(
                filesDirectory = context.filesDir,
                runId = RUN_ID,
                storageReserveBytes = 0L,
                sampleRateHz = 1_000,
                windowMillis = 5L,
            )
            store.start(1, "dreamlog", "quiet")
            store.append(shortArrayOf(99, 99))
            store.append(shortArrayOf(1, 2, 3, 4, 5))

            val closed = store.closeAndCollect("run_finished")

            assertNull(closed.failure)
            assertNull(closed.discarded)
            val result = closed.completed.single()
            val wav = File(context.filesDir, result.relativePath)
            assertTrue(wav.isFile)
            assertFalse(File(runDirectory, ".attempt-001_dreamlog_quiet.wav.part").exists())
            assertEquals(54L, wav.length())
            val bytes = wav.readBytes()
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(
                10,
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40),
            )
        } finally {
            runDirectory.deleteRecursively()
            val root = runDirectory.parentFile
            if (root?.list().isNullOrEmpty()) root?.delete()
        }
    }

    companion object {
        private const val RUN_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }
}
