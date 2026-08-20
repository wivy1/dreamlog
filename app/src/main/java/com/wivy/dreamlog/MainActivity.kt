package com.wivy.dreamlog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wivy.dreamlog.capture.AndroidPreflight
import com.wivy.dreamlog.capture.AndroidPreflightSnapshot
import com.wivy.dreamlog.capture.CaptureJournalStore
import com.wivy.dreamlog.capture.CapturePhase
import com.wivy.dreamlog.capture.CaptureRuntimeSnapshot
import com.wivy.dreamlog.capture.CaptureRuntimeStore
import com.wivy.dreamlog.capture.CueAudioPreflight
import com.wivy.dreamlog.capture.CuePlayer
import com.wivy.dreamlog.capture.NightDateMapper
import com.wivy.dreamlog.capture.NightEndReason
import com.wivy.dreamlog.capture.NightListeningService
import com.wivy.dreamlog.capture.NarrativeBoundaryDetector
import com.wivy.dreamlog.capture.NightStartRequest
import com.wivy.dreamlog.capture.PreflightIssue
import com.wivy.dreamlog.capture.PreflightIssueCode
import com.wivy.dreamlog.capture.PreflightRemediationCode
import com.wivy.dreamlog.capture.PreflightEvaluation
import com.wivy.dreamlog.capture.SessionAudioWriter
import com.wivy.dreamlog.capture.SessionIncompleteReason
import com.wivy.dreamlog.capture.UnreadableActiveJournalException
import com.wivy.dreamlog.enrichment.EnrichmentModelPhase
import com.wivy.dreamlog.enrichment.EnrichmentRuntimePhase
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeSnapshot
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeStore
import com.wivy.dreamlog.enrichment.NightTranscriptSegment
import com.wivy.dreamlog.enrichment.OrderedNightTranscript
import com.wivy.dreamlog.enrichment.persistence.persistedEnrichmentFailureIsRetryable
import com.wivy.dreamlog.export.AndroidExportStore
import com.wivy.dreamlog.export.DreamLogExportDocument
import com.wivy.dreamlog.export.DreamLogExportFormat
import com.wivy.dreamlog.export.DreamLogExportFormatter
import com.wivy.dreamlog.export.DreamLogExportSelection
import com.wivy.dreamlog.export.createDreamLogExportV1
import com.wivy.dreamlog.export.selectNightsForExport
import com.wivy.dreamlog.history.DreamLogDatabase
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.HistoryLoadResult
import com.wivy.dreamlog.history.HistoryFormatters
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightRecord
import com.wivy.dreamlog.history.NightRepository
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioRetentionPolicy
import com.wivy.dreamlog.history.RawAudioUseRegistry
import com.wivy.dreamlog.history.RawAudioState
import com.wivy.dreamlog.settings.AppSettingsStore
import com.wivy.dreamlog.settings.RetentionPeriod
import com.wivy.dreamlog.transcription.TranscriptionModelPhase
import com.wivy.dreamlog.transcription.CaptureTranscriptionOperationGate
import com.wivy.dreamlog.transcription.SherpaParakeetTranscriptionEngine
import com.wivy.dreamlog.transcription.TranscriptionRuntimePhase
import com.wivy.dreamlog.transcription.TranscriptionRuntimeSnapshot
import com.wivy.dreamlog.transcription.TranscriptionRuntimeStore
import com.wivy.dreamlog.ui.history.DreamDetailScreen
import com.wivy.dreamlog.ui.history.NightDetailScreen
import com.wivy.dreamlog.ui.history.NightHistorySection
import com.wivy.dreamlog.ui.theme.DreamLogTheme
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val appSettingsStore by lazy {
        AppSettingsStore(applicationContext)
    }
    private val androidExportStore by lazy {
        AndroidExportStore(applicationContext)
    }
    private val journalStore by lazy {
        CaptureJournalStore(File(filesDir, JOURNAL_DIRECTORY))
    }
    private val nightRepository by lazy {
        NightRepository(
            dao = DreamLogDatabase.get(applicationContext).nightDao(),
            journalStore = journalStore,
            audioRootDirectory = File(filesDir, AUDIO_DIRECTORY),
            transcriptionDao = DreamLogDatabase.get(applicationContext).transcriptionDao(),
            enrichmentDao = DreamLogDatabase.get(applicationContext).enrichmentDao(),
            rawAudioRetentionMillis = {
                appSettingsStore.readRawAudioRetentionDays() *
                    RawAudioRetentionPolicy.MILLIS_PER_DAY
            },
        )
    }

    private var recoveryUiState by mutableStateOf(CaptureRecoveryUiState())
    private var historyUiState by mutableStateOf(PersistentHistoryUiState())
    private var preflightRefreshKey by mutableIntStateOf(0)
    private var recoveryRunning = false
    private var latestResultRefreshRunning = false
    private var latestResultRefreshPending = false
    private var startPersistenceRunning = false
    private var archiveMutationRunning by mutableStateOf(false)
    private var retentionDays by mutableIntStateOf(RetentionPeriod.DEFAULT.days)
    private var retentionMutationRunning by mutableStateOf(false)
    private var retentionMessage by mutableStateOf<String?>(null)
    private var exportBuildRunning by mutableStateOf(false)
    private var historyMutationRevision = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setVolumeControlStream(CueAudioPreflight.volumeControlStream())
        retentionDays = appSettingsStore.readRawAudioRetentionDays()
        TranscriptionRuntimeStore.initialize(applicationContext)
        EnrichmentRuntimeStore.initialize(applicationContext)
        inspectPriorCapture()

        setContent {
            DreamLogTheme {
                DreamLogApp(
                    preflightRefreshKey = preflightRefreshKey,
                    recoveryUiState = recoveryUiState,
                    historyUiState = historyUiState,
                    onRefreshPreflight = ::refreshPreflight,
                    onRetryRecovery = ::resolvePriorCapture,
                    onPreserveUnreadableMarker = ::preserveUnreadableActiveMarker,
                    onReloadLatestResult = ::reloadLatestResult,
                    onStartNight = ::persistAndStartNight,
                    archiveMutationRunning = archiveMutationRunning,
                    retentionDays = retentionDays,
                    retentionMutationRunning = retentionMutationRunning,
                    retentionMessage = retentionMessage,
                    exportBuildRunning = exportBuildRunning,
                    onSaveDream = ::saveDream,
                    onDeleteDream = ::deleteDream,
                    onRestoreDream = ::restoreDream,
                    onDeleteNightRawAudio = ::deleteNightRawAudio,
                    onDeleteWholeNight = ::deleteWholeNight,
                    onUpdateRawAudioRetention = ::updateRawAudioRetention,
                    onCreateExport = ::createDreamLogExport,
                    onShareExport = ::shareDreamLogExport,
                    onSaveExport = ::saveDreamLogExport,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPreflight()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (
            event.action == KeyEvent.ACTION_UP &&
            event.keyCode in setOf(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
            )
        ) {
            window.decorView.post(::refreshPreflight)
        }
        return handled
    }

    private fun refreshPreflight() {
        preflightRefreshKey += 1
    }

    /**
     * Reads recovery state without changing it. An unresolved marker remains a required blocker
     * until the owner chooses the visible recovery action.
     */
    private fun inspectPriorCapture() {
        if (recoveryRunning) return
        recoveryRunning = true
        recoveryUiState = recoveryUiState.copy(
            checking = true,
            resolved = false,
            error = null,
        )

        Thread(
            {
                val outcome = runCatching {
                    val runtime = CaptureRuntimeStore.snapshots.value
                    val unresolved = if (runtime.active) {
                        null
                    } else {
                        journalStore.unresolvedPriorCapture()
                    }
                    InspectionOutcome(
                        resolved = runtime.active || unresolved == null,
                        history = nightRepository.reconcile(
                            runtimeActiveNightId = runtime.nightId.takeIf { runtime.active },
                        ),
                    )
                }

                runOnUiThread {
                    recoveryRunning = false
                    outcome.fold(
                        onSuccess = { inspection ->
                            historyUiState = inspection.history.toUiState()
                            recoveryUiState = CaptureRecoveryUiState(
                                checking = false,
                                resolved = inspection.resolved,
                                latestResult =
                                    inspection.history.nights.latestReviewNight(),
                            )
                            refreshPreflight()
                        },
                        onFailure = { failure ->
                            recoveryUiState = recoveryUiState.copy(
                                checking = false,
                                resolved = false,
                                error = failure.message
                                    ?: "The unfinished capture could not be recovered.",
                                unreadableActiveMarker =
                                    failure is UnreadableActiveJournalException,
                            )
                            refreshPreflight()
                        },
                    )
                    runPendingLatestResultRefresh()
                }
            },
            "DreamLog-capture-recovery",
        ).start()
    }

    /**
     * The owner explicitly selected recovery. Preserve referenced and stray writer-owned audio
     * under this night's directory before clearing the active marker.
     */
    private fun resolvePriorCapture() {
        if (recoveryRunning) return
        recoveryRunning = true
        recoveryUiState = recoveryUiState.copy(
            checking = true,
            resolved = false,
            error = null,
        )

        Thread(
            {
                val outcome = runCatching {
                    val runtime = CaptureRuntimeStore.snapshots.value
                    val recovery = if (runtime.active) {
                        null
                    } else {
                        val unresolved = journalStore.unresolvedPriorCapture()
                        unresolved?.let {
                            journalStore.recoverUnresolved(
                                audioWriter = SessionAudioWriter(
                                    File(
                                        filesDir,
                                        "$AUDIO_DIRECTORY/${it.activeJournal.nightId}",
                                    ),
                                ),
                                reason = SessionIncompleteReason.PROCESS_INTERRUPTED,
                            )
                        }
                    }
                    RecoveryOutcome(
                        recovery = recovery,
                        history = nightRepository.reconcile(
                            runtimeActiveNightId = runtime.nightId.takeIf { runtime.active },
                        ),
                    )
                }

                runOnUiThread {
                    recoveryRunning = false
                    outcome.fold(
                        onSuccess = { resolved ->
                            historyUiState = resolved.history.toUiState()
                            val recovered = resolved.recovery
                            if (recovered?.endRecord?.interrupted == true) {
                                CaptureRuntimeStore.restoreInterrupted(
                                    summary = if (recovered.completedPreviously) {
                                        "Recovered the completed interruption record left by " +
                                            "the earlier process."
                                    } else {
                                        "Recovered the unfinished night and preserved " +
                                            "${recovered.endRecord.sessionCount} session(s)."
                                    },
                                    sessionCount = recovered.endRecord.sessionCount,
                                    incompleteSessionCount =
                                        recovered.endRecord.incompleteSessionCount,
                                )
                            }
                            recoveryUiState = CaptureRecoveryUiState(
                                checking = false,
                                resolved = true,
                                latestResult = resolved.history.nights.latestReviewNight(),
                                recoverySummary = recovered?.let {
                                    if (it.completedPreviously) {
                                        "DreamLog finished clearing an already-completed " +
                                            "night marker."
                                    } else {
                                        "DreamLog recovered an interrupted night. Usable " +
                                            "session audio was kept and marked incomplete."
                                    }
                                },
                            )
                            refreshPreflight()
                        },
                        onFailure = { failure ->
                            recoveryUiState = recoveryUiState.copy(
                                checking = false,
                                resolved = false,
                                error = failure.message
                                    ?: "The unfinished capture could not be recovered.",
                                unreadableActiveMarker =
                                    failure is UnreadableActiveJournalException,
                            )
                            refreshPreflight()
                        },
                    )
                    runPendingLatestResultRefresh()
                }
            },
            "DreamLog-capture-recovery",
        ).start()
    }

    /**
     * An unreadable atomic marker cannot be reconstructed safely. The owner may explicitly move
     * it into app-private quarantine so its bytes and every audio artifact remain preserved while
     * a later night can start.
     */
    private fun preserveUnreadableActiveMarker() {
        if (recoveryRunning) return
        recoveryRunning = true
        recoveryUiState = recoveryUiState.copy(
            checking = true,
            resolved = false,
            error = null,
        )

        Thread(
            {
                val outcome = runCatching {
                    val runtime = CaptureRuntimeStore.snapshots.value
                    check(!runtime.active) {
                        "An active capture cannot be quarantined."
                    }
                    val preservedCount = journalStore.quarantineUnreadableActiveMarkers()
                    check(preservedCount > 0) {
                        "There is no unreadable active marker to preserve."
                    }
                    PreservedMarkerOutcome(
                        preservedCount = preservedCount,
                        history = nightRepository.reconcile(runtimeActiveNightId = null),
                    )
                }

                runOnUiThread {
                    recoveryRunning = false
                    outcome.fold(
                        onSuccess = { preserved ->
                            historyUiState = preserved.history.toUiState()
                            recoveryUiState = CaptureRecoveryUiState(
                                checking = false,
                                resolved = true,
                                latestResult =
                                    preserved.history.nights.latestReviewNight(),
                                recoverySummary =
                                    "DreamLog preserved ${preserved.preservedCount} unreadable " +
                                        "capture marker file(s) in app-private recovery storage. " +
                                        "Existing capture audio was left untouched.",
                            )
                            refreshPreflight()
                        },
                        onFailure = { failure ->
                            recoveryUiState = recoveryUiState.copy(
                                checking = false,
                                resolved = false,
                                error = failure.message
                                    ?: "The unreadable capture marker could not be preserved.",
                                unreadableActiveMarker = true,
                            )
                            refreshPreflight()
                        },
                    )
                    runPendingLatestResultRefresh()
                }
            },
            "DreamLog-marker-quarantine",
        ).start()
    }

    private fun reloadLatestResult() {
        if (latestResultRefreshRunning) {
            latestResultRefreshPending = true
            return
        }
        if (recoveryRunning) {
            latestResultRefreshPending = true
            return
        }
        latestResultRefreshRunning = true
        val requestedMutationRevision = historyMutationRevision
        Thread(
            {
                val runtime = CaptureRuntimeStore.snapshots.value
                val latest = runCatching {
                    nightRepository.reconcile(
                        runtimeActiveNightId = runtime.nightId.takeIf { runtime.active },
                    )
                }
                runOnUiThread {
                    latestResultRefreshRunning = false
                    if (requestedMutationRevision != historyMutationRevision) {
                        latestResultRefreshPending = true
                        runPendingLatestResultRefresh()
                        return@runOnUiThread
                    }
                    latest.fold(
                        onSuccess = { result ->
                            historyUiState = result.toUiState()
                            recoveryUiState = recoveryUiState.copy(
                                latestResult = result.nights.latestReviewNight(),
                            )
                            refreshPreflight()
                        },
                        onFailure = { failure ->
                            historyUiState = historyUiState.copy(
                                loading = false,
                                error = failure.message
                                    ?: "Persistent night history could not be refreshed.",
                            )
                        },
                    )
                    runPendingLatestResultRefresh()
                }
            },
            "DreamLog-latest-result",
        ).start()
    }

    private fun runPendingLatestResultRefresh() {
        if (
            !latestResultRefreshPending ||
            latestResultRefreshRunning ||
            recoveryRunning
        ) {
            return
        }
        latestResultRefreshPending = false
        reloadLatestResult()
    }

    private fun saveDream(
        dreamId: String,
        currentTitle: String?,
        currentText: String,
        onComplete: (String?) -> Unit,
    ) = runArchiveMutation("DreamLog-save-dream", onComplete) {
        nightRepository.editDream(dreamId, currentTitle, currentText)
    }

    private fun deleteDream(
        dreamId: String,
        onComplete: (String?) -> Unit,
    ) = runArchiveMutation("DreamLog-delete-dream", onComplete) {
        check(nightRepository.deleteDream(dreamId)) {
            "The dream was already deleted."
        }
    }

    private fun restoreDream(
        dreamId: String,
        onComplete: (String?) -> Unit,
    ) = runArchiveMutation("DreamLog-restore-dream", onComplete) {
        check(nightRepository.restoreDream(dreamId)) {
            "The dream is no longer deleted."
        }
    }

    private fun deleteNightRawAudio(
        nightId: String,
        onComplete: (String?) -> Unit,
    ) = runArchiveMutation("DreamLog-delete-night-audio", onComplete) {
        check(nightRepository.deleteNightRawAudio(nightId)) {
            "The selected night is no longer present."
        }
    }

    private fun deleteWholeNight(
        nightId: String,
        onComplete: (String?) -> Unit,
    ) = runArchiveMutation("DreamLog-delete-whole-night", onComplete) {
        check(nightRepository.deleteWholeNight(nightId)) {
            "The selected night is no longer present."
        }
    }

    private fun updateRawAudioRetention(
        days: Int,
        onComplete: (String?) -> Unit,
    ) {
        val period = RetentionPeriod.fromDays(days)
        if (period == null) {
            onComplete("Raw-audio retention must be 1, 7, or 30 days.")
            return
        }
        if (retentionMutationRunning) {
            onComplete("The retention change is still finishing.")
            return
        }
        if (
            !CaptureTranscriptionOperationGate.tryClaimLocalOperation {
                CaptureRuntimeStore.snapshots.value.active
            }
        ) {
            onComplete(
                "Finish night listening or the current local processing task before changing retention.",
            )
            return
        }

        retentionMutationRunning = true
        retentionMessage = "Applying ${period.displayLabel} raw-audio retention…"
        historyMutationRevision += 1L
        val launchFailure = runCatching {
            Thread(
                {
                    val result = try {
                        runCatching {
                            appSettingsStore.setRawAudioRetentionPeriod(period)
                            val retention = nightRepository.expireRawAudio(
                                period.days * RawAudioRetentionPolicy.MILLIS_PER_DAY,
                            )
                            retention to nightRepository.readHistory()
                        }
                    } finally {
                        CaptureTranscriptionOperationGate.releaseLocalOperation()
                    }
                    runOnUiThread {
                        retentionMutationRunning = false
                        retentionDays = appSettingsStore.readRawAudioRetentionDays()
                        result.fold(
                            onSuccess = { (retention, nights) ->
                                historyUiState = historyUiState.copy(
                                    loading = false,
                                    nights = nights,
                                    error = null,
                                )
                                recoveryUiState = recoveryUiState.copy(
                                    latestResult = nights.latestReviewNight(),
                                )
                                retentionMessage = when {
                                    retention.failureCount > 0 ->
                                        "Retention was updated. Some eligible audio could not " +
                                            "be removed; DreamLog will retry without deleting text."

                                    retention.deferredNightIds.isNotEmpty() ->
                                        "Retention was updated. Audio in use was kept and will be " +
                                            "checked again later."

                                    retention.expiredNightIds.size == 1 ->
                                        "Retention was updated. Raw audio expired for 1 night; " +
                                            "saved text, if any, remains."

                                    retention.expiredNightIds.size > 1 ->
                                        "Retention was updated. Raw audio expired for " +
                                            "${retention.expiredNightIds.size} nights; saved text, " +
                                            "if any, remains."

                                    else ->
                                        "Retention was updated. No retained audio was old enough " +
                                            "to expire."
                                }
                                onComplete(null)
                            },
                            onFailure = {
                                retentionMessage =
                                    "The retention setting could not be applied safely."
                                onComplete(retentionMessage)
                            },
                        )
                    }
                },
                "DreamLog-raw-audio-retention",
            ).start()
        }.exceptionOrNull()
        if (launchFailure != null) {
            CaptureTranscriptionOperationGate.releaseLocalOperation()
            retentionMutationRunning = false
            retentionMessage = "The retention change could not be started."
            onComplete(retentionMessage)
        }
    }

    private fun createDreamLogExport(
        selectedNightIds: Set<String>,
        format: DreamLogExportFormat,
        onComplete: (DreamLogExportDocument?, String?) -> Unit,
    ) {
        if (exportBuildRunning) {
            onComplete(null, "Another export is still being prepared.")
            return
        }
        if (
            CaptureRuntimeStore.snapshots.value.active ||
            TranscriptionRuntimeStore.snapshots.value.busy ||
            EnrichmentRuntimeStore.snapshots.value.busy ||
            archiveMutationRunning ||
            retentionMutationRunning
        ) {
            onComplete(
                null,
                "Finish night listening or the current local archive task before exporting.",
            )
            return
        }

        exportBuildRunning = true
        val launchFailure = runCatching {
            Thread(
                {
                    val document = runCatching {
                        val selection = DreamLogExportSelection.SelectedNights(selectedNightIds)
                        val initialRecords = nightRepository.readHistory()
                        val initiallySelected = selectNightsForExport(initialRecords, selection)
                        val lease = RawAudioUseRegistry.processWide.tryAcquireUse(
                            initiallySelected.map { it.night.nightId },
                        ) ?: error("The selected archive is being updated.")
                        lease.use {
                            val freshExport = createDreamLogExportV1(
                                availableNights = nightRepository.readHistory(),
                                selection = selection,
                            )
                            val baseName = if (freshExport.nights.size == 1) {
                                "dreamlog-${freshExport.nights.single().displayDate}"
                            } else {
                                "dreamlog-export"
                            }
                            DreamLogExportFormatter.document(
                                export = freshExport,
                                format = format,
                                suggestedBaseName = baseName,
                            )
                        }
                    }
                    runOnUiThread {
                        exportBuildRunning = false
                        document.fold(
                            onSuccess = { onComplete(it, null) },
                            onFailure = {
                                onComplete(
                                    null,
                                    "DreamLog could not prepare this private export. " +
                                        "Review the selected nights and try again.",
                                )
                            },
                        )
                    }
                },
                "DreamLog-build-export",
            ).start()
        }.exceptionOrNull()
        if (launchFailure != null) {
            exportBuildRunning = false
            onComplete(null, "The private export could not be started.")
        }
    }

    private fun shareDreamLogExport(
        document: DreamLogExportDocument,
        onComplete: (String?) -> Unit,
    ) {
        Thread(
            {
                val chooser = runCatching {
                    androidExportStore.createShareChooser(document)
                }
                runOnUiThread {
                    chooser.fold(
                        onSuccess = { intent ->
                            runCatching { startActivity(intent) }.fold(
                                onSuccess = { onComplete(null) },
                                onFailure = {
                                    onComplete("No app could open the Android Sharesheet.")
                                },
                            )
                        },
                        onFailure = {
                            onComplete("DreamLog could not stage the private export for sharing.")
                        },
                    )
                }
            },
            "DreamLog-share-export",
        ).start()
    }

    private fun saveDreamLogExport(
        document: DreamLogExportDocument,
        destination: Uri,
        onComplete: (String?) -> Unit,
    ) {
        Thread(
            {
                val result = runCatching {
                    androidExportStore.writeToUri(document, destination)
                }
                runOnUiThread {
                    result.fold(
                        onSuccess = { onComplete(null) },
                        onFailure = {
                            onComplete("DreamLog could not write the selected export file.")
                        },
                    )
                }
            },
            "DreamLog-save-export",
        ).start()
    }

    private fun runArchiveMutation(
        threadName: String,
        onComplete: (String?) -> Unit,
        mutation: () -> Unit,
    ) {
        if (archiveMutationRunning) {
            onComplete("Another archive change is still finishing.")
            return
        }
        if (
            !CaptureTranscriptionOperationGate.tryClaimLocalOperation {
                CaptureRuntimeStore.snapshots.value.active
            }
        ) {
            onComplete(
                "Finish night listening or the current local processing task before changing the archive.",
            )
            return
        }
        archiveMutationRunning = true
        historyMutationRevision += 1L
        val launchFailure = runCatching {
            Thread(
                {
                    val result = try {
                        runCatching {
                            mutation()
                            nightRepository.readHistory()
                        }
                    } finally {
                        CaptureTranscriptionOperationGate.releaseLocalOperation()
                    }
                    runOnUiThread {
                        archiveMutationRunning = false
                        result.fold(
                            onSuccess = { nights ->
                                historyUiState = historyUiState.copy(
                                    loading = false,
                                    nights = nights,
                                    error = null,
                                )
                                recoveryUiState = recoveryUiState.copy(
                                    latestResult = nights.latestReviewNight(),
                                )
                                onComplete(null)
                            },
                            onFailure = { failure ->
                                onComplete(
                                    failure.message ?: "The private archive could not be changed.",
                                )
                            },
                        )
                    }
                },
                threadName,
            ).start()
        }.exceptionOrNull()
        if (launchFailure != null) {
            CaptureTranscriptionOperationGate.releaseLocalOperation()
            archiveMutationRunning = false
            onComplete(
                launchFailure.message ?: "The private archive change could not be started.",
            )
        }
    }

    private fun persistAndStartNight(
        request: NightStartRequest,
        evaluation: PreflightEvaluation,
        charging: Boolean,
        onComplete: (String?) -> Unit,
    ) {
        if (
            TranscriptionRuntimeStore.snapshots.value.busy ||
            EnrichmentRuntimeStore.snapshots.value.busy
        ) {
            onComplete("Finish the current local model, transcription, or enrichment task first.")
            return
        }
        if (startPersistenceRunning) {
            onComplete("DreamLog is already preparing the local night record.")
            return
        }
        if (
            !CaptureTranscriptionOperationGate.tryReserveCaptureStart {
                CaptureRuntimeStore.snapshots.value.active
            }
        ) {
            onComplete("Finish the current local model, transcription, or enrichment task first.")
            return
        }
        startPersistenceRunning = true
        val launchFailure = runCatching {
            Thread(
                {
                val prepared = runCatching {
                    nightRepository.prepareStartingNight(
                        nightId = request.nightId,
                        displayDate = request.displayDate,
                        startedAtEpochMillis = request.startedAtEpochMillis,
                        startedUtcOffsetSeconds = request.startedAtUtcOffsetSeconds,
                    )
                    nightRepository.readHistory()
                }
                runOnUiThread {
                    startPersistenceRunning = false
                    prepared.fold(
                        onSuccess = { nights ->
                            historyUiState = PersistentHistoryUiState(
                                loading = false,
                                nights = nights,
                            )
                            val startFailure = runCatching {
                                CaptureTranscriptionOperationGate.finishReservedCaptureStart {
                                    CaptureRuntimeStore.prepareStart(
                                        evaluation = evaluation,
                                        nightId = request.nightId,
                                        displayDate = request.displayDate,
                                        startedAtEpochMillis = request.startedAtEpochMillis,
                                        charging = charging,
                                    )
                                }
                                NightListeningService.startNight(this, request)
                            }.exceptionOrNull()
                            if (startFailure == null) {
                                onComplete(null)
                            } else {
                                if (
                                    CaptureRuntimeStore.snapshots.value.phase ==
                                    CapturePhase.STARTING
                                ) {
                                    runCatching {
                                        CaptureRuntimeStore.requestEnd(
                                            NightEndReason.AUDIO_INITIALIZATION_FAILURE,
                                        )
                                        CaptureRuntimeStore.markNightFinalized(
                                            "The supported microphone service could not start.",
                                        )
                                    }
                                }
                                Thread(
                                    {
                                        runCatching {
                                            nightRepository.markStartFailed(
                                                request.nightId,
                                                "audio_initialization_failed",
                                            )
                                        }
                                        runOnUiThread(::reloadLatestResult)
                                    },
                                    "DreamLog-start-failure-history",
                                ).start()
                                onComplete(
                                    startFailure.message
                                        ?: "The supported microphone service could not start.",
                                )
                            }
                        },
                        onFailure = { failure ->
                            CaptureTranscriptionOperationGate.cancelCaptureStartReservation()
                            onComplete(
                                failure.message
                                    ?: "The local night record could not be created.",
                            )
                        },
                    )
                }
                },
                "DreamLog-start-history",
            ).start()
        }.exceptionOrNull()
        if (launchFailure != null) {
            startPersistenceRunning = false
            CaptureTranscriptionOperationGate.cancelCaptureStartReservation()
            onComplete(
                launchFailure.message ?: "The local night record could not be prepared.",
            )
        }
    }

    private data class RecoveryOutcome(
        val recovery: com.wivy.dreamlog.capture.JournalRecoveryResult?,
        val history: HistoryLoadResult,
    )

    private data class InspectionOutcome(
        val resolved: Boolean,
        val history: HistoryLoadResult,
    )

    private data class PreservedMarkerOutcome(
        val preservedCount: Int,
        val history: HistoryLoadResult,
    )

    private companion object {
        const val JOURNAL_DIRECTORY = "capture/journal"
        const val AUDIO_DIRECTORY = "capture/audio"
    }
}

private data class CaptureRecoveryUiState(
    val checking: Boolean = true,
    val resolved: Boolean = false,
    val error: String? = null,
    val latestResult: NightRecord? = null,
    val recoverySummary: String? = null,
    val unreadableActiveMarker: Boolean = false,
)

private data class PersistentHistoryUiState(
    val loading: Boolean = true,
    val nights: List<NightRecord> = emptyList(),
    val warningCount: Int = 0,
    val error: String? = null,
)

private fun HistoryLoadResult.toUiState(): PersistentHistoryUiState =
    PersistentHistoryUiState(
        loading = false,
        nights = nights,
        warningCount = warningCount,
    )

private fun List<NightRecord>.latestReviewNight(): NightRecord? =
    firstOrNull {
        it.night.captureState != NightCaptureState.STARTING &&
            it.night.captureState != NightCaptureState.ACTIVE
    }

private fun NightRecord.hasUnclaimedRetainedTranscriptionSession(): Boolean {
    val claimedSessionIds = transcripts.mapTo(mutableSetOf()) { it.transcript.sessionId }
    return sessions.any { session ->
        session.audioState == AudioEvidenceState.RETAINED &&
            session.finalizedAtEpochMillis != null &&
            session.sessionId !in claimedSessionIds
    }
}

internal fun NightRecord.isReadyForManualEnrichmentBatch(): Boolean {
    val captureEnded = night.captureState in setOf(
        NightCaptureState.ENDED,
        NightCaptureState.INTERRUPTED,
    ) && night.endedAtEpochMillis != null
    val transcriptReady = hasGenuinelyEmptyEnrichmentSource() ||
        hasCompleteEnrichmentSource()
    val enrichmentReady = night.enrichmentState == ProcessingState.WAITING_FOR_TRANSCRIPTION ||
        (
            night.enrichmentState == ProcessingState.FAILED &&
                persistedEnrichmentFailureIsRetryable(night.enrichmentFailure)
            )
    return captureEnded && transcriptReady && enrichmentReady
}

internal fun NightRecord.hasGenuinelyEmptyEnrichmentSource(): Boolean =
    sessions.isEmpty() &&
        transcripts.isEmpty() &&
        night.reportedSessionCount == 0 &&
        night.reportedIncompleteSessionCount == 0 &&
        night.rawAudioState == RawAudioState.NONE &&
        night.transcriptionState != ProcessingState.FAILED &&
        night.transcriptionFailure == null

internal fun NightRecord.hasCompleteEnrichmentSource(): Boolean {
    if (
        sessions.isEmpty() ||
        night.transcriptionState != ProcessingState.COMPLETE ||
        night.transcriptionFailure != null ||
        night.reportedSessionCount != sessions.size
    ) {
        return false
    }
    val sessionsById = sessions.associateBy { it.sessionId }
    if (
        sessionsById.size != sessions.size ||
        sessions.any {
            it.nightId != night.nightId ||
                it.captureOrder < 0 ||
                it.finalizedAtEpochMillis == null
        } ||
        sessions.map { it.captureOrder }.distinct().size != sessions.size
    ) {
        return false
    }
    val transcriptsBySession = transcripts.associateBy { it.transcript.sessionId }
    if (
        transcriptsBySession.size != transcripts.size ||
        transcriptsBySession.keys != sessionsById.keys
    ) {
        return false
    }

    val source = mutableListOf<NightTranscriptSegment>()
    for (record in transcripts) {
        val transcript = record.transcript
        val session = sessionsById[transcript.sessionId] ?: return false
        if (
            transcript.nightId != night.nightId ||
            transcript.state != ProcessingState.COMPLETE ||
            transcript.failureDetail != null ||
            transcript.rawText == null ||
            transcript.attemptCount <= 0 ||
            transcript.startedAtEpochMillis < 0L ||
            transcript.completedAtEpochMillis == null ||
            transcript.completedAtEpochMillis < transcript.startedAtEpochMillis
        ) {
            return false
        }
        val orderedSegments = record.segments.sortedBy { it.segmentIndex }
        if (
            orderedSegments.map { it.segmentIndex } != orderedSegments.indices.toList() ||
            transcript.rawText.isBlank() != orderedSegments.isEmpty()
        ) {
            return false
        }
        for (segment in orderedSegments) {
            if (segment.sessionId != transcript.sessionId) return false
            val mapped = runCatching {
                NightTranscriptSegment(
                    nightId = night.nightId,
                    sessionId = transcript.sessionId,
                    sessionOrder = session.captureOrder,
                    transcriptAttempt = transcript.attemptCount,
                    segmentIndex = segment.segmentIndex,
                    sourceStartMillis = segment.sourceStartMillis,
                    sourceEndMillis = segment.sourceEndMillis,
                    text = segment.text,
                )
            }.getOrNull() ?: return false
            source += mapped
        }
    }
    return source.isNotEmpty() && runCatching {
        OrderedNightTranscript.create(night.nightId, source)
    }.isSuccess
}

internal enum class NightReprocessMode {
    ENRICHMENT_ONLY,
    RETRANSCRIBE_THEN_ENRICH,
}

internal fun selectNightReprocessMode(
    hasCompleteEnrichmentSource: Boolean,
    everyTranscriptUsesCurrentPipeline: Boolean,
): NightReprocessMode = if (
    hasCompleteEnrichmentSource && everyTranscriptUsesCurrentPipeline
) {
    NightReprocessMode.ENRICHMENT_ONLY
} else {
    NightReprocessMode.RETRANSCRIBE_THEN_ENRICH
}

internal fun NightRecord.nightReprocessMode(): NightReprocessMode = selectNightReprocessMode(
    hasCompleteEnrichmentSource = hasCompleteEnrichmentSource(),
    everyTranscriptUsesCurrentPipeline = transcripts.isNotEmpty() && transcripts.all { record ->
        SherpaParakeetTranscriptionEngine.hasCurrentProvenance(record.transcript)
    },
)

private enum class CuePreviewState {
    IDLE,
    PLAYING,
    PLAYED,
    FAILED,
}

internal enum class NightReprocessPhase {
    IDLE,
    TRANSCRIBING,
    ENRICHING,
    ENRICHING_PRESERVED_TRANSCRIPT,
}

internal data class NightReprocessProcessState(
    val ownerProcessInstanceId: String,
    val phaseName: String,
    val message: String?,
)

internal fun reconcileNightReprocessProcessState(
    savedOwnerProcessInstanceId: String,
    currentProcessInstanceId: String,
    phaseName: String,
    message: String?,
): NightReprocessProcessState {
    val phase = runCatching { NightReprocessPhase.valueOf(phaseName) }
        .getOrDefault(NightReprocessPhase.IDLE)
    if (savedOwnerProcessInstanceId == currentProcessInstanceId) {
        return NightReprocessProcessState(
            ownerProcessInstanceId = currentProcessInstanceId,
            phaseName = phase.name,
            message = message,
        )
    }
    return NightReprocessProcessState(
        ownerProcessInstanceId = currentProcessInstanceId,
        phaseName = NightReprocessPhase.IDLE.name,
        message = if (phase == NightReprocessPhase.IDLE) {
            message
        } else {
            "Reprocessing was interrupted when DreamLog stopped. Any completed local work was " +
                "kept; retry this night when ready."
        },
    )
}

internal fun canStartAutomaticTranscription(
    captureActive: Boolean,
    transcriptionBusy: Boolean,
    enrichmentBusy: Boolean,
    reprocessPhaseName: String,
): Boolean =
    !captureActive &&
        !transcriptionBusy &&
        !enrichmentBusy &&
        runCatching { NightReprocessPhase.valueOf(reprocessPhaseName) }
            .getOrDefault(NightReprocessPhase.IDLE) == NightReprocessPhase.IDLE

internal fun shouldKeepScreenOnForLocalProcessing(
    enrichmentPhase: EnrichmentRuntimePhase,
): Boolean = enrichmentPhase == EnrichmentRuntimePhase.RUNNING

internal fun nightReprocessGlobalUnavailableReason(
    captureActive: Boolean,
    archiveMutationRunning: Boolean,
    transcriptionModelPhase: TranscriptionModelPhase,
    transcriptionRuntimePhase: TranscriptionRuntimePhase,
    enrichmentModelPhase: EnrichmentModelPhase,
    enrichmentRuntimePhase: EnrichmentRuntimePhase,
    requiresTranscriptionModel: Boolean = true,
): String? = when {
    captureActive -> "End the active night before reprocessing saved nights."
    archiveMutationRunning -> "Wait for the current archive change to finish."
    transcriptionRuntimePhase == TranscriptionRuntimePhase.RUNNING ->
        "Local transcription is still running. Reprocessing becomes available when it finishes."

    enrichmentRuntimePhase == EnrichmentRuntimePhase.RUNNING ->
        "Dream regrouping is still running. Reprocessing becomes available when it finishes."

    transcriptionModelPhase == TranscriptionModelPhase.VERIFYING ->
        "The local transcription model is still being checked."

    transcriptionModelPhase in setOf(
        TranscriptionModelPhase.INSTALLING,
        TranscriptionModelPhase.CANCELLING,
        TranscriptionModelPhase.REMOVING,
    ) -> "Wait for the transcription model operation to finish."

    requiresTranscriptionModel && transcriptionModelPhase in setOf(
        TranscriptionModelPhase.UNINITIALIZED,
        TranscriptionModelPhase.VERIFICATION_DEFERRED,
    ) -> "The local transcription model is still being checked."

    requiresTranscriptionModel &&
        transcriptionModelPhase == TranscriptionModelPhase.NOT_INSTALLED ->
        "Install the current local transcription model from the home screen."

    requiresTranscriptionModel && transcriptionModelPhase in setOf(
        TranscriptionModelPhase.INVALID,
        TranscriptionModelPhase.ERROR,
    ) -> "Repair the local transcription model from the home screen before reprocessing."

    enrichmentModelPhase in setOf(
        EnrichmentModelPhase.UNINITIALIZED,
        EnrichmentModelPhase.VERIFYING,
        EnrichmentModelPhase.VERIFICATION_DEFERRED,
    ) -> "The local dream-grouping model is still being checked."

    enrichmentModelPhase in setOf(
        EnrichmentModelPhase.INSTALLING,
        EnrichmentModelPhase.CANCELLING,
        EnrichmentModelPhase.REMOVING,
    ) -> "Wait for the dream-grouping model operation to finish."

    enrichmentModelPhase == EnrichmentModelPhase.NOT_INSTALLED ->
        "Install the current local dream-grouping model from the home screen."

    enrichmentModelPhase in setOf(
        EnrichmentModelPhase.INVALID,
        EnrichmentModelPhase.ERROR,
    ) -> "Repair the local dream-grouping model from the home screen before reprocessing."

    else -> null
}

@Composable
private fun DreamLogApp(
    preflightRefreshKey: Int,
    recoveryUiState: CaptureRecoveryUiState,
    historyUiState: PersistentHistoryUiState,
    onRefreshPreflight: () -> Unit,
    onRetryRecovery: () -> Unit,
    onPreserveUnreadableMarker: () -> Unit,
    onReloadLatestResult: () -> Unit,
    onStartNight: (
        NightStartRequest,
        PreflightEvaluation,
        Boolean,
        (String?) -> Unit,
    ) -> Unit,
    archiveMutationRunning: Boolean,
    retentionDays: Int,
    retentionMutationRunning: Boolean,
    retentionMessage: String?,
    exportBuildRunning: Boolean,
    onSaveDream: (String, String?, String, (String?) -> Unit) -> Unit,
    onDeleteDream: (String, (String?) -> Unit) -> Unit,
    onRestoreDream: (String, (String?) -> Unit) -> Unit,
    onDeleteNightRawAudio: (String, (String?) -> Unit) -> Unit,
    onDeleteWholeNight: (String, (String?) -> Unit) -> Unit,
    onUpdateRawAudioRetention: (Int, (String?) -> Unit) -> Unit,
    onCreateExport: (
        Set<String>,
        DreamLogExportFormat,
        (DreamLogExportDocument?, String?) -> Unit,
    ) -> Unit,
    onShareExport: (DreamLogExportDocument, (String?) -> Unit) -> Unit,
    onSaveExport: (DreamLogExportDocument, Uri, (String?) -> Unit) -> Unit,
) {
    val navController = rememberNavController()
    val runtime by CaptureRuntimeStore.snapshots.collectAsState()
    val transcriptionRuntime by TranscriptionRuntimeStore.snapshots.collectAsState()
    val enrichmentRuntime by EnrichmentRuntimeStore.snapshots.collectAsState()
    val rootView = LocalView.current
    var reprocessNightId by rememberSaveable { mutableStateOf<String?>(null) }
    var reprocessPhaseName by rememberSaveable {
        mutableStateOf(NightReprocessPhase.IDLE.name)
    }
    var reprocessMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var reprocessOwnerProcessInstanceId by rememberSaveable {
        mutableStateOf(TranscriptionRuntimeStore.processInstanceId)
    }
    var settingsExportNightId by rememberSaveable { mutableStateOf<String?>(null) }
    val reprocessPhase = runCatching {
        NightReprocessPhase.valueOf(reprocessPhaseName)
    }.getOrDefault(NightReprocessPhase.IDLE)

    LaunchedEffect(TranscriptionRuntimeStore.processInstanceId) {
        val recovered = reconcileNightReprocessProcessState(
            savedOwnerProcessInstanceId = reprocessOwnerProcessInstanceId,
            currentProcessInstanceId = TranscriptionRuntimeStore.processInstanceId,
            phaseName = reprocessPhaseName,
            message = reprocessMessage,
        )
        reprocessOwnerProcessInstanceId = recovered.ownerProcessInstanceId
        reprocessPhaseName = recovered.phaseName
        reprocessMessage = recovered.message
    }

    val keepScreenOnForLocalProcessing = shouldKeepScreenOnForLocalProcessing(
        enrichmentPhase = enrichmentRuntime.runtimePhase,
    )
    DisposableEffect(rootView, keepScreenOnForLocalProcessing) {
        val priorKeepScreenOn = rootView.keepScreenOn
        rootView.keepScreenOn = keepScreenOnForLocalProcessing
        onDispose { rootView.keepScreenOn = priorKeepScreenOn }
    }
    val automaticTranscriptionNightId = historyUiState.nights.firstOrNull { record ->
        record.night.captureState in setOf(
            NightCaptureState.ENDED,
            NightCaptureState.INTERRUPTED,
        ) &&
            record.hasUnclaimedRetainedTranscriptionSession() &&
            record.night.nightId != transcriptionRuntime.nightId
    }?.night?.nightId
    val readyEnrichmentRecords = historyUiState.nights.filter { record ->
        !record.hasProtectedDreamChanges && record.isReadyForManualEnrichmentBatch()
    }

    LaunchedEffect(runtime.phase, runtime.sessionCount, runtime.incompleteSessionCount) {
        if (
            runtime.phase == CapturePhase.ENDED ||
            runtime.phase == CapturePhase.INTERRUPTED
        ) {
            onReloadLatestResult()
        } else if (
            runtime.active &&
            runtime.phase in setOf(
                CapturePhase.LISTENING,
                CapturePhase.RECORDING,
                CapturePhase.ACKNOWLEDGING,
                CapturePhase.FINALIZING,
            )
        ) {
            onReloadLatestResult()
        }
    }

    LaunchedEffect(transcriptionRuntime.historyRevision) {
        if (transcriptionRuntime.historyRevision > 0L) onReloadLatestResult()
    }

    LaunchedEffect(enrichmentRuntime.historyRevision) {
        if (enrichmentRuntime.historyRevision > 0L) onReloadLatestResult()
    }

    LaunchedEffect(
        reprocessNightId,
        reprocessPhaseName,
        transcriptionRuntime.nightId,
        transcriptionRuntime.transcriptionPhase,
        transcriptionRuntime.transcriptionError,
    ) {
        val selectedNightId = reprocessNightId ?: return@LaunchedEffect
        if (reprocessPhase != NightReprocessPhase.TRANSCRIBING) return@LaunchedEffect
        if (transcriptionRuntime.nightId != selectedNightId) return@LaunchedEffect
        when (transcriptionRuntime.transcriptionPhase) {
            TranscriptionRuntimePhase.RUNNING -> Unit
            TranscriptionRuntimePhase.ERROR -> {
                val failureMessage = transcriptionRuntime.transcriptionError
                    ?: "Re-transcription failed. Existing generated text and raw audio were kept."
                reprocessPhaseName =
                    NightReprocessPhase.ENRICHING_PRESERVED_TRANSCRIPT.name
                reprocessMessage =
                    "$failureMessage Regrouping the preserved transcript with the latest " +
                        "enrichment model…"
                if (!EnrichmentRuntimeStore.processNight(selectedNightId)) {
                    reprocessPhaseName = NightReprocessPhase.IDLE.name
                    reprocessMessage =
                        "$failureMessage Dream regrouping could not start; try again when local " +
                            "processing is idle."
                }
            }
            TranscriptionRuntimePhase.IDLE -> {
                reprocessPhaseName = NightReprocessPhase.ENRICHING.name
                reprocessMessage =
                    "High-quality transcription finished. Regrouping dreams semantically…"
                if (!EnrichmentRuntimeStore.processNight(selectedNightId)) {
                    reprocessPhaseName = NightReprocessPhase.IDLE.name
                    reprocessMessage =
                        "The new transcript was saved, but dream regrouping could not start. " +
                            "Keep the app open and try enrichment again."
                }
            }
        }
    }

    LaunchedEffect(
        reprocessNightId,
        reprocessPhaseName,
        enrichmentRuntime.nightId,
        enrichmentRuntime.runtimePhase,
        enrichmentRuntime.runtimeError,
        enrichmentRuntime.batchCompletedNightCount,
        enrichmentRuntime.batchFailedNightCount,
    ) {
        val selectedNightId = reprocessNightId ?: return@LaunchedEffect
        if (
            reprocessPhase !in setOf(
                NightReprocessPhase.ENRICHING,
                NightReprocessPhase.ENRICHING_PRESERVED_TRANSCRIPT,
            )
        ) {
            return@LaunchedEffect
        }
        if (enrichmentRuntime.nightId != selectedNightId) return@LaunchedEffect
        when (enrichmentRuntime.runtimePhase) {
            EnrichmentRuntimePhase.RUNNING -> Unit
            EnrichmentRuntimePhase.ERROR -> {
                reprocessPhaseName = NightReprocessPhase.IDLE.name
                reprocessMessage = enrichmentRuntime.runtimeError
                    ?: "Dream regrouping needs to be retried. The previous generated dreams were kept."
            }
            EnrichmentRuntimePhase.IDLE -> {
                val usedPreservedTranscript =
                    reprocessPhase == NightReprocessPhase.ENRICHING_PRESERVED_TRANSCRIPT
                reprocessPhaseName = NightReprocessPhase.IDLE.name
                reprocessMessage = if (usedPreservedTranscript) {
                    "Reprocessing complete: dreams regrouped from the preserved transcript after " +
                        "re-transcription could not finish."
                } else {
                    "Reprocessing complete: dreams regrouped with the latest enrichment model."
                }
            }
        }
    }

    LaunchedEffect(runtime.active, transcriptionRuntime.modelPhase) {
        if (
            !runtime.active &&
            transcriptionRuntime.modelPhase ==
            TranscriptionModelPhase.VERIFICATION_DEFERRED
        ) {
            TranscriptionRuntimeStore.refreshModelStatus()
        }
    }

    LaunchedEffect(
        runtime.active,
        transcriptionRuntime.busy,
        enrichmentRuntime.modelPhase,
    ) {
        if (
            !runtime.active &&
            !transcriptionRuntime.busy &&
            enrichmentRuntime.modelPhase == EnrichmentModelPhase.VERIFICATION_DEFERRED
        ) {
            EnrichmentRuntimeStore.refreshModelStatus()
        }
    }

    // Raw transcription continues automatically. Dream enrichment is deliberately owner-triggered
    // so several ready nights can be frozen into one finite, model-reusing batch.
    LaunchedEffect(
        runtime.active,
        transcriptionRuntime.modelPhase,
        transcriptionRuntime.transcriptionPhase,
        transcriptionRuntime.busy,
        enrichmentRuntime.busy,
        automaticTranscriptionNightId,
        reprocessPhaseName,
    ) {
        if (
            !canStartAutomaticTranscription(
                captureActive = runtime.active,
                transcriptionBusy = transcriptionRuntime.busy,
                enrichmentBusy = enrichmentRuntime.busy,
                reprocessPhaseName = reprocessPhaseName,
            )
        ) {
            return@LaunchedEffect
        }

        if (
            automaticTranscriptionNightId != null &&
            transcriptionRuntime.modelPhase == TranscriptionModelPhase.INSTALLED
        ) {
            TranscriptionRuntimeStore.processNight(automaticTranscriptionNightId)
        }
    }

    NavHost(
        navController = navController,
        startDestination = HOME_ROUTE,
    ) {
        composable(HOME_ROUTE) {
            DreamLogScreen(
                preflightRefreshKey = preflightRefreshKey,
                recoveryUiState = recoveryUiState,
                historyUiState = historyUiState,
                transcriptionRuntime = transcriptionRuntime,
                enrichmentRuntime = enrichmentRuntime,
                readyEnrichmentRecords = readyEnrichmentRecords,
                onRefreshPreflight = onRefreshPreflight,
                onRetryRecovery = onRetryRecovery,
                onPreserveUnreadableMarker = onPreserveUnreadableMarker,
                onReloadLatestResult = onReloadLatestResult,
                onStartNight = onStartNight,
                onOpenNight = { nightId ->
                    navController.navigate("$NIGHT_ROUTE/$nightId")
                },
                onOpenSettings = {
                    settingsExportNightId = null
                    navController.navigate(SETTINGS_ROUTE)
                },
            )
        }
        composable(
            route = "$NIGHT_ROUTE/{nightId}",
            arguments = listOf(
                navArgument("nightId") {
                    type = NavType.StringType
                },
            ),
        ) { entry ->
            val nightId = entry.arguments?.getString("nightId")
            val selectedRecord = historyUiState.nights.firstOrNull {
                it.night.nightId == nightId
            }
            val selectedReprocessMode = selectedRecord?.nightReprocessMode()
                ?: NightReprocessMode.RETRANSCRIBE_THEN_ENRICH
            NightDetailScreen(
                record = selectedRecord,
                captureActive = runtime.active,
                localProcessingActive = transcriptionRuntime.busy || enrichmentRuntime.busy,
                archiveMutationRunning = archiveMutationRunning,
                onBack = navController::popBackStack,
                onOpenDream = { dreamId ->
                    nightId?.let {
                        navController.navigate("$NIGHT_ROUTE/$it/$DREAM_ROUTE/$dreamId")
                    }
                },
                transcriptionAvailable =
                    transcriptionRuntime.modelPhase == TranscriptionModelPhase.INSTALLED,
                transcriptionRunning =
                    transcriptionRuntime.transcriptionPhase ==
                    TranscriptionRuntimePhase.RUNNING,
                transcriptionMessage = transcriptionRuntime.messageForNight(nightId),
                reprocessUnavailableReason = nightReprocessGlobalUnavailableReason(
                    captureActive = runtime.active,
                    archiveMutationRunning = archiveMutationRunning,
                    transcriptionModelPhase = transcriptionRuntime.modelPhase,
                    transcriptionRuntimePhase = transcriptionRuntime.transcriptionPhase,
                    enrichmentModelPhase = enrichmentRuntime.modelPhase,
                    enrichmentRuntimePhase = enrichmentRuntime.runtimePhase,
                    requiresTranscriptionModel =
                        selectedReprocessMode == NightReprocessMode.RETRANSCRIBE_THEN_ENRICH,
                ),
                reprocessRequiresTranscription =
                    selectedReprocessMode == NightReprocessMode.RETRANSCRIBE_THEN_ENRICH,
                reprocessRunning = nightId == reprocessNightId &&
                    reprocessPhase != NightReprocessPhase.IDLE,
                reprocessMessage = reprocessMessage.takeIf { nightId == reprocessNightId },
                onTranscribeNight = { selectedNightId ->
                    TranscriptionRuntimeStore.processNight(selectedNightId)
                },
                onResumeTranscription = { selectedNightId ->
                    TranscriptionRuntimeStore.resumeNight(selectedNightId)
                },
                onRetryTranscription = { selectedNightId, sessionId ->
                    TranscriptionRuntimeStore.retrySession(selectedNightId, sessionId)
                },
                onRetranscribe = { selectedNightId, sessionId ->
                    TranscriptionRuntimeStore.retranscribeSession(selectedNightId, sessionId)
                },
                onReprocessNight = { selectedNightId ->
                    reprocessOwnerProcessInstanceId = TranscriptionRuntimeStore.processInstanceId
                    reprocessNightId = selectedNightId
                    if (selectedReprocessMode == NightReprocessMode.ENRICHMENT_ONLY) {
                        reprocessMessage =
                            "The saved transcript already uses the current speech model. " +
                                "Regrouping dreams with the latest enrichment model…"
                        reprocessPhaseName = NightReprocessPhase.ENRICHING.name
                        if (!EnrichmentRuntimeStore.processNight(selectedNightId)) {
                            reprocessPhaseName = NightReprocessPhase.IDLE.name
                            reprocessMessage =
                                "Dream regrouping could not start. Finish the current local " +
                                    "operation and verify the enrichment model is installed."
                        }
                    } else {
                        reprocessMessage = "Re-transcribing every retained wakeword session…"
                        reprocessPhaseName = NightReprocessPhase.TRANSCRIBING.name
                        if (!TranscriptionRuntimeStore.retranscribeNight(selectedNightId)) {
                            reprocessPhaseName = NightReprocessPhase.IDLE.name
                            reprocessMessage =
                                "Reprocessing could not start. Finish the current local operation " +
                                    "and verify both models are installed."
                        }
                    }
                },
                onDeleteNightRawAudio = onDeleteNightRawAudio,
                onExportNight = { selectedNightId ->
                    settingsExportNightId = selectedNightId
                    navController.navigate(SETTINGS_ROUTE)
                },
                onDeleteWholeNight = { selectedNightId, onComplete ->
                    onDeleteWholeNight(selectedNightId) { error ->
                        onComplete(error)
                        if (error == null) {
                            navController.popBackStack(HOME_ROUTE, inclusive = false)
                        }
                    }
                },
            )
        }
        composable(
            route = "$NIGHT_ROUTE/{nightId}/$DREAM_ROUTE/{dreamId}",
            arguments = listOf(
                navArgument("nightId") { type = NavType.StringType },
                navArgument("dreamId") { type = NavType.StringType },
            ),
        ) { entry ->
            val nightId = entry.arguments?.getString("nightId")
            val dreamId = entry.arguments?.getString("dreamId").orEmpty()
            DreamDetailScreen(
                record = historyUiState.nights.firstOrNull {
                    it.night.nightId == nightId
                },
                dreamId = dreamId,
                captureActive = runtime.active,
                localProcessingActive = transcriptionRuntime.busy || enrichmentRuntime.busy,
                archiveMutationRunning = archiveMutationRunning,
                onBack = navController::popBackStack,
                onSaveDream = onSaveDream,
                onDeleteDream = onDeleteDream,
                onRestoreDream = onRestoreDream,
            )
        }
        composable(SETTINGS_ROUTE) {
            SettingsScreen(
                nights = historyUiState.nights,
                preselectedNightId = settingsExportNightId,
                cueRefreshKey = preflightRefreshKey,
                captureActive = runtime.active,
                archiveMutationRunning = archiveMutationRunning,
                retentionDays = retentionDays,
                retentionMutationRunning = retentionMutationRunning,
                retentionMessage = retentionMessage,
                exportBuildRunning = exportBuildRunning,
                transcriptionRuntime = transcriptionRuntime,
                enrichmentRuntime = enrichmentRuntime,
                onBack = navController::popBackStack,
                onUpdateRawAudioRetention = onUpdateRawAudioRetention,
                onCreateExport = onCreateExport,
                onShareExport = onShareExport,
                onSaveExport = onSaveExport,
            )
        }
    }
}

@Composable
private fun DreamLogScreen(
    preflightRefreshKey: Int,
    recoveryUiState: CaptureRecoveryUiState,
    historyUiState: PersistentHistoryUiState,
    transcriptionRuntime: TranscriptionRuntimeSnapshot,
    enrichmentRuntime: EnrichmentRuntimeSnapshot,
    readyEnrichmentRecords: List<NightRecord>,
    onRefreshPreflight: () -> Unit,
    onRetryRecovery: () -> Unit,
    onPreserveUnreadableMarker: () -> Unit,
    onReloadLatestResult: () -> Unit,
    onStartNight: (
        NightStartRequest,
        PreflightEvaluation,
        Boolean,
        (String?) -> Unit,
    ) -> Unit,
    onOpenNight: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime by CaptureRuntimeStore.snapshots.collectAsState()
    var cueTestedThisVisit by remember { mutableStateOf(false) }
    var cuePreviewState by remember { mutableStateOf(CuePreviewState.IDLE) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var microphoneRequestedThisVisit by remember { mutableStateOf(false) }
    var notificationRequestedThisVisit by remember { mutableStateOf(false) }
    var startPersistencePending by remember { mutableStateOf(false) }
    var setupDetailsExpanded by rememberSaveable { mutableStateOf(false) }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        microphoneRequestedThisVisit = true
        onRefreshPreflight()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationRequestedThisVisit = true
        onRefreshPreflight()
    }

    val cuePlayerResult = remember(context) {
        runCatching { CuePlayer(context, R.raw.m01_cue) }
    }
    DisposableEffect(cuePlayerResult) {
        onDispose {
            cuePlayerResult.getOrNull()?.close()
        }
    }

    val latestResult = recoveryUiState.latestResult
    val preflight = remember(
        context,
        preflightRefreshKey,
        recoveryUiState.resolved,
        cueTestedThisVisit,
        latestResult?.night?.nightId,
        latestResult?.night?.interrupted,
    ) {
        AndroidPreflight.evaluate(
            context = context,
            priorCaptureStateResolved = recoveryUiState.resolved,
            priorInterruption = latestResult?.night?.interrupted == true,
            cueTestedThisVisit = cueTestedThisVisit,
        )
    }

    val startEnabled =
        preflight.evaluation.canStart &&
            recoveryUiState.resolved &&
            cuePreviewState != CuePreviewState.PLAYING &&
            !startPersistencePending &&
            !runtime.active &&
            !transcriptionRuntime.busy &&
            !enrichmentRuntime.busy
    val setupNeedsAttention =
        !preflight.evaluation.canStart || !recoveryUiState.resolved
    LaunchedEffect(setupNeedsAttention) {
        if (!setupNeedsAttention) setupDetailsExpanded = false
    }
    val startBlockedMessage = when {
        transcriptionRuntime.busy -> transcriptionRuntime.startNightBlockedMessage()
        enrichmentRuntime.busy -> enrichmentRuntime.startNightBlockedMessage()
        cuePreviewState == CuePreviewState.PLAYING ->
            "Wait for the cue preview to finish before starting."

        startPersistencePending -> "DreamLog is preparing the private local night record."
        else -> startBlockedReason(
            recoveryUiState = recoveryUiState,
            preflight = preflight,
        )
    }
    val startNight = {
        val freshPreflight = AndroidPreflight.evaluate(
            context = context,
            priorCaptureStateResolved = recoveryUiState.resolved,
            priorInterruption = latestResult?.night?.interrupted == true,
            cueTestedThisVisit = cueTestedThisVisit,
        )
        if (!freshPreflight.evaluation.canStart) {
            actionMessage = "A required check changed. Review the blockers and try again."
            onRefreshPreflight()
        } else {
            val now = ZonedDateTime.now()
            val startedAtEpochMillis = now.toInstant().toEpochMilli()
            val nightId = UUID.randomUUID()
                .toString()
                .replace("-", "")
            val displayDate = NightDateMapper.displayDate(now).toString()
            val request = NightStartRequest(
                nightId = nightId,
                displayDate = displayDate,
                startedAtEpochMillis = startedAtEpochMillis,
                startedAtUtcOffsetSeconds = now.offset.totalSeconds,
            )
            startPersistencePending = true
            actionMessage = "Preparing the private local night record…"
            onStartNight(
                request,
                freshPreflight.evaluation,
                freshPreflight.charging,
            ) { error ->
                startPersistencePending = false
                actionMessage = error
            }
        }
    }
    val requiredChecksContent: @Composable () -> Unit = {
        PermissionAndRequiredChecks(
            preflight = preflight,
            recoveryUiState = recoveryUiState,
            microphoneRequestedThisVisit = microphoneRequestedThisVisit,
            notificationRequestedThisVisit = notificationRequestedThisVisit,
            onRequestMicrophone = {
                if (
                    microphoneRequestedThisVisit &&
                    !context.shouldShowPermissionRationaleCompat(
                        Manifest.permission.RECORD_AUDIO,
                    )
                ) {
                    context.openAppDetails()
                } else {
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onRequestNotifications = {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !preflight.notificationPermissionGranted &&
                    !(
                        notificationRequestedThisVisit &&
                            !context.shouldShowPermissionRationaleCompat(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        )
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.openNotificationSettings()
                }
            },
            onRetryRecovery = onRetryRecovery,
            onPreserveUnreadableMarker = onPreserveUnreadableMarker,
        )
    }
    val morningAction = homeMorningAction(
        latestResult = latestResult,
        transcriptionRuntime = transcriptionRuntime,
        enrichmentRuntime = enrichmentRuntime,
        readyEnrichmentRecords = readyEnrichmentRecords,
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp,
                vertical = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "DreamLog",
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                }
            }

            item {
                HomePrimaryActionCard(
                    runtime = runtime,
                    morningAction = morningAction,
                    startEnabled = startEnabled,
                    setupNeedsAttention = setupNeedsAttention,
                    startBlockedMessage = startBlockedMessage,
                    actionMessage = actionMessage,
                    onStartNight = startNight,
                    onEndNight = {
                        actionMessage = runCatching {
                            NightListeningService.endNight(context)
                            null
                        }.getOrElse { failure ->
                            failure.message ?: "DreamLog could not request night end."
                        }
                    },
                    onMorningAction = { action ->
                        when (action.kind) {
                            HomeNextActionKind.RESUME_TRANSCRIPTION -> {
                                if (action.requiresSettings) {
                                    onOpenSettings()
                                } else {
                                    val nightId = action.nightId
                                    if (nightId != null) {
                                        TranscriptionRuntimeStore.resumeNight(nightId)
                                    }
                                }
                            }

                            HomeNextActionKind.ENRICH -> {
                                if (action.requiresSettings) {
                                    onOpenSettings()
                                } else if (
                                    !runtime.active &&
                                    !transcriptionRuntime.busy &&
                                    !enrichmentRuntime.busy
                                ) {
                                    EnrichmentRuntimeStore.processNights(
                                        readyEnrichmentRecords
                                            .map { it.night.nightId }
                                            .distinct(),
                                    )
                                }
                            }

                            HomeNextActionKind.TRANSCRIBING,
                            HomeNextActionKind.ENRICHING,
                            -> Unit
                        }
                    },
                )
            }

            if (!runtime.active && morningAction == null) {
                item {
                    if (setupNeedsAttention) {
                        Button(
                            onClick = { setupDetailsExpanded = !setupDetailsExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (setupDetailsExpanded) "Hide setup" else "Resolve setup")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { setupDetailsExpanded = !setupDetailsExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (setupDetailsExpanded) {
                                    "Hide setup details"
                                } else {
                                    "Show setup details"
                                },
                            )
                        }
                    }
                }
                if (setupDetailsExpanded) {
                    item { requiredChecksContent() }
                    item { DeferredStartChecksCard() }
                    item {
                        CueCheckCard(
                            preflight = preflight,
                            cuePlayerAvailable = cuePlayerResult.isSuccess,
                            cuePreviewState = cuePreviewState,
                            onPreviewCue = {
                                val player = cuePlayerResult.getOrNull()
                                if (player == null) {
                                    cuePreviewState = CuePreviewState.FAILED
                                    actionMessage = cuePlayerResult.exceptionOrNull()?.message
                                        ?: "The local cue could not be prepared."
                                } else {
                                    cuePreviewState = CuePreviewState.PLAYING
                                    actionMessage = null
                                    runCatching {
                                        player.play(
                                            onFirstFrame = {
                                                cuePreviewState = CuePreviewState.PLAYING
                                            },
                                            onComplete = {
                                                cuePreviewState = CuePreviewState.PLAYED
                                                cueTestedThisVisit = true
                                                onRefreshPreflight()
                                            },
                                        )
                                    }.onFailure { failure ->
                                        cuePreviewState = CuePreviewState.FAILED
                                        actionMessage = failure.message
                                            ?: "The cue preview could not be played."
                                    }
                                }
                            },
                        )
                    }
                    item { WarningChecksCard(preflight) }
                    recoveryUiState.recoverySummary?.let { summary ->
                        item { InformationCard(title = "Recovery", body = summary) }
                    }
                }
            }

            item {
                NightHistorySection(
                    nights = historyUiState.nights,
                    loading = historyUiState.loading,
                    error = historyUiState.error,
                    warningCount = historyUiState.warningCount,
                    onOpenNight = onOpenNight,
                    onRetry = onReloadLatestResult,
                )
            }

        }
    }
}

@Composable
private fun SettingsScreen(
    nights: List<NightRecord>,
    preselectedNightId: String?,
    cueRefreshKey: Int,
    captureActive: Boolean,
    archiveMutationRunning: Boolean,
    retentionDays: Int,
    retentionMutationRunning: Boolean,
    retentionMessage: String?,
    exportBuildRunning: Boolean,
    transcriptionRuntime: TranscriptionRuntimeSnapshot,
    enrichmentRuntime: EnrichmentRuntimeSnapshot,
    onBack: () -> Unit,
    onUpdateRawAudioRetention: (Int, (String?) -> Unit) -> Unit,
    onCreateExport: (
        Set<String>,
        DreamLogExportFormat,
        (DreamLogExportDocument?, String?) -> Unit,
    ) -> Unit,
    onShareExport: (DreamLogExportDocument, (String?) -> Unit) -> Unit,
    onSaveExport: (DreamLogExportDocument, Uri, (String?) -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val availableNightIds = nights.map { it.night.nightId }
    var selectedNightIds by remember(preselectedNightId, availableNightIds) {
        mutableStateOf(
            if (preselectedNightId == null) {
                availableNightIds.toSet()
            } else {
                setOf(preselectedNightId).filterTo(mutableSetOf()) {
                    it in availableNightIds
                }
            },
        )
    }
    var selectedFormatName by rememberSaveable {
        mutableStateOf(DreamLogExportFormat.JSON.name)
    }
    val selectedFormat = runCatching {
        DreamLogExportFormat.valueOf(selectedFormatName)
    }.getOrDefault(DreamLogExportFormat.JSON)
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var exportActionRunning by remember { mutableStateOf(false) }
    var pendingSaveDocument by remember { mutableStateOf<DreamLogExportDocument?>(null) }
    var pendingRetentionPeriod by remember { mutableStateOf<RetentionPeriod?>(null) }
    var cuePreviewState by remember { mutableStateOf(CuePreviewState.IDLE) }
    val cuePlayerResult = remember(context) {
        runCatching { CuePlayer(context, R.raw.m01_cue) }
    }
    val cueStatus = remember(context, cueRefreshKey) {
        runCatching { CueAudioPreflight.read(context) }.getOrNull()
    }
    val settingsActionsBlocked =
        captureActive ||
            archiveMutationRunning ||
            retentionMutationRunning ||
            transcriptionRuntime.busy ||
            enrichmentRuntime.busy
    val exportActionsBlocked =
        settingsActionsBlocked || exportBuildRunning || exportActionRunning

    DisposableEffect(cuePlayerResult) {
        onDispose { cuePlayerResult.getOrNull()?.close() }
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val document = pendingSaveDocument
        pendingSaveDocument = null
        val destination = result.data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || destination == null) {
            exportActionRunning = false
            actionMessage = "Save canceled; no export file was written."
        } else if (document == null) {
            exportActionRunning = false
            actionMessage = "The prepared export is no longer available."
        } else {
            actionMessage = "Writing the private export to the selected destination…"
            onSaveExport(document, destination) { error ->
                exportActionRunning = false
                actionMessage = error ?: "Export saved to the selected destination."
            }
        }
    }

    fun prepareExport(share: Boolean) {
        if (selectedNightIds.isEmpty()) {
            actionMessage = "Select at least one night to export."
            return
        }
        exportActionRunning = true
        actionMessage = "Preparing a private ${selectedFormat.name} export…"
        onCreateExport(selectedNightIds, selectedFormat) { document, error ->
            if (document == null || error != null) {
                exportActionRunning = false
                actionMessage = error ?: "The export could not be prepared."
                return@onCreateExport
            }
            if (share) {
                actionMessage = "Opening the Android Sharesheet…"
                onShareExport(document) { shareError ->
                    exportActionRunning = false
                    actionMessage = shareError ?: "Export opened in the Android Sharesheet."
                }
            } else {
                pendingSaveDocument = document
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = document.mimeType
                    putExtra(Intent.EXTRA_TITLE, document.fileName)
                }
                runCatching { saveDocumentLauncher.launch(intent) }
                    .onFailure {
                        pendingSaveDocument = null
                        exportActionRunning = false
                        actionMessage = "The system save destination could not be opened."
                    }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp,
                vertical = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Text(
                        text = "Settings",
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            item {
                SettingsCueCard(
                    cueStatus = cueStatus,
                    cuePlayerAvailable = cuePlayerResult.isSuccess && !captureActive,
                    cuePreviewState = cuePreviewState,
                    captureActive = captureActive,
                    onPreviewCue = {
                        val player = cuePlayerResult.getOrNull()
                        if (player == null) {
                            cuePreviewState = CuePreviewState.FAILED
                            actionMessage = "The local cue could not be prepared."
                        } else {
                            cuePreviewState = CuePreviewState.PLAYING
                            actionMessage = null
                            runCatching {
                                player.play(
                                    onFirstFrame = {
                                        cuePreviewState = CuePreviewState.PLAYING
                                    },
                                    onComplete = {
                                        cuePreviewState = CuePreviewState.PLAYED
                                        actionMessage = "Cue played at the current system volume."
                                    },
                                )
                            }.onFailure {
                                cuePreviewState = CuePreviewState.FAILED
                                actionMessage = "The local cue could not be played."
                            }
                        }
                    },
                )
            }

            item {
                SectionCard(title = "Raw audio retention") {
                    Text(
                        "Wake-triggered audio expires from capture completion. Saved text, if " +
                            "any, remains; expired audio cannot be restored.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    RetentionPeriod.SUPPORTED.forEach { period ->
                        if (period.days == retentionDays) {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("${period.displayLabel} · selected")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    actionMessage = null
                                    if (period.days < retentionDays) {
                                        pendingRetentionPeriod = period
                                    } else {
                                        onUpdateRawAudioRetention(period.days) { error ->
                                            actionMessage = error
                                        }
                                    }
                                },
                                enabled = !settingsActionsBlocked,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(period.displayLabel)
                            }
                        }
                    }
                    retentionMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            item {
                LocalTranscriptionCard(
                    state = transcriptionRuntime,
                    captureActive = captureActive || enrichmentRuntime.busy,
                    onInstall = { TranscriptionRuntimeStore.installModel() },
                    onCancelInstall = { TranscriptionRuntimeStore.cancelModelInstall() },
                    onRemove = { TranscriptionRuntimeStore.removeModel() },
                    onRefresh = { TranscriptionRuntimeStore.refreshModelStatus() },
                )
            }

            item {
                LocalEnrichmentCard(
                    state = enrichmentRuntime,
                    captureActive = captureActive,
                    anotherLocalOperationActive = transcriptionRuntime.busy,
                    readyNights = emptyList(),
                    onInstall = { EnrichmentRuntimeStore.installModel() },
                    onCancelInstall = { EnrichmentRuntimeStore.cancelModelInstall() },
                    onRemove = { EnrichmentRuntimeStore.removeModel() },
                    onRefresh = { EnrichmentRuntimeStore.refreshModelStatus() },
                    onEnrichBatch = {},
                    showBatchControls = false,
                )
            }

            item {
                SettingsExportCard(
                    nights = nights,
                    selectedNightIds = selectedNightIds,
                    selectedFormat = selectedFormat,
                    actionsBlocked = exportActionsBlocked,
                    onToggleNight = { nightId ->
                        selectedNightIds = selectedNightIds.toMutableSet().apply {
                            if (!add(nightId)) remove(nightId)
                        }
                    },
                    onSelectAll = { selectedNightIds = availableNightIds.toSet() },
                    onClearSelection = { selectedNightIds = emptySet() },
                    onSelectFormat = { format -> selectedFormatName = format.name },
                    onShare = { prepareExport(share = true) },
                    onSave = { prepareExport(share = false) },
                )
            }

            item {
                SectionCard(title = "About and diagnostics") {
                    val packageInfo = remember(context) {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    }
                    Text(
                        "DreamLog ${packageInfo.versionName ?: "Unknown"} " +
                            "(${packageInfo.longVersionCode})",
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Capture diagnostics remain attached to each night so they can be " +
                            "reviewed without exporting dream content or creating logs.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Idle room audio is never persisted. Only wake-triggered recollections " +
                            "are written to app-private storage; saved content is not uploaded.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            actionMessage?.let { message ->
                item { InformationCard(title = "Settings", body = message) }
            }
        }
    }

    pendingRetentionPeriod?.let { period ->
        AlertDialog(
            onDismissRequest = { pendingRetentionPeriod = null },
            title = { Text("Shorten raw-audio retention?") },
            text = {
                Text(
                    "Raw audio at least ${period.displayLabel} old will be permanently deleted " +
                        "now and during future checks. Saved text, if any, will remain. Audio " +
                        "without saved text can't be recovered, and deleted audio can't be " +
                        "restored.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRetentionPeriod = null
                        actionMessage = null
                        onUpdateRawAudioRetention(period.days) { error ->
                            actionMessage = error
                        }
                    },
                    enabled = !settingsActionsBlocked,
                ) {
                    Text("Shorten retention")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRetentionPeriod = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SettingsCueCard(
    cueStatus: com.wivy.dreamlog.capture.CueAudioStatus?,
    cuePlayerAvailable: Boolean,
    cuePreviewState: CuePreviewState,
    captureActive: Boolean,
    onPreviewCue: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SectionCard(title = "Acknowledgement cue") {
        Text(
            if (cueStatus == null) {
                "Assistant volume status is unavailable."
            } else {
                "${cueStatus.streamName} volume ${cueStatus.volumePercent}% · " +
                    cueStatus.interruptionFilterName
            },
            fontWeight = FontWeight.Medium,
        )
        if (captureActive) {
            Text(
                "Cue testing and volume changes are disabled while night listening is active.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = onPreviewCue,
            enabled = cuePlayerAvailable && cuePreviewState != CuePreviewState.PLAYING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (cuePreviewState) {
                    CuePreviewState.IDLE -> "Test cue"
                    CuePreviewState.PLAYING -> "Playing cue…"
                    CuePreviewState.PLAYED -> "Test cue again"
                    CuePreviewState.FAILED -> "Try cue again"
                },
            )
        }
        OutlinedButton(
            onClick = { context.openSettings(Intent(Settings.Panel.ACTION_VOLUME)) },
            enabled = !captureActive,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Assistant volume")
        }
        OutlinedButton(
            onClick = { context.openSettings(Intent(ACTION_ZEN_MODE_SETTINGS)) },
            enabled = !captureActive,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Modes")
        }
    }
}

@Composable
private fun SettingsExportCard(
    nights: List<NightRecord>,
    selectedNightIds: Set<String>,
    selectedFormat: DreamLogExportFormat,
    actionsBlocked: Boolean,
    onToggleNight: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectFormat: (DreamLogExportFormat) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    SectionCard(title = "Privacy and export") {
        Text(
            "Dream audio and text stay in private local storage. DreamLog never uploads dream " +
                "data, automatic Android cloud backup is off, and exports include text and " +
                "metadata—not raw WAV audio.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (nights.isEmpty()) {
            Text("No retained nights are available to export.")
        } else {
            Text(
                "Nights",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSelectAll,
                    enabled = !actionsBlocked,
                    modifier = Modifier.weight(1f),
                ) { Text("Select all") }
                OutlinedButton(
                    onClick = onClearSelection,
                    enabled = !actionsBlocked,
                    modifier = Modifier.weight(1f),
                ) { Text("Clear") }
            }
            nights.forEach { record ->
                val selected = record.night.nightId in selectedNightIds
                OutlinedButton(
                    onClick = { onToggleNight(record.night.nightId) },
                    enabled = !actionsBlocked,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Text(
                        (if (selected) "Selected · " else "") +
                            HistoryFormatters.date(record.night.displayDate),
                    )
                }
            }
        }

        HorizontalDivider()
        Text(
            "Format",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        DreamLogExportFormat.entries.forEach { format ->
            if (format == selectedFormat) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("${format.name} · selected") }
            } else {
                OutlinedButton(
                    onClick = { onSelectFormat(format) },
                    enabled = !actionsBlocked,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(format.name) }
            }
        }
        Button(
            onClick = onShare,
            enabled = !actionsBlocked && selectedNightIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Share selected nights") }
        OutlinedButton(
            onClick = onSave,
            enabled = !actionsBlocked && selectedNightIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save selected nights") }
        Text(
            "Android chooses the receiving app or save destination. DreamLog requests no broad " +
                "storage or media permission.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LocalTranscriptionCard(
    state: TranscriptionRuntimeSnapshot,
    captureActive: Boolean,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
) {
    SectionCard(title = "Local transcription") {
        when (state.modelPhase) {
            TranscriptionModelPhase.UNINITIALIZED ->
                BulletText("Preparing the private model manager…")

            TranscriptionModelPhase.VERIFYING ->
                BulletText("Verifying the installed model in private storage…")

            TranscriptionModelPhase.VERIFICATION_DEFERRED ->
                BulletText("Model verification will resume after night listening stops.")

            TranscriptionModelPhase.NOT_INSTALLED -> {
                BulletText(
                    "Install the selected ${state.modelSizeMiB} MiB English model before " +
                        "transcription. Installation is an explicit one-time download.",
                )
                state.modelError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = onInstall,
                    enabled = !captureActive && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Install ${state.modelSizeMiB} MiB model")
                }
            }

            TranscriptionModelPhase.INSTALLING,
            TranscriptionModelPhase.CANCELLING,
            -> {
                BulletText(
                    "Downloading ${modelDownloadProgress(state)}" +
                        state.modelCurrentFile?.let { " · $it" }.orEmpty(),
                )
                OutlinedButton(
                    onClick = onCancelInstall,
                    enabled = state.modelPhase == TranscriptionModelPhase.INSTALLING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.modelPhase == TranscriptionModelPhase.CANCELLING) {
                            "Cancelling…"
                        } else {
                            "Cancel download"
                        },
                    )
                }
            }

            TranscriptionModelPhase.INSTALLED -> {
                BulletText(
                    "Ready. After this explicit install, saved audio is transcribed fully " +
                        "offline on this device.",
                )
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !captureActive && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove local model")
                }
            }

            TranscriptionModelPhase.REMOVING ->
                BulletText("Removing the local transcription model…")

            TranscriptionModelPhase.INVALID -> {
                Text(
                    text = state.modelError ?: "The installed model did not pass verification.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onInstall,
                    enabled = !captureActive && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reinstall verified model")
                }
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !captureActive && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove invalid model")
                }
            }

            TranscriptionModelPhase.ERROR -> {
                Text(
                    text = state.modelError ?: "The local model status could not be checked.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !captureActive && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry model check")
                }
            }
        }

        if (state.transcriptionPhase != TranscriptionRuntimePhase.IDLE) {
            HorizontalDivider()
            Text(
                text = if (state.transcriptionPhase == TranscriptionRuntimePhase.RUNNING) {
                    "Transcribing locally"
                } else {
                    "Transcription needs attention"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.transcriptionError
                    ?: "${state.appOpenMessage} ${state.transcriptionCountMessage()}.",
                color = if (state.transcriptionPhase == TranscriptionRuntimePhase.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        BulletText(
            "DreamLog downloads only the four pinned model files. It never sends saved audio " +
                "or transcript text over the network.",
        )
    }
}

@Composable
private fun LocalEnrichmentCard(
    state: EnrichmentRuntimeSnapshot,
    captureActive: Boolean,
    anotherLocalOperationActive: Boolean,
    readyNights: List<NightRecord>,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
    onEnrichBatch: (List<String>) -> Unit,
    showBatchControls: Boolean = true,
) {
    val actionsEnabled = !captureActive && !anotherLocalOperationActive && !state.busy
    val batchCanRunWithoutModel = readyNights.all(
        NightRecord::hasGenuinelyEmptyEnrichmentSource,
    )
    val batchModelReady = state.modelPhase == EnrichmentModelPhase.INSTALLED ||
        batchCanRunWithoutModel
    val currentBatchNight = readyNights.firstOrNull { it.night.nightId == state.nightId }
    val modelSizeLabel = "%.2f".format(state.modelSizeMiB)
    SectionCard(title = "Local dream enrichment") {
        when (state.modelPhase) {
            EnrichmentModelPhase.UNINITIALIZED ->
                BulletText("Preparing the private enrichment model manager…")

            EnrichmentModelPhase.VERIFYING ->
                BulletText("Verifying the installed enrichment model in private storage…")

            EnrichmentModelPhase.VERIFICATION_DEFERRED ->
                BulletText("Model verification will resume after other local work finishes.")

            EnrichmentModelPhase.NOT_INSTALLED -> {
                BulletText(
                    "Install the selected $modelSizeLabel MiB English model to turn a completed night's " +
                        "ordered raw transcript into a faithful reading version. This is an " +
                        "explicit one-time download. Dream text stays on this device; enrichment " +
                        "runs only while DreamLog remains open, and the model is unloaded before " +
                        "night listening.",
                )
                state.modelError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = onInstall,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Install $modelSizeLabel MiB model")
                }
            }

            EnrichmentModelPhase.INSTALLING,
            EnrichmentModelPhase.CANCELLING,
            -> {
                BulletText(
                    "Downloading ${enrichmentDownloadProgress(state)}" +
                        state.modelCurrentFile?.let { " · $it" }.orEmpty(),
                )
                OutlinedButton(
                    onClick = onCancelInstall,
                    enabled = state.modelPhase == EnrichmentModelPhase.INSTALLING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.modelPhase == EnrichmentModelPhase.CANCELLING) {
                            "Cancelling…"
                        } else {
                            "Cancel download"
                        },
                    )
                }
            }

            EnrichmentModelPhase.INSTALLED -> {
                BulletText(
                    "Ready. The selected Qwen3 4B Instruct model runs with LiteRT-LM entirely on this " +
                        "device after installation.",
                )
                BulletText(
                    "Choose Enrich when you are ready. DreamLog reuses one loaded model for the " +
                        "whole pending-night batch, then unloads it before night listening can start.",
                )
                OutlinedButton(
                    onClick = onRemove,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove local enrichment model")
                }
            }

            EnrichmentModelPhase.REMOVING ->
                BulletText("Removing the local enrichment model…")

            EnrichmentModelPhase.INVALID -> {
                Text(
                    text = state.modelError ?: "The installed model did not pass verification.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onInstall,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reinstall verified model")
                }
                OutlinedButton(
                    onClick = onRemove,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove invalid model")
                }
            }

            EnrichmentModelPhase.ERROR -> {
                Text(
                    text = state.modelError ?: "The enrichment model status could not be checked.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry model check")
                }
            }
        }

        if (
            showBatchControls &&
            (state.runtimePhase != EnrichmentRuntimePhase.IDLE || state.batchTotalNightCount > 0)
        ) {
            HorizontalDivider()
            Text(
                text = when (state.runtimePhase) {
                    EnrichmentRuntimePhase.RUNNING -> "Enriching pending nights locally"
                    EnrichmentRuntimePhase.ERROR -> "Enrichment batch needs attention"
                    EnrichmentRuntimePhase.IDLE -> "Last enrichment batch"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = currentBatchNight
                    ?.takeIf { state.runtimePhase == EnrichmentRuntimePhase.RUNNING }
                    ?.let { "Current night: ${formatNightDate(it.night.displayDate)}. " }
                    .orEmpty() + (state.runtimeError
                    ?: state.runtimeMessage
                    ?: "Keep DreamLog open until source validation and saving finish."),
                color = if (state.runtimePhase == EnrichmentRuntimePhase.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (showBatchControls) {
            HorizontalDivider()
            Text(
                text = "Pending-night batch",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (readyNights.isEmpty()) {
                BulletText(
                    "No ended, transcript-ready nights are waiting for enrichment. DreamLog will " +
                        "continue transcribing retained sessions automatically.",
                )
            } else {
                val nightLabel = if (readyNights.size == 1) "night is" else "nights are"
                BulletText(
                    "${readyNights.size} $nightLabel ready. Press once to freeze this list and " +
                        "enrich it in one private, on-device pass. Keep DreamLog open until it " +
                        "finishes.",
                )
                Button(
                    onClick = {
                        onEnrichBatch(readyNights.map { it.night.nightId }.distinct())
                    },
                    enabled = actionsEnabled && batchModelReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (readyNights.size == 1) {
                            "Enrich 1 ready night"
                        } else {
                            "Enrich ${readyNights.size} ready nights"
                        },
                    )
                }
            }
        }

        BulletText(
            "DreamLog downloads only the pinned model artifact. Transcript and dream text are " +
                "never sent over the network, and the raw transcript remains separate.",
        )
    }
}

private fun enrichmentDownloadProgress(state: EnrichmentRuntimeSnapshot): String {
    val completedMiB = state.modelDownloadedBytes.toDouble() / BYTES_PER_MEBIBYTE
    return "%.1f of %.1f MiB".format(
        completedMiB,
        state.modelTotalBytes.toDouble() / BYTES_PER_MEBIBYTE,
    )
}

private fun modelDownloadProgress(state: TranscriptionRuntimeSnapshot): String {
    val completedMiB = state.modelDownloadedBytes.toDouble() / BYTES_PER_MEBIBYTE
    return "%.1f of %.1f MiB".format(
        completedMiB,
        state.modelTotalBytes.toDouble() / BYTES_PER_MEBIBYTE,
    )
}

private fun TranscriptionRuntimeSnapshot.transcriptionCountMessage(): String {
    if (activeSessionId != null && runningSessionCount == 0) {
        return "Re-transcribing one completed session; its existing result stays until " +
            "replacement succeeds"
    }
    return "$completedSessionCount of $eligibleSessionCount retained sessions complete" +
        when {
            failedSessionCount > 0 -> " · $failedSessionCount failed"
            pendingSessionCount > 0 -> " · $pendingSessionCount waiting"
            unavailableSessionCount > 0 -> " · $unavailableSessionCount unavailable"
            else -> ""
        }
}

private fun TranscriptionRuntimeSnapshot.messageForNight(selectedNightId: String?): String? {
    if (selectedNightId == null) return null
    if (
        transcriptionPhase == TranscriptionRuntimePhase.RUNNING &&
        nightId != selectedNightId
    ) {
        return "Another night is being transcribed locally. It may continue with the screen off; " +
            "use the ongoing notification to follow progress."
    }
    if (nightId != selectedNightId) return null
    return transcriptionError ?: if (eligibleSessionCount > 0) {
        "$appOpenMessage ${transcriptionCountMessage()}."
    } else {
        appOpenMessage
    }
}

private fun TranscriptionRuntimeSnapshot.startNightBlockedMessage(): String =
    when (transcriptionPhase) {
        TranscriptionRuntimePhase.RUNNING ->
            "Wait for local transcription to finish before starting another night."

        else -> "Finish or cancel the current local model task before starting a night."
    }

private fun EnrichmentRuntimeSnapshot.startNightBlockedMessage(): String = when {
    runtimePhase == EnrichmentRuntimePhase.RUNNING ->
        "Wait for local dream enrichment to finish before starting another night."

    modelPhase == EnrichmentModelPhase.INSTALLING ||
        modelPhase == EnrichmentModelPhase.CANCELLING ->
        "Finish or cancel the enrichment model download before starting a night."

    else -> "Finish the current enrichment model task before starting a night."
}

internal enum class HomeNextActionKind {
    TRANSCRIBING,
    RESUME_TRANSCRIPTION,
    ENRICH,
    ENRICHING,
}

internal const val HOME_PRIMARY_ACTION_HEIGHT_DP = 152

internal data class HomeMorningAction(
    val kind: HomeNextActionKind,
    val title: String,
    val body: String,
    val buttonLabel: String? = null,
    val nightId: String? = null,
    val sessionId: String? = null,
    val requiresSettings: Boolean = false,
    val enabled: Boolean = true,
    val detail: String? = null,
)

/**
 * Keeps the owner-facing processing sequence in one place while durable history remains the
 * source of truth. A completed or terminally failed pipeline is not an acknowledgement gate: Home
 * returns to Start night, and opening the saved result from History remains optional.
 */
internal fun homeMorningAction(
    latestResult: NightRecord?,
    transcriptionRuntime: TranscriptionRuntimeSnapshot,
    enrichmentRuntime: EnrichmentRuntimeSnapshot,
    readyEnrichmentRecords: List<NightRecord>,
): HomeMorningAction? {
    val record = latestResult
    val runtimeRecord = record?.takeIf { it.night.nightId == transcriptionRuntime.nightId }
    val totalSessionCount = when {
        (transcriptionRuntime.transcriptionPhase == TranscriptionRuntimePhase.RUNNING ||
            transcriptionRuntime.resumeAvailable) &&
            transcriptionRuntime.eligibleSessionCount > 0 ->
            transcriptionRuntime.eligibleSessionCount

        else -> record?.sessions?.size ?: 0
    }
    val completedSessionCount = when {
        (transcriptionRuntime.transcriptionPhase == TranscriptionRuntimePhase.RUNNING ||
            transcriptionRuntime.resumeAvailable) &&
            transcriptionRuntime.eligibleSessionCount > 0 ->
            transcriptionRuntime.completedSessionCount

        else -> record?.transcripts
            ?.asSequence()
            ?.filter { it.transcript.state == ProcessingState.COMPLETE }
            ?.map { it.transcript.sessionId }
            ?.distinct()
            ?.count() ?: 0
    }.coerceAtMost(totalSessionCount)

    if (transcriptionRuntime.transcriptionPhase == TranscriptionRuntimePhase.RUNNING) {
        return HomeMorningAction(
            kind = HomeNextActionKind.TRANSCRIBING,
            title = "Transcribing $completedSessionCount/$totalSessionCount",
            body = "Transcription may continue with the screen off. Use the ongoing notification " +
                "to follow progress.",
            nightId = transcriptionRuntime.nightId ?: record?.night?.nightId,
        )
    }

    if (enrichmentRuntime.runtimePhase == EnrichmentRuntimePhase.RUNNING) {
        return HomeMorningAction(
            kind = HomeNextActionKind.ENRICHING,
            title = "Enriching dreams",
            body = "Keep DreamLog open while generated dreams are prepared locally.",
            nightId = enrichmentRuntime.nightId ?: record?.night?.nightId,
        )
    }

    if (record == null) return null

    val retainedSessionIds = record.sessions
        .asSequence()
        .filter { it.audioState == AudioEvidenceState.RETAINED }
        .mapTo(mutableSetOf()) { it.sessionId }
    val failedSession = record.transcripts.firstOrNull {
        it.transcript.state == ProcessingState.FAILED &&
            it.transcript.sessionId in retainedSessionIds
    }?.transcript
    val runtimeRetrySessionId = transcriptionRuntime
        .takeIf { it.nightId == record.night.nightId }
        ?.retryableSessionIds
        ?.firstOrNull { it in retainedSessionIds }
    val hasDeferredSession = record.hasUnclaimedRetainedTranscriptionSession()
    if (
        failedSession != null ||
        runtimeRetrySessionId != null ||
        hasDeferredSession ||
        (transcriptionRuntime.nightId == record.night.nightId &&
            transcriptionRuntime.resumeAvailable)
    ) {
        val detail = listOfNotNull(
            record.night.transcriptionFailure,
            failedSession?.failureDetail,
            runtimeRecord?.let { transcriptionRuntime.pauseMessage },
            runtimeRecord?.let { transcriptionRuntime.transcriptionError },
        ).distinct().joinToString("\n").takeIf(String::isNotBlank)
        val modelReady = transcriptionRuntime.modelPhase == TranscriptionModelPhase.INSTALLED
        return HomeMorningAction(
            kind = HomeNextActionKind.RESUME_TRANSCRIPTION,
            title =
                "Transcription paused — $completedSessionCount of " +
                    "$totalSessionCount complete",
            body = "Transcription stopped before every retained session finished. Completed " +
                "transcripts and retained source audio were kept.",
            buttonLabel = if (modelReady) "Resume transcription" else "Set up transcription",
            nightId = record.night.nightId,
            sessionId = failedSession?.sessionId ?: runtimeRetrySessionId,
            requiresSettings = !modelReady,
            enabled = !modelReady ||
                (!transcriptionRuntime.busy && !enrichmentRuntime.busy),
            detail = detail,
        )
    }

    if (readyEnrichmentRecords.any { it.night.nightId == record.night.nightId }) {
        val modelReady = enrichmentRuntime.modelPhase == EnrichmentModelPhase.INSTALLED ||
            record.hasGenuinelyEmptyEnrichmentSource()
        return HomeMorningAction(
            kind = HomeNextActionKind.ENRICH,
            title = "Ready to enrich",
            body = if (record.night.enrichmentState == ProcessingState.FAILED) {
                "Dream generation stopped. The completed transcript was kept; choose Enrich to retry."
            } else {
                "Transcription is complete. Generate this night's dream grouping on this device."
            },
            buttonLabel = if (modelReady) "Enrich" else "Set up enrichment",
            nightId = record.night.nightId,
            requiresSettings = !modelReady,
            enabled = !modelReady ||
                (!transcriptionRuntime.busy && !enrichmentRuntime.busy),
            detail = if (record.night.enrichmentState == ProcessingState.FAILED) {
                record.night.enrichmentFailure
            } else {
                null
            },
        )
    }

    return null
}

@Composable
private fun HomePrimaryActionCard(
    runtime: CaptureRuntimeSnapshot,
    morningAction: HomeMorningAction?,
    startEnabled: Boolean,
    setupNeedsAttention: Boolean,
    startBlockedMessage: String?,
    actionMessage: String?,
    onStartNight: () -> Unit,
    onEndNight: () -> Unit,
    onMorningAction: (HomeMorningAction) -> Unit,
) {
    var detailExpanded by remember(morningAction?.kind, morningAction?.nightId) {
        mutableStateOf(false)
    }
    val actionableMorningAction = morningAction?.takeIf { it.buttonLabel != null }
    val active = runtime.active
    val title = homePrimaryStatusTitle(
        runtime = runtime,
        morningAction = morningAction,
        startEnabled = startEnabled,
        setupNeedsAttention = setupNeedsAttention,
    )
    val body = when {
        active && runtime.microphoneSilenced ->
            "Android is silencing the microphone. Stop other recorders and check microphone access."

        active && runtime.phase in setOf(
            CapturePhase.ACKNOWLEDGING,
            CapturePhase.RECORDING,
            CapturePhase.FINALIZING,
        ) -> "Speak naturally. DreamLog is saving this wake-triggered recollection locally."

        active -> "You may lock the phone now. Later, say “DreamLog” or “Hey DreamLog” before narrating."
        morningAction != null -> morningAction.body
        startBlockedMessage != null && setupNeedsAttention ->
            "$startBlockedMessage Use Resolve setup below."

        startBlockedMessage != null -> startBlockedMessage
        else -> "Start while DreamLog is visible, wait for Listening, then lock the phone."
    }
    val errorTone = runtime.microphoneSilenced ||
        (morningAction?.detail != null &&
            morningAction.kind == HomeNextActionKind.RESUME_TRANSCRIPTION) ||
        actionMessage != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = if (errorTone) LiveRegionMode.Assertive else LiveRegionMode.Polite
                stateDescription = title
            },
        colors = CardDefaults.cardColors(
            containerColor = when {
                runtime.microphoneSilenced -> MaterialTheme.colorScheme.errorContainer
                active -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(body, style = MaterialTheme.typography.bodyLarge)
            actionMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when {
                active -> Button(
                    onClick = onEndNight,
                    enabled = runtime.phase != CapturePhase.ENDING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HOME_PRIMARY_ACTION_HEIGHT_DP.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        if (runtime.phase == CapturePhase.ENDING) "Ending…" else "End night",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                actionableMorningAction != null -> Button(
                    onClick = { onMorningAction(actionableMorningAction) },
                    enabled = actionableMorningAction.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HOME_PRIMARY_ACTION_HEIGHT_DP.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        actionableMorningAction.buttonLabel.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                morningAction == null -> Button(
                    onClick = onStartNight,
                    enabled = startEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HOME_PRIMARY_ACTION_HEIGHT_DP.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        "Start night",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            morningAction?.detail?.let { detail ->
                OutlinedButton(
                    onClick = { detailExpanded = !detailExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (detailExpanded) "Hide error details" else "Show error details")
                }
                if (detailExpanded) {
                    Text(
                        text = detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

internal fun homePrimaryStatusTitle(
    runtime: CaptureRuntimeSnapshot,
    morningAction: HomeMorningAction?,
    startEnabled: Boolean,
    setupNeedsAttention: Boolean,
): String = when {
    runtime.active && runtime.phase == CapturePhase.ENDING -> "Ending night"
    runtime.active && runtime.microphoneSilenced -> "Microphone blocked"
    runtime.active && runtime.phase == CapturePhase.STARTING -> "Checking microphone"
    runtime.active && runtime.phase in setOf(
        CapturePhase.ACKNOWLEDGING,
        CapturePhase.RECORDING,
        CapturePhase.FINALIZING,
    ) -> "Recording dream"
    runtime.active -> "Listening for wakewords"
    morningAction != null -> morningAction.title
    setupNeedsAttention -> "Setup required"
    startEnabled -> "Ready to start"
    else -> "Not ready to start"
}

@Composable
private fun NightStatusCard(
    runtime: CaptureRuntimeSnapshot,
    latestResult: NightRecord?,
    actionMessage: String?,
) {
    val presentation = nightStatusPresentation(runtime, latestResult)
    val containerColor = when (presentation.tone) {
        NightStatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        NightStatusTone.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        NightStatusTone.STANDARD -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (presentation.tone) {
        NightStatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        NightStatusTone.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        NightStatusTone.STANDARD -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = if (presentation.tone == NightStatusTone.ERROR) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
                stateDescription = presentation.status
            },
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = presentation.status,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            Text(
                text = presentation.detail,
                style = MaterialTheme.typography.bodyLarge,
                color = if (presentation.tone == NightStatusTone.STANDARD) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    contentColor
                },
            )
            actionMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (presentation.tone == NightStatusTone.ERROR) {
                        contentColor
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

private data class NightStatusPresentation(
    val status: String,
    val detail: String,
    val tone: NightStatusTone,
)

private enum class NightStatusTone {
    STANDARD,
    ACTIVE,
    ERROR,
}

private fun nightStatusPresentation(
    runtime: CaptureRuntimeSnapshot,
    latestResult: NightRecord?,
): NightStatusPresentation =
    when {
        runtime.active && runtime.microphoneSilenced ->
            NightStatusPresentation(
                status = "Not listening",
                detail = "Android is silencing DreamLog's microphone. Stop other recorders and " +
                    "check the system microphone access control.",
                tone = NightStatusTone.ERROR,
            )

        runtime.phase == CapturePhase.STARTING ->
            NightStatusPresentation(
                status = "Starting",
                detail = "Checking the foreground service, microphone input, and a fresh " +
                    "non-silenced frame. Keep DreamLog visible for this step.",
                tone = NightStatusTone.ACTIVE,
            )

        runtime.phase == CapturePhase.LISTENING ->
            NightStatusPresentation(
                status = "Ready to sleep",
                detail = "DreamLog is listening locally. Say “DreamLog” or “Hey DreamLog.” " +
                    "You can lock the phone.",
                tone = NightStatusTone.ACTIVE,
            )

        runtime.phase == CapturePhase.ACKNOWLEDGING ||
            runtime.phase == CapturePhase.RECORDING ||
            runtime.phase == CapturePhase.FINALIZING ->
            NightStatusPresentation(
                status = "Recording",
                detail = "Speak naturally. This recollection ends after the " +
                    "${NarrativeBoundaryDetector.DEFAULT_CONTINUOUS_NON_SPEECH_SECONDS}-second " +
                    "continuous non-speech threshold is reached.",
                tone = NightStatusTone.ACTIVE,
            )

        runtime.phase == CapturePhase.ENDING ->
            NightStatusPresentation(
                status = "Ending",
                detail = "DreamLog is finalizing usable session audio and the night journal.",
                tone = NightStatusTone.STANDARD,
            )

        runtime.phase == CapturePhase.ENDED ->
            NightStatusPresentation(
                status = "Ended",
                detail = "The night ended normally. The latest local result appears below.",
                tone = NightStatusTone.STANDARD,
            )

        runtime.phase == CapturePhase.INTERRUPTED ->
            NightStatusPresentation(
                status = "Interrupted",
                detail = "Monitoring did not finish normally. Usable audio was preserved " +
                    "when possible.",
                tone = NightStatusTone.STANDARD,
            )

        latestResult?.night?.interrupted == true ->
            NightStatusPresentation(
                status = "Interrupted",
                detail = "The latest night did not finish normally. Its preserved local " +
                    "evidence appears below.",
                tone = NightStatusTone.STANDARD,
            )

        latestResult != null ->
            NightStatusPresentation(
                status = "Ended",
                detail = "The latest night ended normally. Its local result appears below.",
                tone = NightStatusTone.STANDARD,
            )

        else ->
            NightStatusPresentation(
                status = "Set up tonight",
                detail = "Complete the required checks, preview the cue, then start while this " +
                    "screen is visible.",
                tone = NightStatusTone.STANDARD,
            )
    }

@Composable
private fun ActiveNightCard(runtime: CaptureRuntimeSnapshot) {
    InformationCard(
        title = "Active night",
        body = buildString {
            append("Night of ")
            append(formatNightDate(runtime.displayDate))
            append(" · Started ")
            append(formatTime(runtime.startedAtEpochMillis))
            append("\n")
            append(runtime.sessionCount)
            append(if (runtime.sessionCount == 1) " session saved" else " sessions saved")
            if (runtime.incompleteSessionCount > 0) {
                append(" · ")
                append(runtime.incompleteSessionCount)
                append(" incomplete")
            }
            if (!runtime.charging) {
                append("\nNot charging; monitoring continues.")
            }
        },
    )
}

@Composable
private fun PermissionAndRequiredChecks(
    preflight: AndroidPreflightSnapshot,
    recoveryUiState: CaptureRecoveryUiState,
    microphoneRequestedThisVisit: Boolean,
    notificationRequestedThisVisit: Boolean,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRetryRecovery: () -> Unit,
    onPreserveUnreadableMarker: () -> Unit,
) {
    SectionCard(title = "Required before starting") {
        when {
            !preflight.microphonePermissionGranted -> {
                CheckRow(
                    title = "Microphone permission",
                    body =
                        "DreamLog needs microphone access only during an active night to " +
                            "hear the two wake phrases and save triggered recollections. " +
                            "Idle room audio is never written to disk.",
                    blocking = true,
                )
                Button(
                    onClick = onRequestMicrophone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (microphoneRequestedThisVisit) {
                            "Allow in app settings"
                        } else {
                            "Allow microphone"
                        },
                    )
                }
            }

            !preflight.notificationPermissionGranted || !preflight.notificationsEnabled -> {
                CheckRow(
                    title = "Night status notification",
                    body =
                        "Android requires a quiet ongoing notification while DreamLog " +
                            "listens with the screen off. It includes an End night action.",
                    blocking = true,
                )
                Button(
                    onClick = onRequestNotifications,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (
                            notificationRequestedThisVisit ||
                            preflight.notificationPermissionGranted
                        ) {
                            "Open notification settings"
                        } else {
                            "Allow notifications"
                        },
                    )
                }
            }

            else -> {
                CheckRow(
                    title = "Permissions ready",
                    body = "Microphone access and the ongoing night notification are available.",
                    blocking = false,
                )
            }
        }

        preflight.evaluation.blockers
            .filterNot {
                it.code == PreflightIssueCode.MICROPHONE_PERMISSION_REQUIRED ||
                    it.code == PreflightIssueCode.NOTIFICATION_PERMISSION_REQUIRED
            }
            .forEach { issue ->
                HorizontalDivider()
                RequiredIssueRow(
                    issue = issue,
                    preflight = preflight,
                    recoveryUiState = recoveryUiState,
                    onRetryRecovery = onRetryRecovery,
                    onPreserveUnreadableMarker = onPreserveUnreadableMarker,
                )
            }
    }
}

@Composable
private fun RequiredIssueRow(
    issue: PreflightIssue,
    preflight: AndroidPreflightSnapshot,
    recoveryUiState: CaptureRecoveryUiState,
    onRetryRecovery: () -> Unit,
    onPreserveUnreadableMarker: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val title: String
    val body: String
    val actionLabel: String?
    val action: (() -> Unit)?

    when (issue.remediation) {
        PreflightRemediationCode.ENABLE_MICROPHONE_ACCESS -> {
            title = "System microphone access is off"
            body =
                "Turn on Microphone access in Android privacy controls, then return here."
            actionLabel = "Open privacy controls"
            action = {
                context.openSettings(
                    Intent(Settings.ACTION_PRIVACY_SETTINGS),
                )
            }
        }

        PreflightRemediationCode.REPAIR_WAKE_MODEL -> {
            title = "Local wake model is unavailable"
            body =
                "${preflight.assetValidation.detail} Reinstall this private DreamLog build " +
                    "before starting a night."
            actionLabel = "Open app info"
            action = context::openAppDetails
        }

        PreflightRemediationCode.FREE_STORAGE -> {
            title = "Protected storage reserve reached"
            body =
                "DreamLog will not start when app-private storage is too low to preserve " +
                    "an interrupted session safely. Free device storage, then return here."
            actionLabel = "Manage storage"
            action = {
                context.openSettings(
                    Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
                )
            }
        }

        PreflightRemediationCode.RESOLVE_PRIOR_CAPTURE -> {
            when {
                recoveryUiState.checking -> {
                    title = "Checking the earlier capture"
                    body = "DreamLog is checking the earlier capture evidence."
                    actionLabel = null
                    action = null
                }

                recoveryUiState.unreadableActiveMarker -> {
                    title = "Earlier capture marker is unreadable"
                    body =
                        "${recoveryUiState.error} Preserve the unreadable marker in " +
                            "app-private recovery storage to continue. Capture audio remains " +
                            "untouched, but DreamLog cannot attach this marker automatically."
                    actionLabel = "Preserve marker and continue"
                    action = onPreserveUnreadableMarker
                }

                else -> {
                    title = "Earlier capture needs recovery"
                    body = recoveryUiState.error
                        ?: "DreamLog must preserve and close the earlier capture journal " +
                            "before a new night can start."
                    actionLabel = if (recoveryUiState.error != null) {
                        "Try recovery again"
                    } else {
                        "Recover previous capture"
                    }
                    action = onRetryRecovery
                }
            }
        }

        PreflightRemediationCode.ADJUST_CUE_VOLUME -> {
            val cue = preflight.cueAudioStatus
            title = "Assistant volume is too low"
            body = if (cue?.streamMuted == true) {
                "${cue.streamName} audio is muted. Unmute it before starting the night."
            } else {
                "${cue?.streamName ?: "Assistant"} volume is " +
                    "${cue?.volumePercent?.let { "$it%" } ?: "too low"}. Raise it above " +
                    "${CueAudioPreflight.LOW_VOLUME_WARNING_PERCENT}% before starting."
            }
            actionLabel = "Adjust Assistant volume"
            action = {
                context.openSettings(Intent(Settings.Panel.ACTION_VOLUME))
            }
        }

        PreflightRemediationCode.ALLOW_CUE_PLAYBACK -> {
            title = "Active Mode is blocking the cue"
            body =
                "Allow Media sounds in the current Android Mode before starting the night."
            actionLabel = "Review Modes"
            action = {
                context.openSettings(Intent(ACTION_ZEN_MODE_SETTINGS))
            }
        }

        PreflightRemediationCode.START_FROM_VISIBLE_ACTIVITY -> {
            title = "Visible start required"
            body = "Return to this screen and use Start night while DreamLog is visible."
            actionLabel = null
            action = null
        }

        PreflightRemediationCode.RETRY_AUDIO_INITIALIZATION -> {
            title = "Microphone input did not initialize"
            body = "Stop other recording apps, check microphone access, and try Start night again."
            actionLabel = "Open privacy controls"
            action = {
                context.openSettings(Intent(Settings.ACTION_PRIVACY_SETTINGS))
            }
        }

        PreflightRemediationCode.RETRY_WITH_OTHER_RECORDERS_STOPPED -> {
            title = "No usable microphone frames arrived"
            body =
                "Fully stop SnoreLab and every other recorder, then try Start night again."
            actionLabel = null
            action = null
        }

        else -> {
            title = "Required check needs attention"
            body = "Resolve this Android capture requirement before starting."
            actionLabel = null
            action = null
        }
    }

    CheckRow(title = title, body = body, blocking = true)
    if (actionLabel != null && action != null) {
        OutlinedButton(
            onClick = action,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun DeferredStartChecksCard() {
    SectionCard(title = "Checked when you start") {
        Text(
            text =
                "Android cannot verify these safely until you tap Start night. DreamLog " +
                    "keeps Starting on screen while it confirms:",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        BulletText("the microphone foreground service started from this visible screen")
        BulletText("the audio input initialized")
        BulletText("a fresh, non-silenced microphone frame arrived")
        Text(
            text =
                "Ready to sleep appears only after all three succeed. A failure stops the " +
                    "start and is reported as an interruption.",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CueCheckCard(
    preflight: AndroidPreflightSnapshot,
    cuePlayerAvailable: Boolean,
    cuePreviewState: CuePreviewState,
    onPreviewCue: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cue = preflight.cueAudioStatus
    SectionCard(title = "Acknowledgement cue") {
        Text(
            text =
                "DreamLog plays this local cue once after a wake phrase. The Assistant " +
                    "volume and current Android Mode must allow an audible cue before Start " +
                    "night is enabled. Use Preview cue to confirm the bedside level.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = if (cue == null) {
                "Assistant volume status is unavailable."
            } else {
                "${cue.streamName} volume ${cue.volumePercent}% · " +
                    "${cue.interruptionFilterName}"
            },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Button(
            onClick = onPreviewCue,
            enabled = cuePlayerAvailable && cuePreviewState != CuePreviewState.PLAYING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (cuePreviewState) {
                    CuePreviewState.IDLE -> "Preview cue"
                    CuePreviewState.PLAYING -> "Playing cue…"
                    CuePreviewState.PLAYED -> "Play cue again"
                    CuePreviewState.FAILED -> "Try cue again"
                },
            )
        }
        OutlinedButton(
            onClick = {
                context.openSettings(Intent(Settings.Panel.ACTION_VOLUME))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Assistant volume")
        }
        OutlinedButton(
            onClick = {
                context.openSettings(Intent(ACTION_ZEN_MODE_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Modes")
        }
    }
}

@Composable
private fun WarningChecksCard(preflight: AndroidPreflightSnapshot) {
    val warnings = preflight.evaluation.warnings
        .filterNot { it.code == PreflightIssueCode.CUE_VOLUME_UNTESTED }
    if (warnings.isEmpty()) return

    SectionCard(title = "Recommendations") {
        warnings.forEachIndexed { index, issue ->
            if (index > 0) HorizontalDivider()
            when (issue.remediation) {
                PreflightRemediationCode.CONNECT_CHARGER ->
                    CheckRow(
                        title = "Connect a charger",
                        body =
                            "Charging is strongly recommended overnight. Brief unplugging " +
                                "does not end an active night.",
                        blocking = false,
                    )

                PreflightRemediationCode.REVIEW_BATTERY_SETTINGS ->
                    CheckRow(
                        title = "Review the prior interruption",
                        body =
                            "The latest night was interrupted. Keep DreamLog allowed to run " +
                                "and review Android battery restrictions before sleeping.",
                        blocking = false,
                    )

                PreflightRemediationCode.STOP_OTHER_RECORDER ->
                    CheckRow(
                        title = "Stop other recorders",
                        body = if (preflight.visibleOtherRecorderCount > 0) {
                            "Android reports ${preflight.visibleOtherRecorderCount} active " +
                                "recording client(s). Fully stop SnoreLab and any other " +
                                "recorder before starting."
                        } else {
                            "Android cannot prove another recorder is stopped. SnoreLab and " +
                                "DreamLog are mutually exclusive on this phone for a night."
                        },
                        blocking = false,
                    )

                else ->
                    CheckRow(
                        title = "Recommended check",
                        body = "Review this recommendation before sleeping.",
                        blocking = false,
                    )
            }
        }
    }
}

@Composable
private fun StartNightCard(
    enabled: Boolean,
    blockedReason: String?,
    onStart: () -> Unit,
) {
    SectionCard(title = "Start tonight") {
        Text(
            text =
                "DreamLog keeps all captured audio on this device. Start here, wait for " +
                    "Ready to sleep, then lock the phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        blockedReason?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = onStart,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Start night")
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun InformationCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CheckRow(
    title: String,
    body: String,
    blocking: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = if (blocking) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        ) {}
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BulletText(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PrivacyFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "On-device capture",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                "Idle room audio is never persisted. Only wake-triggered recollections " +
                    "are written to app-private storage.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
    }
}

private fun startBlockedReason(
    recoveryUiState: CaptureRecoveryUiState,
    preflight: AndroidPreflightSnapshot,
): String? =
    when {
        recoveryUiState.checking -> "Finishing the capture recovery check…"
        recoveryUiState.error != null -> "Recover the earlier capture before starting."
        preflight.evaluation.blockers.isNotEmpty() ->
            if (preflight.evaluation.blockers.size == 1) {
                "1 required setup item needs attention."
            } else {
                "${preflight.evaluation.blockers.size} required setup items need attention."
            }
        else -> null
    }

private fun Context.openNotificationSettings() {
    openSettings(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        },
    )
}

private fun Context.openAppDetails() {
    openSettings(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ),
    )
}

private fun Context.openSettings(intent: Intent) {
    runCatching { startActivity(intent) }
        .onFailure { openAppDetailsFallback() }
}

private fun Context.openAppDetailsFallback() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }
}

private fun Context.shouldShowPermissionRationaleCompat(permission: String): Boolean =
    (this as? ComponentActivity)?.shouldShowRequestPermissionRationale(permission) == true

private fun formatNightDate(value: String?): String {
    return HistoryFormatters.date(value)
}

private fun formatTime(epochMillis: Long?): String {
    if (epochMillis == null) return "Unknown"
    val offset = runCatching {
        java.time.ZoneId.systemDefault()
            .rules
            .getOffset(Instant.ofEpochMilli(epochMillis))
            .totalSeconds
    }.getOrNull()
    return HistoryFormatters.time(epochMillis, offset)
}

private const val ACTION_ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"
private const val BYTES_PER_MEBIBYTE = 1_048_576.0
private const val HOME_ROUTE = "home"
private const val NIGHT_ROUTE = "night"
private const val DREAM_ROUTE = "dream"
private const val SETTINGS_ROUTE = "settings"
