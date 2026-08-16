package com.wivy.dreamlog.transcription

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionServiceManifestTest {
    @Test
    fun localAsrUsesTheMediaProcessingForegroundServiceContract() {
        val manifest = File("src/main/AndroidManifest.xml")
            .takeIf(File::isFile)
            ?: File("app/src/main/AndroidManifest.xml")
        val text = manifest.readText()

        assertTrue(
            text.contains(
                "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
            ),
        )
        assertTrue(text.contains(".transcription.TranscriptionProcessingService"))
        assertTrue(text.contains("android:foregroundServiceType=\"mediaProcessing\""))
        assertTrue(text.contains("android:exported=\"false\""))
    }

    @Test
    fun reusedServiceResetsIntentionalStopBeforeStartingForegroundAgain() {
        val source = File(
            "src/main/java/com/wivy/dreamlog/transcription/" +
                "TranscriptionProcessingService.kt",
        ).takeIf(File::isFile)
            ?: File(
                "app/src/main/java/com/wivy/dreamlog/transcription/" +
                    "TranscriptionProcessingService.kt",
            )
        val startBranch = source.readText()
            .substringAfter("ACTION_START -> {")
            .substringBefore("ACTION_STOP -> {")

        assertTrue(
            startBranch.indexOf("intentionalStop = false") in
                0 until startBranch.indexOf("startForeground("),
        )
    }
}
