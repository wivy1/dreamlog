package com.wivy.dreamlog

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wivy.dreamlog.enrichment.EnrichmentModelPhase
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeStore
import com.wivy.dreamlog.enrichment.model.EnrichmentModelManifest
import com.wivy.dreamlog.transcription.CaptureTranscriptionOperationGate
import com.wivy.dreamlog.transcription.TranscriptionModelPhase
import com.wivy.dreamlog.transcription.TranscriptionRuntimeStore
import com.wivy.dreamlog.transcription.model.LocalAsrModelManifest
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessingStartupInstrumentedTest {
    @Test
    fun startupDefersModelStorageAccessUntilExplicitVerification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.wivy.dreamlog.devicetest", context.packageName)
        assertFalse(TranscriptionRuntimeStore.snapshots.value.initialized)
        assertFalse(EnrichmentRuntimeStore.snapshots.value.initialized)

        val stagingDirectories = listOf(
            File(
                context.filesDir,
                "transcription-models/.${LocalAsrModelManifest.DIRECTORY_NAME}.installing",
            ),
            File(
                context.filesDir,
                "enrichment-models/.${EnrichmentModelManifest.DIRECTORY_NAME}.installing",
            ),
        )
        stagingDirectories.forEach { directory ->
            assertFalse("A pre-existing model staging directory must be preserved.", directory.exists())
        }
        val createdDirectories = mutableListOf<File>()
        val markers = mutableListOf<File>()
        try {
            stagingDirectories.forEach { directory ->
                assertTrue(directory.mkdirs())
                createdDirectories += directory
                val marker = File(directory, "startup-test-${UUID.randomUUID()}.marker")
                markers += marker
                marker.writeText("synthetic startup verification marker")
            }

            ActivityScenario.launch(MainActivity::class.java).use {
                await("transcription recovery") { TranscriptionRuntimeStore.snapshots.value.initialized }
                await("enrichment recovery") { EnrichmentRuntimeStore.snapshots.value.initialized }
                await("Home readiness") {
                    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                    root?.containsHomeStatus() == true
                }
                assertEquals(TranscriptionModelPhase.VERIFICATION_DEFERRED, TranscriptionRuntimeStore.snapshots.value.modelPhase)
                assertEquals(EnrichmentModelPhase.VERIFICATION_DEFERRED, EnrichmentRuntimeStore.snapshots.value.modelPhase)
                assertTrue(markers.all(File::exists))
                assertFalse(TranscriptionRuntimeStore.snapshots.value.busy)
                assertFalse(EnrichmentRuntimeStore.snapshots.value.busy)
            }
            assertTrue(CaptureTranscriptionOperationGate.tryReserveCaptureStart { false })
            try {
                assertFalse(TranscriptionRuntimeStore.refreshModelStatus())
                assertFalse(EnrichmentRuntimeStore.refreshModelStatus())
                assertTrue(markers.all(File::exists))
            } finally {
                CaptureTranscriptionOperationGate.cancelCaptureStartReservation()
            }

            assertTrue(TranscriptionRuntimeStore.refreshModelStatus())
            await("explicit transcription verification") {
                TranscriptionRuntimeStore.snapshots.value.modelPhase != TranscriptionModelPhase.VERIFYING
            }
            assertFalse(markers[0].exists())
            assertTrue(
                TranscriptionRuntimeStore.snapshots.value.modelPhase in setOf(
                    TranscriptionModelPhase.NOT_INSTALLED,
                    TranscriptionModelPhase.INSTALLED,
                    TranscriptionModelPhase.INVALID,
                ),
            )

            assertTrue(EnrichmentRuntimeStore.refreshModelStatus())
            await("explicit enrichment verification") {
                EnrichmentRuntimeStore.snapshots.value.modelPhase != EnrichmentModelPhase.VERIFYING
            }
            assertFalse(markers[1].exists())
            assertTrue(
                EnrichmentRuntimeStore.snapshots.value.modelPhase in setOf(
                    EnrichmentModelPhase.NOT_INSTALLED,
                    EnrichmentModelPhase.INSTALLED,
                    EnrichmentModelPhase.INVALID,
                ),
            )
        } finally {
            markers.forEach { marker -> marker.delete() }
            createdDirectories.forEach { directory -> directory.delete() }
        }
    }

    private fun AccessibilityNodeInfo.containsHomeStatus(): Boolean {
        if (text?.toString() in setOf("Ready to start", "Setup required")) return true
        return (0 until childCount).any { getChild(it)?.containsHomeStatus() == true }
    }

    private fun await(description: String, completed: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        while (!completed() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L)
        }
        assertTrue("Timed out waiting for $description.", completed())
    }
}
