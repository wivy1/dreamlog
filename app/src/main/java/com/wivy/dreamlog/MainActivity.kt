package com.wivy.dreamlog

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
import com.wivy.dreamlog.capture.CueOutputRoute
import com.wivy.dreamlog.capture.NightDateMapper
import com.wivy.dreamlog.capture.NightEndReason
import com.wivy.dreamlog.capture.NightListeningService
import com.wivy.dreamlog.capture.NightStartRequest
import com.wivy.dreamlog.capture.PreflightIssue
import com.wivy.dreamlog.capture.PreflightIssueCode
import com.wivy.dreamlog.capture.PreflightRemediationCode
import com.wivy.dreamlog.capture.PreflightEvaluation
import com.wivy.dreamlog.capture.SessionAudioWriter
import com.wivy.dreamlog.capture.SessionIncompleteReason
import com.wivy.dreamlog.capture.UnreadableActiveJournalException
import com.wivy.dreamlog.enrichment.EnrichmentInterruptionCause
import com.wivy.dreamlog.enrichment.EnrichmentModelPhase
import com.wivy.dreamlog.enrichment.EnrichmentRuntimePhase
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeSnapshot
import com.wivy.dreamlog.enrichment.EnrichmentRuntimeStore
import com.wivy.dreamlog.enrichment.NightTranscriptSegment
import com.wivy.dreamlog.enrichment.OrderedNightTranscript
import com.wivy.dreamlog.enrichment.persistence.persistedEnrichmentFailureIsRetryable
import com.wivy.dreamlog.enrichment.persistence.persistedEnrichmentFailureDisplayDetail
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
import com.wivy.dreamlog.history.NightAudioArtifactInspection
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
import com.wivy.dreamlog.transcription.transcriptionFailureDisplayText
import com.wivy.dreamlog.ui.history.DreamDetailScreen
import com.wivy.dreamlog.ui.history.NightDetailScreen
import com.wivy.dreamlog.ui.history.NightHistorySection
import com.wivy.dreamlog.ui.theme.DreamLogTheme
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

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

        setContent {
            DreamLogTheme {
                DreamLogApp(
                    preflightRefreshKey = preflightRefreshKey,
                    recoveryUiState = recoveryUiState,
                    historyUiState = historyUiState,
                    onInspectPriorCapture = ::inspectPriorCapture,
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
                    onMarkCaptureIssueReviewed = ::markCaptureIssueReviewed,
                    onShowCaptureIssueAgain = ::showCaptureIssueAgain,
                    onInspectNightAudio = ::inspectNightAudio,
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

    override fun onStop() {
        if (
            !isChangingConfigurations &&
            EnrichmentRuntimeStore.snapshots.value.runtimePhase ==
            EnrichmentRuntimePhase.RUNNING
        ) {
            val powerManager = getSystemService(PowerManager::class.java)
            val keyguardManager = getSystemService(KeyguardManager::class.java)
            EnrichmentRuntimeStore.requestForegroundInterruption(
                enrichmentForegroundLossCause(
                    screenInteractive = powerManager?.isInteractive ?: true,
                    keyguardLocked = keyguardManager?.isKeyguardLocked ?: false,
                ),
            )
        }
        super.onStop()
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
                                        "Interrupted night recovered."
                                    } else {
                                        "Night recovered: ${recovered.endRecord.sessionCount} recordings kept."
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
                                        "Night recovery complete."
                                    } else {
                                        "Interrupted night recovered. Incomplete recordings kept."
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
                                    "${preserved.preservedCount} recovery files preserved. Recordings kept.",
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
        if (recoveryRunning || recoveryUiState.checking) {
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

    private fun markCaptureIssueReviewed(
        nightId: String,
        onComplete: (String?) -> Unit,
    ) = runArchiveMutation("DreamLog-mark-capture-issue-reviewed", onComplete) {
        check(nightRepository.markCaptureIssueReviewed(nightId)) {
            "The capture issue is no longer present or was already reviewed."
        }
    }

    private fun showCaptureIssueAgain(
        nightId: String,
        onComplete: (String?) -> Unit,
    ) = runArchiveMutation("DreamLog-show-capture-issue-again", onComplete) {
        check(nightRepository.showCaptureIssueAgain(nightId)) {
            "The capture issue is already shown or the night is no longer present."
        }
    }

    private fun inspectNightAudio(
        nightId: String,
        onComplete: (NightAudioArtifactInspection?, String?) -> Unit,
    ) {
        if (archiveMutationRunning) {
            onComplete(null, "Another archive action is still finishing.")
            return
        }
        if (
            !CaptureTranscriptionOperationGate.tryClaimLocalOperation {
                CaptureRuntimeStore.snapshots.value.active
            }
        ) {
            onComplete(
                null,
                "End the night and wait for processing before checking audio.",
            )
            return
        }
        archiveMutationRunning = true
        val launchFailure = runCatching {
            Thread(
                {
                    val result = try {
                        runCatching { nightRepository.inspectEndedNightAudio(nightId) }
                    } finally {
                        CaptureTranscriptionOperationGate.releaseLocalOperation()
                    }
                    runOnUiThread {
                        archiveMutationRunning = false
                        result.fold(
                            onSuccess = { inspection -> onComplete(inspection, null) },
                            onFailure = { failure ->
                                onComplete(
                                    null,
                                    failure.message
                                        ?: "The saved-audio check could not be completed.",
                                )
                            },
                        )
                    }
                },
                "DreamLog-inspect-night-audio",
            ).start()
        }.exceptionOrNull()
        if (launchFailure != null) {
            CaptureTranscriptionOperationGate.releaseLocalOperation()
            archiveMutationRunning = false
            onComplete(
                null,
                launchFailure.message ?: "The saved-audio check could not be started.",
            )
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
                "End the night and wait for processing before changing retention.",
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
                                        "Retention updated. Some audio could not be deleted; will retry."

                                    retention.deferredNightIds.isNotEmpty() ->
                                        "Retention updated. Audio in use will be checked later."

                                    retention.expiredNightIds.size == 1 ->
                                        "Recordings deleted for 1 night. Saved text kept."

                                    retention.expiredNightIds.size > 1 ->
                                        "Recordings deleted for ${retention.expiredNightIds.size} nights. Saved text kept."

                                    else ->
                                        "Retention updated. No recordings expired."
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
                                    "Export failed. Check the selected nights and try again.",
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
                "End the night and wait for processing before editing the archive.",
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
            onComplete("Wait for processing to finish.")
            return
        }
        if (startPersistenceRunning) {
            onComplete("Preparing the night…")
            return
        }
        if (
            !CaptureTranscriptionOperationGate.tryReserveCaptureStart {
                CaptureRuntimeStore.snapshots.value.active
            }
        ) {
            onComplete("Wait for processing to finish.")
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
            "Reprocessing interrupted. Saved work kept; try again."
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

internal fun shouldVerifyLocalModel(
    historyLoaded: Boolean,
    captureActive: Boolean,
    modelControlsVisible: Boolean,
    hasPendingWork: Boolean,
): Boolean = !captureActive && (modelControlsVisible || (historyLoaded && hasPendingWork))

internal fun shouldKeepScreenOnForLocalProcessing(
    enrichmentPhase: EnrichmentRuntimePhase,
): Boolean = enrichmentPhase == EnrichmentRuntimePhase.RUNNING

internal fun enrichmentForegroundLossCause(
    screenInteractive: Boolean,
    keyguardLocked: Boolean,
): EnrichmentInterruptionCause = if (!screenInteractive || keyguardLocked) {
    EnrichmentInterruptionCause.SCREEN_OFF_OR_LOCKED
} else {
    EnrichmentInterruptionCause.APP_HIDDEN
}

internal fun canStartNightInstead(
    morningAction: HomeMorningAction?,
    startEnabled: Boolean,
): Boolean = morningAction?.kind in setOf(
    HomeNextActionKind.ENRICH,
    HomeNextActionKind.RESUME_TRANSCRIPTION,
) && startEnabled

internal fun automaticTranscriptionNightId(
    nights: List<NightRecord>,
    captureRuntime: CaptureRuntimeSnapshot,
    processingNightId: String?,
): String? {
    if (captureRuntime.phase !in setOf(CapturePhase.ENDED, CapturePhase.INTERRUPTED)) return null
    return nights.firstOrNull { record ->
        record.night.nightId == captureRuntime.nightId &&
            record.night.captureState in setOf(NightCaptureState.ENDED, NightCaptureState.INTERRUPTED) &&
            record.hasUnclaimedRetainedTranscriptionSession() &&
            record.night.nightId != processingNightId
    }?.night?.nightId
}

internal fun pendingTranscriptionNight(nights: List<NightRecord>): NightRecord? =
    nights.firstOrNull { record ->
        record.night.captureState in setOf(NightCaptureState.ENDED, NightCaptureState.INTERRUPTED) &&
            (record.hasUnclaimedRetainedTranscriptionSession() || record.transcripts.any { transcript ->
                transcript.transcript.state == ProcessingState.FAILED && record.sessions.any {
                    it.sessionId == transcript.transcript.sessionId &&
                        it.audioState == AudioEvidenceState.RETAINED
                }
            })
    }

private fun enrichmentInterruptionMessage(
    cause: EnrichmentInterruptionCause,
): String = when (cause) {
    EnrichmentInterruptionCause.APP_HIDDEN ->
        "Stopping enrichment after you left. Saved work kept."

    EnrichmentInterruptionCause.SCREEN_OFF_OR_LOCKED ->
        "Stopping enrichment: screen off or phone locked. Saved work kept."

    EnrichmentInterruptionCause.USER_CANCELLED ->
        "Stopping enrichment. Saved work kept."
}

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
        "Wait for transcription to finish."

    enrichmentRuntimePhase == EnrichmentRuntimePhase.RUNNING ->
        "Wait for enrichment to finish."

    transcriptionModelPhase == TranscriptionModelPhase.VERIFYING ->
        "Checking the speech model…"

    transcriptionModelPhase in setOf(
        TranscriptionModelPhase.INSTALLING,
        TranscriptionModelPhase.CANCELLING,
        TranscriptionModelPhase.REMOVING,
    ) -> "Wait for the transcription model operation to finish."

    requiresTranscriptionModel && transcriptionModelPhase in setOf(
        TranscriptionModelPhase.UNINITIALIZED,
        TranscriptionModelPhase.VERIFICATION_DEFERRED,
    ) -> "Checking the speech model…"

    requiresTranscriptionModel &&
        transcriptionModelPhase == TranscriptionModelPhase.NOT_INSTALLED ->
        "Install the speech model in Settings."

    requiresTranscriptionModel && transcriptionModelPhase in setOf(
        TranscriptionModelPhase.INVALID,
        TranscriptionModelPhase.ERROR,
    ) -> "Repair the speech model in Settings."

    enrichmentModelPhase in setOf(
        EnrichmentModelPhase.UNINITIALIZED,
        EnrichmentModelPhase.VERIFYING,
        EnrichmentModelPhase.VERIFICATION_DEFERRED,
    ) -> "Checking the enrichment model…"

    enrichmentModelPhase in setOf(
        EnrichmentModelPhase.INSTALLING,
        EnrichmentModelPhase.CANCELLING,
        EnrichmentModelPhase.REMOVING,
    ) -> "Wait for the dream-grouping model operation to finish."

    enrichmentModelPhase == EnrichmentModelPhase.NOT_INSTALLED ->
        "Install the enrichment model in Settings."

    enrichmentModelPhase in setOf(
        EnrichmentModelPhase.INVALID,
        EnrichmentModelPhase.ERROR,
    ) -> "Repair the enrichment model in Settings."

    else -> null
}

@Composable
private fun DreamLogApp(
    preflightRefreshKey: Int,
    recoveryUiState: CaptureRecoveryUiState,
    historyUiState: PersistentHistoryUiState,
    onInspectPriorCapture: () -> Unit,
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
    onMarkCaptureIssueReviewed: (String, (String?) -> Unit) -> Unit,
    onShowCaptureIssueAgain: (String, (String?) -> Unit) -> Unit,
    onInspectNightAudio: (
        String,
        (NightAudioArtifactInspection?, String?) -> Unit,
    ) -> Unit,
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
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val runtime by CaptureRuntimeStore.snapshots.collectAsState()
    val transcriptionRuntime by TranscriptionRuntimeStore.snapshots.collectAsState()
    val enrichmentRuntime by EnrichmentRuntimeStore.snapshots.collectAsState()
    LaunchedEffect(Unit) {
        combine(TranscriptionRuntimeStore.snapshots, EnrichmentRuntimeStore.snapshots) { transcription, enrichment ->
            transcription.modelPhase != TranscriptionModelPhase.UNINITIALIZED &&
                enrichment.modelPhase != EnrichmentModelPhase.UNINITIALIZED
        }.first { recoveryFinished -> recoveryFinished }
        onInspectPriorCapture()
    }
    val rootView = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onRefreshPreflight()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onRefreshPreflight()
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    var showLeaveEnrichmentConfirmation by rememberSaveable { mutableStateOf(false) }
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

    BackHandler(
        enabled = currentBackStackEntry?.destination?.route == HOME_ROUTE &&
            enrichmentRuntime.runtimePhase == EnrichmentRuntimePhase.RUNNING,
    ) {
        showLeaveEnrichmentConfirmation = true
    }
    if (showLeaveEnrichmentConfirmation) {
        AlertDialog(
            onDismissRequest = { showLeaveEnrichmentConfirmation = false },
            title = { Text("Stop local enrichment?") },
            text = {
                Text(
                    "Leaving stops enrichment. You can resume later.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveEnrichmentConfirmation = false
                        EnrichmentRuntimeStore.requestForegroundInterruption(
                            EnrichmentInterruptionCause.USER_CANCELLED,
                        )
                        (context as? ComponentActivity)?.finish()
                    },
                ) {
                    Text("Leave and stop")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveEnrichmentConfirmation = false }) {
                    Text("Keep working")
                }
            },
        )
    }
    LaunchedEffect(enrichmentRuntime.runtimePhase) {
        if (enrichmentRuntime.runtimePhase != EnrichmentRuntimePhase.RUNNING) {
            showLeaveEnrichmentConfirmation = false
        }
    }
    val automaticTranscriptionNightId = remember(historyUiState.nights, runtime, transcriptionRuntime.nightId) {
        automaticTranscriptionNightId(historyUiState.nights, runtime, transcriptionRuntime.nightId)
    }
    val readyEnrichmentRecords = remember(historyUiState.nights) {
        historyUiState.nights.filter { record ->
            !record.hasProtectedDreamChanges &&
                !record.hasGenuinelyEmptyEnrichmentSource() &&
                record.isReadyForManualEnrichmentBatch()
        }
    }
    val modelControlsVisible = currentBackStackEntry?.destination?.route?.let { it != HOME_ROUTE } == true
    val pendingTranscription = remember(historyUiState.nights) {
        historyUiState.nights.any { record ->
            record.night.transcriptionState != ProcessingState.COMPLETE &&
                record.sessions.any { it.audioState == AudioEvidenceState.RETAINED }
        }
    }
    val verifyTranscription = shouldVerifyLocalModel(
        historyLoaded = !historyUiState.loading,
        captureActive = runtime.active,
        modelControlsVisible = modelControlsVisible,
        hasPendingWork = pendingTranscription,
    )
    val verifyEnrichment = shouldVerifyLocalModel(
        historyLoaded = !historyUiState.loading,
        captureActive = runtime.active,
        modelControlsVisible = modelControlsVisible,
        hasPendingWork = readyEnrichmentRecords.isNotEmpty(),
    )

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
                    ?: "Transcription failed. Previous text and audio kept."
                reprocessPhaseName =
                    NightReprocessPhase.ENRICHING_PRESERVED_TRANSCRIPT.name
                reprocessMessage =
                    "$failureMessage Enriching the saved transcript…"
                if (!EnrichmentRuntimeStore.processNight(selectedNightId)) {
                    reprocessPhaseName = NightReprocessPhase.IDLE.name
                    reprocessMessage =
                        "$failureMessage Enrichment could not start. Try again when processing finishes."
                }
            }
            TranscriptionRuntimePhase.IDLE -> {
                reprocessPhaseName = NightReprocessPhase.ENRICHING.name
                reprocessMessage =
                    "Transcription complete. Enriching…"
                if (!EnrichmentRuntimeStore.processNight(selectedNightId)) {
                    reprocessPhaseName = NightReprocessPhase.IDLE.name
                    reprocessMessage =
                        "Transcript saved. Enrichment could not start; try again."
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
                    ?: "Enrichment failed. Previous dreams kept; try again."
            }
            EnrichmentRuntimePhase.IDLE -> {
                val usedPreservedTranscript =
                    reprocessPhase == NightReprocessPhase.ENRICHING_PRESERVED_TRANSCRIPT
                reprocessPhaseName = NightReprocessPhase.IDLE.name
                reprocessMessage = if (usedPreservedTranscript) {
                    "Enrichment complete using the previous transcript."
                } else {
                    "Reprocessing complete."
                }
            }
        }
    }

    LaunchedEffect(verifyTranscription, transcriptionRuntime.modelPhase, enrichmentRuntime.busy) {
        if (
            verifyTranscription && !enrichmentRuntime.busy &&
            transcriptionRuntime.modelPhase ==
            TranscriptionModelPhase.VERIFICATION_DEFERRED
        ) {
            TranscriptionRuntimeStore.refreshModelStatus()
        }
    }

    LaunchedEffect(
        verifyEnrichment,
        verifyTranscription,
        transcriptionRuntime.busy,
        transcriptionRuntime.modelPhase,
        enrichmentRuntime.modelPhase,
    ) {
        if (
            verifyEnrichment &&
            !transcriptionRuntime.busy &&
            !(verifyTranscription && transcriptionRuntime.modelPhase == TranscriptionModelPhase.VERIFICATION_DEFERRED) &&
            enrichmentRuntime.modelPhase == EnrichmentModelPhase.VERIFICATION_DEFERRED
        ) {
            EnrichmentRuntimeStore.refreshModelStatus()
        }
    }

    // Transcribe the night just ended in this process. Opening old unfinished history must leave
    // the owner free to start tonight; those recordings remain available for an explicit resume.
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
                            "Transcript is current. Enriching…"
                        reprocessPhaseName = NightReprocessPhase.ENRICHING.name
                        if (!EnrichmentRuntimeStore.processNight(selectedNightId)) {
                            reprocessPhaseName = NightReprocessPhase.IDLE.name
                            reprocessMessage =
                                "Enrichment could not start. Wait for processing, then check the model in Settings."
                        }
                    } else {
                        reprocessMessage = "Transcribing saved recordings…"
                        reprocessPhaseName = NightReprocessPhase.TRANSCRIBING.name
                        if (!TranscriptionRuntimeStore.retranscribeNight(selectedNightId)) {
                            reprocessPhaseName = NightReprocessPhase.IDLE.name
                            reprocessMessage =
                                "Reprocessing could not start. Wait for processing, then check both models in Settings."
                        }
                    }
                },
                onMarkCaptureIssueReviewed = onMarkCaptureIssueReviewed,
                onShowCaptureIssueAgain = onShowCaptureIssueAgain,
                onInspectNightAudio = onInspectNightAudio,
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

    val startupChecking = recoveryUiState.checking ||
        transcriptionRuntime.modelPhase == TranscriptionModelPhase.UNINITIALIZED ||
        enrichmentRuntime.modelPhase == EnrichmentModelPhase.UNINITIALIZED
    val startEnabled =
        !startupChecking &&
        preflight.evaluation.canStart &&
            recoveryUiState.resolved &&
            cuePreviewState != CuePreviewState.PLAYING &&
            !startPersistencePending &&
            !runtime.active &&
            !transcriptionRuntime.busy &&
            !enrichmentRuntime.busy
    val setupNeedsAttention =
        !startupChecking && (!preflight.evaluation.canStart || !recoveryUiState.resolved)
    LaunchedEffect(setupNeedsAttention) {
        if (!setupNeedsAttention) setupDetailsExpanded = false
    }
    val startBlockedMessage = when {
        transcriptionRuntime.busy -> transcriptionRuntime.startNightBlockedMessage()
        enrichmentRuntime.busy -> enrichmentRuntime.startNightBlockedMessage()
        cuePreviewState == CuePreviewState.PLAYING ->
            "Wait for the alert to finish."

        startPersistencePending -> "Starting night…"
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
            actionMessage = "Starting night…"
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
        latestResult = pendingTranscriptionNight(historyUiState.nights) ?: latestResult,
        transcriptionRuntime = transcriptionRuntime,
        enrichmentRuntime = enrichmentRuntime,
        readyEnrichmentRecords = readyEnrichmentRecords,
    )
    val primaryActionContent: @Composable () -> Unit = {
        HomePrimaryActionCard(
            runtime = runtime,
            morningAction = morningAction,
            startEnabled = startEnabled,
            setupNeedsAttention = setupNeedsAttention,
            startBlockedMessage = startBlockedMessage,
            actionMessage = actionMessage,
            startupChecking = startupChecking,
            cueOutputRoute = preflight.cueAudioStatus?.outputRoute ?: CueOutputRoute.UNKNOWN,
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
    val showSetupSection =
        !runtime.active && !startupChecking &&
            (
                morningAction == null ||
                    (canStartNightInstead(morningAction, startEnabled = true) &&
                        !startEnabled &&
                        setupNeedsAttention)
                )
    val setupToggleContent: @Composable () -> Unit = {
        if (setupNeedsAttention) {
            Button(
                onClick = { setupDetailsExpanded = !setupDetailsExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (setupDetailsExpanded) "Hide setup" else "Resolve setup")
            }
        } else {
            TextButton(
                onClick = { setupDetailsExpanded = !setupDetailsExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (setupDetailsExpanded) {
                        "Hide checks"
                    } else {
                        "Night checks"
                    },
                )
            }
        }
    }
    val cueCheckContent: @Composable () -> Unit = {
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
    val expandedSetupContent: @Composable ColumnScope.() -> Unit = {
        requiredChecksContent()
        DeferredStartChecksCard()
        cueCheckContent()
        WarningChecksCard(preflight)
        recoveryUiState.recoverySummary?.let { summary ->
            InformationCard(title = "Recovery", body = summary)
        }
    }
    val homeControls: @Composable ColumnScope.() -> Unit = {
        primaryActionContent()
        if (showSetupSection) {
            setupToggleContent()
            if (setupDetailsExpanded) expandedSetupContent()
        }
    }
    val historyContent: @Composable () -> Unit = {
        NightHistorySection(
            nights = historyUiState.nights,
            loading = historyUiState.loading,
            error = historyUiState.error,
            warningCount = historyUiState.warningCount,
            onOpenNight = onOpenNight,
            onRetry = onReloadLatestResult,
        )
    }
    val layoutMode = homeLayoutMode(
        isLandscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE,
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
                vertical = 16.dp,
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
                    )
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                }
            }

            when (layoutMode) {
                HomeLayoutMode.SINGLE_COLUMN -> {
                    item { primaryActionContent() }
                    if (showSetupSection) {
                        item { setupToggleContent() }
                        if (setupDetailsExpanded) {
                            item { requiredChecksContent() }
                            item { DeferredStartChecksCard() }
                            item { cueCheckContent() }
                            item { WarningChecksCard(preflight) }
                            recoveryUiState.recoverySummary?.let { summary ->
                                item { InformationCard(title = "Recovery", body = summary) }
                            }
                        }
                    }
                    item { historyContent() }
                }

                HomeLayoutMode.TWO_COLUMN -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                content = homeControls,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                historyContent()
                            }
                        }
                    }
                }
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
            actionMessage = "Save canceled."
        } else if (document == null) {
            exportActionRunning = false
            actionMessage = "The prepared export is no longer available."
        } else {
            actionMessage = "Saving export…"
            onSaveExport(document, destination) { error ->
                exportActionRunning = false
                actionMessage = error ?: "Export saved."
            }
        }
    }

    fun prepareExport(share: Boolean) {
        if (selectedNightIds.isEmpty()) {
            actionMessage = "Select at least one night to export."
            return
        }
        exportActionRunning = true
        actionMessage = "Preparing ${selectedFormat.name} export…"
        onCreateExport(selectedNightIds, selectedFormat) { document, error ->
            if (document == null || error != null) {
                exportActionRunning = false
                actionMessage = error ?: "The export could not be prepared."
                return@onCreateExport
            }
            if (share) {
                actionMessage = "Opening sharing…"
                onShareExport(document) { shareError ->
                    exportActionRunning = false
                    actionMessage = shareError
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
                vertical = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Text(
                        text = "Settings",
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
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
                                        actionMessage = "Alert played."
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
                SectionCard(title = "Keep recordings") {
                    Text(
                        "Recordings are deleted after this period. Saved text stays.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        RetentionPeriod.SUPPORTED.forEachIndexed { index, period ->
                            SegmentedButton(
                                selected = period.days == retentionDays,
                                shape = SegmentedButtonDefaults.itemShape(index, RetentionPeriod.SUPPORTED.size),
                                onClick = {
                                    actionMessage = null
                                    if (period.days == retentionDays) {
                                        Unit
                                    } else if (period.days < retentionDays) {
                                        pendingRetentionPeriod = period
                                    } else {
                                        onUpdateRawAudioRetention(period.days) { error ->
                                            actionMessage = error
                                        }
                                    }
                                },
                                enabled = !settingsActionsBlocked,
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
                    onInstall = { EnrichmentRuntimeStore.installModel() },
                    onCancelInstall = { EnrichmentRuntimeStore.cancelModelInstall() },
                    onRemove = { EnrichmentRuntimeStore.removeModel() },
                    onRefresh = { EnrichmentRuntimeStore.refreshModelStatus() },
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
                SectionCard(title = "About") {
                    val packageInfo = remember(context) {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    }
                    Text(
                        "DreamLog ${packageInfo.versionName ?: "Unknown"} " +
                            "(${packageInfo.longVersionCode})",
                    )
                    Text(
                        "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Capture details are available in each night.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Only wake-triggered recordings are saved. Audio and text stay on this phone.",
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
                    "Recordings at least ${period.displayLabel} old will be permanently deleted, " +
                        "including any not yet transcribed. Saved text stays.",
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
    SectionCard(title = "Wake alert") {
        cueOutputWarning(cueStatus?.outputRoute ?: CueOutputRoute.UNKNOWN)?.let { warning ->
            Text(warning, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            if (cueStatus == null) {
                "Assistant volume status is unavailable."
            } else {
                "${cueStatus.streamName} volume ${cueStatus.volumePercent}% · " +
                    cueStatus.interruptionFilterName
            },
        )
        if (captureActive) {
            Text(
                "End the night to test the alert or change its volume.",
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
                    CuePreviewState.IDLE -> "Test alert"
                    CuePreviewState.PLAYING -> "Playing…"
                    CuePreviewState.PLAYED -> "Play again"
                    CuePreviewState.FAILED -> "Try again"
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
    SectionCard(title = "Export") {
        Text(
            "Share or save text and source details. Recordings are not included.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (nights.isEmpty()) {
            Text("No retained nights are available to export.")
        } else {
            Text(
                "Nights",
                style = MaterialTheme.typography.titleMedium,
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
                SupportingText("Preparing…")

            TranscriptionModelPhase.VERIFYING ->
                SupportingText("Checking model…")

            TranscriptionModelPhase.VERIFICATION_DEFERRED ->
                SupportingText("Model check pending.")

            TranscriptionModelPhase.NOT_INSTALLED -> {
                SupportingText(
                    "Download the English speech model once to transcribe offline.",
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
                SupportingText(
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
                SupportingText(
                    "Ready for offline transcription.",
                )
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !captureActive && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove model")
                }
            }

            TranscriptionModelPhase.REMOVING ->
                SupportingText("Removing model…")

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

    }
}

@Composable
private fun LocalEnrichmentCard(
    state: EnrichmentRuntimeSnapshot,
    captureActive: Boolean,
    anotherLocalOperationActive: Boolean,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
) {
    val actionsEnabled = !captureActive && !anotherLocalOperationActive && !state.busy
    val modelSizeLabel = "%.2f".format(state.modelSizeMiB)
    SectionCard(title = "Local enrichment") {
        when (state.modelPhase) {
            EnrichmentModelPhase.UNINITIALIZED ->
                SupportingText("Preparing…")

            EnrichmentModelPhase.VERIFYING ->
                SupportingText("Checking model…")

            EnrichmentModelPhase.VERIFICATION_DEFERRED ->
                SupportingText("Model check pending.")

            EnrichmentModelPhase.NOT_INSTALLED -> {
                SupportingText(
                    "Organize transcripts into dreams offline. Keep DreamLog open while enriching.",
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
                SupportingText(
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
                SupportingText(
                    "Ready. Keep DreamLog open while enriching.",
                )
                OutlinedButton(
                    onClick = onRemove,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove model")
                }
            }

            EnrichmentModelPhase.REMOVING ->
                SupportingText("Removing model…")

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
        return "Transcribing again; previous result kept until complete"
    }
    return "$completedSessionCount of $eligibleSessionCount recordings complete" +
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
        return "Transcribing another night. Check the notification for progress."
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
            "Wait for transcription to finish."

        else -> "Finish or cancel the model task first."
    }

private fun EnrichmentRuntimeSnapshot.startNightBlockedMessage(): String = when {
    runtimePhase == EnrichmentRuntimePhase.RUNNING ->
        "Wait for enrichment to finish."

    modelPhase == EnrichmentModelPhase.INSTALLING ||
        modelPhase == EnrichmentModelPhase.CANCELLING ->
        "Finish or cancel the model download first."

    else -> "Wait for the model task to finish."
}

internal enum class HomeNextActionKind {
    TRANSCRIBING,
    RESUME_TRANSCRIPTION,
    ENRICH,
    ENRICHING,
}

internal enum class HomeLayoutMode {
    SINGLE_COLUMN,
    TWO_COLUMN,
}

internal fun homeLayoutMode(isLandscape: Boolean): HomeLayoutMode =
    if (isLandscape) HomeLayoutMode.TWO_COLUMN else HomeLayoutMode.SINGLE_COLUMN

internal const val HOME_PRIMARY_ACTION_HEIGHT_DP = 64

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
            (runtimeRecord != null && transcriptionRuntime.resumeAvailable)) &&
            transcriptionRuntime.eligibleSessionCount > 0 ->
            transcriptionRuntime.eligibleSessionCount

        else -> record?.sessions?.size ?: 0
    }
    val completedSessionCount = when {
        (transcriptionRuntime.transcriptionPhase == TranscriptionRuntimePhase.RUNNING ||
            (runtimeRecord != null && transcriptionRuntime.resumeAvailable)) &&
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
            body = "You can lock the phone. Progress appears in the notification.",
            nightId = transcriptionRuntime.nightId ?: record?.night?.nightId,
        )
    }

    if (enrichmentRuntime.runtimePhase == EnrichmentRuntimePhase.RUNNING) {
        return HomeMorningAction(
            kind = HomeNextActionKind.ENRICHING,
            title = if (enrichmentRuntime.interruptionCause == null) {
                "Enriching dreams"
            } else {
                "Stopping enrichment"
            },
            body = enrichmentRuntime.interruptionCause?.let(::enrichmentInterruptionMessage)
                ?: "Keep DreamLog open. The screen stays awake.",
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
        val checkingModel = transcriptionRuntime.modelPhase in setOf(
            TranscriptionModelPhase.UNINITIALIZED,
            TranscriptionModelPhase.VERIFICATION_DEFERRED,
            TranscriptionModelPhase.VERIFYING,
        )
        val neverStarted = record.transcripts.isEmpty() &&
            record.night.transcriptionState == ProcessingState.NOT_STARTED &&
            runtimeRecord?.let { transcriptionRuntime.pauseReason } == null
        val explanation = transcriptionFailureDisplayText(failedSession?.failureDetail)
            ?: runtimeRecord?.let { transcriptionRuntime.pauseMessage }
            ?: transcriptionFailureDisplayText(record.night.transcriptionFailure)
        return HomeMorningAction(
            kind = HomeNextActionKind.RESUME_TRANSCRIPTION,
            title = if (neverStarted) "Ready to transcribe" else "Transcription paused",
            body = listOfNotNull(
                explanation,
                "$completedSessionCount of $totalSessionCount recordings complete.",
                "Audio is saved.".takeIf { explanation == null },
            ).joinToString(" "),
            buttonLabel = when {
                checkingModel -> "Checking model…"
                modelReady -> if (neverStarted) "Transcribe" else "Resume transcription"
                else -> "Set up transcription"
            },
            nightId = record.night.nightId,
            sessionId = failedSession?.sessionId ?: runtimeRetrySessionId,
            requiresSettings = !modelReady && !checkingModel,
            enabled = !checkingModel &&
                (!modelReady || (!transcriptionRuntime.busy && !enrichmentRuntime.busy)),
            detail = detail,
        )
    }

    val enrichmentRecord = readyEnrichmentRecords.firstOrNull { !it.hasGenuinelyEmptyEnrichmentSource() }
    if (enrichmentRecord != null) {
        val modelReady = enrichmentRuntime.modelPhase == EnrichmentModelPhase.INSTALLED
        val checkingModel = enrichmentRuntime.modelPhase in setOf(
            EnrichmentModelPhase.UNINITIALIZED,
            EnrichmentModelPhase.VERIFICATION_DEFERRED,
            EnrichmentModelPhase.VERIFYING,
        )
        return HomeMorningAction(
            kind = HomeNextActionKind.ENRICH,
            title = "Ready to enrich",
            body = if (enrichmentRecord.night.enrichmentState == ProcessingState.FAILED) {
                val safeDetail = persistedEnrichmentFailureDisplayDetail(
                    enrichmentRecord.night.enrichmentFailure,
                )
                if (
                    persistedEnrichmentFailureIsRetryable(
                        enrichmentRecord.night.enrichmentFailure,
                    )
                ) {
                    buildString {
                        append("Enrichment stopped")
                        if (safeDetail == null) {
                            append('.')
                        } else {
                            append(": $safeDetail")
                            if (safeDetail.last() !in ".!?") append('.')
                        }
                        append(" Choose Enrich to retry.")
                    }
                } else {
                    "Dream generation stopped. This failure cannot be retried from Home."
                }
            } else if (enrichmentRuntime.runtimeError != null) {
                "${enrichmentRuntime.runtimeError} Choose Enrich to retry."
            } else {
                "Turn saved recordings into organized dreams."
            },
            buttonLabel = when {
                checkingModel -> "Checking model…"
                modelReady -> "Enrich"
                else -> "Set up enrichment"
            },
            nightId = enrichmentRecord.night.nightId,
            requiresSettings = !modelReady && !checkingModel,
            enabled = !checkingModel &&
                (!modelReady || (!transcriptionRuntime.busy && !enrichmentRuntime.busy)),
            detail = if (enrichmentRecord.night.enrichmentState == ProcessingState.FAILED) {
                persistedEnrichmentFailureDisplayDetail(
                    enrichmentRecord.night.enrichmentFailure,
                )
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
    startupChecking: Boolean,
    cueOutputRoute: CueOutputRoute,
    onStartNight: () -> Unit,
    onEndNight: () -> Unit,
    onMorningAction: (HomeMorningAction) -> Unit,
) {
    val checking = startupChecking && !runtime.active
    var detailExpanded by remember(morningAction?.kind, morningAction?.nightId) {
        mutableStateOf(false)
    }
    var showEnrichConfirmation by remember(morningAction?.kind, morningAction?.nightId) {
        mutableStateOf(false)
    }
    val actionableMorningAction = morningAction?.takeIf { !checking && it.buttonLabel != null }
    val active = runtime.active
    val showStartNightInstead = !checking && !active && canStartNightInstead(morningAction, startEnabled = true)
    val startNightInsteadEnabled = canStartNightInstead(morningAction, startEnabled)
    val title = homePrimaryStatusTitle(
        runtime = runtime,
        morningAction = morningAction,
        startEnabled = startEnabled,
        setupNeedsAttention = setupNeedsAttention,
        checking = checking,
    )
    val body = when {
        checking -> "Checking saved nights…"
        active && runtime.microphoneSilenced ->
            "Stop other recorders and check microphone access."

        active && runtime.phase in setOf(
            CapturePhase.ACKNOWLEDGING,
            CapturePhase.RECORDING,
            CapturePhase.FINALIZING,
        ) -> "Speak naturally. Recording ends after 10 seconds of silence."

        active -> "You can lock the phone. Say “DreamLog” or “Hey DreamLog” when you wake."
        morningAction != null -> morningAction.body
        startBlockedMessage != null && setupNeedsAttention ->
            startBlockedMessage

        startBlockedMessage != null -> startBlockedMessage
        else -> "Say “DreamLog” when you wake. Wait for the alert, then speak."
    }
    val errorTone = runtime.microphoneSilenced ||
        morningAction?.detail != null ||
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
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!active && !checking) {
                cueOutputWarning(cueOutputRoute)?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            actionMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when {
                checking -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                active -> Button(
                    onClick = onEndNight,
                    enabled = runtime.phase != CapturePhase.ENDING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HOME_PRIMARY_ACTION_HEIGHT_DP.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        if (runtime.phase == CapturePhase.ENDING) "Ending…" else "End night",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                actionableMorningAction != null -> Button(
                    onClick = {
                        if (
                            actionableMorningAction.kind == HomeNextActionKind.ENRICH &&
                                !actionableMorningAction.requiresSettings
                        ) {
                            showEnrichConfirmation = true
                        } else {
                            onMorningAction(actionableMorningAction)
                        }
                    },
                    enabled = actionableMorningAction.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HOME_PRIMARY_ACTION_HEIGHT_DP.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(
                        actionableMorningAction.buttonLabel.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                morningAction == null -> Button(
                    onClick = onStartNight,
                    enabled = startEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HOME_PRIMARY_ACTION_HEIGHT_DP.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(
                        "Start night",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (showStartNightInstead) {
                TextButton(
                    onClick = onStartNight,
                    enabled = startNightInsteadEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start night instead")
                }
                Text(
                    "Earlier recordings stay saved. Finish processing later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!startNightInsteadEnabled && setupNeedsAttention) {
                    Text(
                        text = "Resolve setup below before starting a new night.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            morningAction?.detail?.let { detail ->
                TextButton(
                    onClick = { detailExpanded = !detailExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (detailExpanded) "Hide details" else "Details")
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

    if (showEnrichConfirmation) {
        AlertDialog(
            onDismissRequest = { showEnrichConfirmation = false },
            title = { Text("Enrich pending nights?") },
            text = {
                Text(
                    "Keep DreamLog open and unlocked until done.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnrichConfirmation = false
                        actionableMorningAction?.let(onMorningAction)
                    },
                ) {
                    Text("Enrich now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnrichConfirmation = false }) {
                    Text("Not now")
                }
            },
        )
    }
}

internal fun homePrimaryStatusTitle(
    runtime: CaptureRuntimeSnapshot,
    morningAction: HomeMorningAction?,
    startEnabled: Boolean,
    setupNeedsAttention: Boolean,
    checking: Boolean = false,
): String = when {
    checking && !runtime.active -> "Getting ready"
    runtime.active && runtime.phase == CapturePhase.ENDING -> "Ending night"
    runtime.active && runtime.microphoneSilenced -> "Microphone blocked"
    runtime.active && runtime.phase == CapturePhase.STARTING -> "Checking microphone"
    runtime.active && runtime.phase in setOf(
        CapturePhase.ACKNOWLEDGING,
        CapturePhase.RECORDING,
        CapturePhase.FINALIZING,
    ) -> "Recording dream"
    runtime.active -> "Listening"
    morningAction != null -> morningAction.title
    setupNeedsAttention -> "Setup required"
    startEnabled -> "Ready to start"
    else -> "Please wait"
}

internal fun cueOutputWarning(route: CueOutputRoute): String? = when (route) {
    CueOutputRoute.OTHER_OUTPUT ->
        "Wake alert uses connected audio. Disconnect it to use the phone speaker."
    CueOutputRoute.EXTERNAL_OUTPUT_CONNECTED ->
        "Connected audio may receive the wake alert. Test it before starting."
    CueOutputRoute.PHONE_SPEAKER, CueOutputRoute.UNKNOWN -> null
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
                        "Allow access to hear the wake phrase and record dreams during a night.",
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
                        "Required for listening with the screen off. Includes End night.",
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
                    body = "Microphone and notifications enabled.",
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
                "${preflight.assetValidation.detail} Install a DreamLog update to repair it."
            actionLabel = "Open app info"
            action = context::openAppDetails
        }

        PreflightRemediationCode.FREE_STORAGE -> {
            title = "Storage is low"
            body = "Free some space before starting a night."
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
                    title = "Checking the previous night"
                    body = "Please wait…"
                    actionLabel = null
                    action = null
                }

                recoveryUiState.unreadableActiveMarker -> {
                    title = "Previous night needs recovery"
                    body =
                        "Save the unreadable recovery file to continue. Audio is kept, but cannot be linked automatically."
                    actionLabel = "Preserve and continue"
                    action = onPreserveUnreadableMarker
                }

                else -> {
                    title = "Earlier capture needs recovery"
                    body = recoveryUiState.error
                        ?: "Recover the previous night before starting another."
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
            title = "Microphone unavailable"
            body = "Stop other recording apps, check microphone access, and try Start night again."
            actionLabel = "Open privacy controls"
            action = {
                context.openSettings(Intent(Settings.ACTION_PRIVACY_SETTINGS))
            }
        }

        PreflightRemediationCode.RETRY_WITH_OTHER_RECORDERS_STOPPED -> {
            title = "No microphone audio"
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
            text = "Wait for Listening before locking the phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
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
    SettingsCueCard(
        cueStatus = preflight.cueAudioStatus,
        cuePlayerAvailable = cuePlayerAvailable,
        cuePreviewState = cuePreviewState,
        captureActive = false,
        onPreviewCue = onPreviewCue,
    )
}

@Composable
private fun WarningChecksCard(preflight: AndroidPreflightSnapshot) {
    val warnings = preflight.evaluation.warnings
        .filterNot {
            it.code == PreflightIssueCode.CUE_VOLUME_UNTESTED ||
                it.code == PreflightIssueCode.CUE_OUTPUT_MAY_BYPASS_PHONE_SPEAKER
        }
    if (warnings.isEmpty()) return

    SectionCard(title = "Recommendations") {
        warnings.forEachIndexed { index, issue ->
            if (index > 0) HorizontalDivider()
            when (issue.remediation) {
                PreflightRemediationCode.CONNECT_CHARGER ->
                    CheckRow(
                        title = "Connect a charger",
                        body =
                            "Keep the phone charging overnight.",
                        blocking = false,
                    )

                PreflightRemediationCode.REVIEW_BATTERY_SETTINGS ->
                    CheckRow(
                        title = "Review the prior interruption",
                        body =
                            "The last night was interrupted. Check Android's battery restrictions.",
                        blocking = false,
                    )

                PreflightRemediationCode.STOP_OTHER_RECORDER ->
                    CheckRow(
                        title = "Stop other recorders",
                        body = if (preflight.visibleOtherRecorderCount > 0) {
                            "Another app is recording. Stop it before starting."
                        } else {
                            "Close SnoreLab and other listening apps before starting."
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
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

@Composable
private fun InformationCard(title: String, body: String) {
    SectionCard(title) { SupportingText(body) }
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
private fun SupportingText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
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

private const val ACTION_ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"
private const val BYTES_PER_MEBIBYTE = 1_048_576.0
private const val HOME_ROUTE = "home"
private const val NIGHT_ROUTE = "night"
private const val DREAM_ROUTE = "dream"
private const val SETTINGS_ROUTE = "settings"
