package com.wivy.dreamlog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wivy.dreamlog.capture.CaptureJournalStore
import com.wivy.dreamlog.capture.CapturePhase
import com.wivy.dreamlog.capture.CaptureRuntimeStore
import com.wivy.dreamlog.capture.NightListeningService
import com.wivy.dreamlog.capture.SessionAudioWriter
import com.wivy.dreamlog.enrichment.EnrichmentModelPhase
import com.wivy.dreamlog.enrichment.EnrichmentRuntimePhase
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeSnapshot
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeStore
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamLogDatabase
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightEntity
import com.wivy.dreamlog.history.NightRepository
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioState
import com.wivy.dreamlog.history.TranscriptSegmentDraft
import com.wivy.dreamlog.history.TranscriptionProvenance
import com.wivy.dreamlog.transcription.TranscriptionModelPhase
import com.wivy.dreamlog.transcription.TranscriptionPauseReason
import com.wivy.dreamlog.transcription.TranscriptionRuntimePhase
import com.wivy.dreamlog.transcription.TranscriptionRuntimeSnapshot
import com.wivy.dreamlog.transcription.TranscriptionRuntimeStore
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Home clicks, Room history, capture journal, and microphone service in the isolated helper.
 * Model readiness and paused/running processing presentation are injected; inference is not run.
 * Requires an unlocked device with working capture preflight and no other recorder running.
 */
@RunWith(AndroidJUnit4::class)
class DeferredProcessingNightStartInstrumentedTest {
    @Test
    fun deferredProcessingAllowsAnotherNightAndRetainsPreviousWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(HELPER_PACKAGE, context.packageName)
        val database = DreamLogDatabase.get(context)
        val journal = CaptureJournalStore(File(context.filesDir, "capture/journal"))
        assertTrue("Use an empty isolated helper archive.", database.nightDao().readHistory().isEmpty())
        assertFalse("Existing capture must be preserved.", CaptureRuntimeStore.snapshots.value.active)
        assertFalse("Existing capture journal must be preserved.", journal.hasActiveMarker())
        val repository = NightRepository(
            database.nightDao(), journal, File(context.filesDir, "capture/audio"),
            database.transcriptionDao(), database.enrichmentDao(),
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val permissions = listOf(Manifest.permission.RECORD_AUDIO) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val granted = permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        granted.forEach { automation.grantRuntimePermission(HELPER_PACKAGE, it) }

        TranscriptionRuntimeStore.initialize(context)
        EnrichmentRuntimeStore.initialize(context)
        await("processing recovery") {
            TranscriptionRuntimeStore.snapshots.value.initialized &&
                EnrichmentRuntimeStore.snapshots.value.initialized &&
                !TranscriptionRuntimeStore.snapshots.value.busy && !EnrichmentRuntimeStore.snapshots.value.busy
        }
        val transcription = snapshotFlow<TranscriptionRuntimeSnapshot>(TranscriptionRuntimeStore)
        val enrichment = snapshotFlow<EnrichmentRuntimeSnapshot>(EnrichmentRuntimeStore)
        val originalTranscription = transcription.value
        val originalEnrichment = enrichment.value
        try {
            for (pending in Pending.entries) {
                val nightId = "deferred${UUID.randomUUID().toString().replace("-", "") }"
                var startedNightId: String? = null
                try {
                    seedNight(context, database, nightId, pending)
                    val previous = requireNotNull(database.nightDao().readNight(nightId))
                    val audioDirectory = File(context.filesDir, "capture/audio/$nightId")
                    val previousFiles = fileHashes(audioDirectory)
                    val readyTranscription = TranscriptionRuntimeSnapshot(
                        initialized = true, modelPhase = TranscriptionModelPhase.INSTALLED,
                        nightId = if (pending == Pending.TRANSCRIPTION_PAUSED) nightId else null,
                        eligibleSessionCount = if (pending == Pending.TRANSCRIPTION_PAUSED) 1 else 0,
                        pendingSessionCount = if (pending == Pending.TRANSCRIPTION_PAUSED) 1 else 0,
                        pauseReason = if (pending == Pending.TRANSCRIPTION_PAUSED) TranscriptionPauseReason.THERMAL else null,
                        pauseMessage = if (pending == Pending.TRANSCRIPTION_PAUSED) "Synthetic thermal pause." else null,
                    )
                    val readyEnrichment = EnrichmentRuntimeSnapshot(
                        initialized = true, modelPhase = EnrichmentModelPhase.INSTALLED,
                        nightId = if (pending == Pending.ENRICHMENT_PAUSED) nightId else null,
                        runtimePhase = if (pending == Pending.ENRICHMENT_PAUSED) EnrichmentRuntimePhase.ERROR else EnrichmentRuntimePhase.IDLE,
                        runtimeError = if (pending == Pending.ENRICHMENT_PAUSED) "Enrichment stopped: DreamLog was hidden." else null,
                    )
                    transcription.value = readyTranscription
                    enrichment.value = readyEnrichment
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        await("$pending Start night instead") { clickableLabel("Start night instead")?.isEnabled == true }
                        // Give Home effects time to run; opening an old night must not claim processing.
                        SystemClock.sleep(750L)
                        assertEquals("$pending started transcription on launch.", readyTranscription, transcription.value)
                        assertEquals("$pending started enrichment on launch.", readyEnrichment, enrichment.value)
                        assertEquals(previous, database.nightDao().readNight(nightId))

                        if (pending == Pending.TRANSCRIPTION_UNSTARTED) {
                            scenario.onActivity {
                                transcription.value = readyTranscription.copy(
                                    transcriptionPhase = TranscriptionRuntimePhase.RUNNING,
                                    nightId = nightId, eligibleSessionCount = 1, runningSessionCount = 1,
                                )
                            }
                            await("active transcription excludes capture") { visibleLabel("Transcribing 0/1") }
                            assertFalse("Active transcription offered another night.", startButtonEnabled())
                            scenario.onActivity {
                                transcription.value = readyTranscription
                                enrichment.value = readyEnrichment.copy(
                                    runtimePhase = EnrichmentRuntimePhase.RUNNING, nightId = nightId,
                                )
                            }
                            await("active enrichment excludes capture") { visibleLabel("Enriching dreams") }
                            assertFalse("Active enrichment offered another night.", startButtonEnabled())
                            scenario.onActivity { enrichment.value = readyEnrichment }
                        }

                        clickLabel("Start night instead")
                        await("$pending real microphone Listening") {
                            CaptureRuntimeStore.snapshots.value.phase == CapturePhase.LISTENING && visibleLabel("Listening")
                        }
                        startedNightId = requireNotNull(CaptureRuntimeStore.snapshots.value.nightId)
                        assertFalse(nightId == startedNightId)
                        assertEquals(readyTranscription, transcription.value)
                        assertEquals(readyEnrichment, enrichment.value)
                        assertEquals(previous, database.nightDao().readNight(nightId))
                        assertEquals(previousFiles, fileHashes(audioDirectory))
                        clickLabel("End night")
                        await("$pending capture finalized") {
                            !CaptureRuntimeStore.snapshots.value.active && !journal.hasActiveMarker() &&
                                database.nightDao().readNight(startedNightId!!)?.night?.captureState == NightCaptureState.ENDED
                        }
                        // Ending a newer empty night must not drain the older pending queue either.
                        await("$pending backlog remains available") { clickableLabel("Start night instead")?.isEnabled == true }
                        assertEquals(previous, database.nightDao().readNight(nightId))
                        assertEquals(previousFiles, fileHashes(audioDirectory))
                        assertEquals(readyTranscription, transcription.value)
                        assertEquals(readyEnrichment, enrichment.value)
                        assertEquals(listOf(nightId), repository.readHistory().map { it.night.nightId })
                        // The just-ended empty night must stay absent after a real Activity reload.
                        // Remove only our synthetic backlog so the empty History UI is observable.
                        assertTrue(repository.deleteWholeNight(nightId))
                        scenario.recreate()
                        await("$pending empty night omitted from History after reload") { visibleLabel("No nights yet.") }
                        assertTrue(repository.readHistory().isEmpty())
                        assertEquals(NightCaptureState.ENDED,
                            database.nightDao().readNight(startedNightId!!)?.night?.captureState)
                    }
                } finally {
                    // Only the night created by this click is eligible for service cleanup.
                    val capture = CaptureRuntimeStore.snapshots.value
                    if (capture.active && capture.nightId != nightId) {
                        val candidate = capture.nightId
                        if (candidate != null && database.nightDao().readHistory().all {
                                it.night.nightId == nightId || it.night.nightId == candidate
                            }) {
                            startedNightId = candidate
                            NightListeningService.endNight(context)
                            await("test capture cleanup") { !CaptureRuntimeStore.snapshots.value.active && !journal.hasActiveMarker() }
                        }
                    }
                    repository.reconcile(null)
                    startedNightId?.let { repository.deleteWholeNight(it) }
                    if (database.nightDao().readNight(nightId) != null) repository.deleteWholeNight(nightId)
                }
            }
            assertTrue(database.nightDao().readHistory().isEmpty())
            assertFalse(journal.hasActiveMarker())
        } finally {
            transcription.value = originalTranscription
            enrichment.value = originalEnrichment
            // Permission revocation can terminate the helper process; leave helper grants in place.
        }
    }

    private fun seedNight(context: Context, database: DreamLogDatabase, nightId: String, pending: Pending) {
        val start = System.currentTimeMillis() - 7_200_000L
        val writer = SessionAudioWriter(File(context.filesDir, "capture/audio/$nightId"), clock = { start + 12_000L })
        val audio = writer.startSession(ShortArray(16_000), start + 1_000L).run {
            markCueStart()
            append(ShortArray(1_600))
            markCueEnd()
            append(ShortArray(160_000))
            finalizeComplete(automaticSilenceTailSampleCount = 160_000L)
        }
        val night = NightEntity(
            nightId = nightId, displayDate = Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC).toLocalDate().toString(),
            startedAtEpochMillis = start, startedUtcOffsetSeconds = 0,
            endedAtEpochMillis = start + 60_000L, endedUtcOffsetSeconds = 0,
            captureState = NightCaptureState.ENDED, endReason = "owner_stopped", interrupted = false,
            lastHeartbeatEpochMillis = start + 60_000L, lastHeartbeatUtcOffsetSeconds = 0,
            reportedSessionCount = 1, reportedIncompleteSessionCount = 0,
            hadMicrophoneSilencing = false, hadAudioGap = false, rawAudioState = RawAudioState.RETAINED,
            transcriptionState = ProcessingState.NOT_STARTED, transcriptionFailure = null,
            enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION, enrichmentFailure = null, importWarning = null,
        )
        val session = CaptureSessionEntity(
            sessionId = audio.sessionId, nightId = nightId, captureOrder = 0,
            startedAtEpochMillis = audio.startedAtEpochMillis, startedUtcOffsetSeconds = audio.startedAtUtcOffsetSeconds,
            finalizedAtEpochMillis = audio.finalizedAtEpochMillis, finalizedUtcOffsetSeconds = audio.finalizedAtUtcOffsetSeconds,
            incompleteReason = null, audioFileName = audio.audioFileName, audioState = AudioEvidenceState.RETAINED,
            sampleRateHz = audio.sampleRateHz, channelCount = audio.channelCount, bitsPerSample = audio.bitsPerSample,
            sampleCount = audio.sampleCount, preRollSampleCount = audio.preRollSampleCount,
            cueStartSample = audio.cueStartSample, cueEndSampleExclusive = audio.cueEndSampleExclusive,
            automaticSilenceTailSampleCount = audio.automaticSilenceTailSampleCount,
        )
        database.nightDao().upsertCaptureGraph(night, listOf(session), emptyList())
        if (pending in setOf(Pending.TRANSCRIPTION_FAILED, Pending.ENRICHMENT_PENDING, Pending.ENRICHMENT_PAUSED)) {
            val dao = database.transcriptionDao()
            assertTrue(dao.startSession(audio.sessionId, TranscriptionProvenance(
                "en-US", "synthetic", "1", "synthetic", "1", "synthetic", "1", "0".repeat(64),
            ), start + 61_000L))
            if (pending == Pending.TRANSCRIPTION_FAILED) {
                assertTrue(dao.markSessionFailed(audio.sessionId, "Synthetic interrupted transcription.", start + 62_000L))
            } else {
                assertTrue(dao.markSessionSucceeded(audio.sessionId, "Synthetic test narration.",
                    listOf(TranscriptSegmentDraft(1_100L, 2_000L, "Synthetic test narration.")), start + 62_000L))
                if (pending == Pending.ENRICHMENT_PAUSED) {
                    database.nightDao().upsertCaptureGraph(
                        requireNotNull(database.nightDao().readNight(nightId)).night.copy(
                            enrichmentState = ProcessingState.FAILED,
                            enrichmentFailure = "Enrichment stopped: DreamLog was hidden. [code=app_hidden; retryable=true]",
                        ), emptyList(), emptyList(),
                    )
                }
            }
        }
    }

    private fun fileHashes(directory: File): Map<String, List<Byte>> = directory.listFiles().orEmpty()
        .filter(File::isFile).associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> snapshotFlow(store: Any): MutableStateFlow<T> =
        store.javaClass.getDeclaredField("mutableSnapshots").apply { isAccessible = true }.get(store) as MutableStateFlow<T>

    private fun findLabel(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { visit(it)?.let { return it } } }
            return null
        }
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != HELPER_PACKAGE) return null
        return visit(root)
    }

    private fun visibleLabel(label: String): Boolean = findLabel(label) != null

    private fun clickableLabel(label: String): AccessibilityNodeInfo? {
        var node = findLabel(label)
        while (node != null && !node.isClickable) node = node.parent
        return node
    }

    private fun startButtonEnabled(): Boolean =
        clickableLabel("Start night instead")?.isEnabled == true || clickableLabel("Start night")?.isEnabled == true

    private fun clickLabel(label: String) {
        await("enabled $label") { clickableLabel(label)?.isEnabled == true }
        assertTrue("Could not click $label.", clickableLabel(label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (!ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L)
        assertTrue("Timed out waiting for $description.", ready())
    }

    private enum class Pending {
        TRANSCRIPTION_UNSTARTED, TRANSCRIPTION_FAILED, TRANSCRIPTION_PAUSED, ENRICHMENT_PENDING, ENRICHMENT_PAUSED,
    }

    private companion object {
        const val HELPER_PACKAGE = "com.wivy.dreamlog.devicetest"
    }
}
