package com.wivy.dreamlog

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wivy.dreamlog.capture.CaptureRuntimeStore
import com.wivy.dreamlog.enrichment.EnrichmentModelPhase
import com.wivy.dreamlog.enrichment.EnrichmentOperationPhase
import com.wivy.dreamlog.enrichment.EnrichmentOperationSnapshot
import com.wivy.dreamlog.enrichment.EnrichmentRuntimePhase
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeSnapshot
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeStore
import com.wivy.dreamlog.enrichment.model.EnrichmentModelManifest
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamEntity
import com.wivy.dreamlog.history.DreamKind
import com.wivy.dreamlog.history.DreamRecord
import com.wivy.dreamlog.history.DreamSourceRole
import com.wivy.dreamlog.history.DreamSourceSpanEntity
import com.wivy.dreamlog.history.EnrichmentRunEntity
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightEntity
import com.wivy.dreamlog.history.NightRecord
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioState
import com.wivy.dreamlog.history.SessionTranscriptEntity
import com.wivy.dreamlog.history.SessionTranscriptRecord
import com.wivy.dreamlog.history.TranscriptSegmentEntity
import com.wivy.dreamlog.transcription.TranscriptionModelPhase
import com.wivy.dreamlog.transcription.TranscriptionRuntimeSnapshot
import com.wivy.dreamlog.transcription.TranscriptionRuntimeStore
import com.wivy.dreamlog.transcription.model.LocalAsrModelManifest
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in README artwork using real screens with controlled in-memory demo states.
 * This does not establish capture, model-installation, or inference acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshotsInstrumentedTest {
    @Test
    fun generateReadmeScreenshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        assumeTrue(
            "README screenshots require the isolated helper and explicit opt-in.",
            context.packageName == HELPER_PACKAGE &&
                InstrumentationRegistry.getArguments().getString("readmeScreenshots") == "true",
        )
        val automation = instrumentation.uiAutomation
        val permissions = listOf(Manifest.permission.RECORD_AUDIO) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        permissions.forEach { permission ->
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                automation.grantRuntimePermission(HELPER_PACKAGE, permission)
            }
        }
        val outputDirectory = File(context.filesDir, "readme-screenshots")
        assertTrue(outputDirectory.isDirectory || outputDirectory.mkdirs())
        val transcription = snapshotFlow<TranscriptionRuntimeSnapshot>(TranscriptionRuntimeStore)
        val enrichment = snapshotFlow<EnrichmentRuntimeSnapshot>(EnrichmentRuntimeStore)
        var originalTranscription: TranscriptionRuntimeSnapshot? = null
        var originalEnrichment: EnrichmentRuntimeSnapshot? = null

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("empty Home startup") {
                    transcription.value.initialized && enrichment.value.initialized &&
                        !transcription.value.busy && !enrichment.value.busy &&
                        visibleLabel("Ready to start") && visibleLabel("No nights yet.")
                }
                assertFalse(CaptureRuntimeStore.snapshots.value.active)
                originalTranscription = transcription.value
                originalEnrichment = enrichment.value
                val readyTranscription = transcription.value.copy(
                    modelPhase = TranscriptionModelPhase.INSTALLED,
                    modelDownloadedBytes = transcription.value.modelTotalBytes,
                )
                val readyEnrichment = enrichment.value.copy(
                    modelPhase = EnrichmentModelPhase.INSTALLED,
                    modelDownloadedBytes = enrichment.value.modelTotalBytes,
                )
                val nights = listOf("2026-08-18", "2026-08-16", "2026-08-14", "2026-08-12")
                    .map(::demoNight)
                lateinit var history: MutableState<Any>
                lateinit var originalHistory: Any
                scenario.onActivity { activity ->
                    history = historyState(activity)
                    originalHistory = history.value
                }
                try {
                    scenario.onActivity {
                        transcription.value = readyTranscription
                        enrichment.value = readyEnrichment
                        history.value = historyValue(nights)
                    }
                    await("ready Home with demo history") {
                        visibleLabel("Ready to start") && visibleLabel("2026-08-18")
                    }
                    capture(outputDirectory, "01-ready-to-start")

                    clickLabel("Settings")
                    await("Settings") { visibleLabel("Keep recordings") }
                    await("both model cards") {
                        visibleLabel("Local transcription") && visibleLabel("Local enrichment") &&
                            visibleLabel("Ready. Keep DreamLog open while enriching.")
                    }
                    capture(outputDirectory, "02-local-models")
                    clickLabel("Back")
                    await("Home") { visibleLabel("Ready to start") }

                    val pending = nights.first().let { night ->
                        night.copy(
                            night = night.night.copy(enrichmentState = ProcessingState.RUNNING),
                            enrichmentRuns = night.enrichmentRuns.map {
                                it.copy(state = ProcessingState.RUNNING, completedAtEpochMillis = null)
                            },
                            dreams = emptyList(),
                        )
                    }
                    scenario.onActivity {
                        enrichment.value = readyEnrichment.copy(
                            runtimePhase = EnrichmentRuntimePhase.RUNNING,
                            operation = EnrichmentOperationSnapshot(
                                phase = EnrichmentOperationPhase.GENERATING,
                                nightId = pending.night.nightId,
                                attempt = 1,
                                rawFallbackAvailable = true,
                            ),
                            nightId = pending.night.nightId,
                            batchCurrentNightNumber = 1,
                            batchTotalNightCount = 1,
                            batchUnstartedNightCount = 1,
                            runtimeMessage = "Night 1/1. Organizing dreams.",
                        )
                        history.value = historyValue(listOf(pending) + nights.drop(1))
                    }
                    await("enriching Home") {
                        visibleLabel("Enriching dreams") && visibleLabel("2026-08-18")
                    }
                    capture(outputDirectory, "03-enriching-dreams")

                    scenario.onActivity {
                        enrichment.value = readyEnrichment
                        history.value = historyValue(nights)
                    }
                    await("completed demo history") { visibleLabel("Ready to start") }
                    clickLabel("2026-08-18")
                    await("enriched night preview") {
                        visibleLabel("Dreams") && visibleLabel("Review dream") &&
                            visibleLabel("This was a vision, fresh and clear as a mountain stream")
                    }
                    capture(outputDirectory, "04-enriched-dream")
                } finally {
                    scenario.onActivity {
                        enrichment.value = readyEnrichment
                        transcription.value = readyTranscription
                        history.value = originalHistory
                    }
                }
            }
        } finally {
            // Restore after closing Compose so deferred statuses cannot trigger model verification.
            originalTranscription?.let { transcription.value = it }
            originalEnrichment?.let { enrichment.value = it }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> snapshotFlow(store: Any): MutableStateFlow<T> =
        store.javaClass.getDeclaredField("mutableSnapshots").apply { isAccessible = true }
            .get(store) as MutableStateFlow<T>

    @Suppress("UNCHECKED_CAST")
    private fun historyState(activity: MainActivity): MutableState<Any> =
        MainActivity::class.java.getDeclaredField("historyUiState\$delegate")
            .apply { isAccessible = true }.get(activity) as MutableState<Any>

    private fun historyValue(nights: List<NightRecord>): Any =
        Class.forName("com.wivy.dreamlog.PersistentHistoryUiState")
            .getDeclaredConstructor(
                Boolean::class.javaPrimitiveType, List::class.java,
                Int::class.javaPrimitiveType, String::class.java,
            )
            .apply { isAccessible = true }
            .newInstance(false, nights, 0, null)

    private fun visibleLabel(label: String): Boolean = findNode { node ->
        node.isVisibleToUser && node.text?.toString()?.contains(label) == true
    } != null

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { visit(it)?.let { return it } } }
            return null
        }
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return null
        if (root.packageName?.toString() != HELPER_PACKAGE) return null
        return visit(root)
    }

    private fun clickLabel(label: String) {
        await(label) { visibleLabel(label) }
        var node = findNode { it.isVisibleToUser && it.text?.toString()?.contains(label) == true }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Could not click $label.", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun capture(directory: File, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250L)
        val automation = instrumentation.uiAutomation
        assertEquals(HELPER_PACKAGE, automation.rootInActiveWindow?.packageName?.toString())
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Screenshot unavailable." }
        try {
            assertEquals(HELPER_PACKAGE, automation.rootInActiveWindow?.packageName?.toString())
            File(directory, "$name.png").outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (!ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L)
        assertTrue("Timed out waiting for $description.", ready())
    }

    private fun demoNight(displayDate: String): NightRecord {
        val date = LocalDate.parse(displayDate)
        val offset = ZoneOffset.ofHours(-5)
        val start = date.atTime(22, 30).atOffset(offset).toInstant().toEpochMilli()
        val end = date.plusDays(1).atTime(6, 45).atOffset(offset).toInstant().toEpochMilli()
        val narrated = date.plusDays(1).atTime(3, 10).atOffset(offset).toInstant().toEpochMilli()
        val nightId = "readme-$displayDate"
        val sessionId = "$nightId-session"
        val runId = "$nightId-enrichment"
        val dreamId = "$nightId-dream"
        val night = NightEntity(
            nightId = nightId, displayDate = displayDate,
            startedAtEpochMillis = start, startedUtcOffsetSeconds = offset.totalSeconds,
            endedAtEpochMillis = end, endedUtcOffsetSeconds = offset.totalSeconds,
            captureState = NightCaptureState.ENDED, endReason = "owner_stopped", interrupted = false,
            lastHeartbeatEpochMillis = end, lastHeartbeatUtcOffsetSeconds = offset.totalSeconds,
            reportedSessionCount = 1, reportedIncompleteSessionCount = 0,
            hadMicrophoneSilencing = false, hadAudioGap = false, rawAudioState = RawAudioState.RETAINED,
            transcriptionState = ProcessingState.COMPLETE, transcriptionFailure = null,
            enrichmentState = ProcessingState.COMPLETE, enrichmentFailure = null, importWarning = null,
        )
        val session = CaptureSessionEntity(
            sessionId = sessionId, nightId = nightId, captureOrder = 0,
            startedAtEpochMillis = narrated, startedUtcOffsetSeconds = offset.totalSeconds,
            finalizedAtEpochMillis = narrated + 24_000L, finalizedUtcOffsetSeconds = offset.totalSeconds,
            incompleteReason = null, audioFileName = "$sessionId.wav", audioState = AudioEvidenceState.RETAINED,
            sampleRateHz = 16_000, channelCount = 1, bitsPerSample = 16, sampleCount = 384_000L,
            preRollSampleCount = 32_000L, cueStartSample = 32_000L, cueEndSampleExclusive = 40_000L,
        )
        val transcript = SessionTranscriptRecord(
            SessionTranscriptEntity(
                sessionId = sessionId, nightId = nightId, state = ProcessingState.COMPLETE,
                failureDetail = null, rawText = DEMO_TEXT, localeTag = "en-US",
                engineId = "readme-fixture", engineVersion = "1", runtimeId = "readme-fixture", runtimeVersion = "1",
                modelId = LocalAsrModelManifest.ID, modelVersion = LocalAsrModelManifest.REVISION,
                modelSha256 = LocalAsrModelManifest.MODEL_SHA256, attemptCount = 1,
                startedAtEpochMillis = end + 1_000L, completedAtEpochMillis = end + 20_000L,
            ),
            listOf(TranscriptSegmentEntity(sessionId, 0, 3_000L, 23_000L, DEMO_TEXT)),
        )
        val run = EnrichmentRunEntity(
            runId = runId, nightId = nightId, attemptNumber = 1, state = ProcessingState.COMPLETE,
            failureDetail = null, localeTag = "en-US", engineId = "readme-fixture", engineVersion = "1",
            runtimeId = "readme-fixture", runtimeVersion = "1", backendId = "gpu",
            modelId = EnrichmentModelManifest.ID, modelVersion = EnrichmentModelManifest.REVISION,
            modelSha256 = EnrichmentModelManifest.MODEL_SHA256, modelBytes = EnrichmentModelManifest.MODEL_BYTES,
            contextWindowTokens = 2_048, maxTotalTokens = 2_048, promptId = "readme-fixture", promptVersion = "1",
            promptSha256 = "0".repeat(64), outputSchemaVersion = 6, inputSha256 = "0".repeat(64),
            startedAtEpochMillis = end + 21_000L, completedAtEpochMillis = end + 45_000L,
        )
        val dream = DreamRecord(
            DreamEntity(
                dreamId = dreamId, nightId = nightId, runId = runId, dreamOrder = 0,
                kind = DreamKind.DREAM, isUncertain = false, generatedTitle = null, generatedText = DEMO_TEXT,
                currentTitle = null, currentText = DEMO_TEXT, ownerEdited = false, editedAtEpochMillis = null,
            ),
            listOf(
                DreamSourceSpanEntity(
                    dreamId = dreamId, spanOrder = 0, sessionId = sessionId, sourceTranscriptAttemptCount = 1,
                    firstSegmentIndex = 0, lastSegmentIndex = 0, sourceStartMillis = 3_000L,
                    sourceEndMillis = 23_000L, sourceText = DEMO_TEXT, role = DreamSourceRole.NARRATIVE,
                ),
            ),
        )
        return NightRecord(night, listOf(session), emptyList(), listOf(transcript), listOf(run), listOf(dream))
    }

    private companion object {
        const val HELPER_PACKAGE = "com.wivy.dreamlog.devicetest"
        const val DEMO_TEXT = "This was a vision, fresh and clear as a mountain stream, the mind revealing " +
            "itself to itself. In my vision I was on the veranda of a vast estate, a palazzo of some " +
            "fantastic proportion."
    }
}
