package com.wivy.dreamlog.ui.history

import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wivy.dreamlog.capture.CueAudioPreflight
import com.wivy.dreamlog.capture.SessionIncompleteReason
import com.wivy.dreamlog.capture.captureMicrophoneSilencedState
import com.wivy.dreamlog.enrichment.EnrichmentFailureCode
import com.wivy.dreamlog.enrichment.persistence.persistedEnrichmentFailureDisplayDetail
import com.wivy.dreamlog.enrichment.persistence.persistedEnrichmentFailureCode
import com.wivy.dreamlog.enrichment.persistence.persistedEnrichmentFailureIsRetryable
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureIssueFingerprint
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.HistoryFormatters
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightAudioArtifactInspection
import com.wivy.dreamlog.history.NightEventEntity
import com.wivy.dreamlog.history.NightRecord
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioState
import com.wivy.dreamlog.history.SessionTranscriptRecord
import com.wivy.dreamlog.playback.RawSessionPlaybackPhase
import com.wivy.dreamlog.playback.RawSessionPlaybackState
import com.wivy.dreamlog.playback.RawSessionPlayer
import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val NO_SPEECH_RECOGNIZED_TEXT = "No speech recognized"

@Composable
fun NightHistorySection(
    nights: List<NightRecord>,
    loading: Boolean,
    error: String?,
    warningCount: Int,
    onOpenNight: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "History",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        when {
            loading -> SupportingText("Loading history…")

            error != null -> {
                WarningText(error)
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry")
                }
            }

            else -> {
                if (warningCount > 0) {
                    WarningText(
                        "Capture evidence unmatched. Open a night for details.",
                    )
                }
                if (nights.isEmpty()) {
                    SupportingText("No nights yet.")
                } else {
                    nights.forEach { record ->
                        HistoryRow(record, onOpenNight)
                    }
                }
            }
        }
    }
}

@Composable
fun NightDetailScreen(
    record: NightRecord?,
    captureActive: Boolean,
    localProcessingActive: Boolean = false,
    archiveMutationRunning: Boolean = false,
    onBack: () -> Unit,
    onOpenDream: (String) -> Unit = {},
    transcriptionAvailable: Boolean = false,
    transcriptionRunning: Boolean = false,
    transcriptionMessage: String? = null,
    reprocessUnavailableReason: String? = null,
    reprocessRequiresTranscription: Boolean = true,
    reprocessRunning: Boolean = false,
    reprocessMessage: String? = null,
    onTranscribeNight: (String) -> Unit = {},
    onResumeTranscription: (String) -> Unit = {},
    onRetryTranscription: (String, String) -> Unit = { _, _ -> },
    onRetranscribe: (String, String) -> Unit = { _, _ -> },
    onReprocessNight: (String) -> Unit = {},
    onExportNight: (String) -> Unit = {},
    onDeleteNightRawAudio: (String, (String?) -> Unit) -> Unit = { _, completion ->
        completion("Recording deletion unavailable.")
    },
    onDeleteWholeNight: (String, (String?) -> Unit) -> Unit = { _, completion ->
        completion("Night deletion unavailable.")
    },
    onMarkCaptureIssueReviewed: (String, (String?) -> Unit) -> Unit = { _, completion ->
        completion("Capture review unavailable.")
    },
    onShowCaptureIssueAgain: (String, (String?) -> Unit) -> Unit = { _, completion ->
        completion("Capture review unavailable.")
    },
    onInspectNightAudio: (
        String,
        (NightAudioArtifactInspection?, String?) -> Unit,
    ) -> Unit = { _, completion ->
        completion(null, "Audio check unavailable.")
    },
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var playbackState by remember { mutableStateOf(RawSessionPlaybackState()) }
    val player = remember(context) {
        RawSessionPlayer(context) { playbackState = it }
    }
    var technicalDetailsExpanded by remember(record?.night?.nightId) { mutableStateOf(false) }
    var captureIssueReviewMessage by remember(record?.night?.nightId) {
        mutableStateOf<String?>(null)
    }
    var audioInspectionMessage by remember(record?.night?.nightId) {
        mutableStateOf<String?>(null)
    }
    val reviewActionsBlocked =
        captureActive || localProcessingActive || archiveMutationRunning

    DisposableEffect(player, activity) {
        activity?.setVolumeControlStream(AudioManager.STREAM_MUSIC)
        onDispose {
            player.release()
            activity?.setVolumeControlStream(CueAudioPreflight.volumeControlStream())
        }
    }
    LaunchedEffect(
        reviewActionsBlocked,
        record?.night?.rawAudioState,
        record?.retainedSessionCount,
    ) {
        if (reviewActionsBlocked || record?.retainedSessionCount == 0) player.stop()
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
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                    Text(
                        text = record?.night?.displayDate ?: "Night unavailable",
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(
                        onClick = { record?.night?.nightId?.let(onExportNight) },
                        enabled = record != null && !reviewActionsBlocked,
                    ) {
                        Text("Export")
                    }
                }
            }

            if (record == null) {
                item {
                    HistoryCard(title = "Night unavailable") {
                        SupportingText(
                            "Night not found.",
                        )
                    }
                }
            } else {
                item {
                    ProcessedDreamSection(record, onOpenDream)
                }
                item {
                    NightOutcomeSummary(
                        record = record,
                        actionsBlocked = reviewActionsBlocked,
                        transcriptionAvailable = transcriptionAvailable,
                        transcriptionRunning = transcriptionRunning,
                        onResumeTranscription = {
                            onResumeTranscription(record.night.nightId)
                        },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { technicalDetailsExpanded = !technicalDetailsExpanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (technicalDetailsExpanded) {
                                "Hide technical details"
                            } else {
                                "Technical details"
                            },
                        )
                    }
                }
                if (technicalDetailsExpanded) {
                    item {
                        NightDetailSummary(
                            record = record,
                            actionsBlocked = reviewActionsBlocked,
                            reviewMessage = captureIssueReviewMessage,
                            audioInspectionMessage = audioInspectionMessage,
                            onMarkCaptureIssueReviewed = {
                                onMarkCaptureIssueReviewed(record.night.nightId) { error ->
                                    captureIssueReviewMessage = error
                                        ?: "Marked reviewed."
                                }
                            },
                            onShowCaptureIssueAgain = {
                                onShowCaptureIssueAgain(record.night.nightId) { error ->
                                    captureIssueReviewMessage = error
                                        ?: "Issue shown again."
                                }
                            },
                            onInspectNightAudio = {
                                audioInspectionMessage = "Checking recordings…"
                                onInspectNightAudio(record.night.nightId) { inspection, error ->
                                    audioInspectionMessage = error
                                        ?: inspection?.let(::nightAudioInspectionText)
                                        ?: "Audio check returned no result."
                                }
                            },
                        )
                    }
                    item {
                        RawTranscriptSection(
                            record = record,
                            playbackState = playbackState,
                            actionsBlocked = reviewActionsBlocked,
                            transcriptionAvailable = transcriptionAvailable,
                            transcriptionRunning = transcriptionRunning,
                            transcriptionMessage = transcriptionMessage,
                            ownerChangesProtected = record.hasProtectedDreamChanges,
                            onTranscribeNight = { onTranscribeNight(record.night.nightId) },
                            onRetryTranscription = { sessionId ->
                                onRetryTranscription(record.night.nightId, sessionId)
                            },
                            onRetranscribe = { sessionId ->
                                onRetranscribe(record.night.nightId, sessionId)
                            },
                            onPlayFrom = { session, sourceStartMillis ->
                                player.playOrPause(
                                    nightId = record.night.nightId,
                                    audioFileName = session.audioFileName,
                                    audioEvidenceState = session.audioState,
                                    captureActive = reviewActionsBlocked,
                                    startPositionMillis = sourceStartMillis,
                                )
                            },
                        )
                    }
                    item {
                        SessionEvidenceSection(
                            record = record,
                            playbackState = playbackState,
                            playbackBlocked = reviewActionsBlocked,
                            onPlayOrPause = { session ->
                                player.playOrPause(
                                    nightId = record.night.nightId,
                                    audioFileName = session.audioFileName,
                                    audioEvidenceState = session.audioState,
                                    captureActive = reviewActionsBlocked,
                                )
                            },
                        )
                    }
                    item { PrivacyDetailFooter() }
                }
                item {
                    ManageNightDataCard(
                        record = record,
                        blocked = reviewActionsBlocked,
                        reprocessUnavailableReason = reprocessUnavailableReason,
                        reprocessRequiresTranscription = reprocessRequiresTranscription,
                        reprocessRunning = reprocessRunning,
                        reprocessMessage = reprocessMessage,
                        onReprocessNight = {
                            player.stop()
                            onReprocessNight(record.night.nightId)
                        },
                        onDeleteRawAudio = { onComplete ->
                            player.stop()
                            onDeleteNightRawAudio(record.night.nightId, onComplete)
                        },
                        onDeleteWholeNight = { onComplete ->
                            player.stop()
                            onDeleteWholeNight(record.night.nightId, onComplete)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    record: NightRecord,
    onOpenNight: (String) -> Unit,
) {
    val night = record.night
    val status = historyStatus(record)
    Card(
        onClick = { onOpenNight(night.nightId) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = HistoryFormatters.date(night.displayDate),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = dreamCountText(record),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = status,
                color = if (historyStatusIsError(record)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NightOutcomeSummary(
    record: NightRecord,
    actionsBlocked: Boolean,
    transcriptionAvailable: Boolean,
    transcriptionRunning: Boolean,
    onResumeTranscription: () -> Unit,
) {
    val resumeAction = nightOutcomeResumeAction(record)
    val completedCount = resumeAction?.completedCount ?: record.transcripts
        .asSequence()
        .filter { it.transcript.state == ProcessingState.COMPLETE }
        .map { it.transcript.sessionId }
        .distinct()
        .count()
        .coerceAtMost(record.sessions.size)
    val noRecognizedSpeech = !transcriptionRunning &&
        record.dreams.isEmpty() &&
        record.night.enrichmentState in setOf(
            ProcessingState.NOT_STARTED,
            ProcessingState.WAITING_FOR_TRANSCRIPTION,
        ) && record.hasNoRecognizedSpeech

    HistoryCard(title = "Night summary") {
        SummaryRow(
            "Dreams",
            when {
                record.dreams.size == 1 -> "1 dream"
                record.dreams.isNotEmpty() -> "${record.dreams.size} dreams"
                record.night.enrichmentState == ProcessingState.COMPLETE ->
                    "None identified"
                noRecognizedSpeech -> NO_SPEECH_RECOGNIZED_TEXT
                else -> "Not enriched yet"
            },
        )
        when {
            transcriptionRunning -> SupportingText(
                "Transcribing $completedCount/${record.sessions.size}. Screen can be off.",
            )

            resumeAction != null -> {
                WarningText(
                    "Transcription stopped. Resume below.",
                )
                Button(
                    onClick = onResumeTranscription,
                    enabled = transcriptionAvailable && !actionsBlocked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Resume transcription (${resumeAction.completedCount}/${resumeAction.totalCount})",
                    )
                }
                if (!transcriptionAvailable) {
                    SupportingText("Set up transcription in Settings.")
                }
            }

            record.night.enrichmentState == ProcessingState.FAILED -> WarningText(
                enrichmentFailureWarningText(record.night.enrichmentFailure),
            )

            captureEvidence(record) != null -> WarningText(
                "Capture issue. See Technical details.",
            )

            record.dreams.isNotEmpty() -> Unit

            record.night.enrichmentState == ProcessingState.COMPLETE ->
                SupportingText("Processing complete.")

            noRecognizedSpeech -> Unit

            else -> SupportingText("Continue processing from Home.")
        }
    }
}

internal fun enrichmentFailureWarningText(failure: String?): String =
    when {
        persistedEnrichmentFailureIsRetryable(failure) ->
            "Enrichment failed. Retry with Enrich on Home."

        persistedEnrichmentFailureCode(failure) ==
            EnrichmentFailureCode.RAW_SOURCE_UNAVAILABLE.persistedValue ->
            "Enrichment failed: raw transcript incomplete or unavailable. This night can't be retried."

        persistedEnrichmentFailureCode(failure) ==
            EnrichmentFailureCode.INVALID_SOURCE.persistedValue ->
            "Enrichment failed: invalid raw transcript source. This night can't be retried."

        else -> "Enrichment failed. This night can't be retried."
    }

internal data class NightOutcomeResumeAction(
    val completedCount: Int,
    val totalCount: Int,
)

internal fun nightOutcomeResumeAction(record: NightRecord): NightOutcomeResumeAction? {
    val retainedSessionIds = record.sessions
        .asSequence()
        .filter { it.audioState == AudioEvidenceState.RETAINED }
        .mapTo(mutableSetOf()) { it.sessionId }
    val hasFailedRetainedSession = record.transcripts.any {
        it.transcript.state == ProcessingState.FAILED &&
            it.transcript.sessionId in retainedSessionIds
    }
    val claimedSessionIds = record.transcripts
        .mapTo(mutableSetOf()) { it.transcript.sessionId }
    val hasDeferredRetainedSession = record.sessions.any {
        it.audioState == AudioEvidenceState.RETAINED &&
            it.finalizedAtEpochMillis != null &&
            it.sessionId !in claimedSessionIds
    }
    if (!hasFailedRetainedSession && !hasDeferredRetainedSession) return null
    val completedCount = record.transcripts
        .asSequence()
        .filter { it.transcript.state == ProcessingState.COMPLETE }
        .map { it.transcript.sessionId }
        .distinct()
        .count()
        .coerceAtMost(record.sessions.size)
    return NightOutcomeResumeAction(
        completedCount = completedCount,
        totalCount = record.sessions.size,
    )
}

@Composable
private fun NightDetailSummary(
    record: NightRecord,
    actionsBlocked: Boolean,
    reviewMessage: String?,
    audioInspectionMessage: String?,
    onMarkCaptureIssueReviewed: () -> Unit,
    onShowCaptureIssueAgain: () -> Unit,
    onInspectNightAudio: () -> Unit,
) {
    val night = record.night
    val captureEvidenceDisplay = captureEvidenceText(record)
    val captureIssueReviewed = CaptureIssueFingerprint.isReviewed(record)
    HistoryCard(title = "Capture and processing") {
        SummaryRow("Night", HistoryFormatters.date(night.displayDate))
        SummaryRow("Status", nightStatus(night.captureState, night.interrupted))
        SummaryRow("Monitoring", monitoringRange(record))
        SummaryRow(
            "Start offset",
            HistoryFormatters.utcOffset(night.startedUtcOffsetSeconds),
        )
        night.endedUtcOffsetSeconds?.let {
            SummaryRow("End offset", HistoryFormatters.utcOffset(it))
        }
        SummaryRow("Wakewords", wakewordCountText(record))
        SummaryRow(
            "Dreams",
            if (record.dreams.size == 1) "1 dream" else "${record.dreams.size} dreams",
        )
        night.endReason?.let { SummaryRow("End reason", humanizeReason(it)) }
        night.lastHeartbeatEpochMillis?.let {
            SummaryRow(
                "Last heartbeat",
                HistoryFormatters.dateTime(
                    it,
                    night.lastHeartbeatUtcOffsetSeconds,
                ),
            )
        }
        SummaryRow("Raw audio", rawAudioText(record))
        SummaryRow(
            "Transcription",
            transcriptionProcessingText(record),
        )
        SummaryRow(
            "Enrichment",
            enrichmentProcessingText(
                night.enrichmentState,
                night.enrichmentFailure,
                night.transcriptionState,
                hasNoRecognizedSpeech = record.dreams.isEmpty() && record.hasNoRecognizedSpeech,
            ),
        )
        captureEvidenceDisplay?.let { evidence ->
            if (captureIssueReviewed) {
                SupportingText(evidence)
                TextButton(
                    onClick = onShowCaptureIssueAgain,
                    enabled = !actionsBlocked,
                ) {
                    Text("Show issue again")
                }
            } else {
                WarningText(evidence)
                TextButton(
                    onClick = onMarkCaptureIssueReviewed,
                    enabled = !actionsBlocked,
                ) {
                    Text("Mark reviewed")
                }
            }
        }
        reviewMessage?.let { message ->
            SupportingText(message)
        }
        TextButton(
            onClick = onInspectNightAudio,
            enabled = !actionsBlocked && canInspectNightAudio(record),
        ) {
            Text("Check recordings")
        }
        audioInspectionMessage?.let { message -> SupportingText(message) }
        night.importWarning?.let { WarningText(it) }
        if (night.reportedSessionCount == 0) {
            SupportingText(
                "No wake-triggered recordings.",
            )
        }
    }
}

internal fun nightAudioInspectionText(
    inspection: NightAudioArtifactInspection,
): String {
    if (!inspection.directoryPresent) {
        return if (inspection.recordedSessionCount == 0) {
            "No recordings or audio directory."
        } else {
            "Saved-audio directory missing."
        }
    }

    val parts = mutableListOf<String>()
    parts += when {
        inspection.recordedSessionCount == 0 ->
            "No recorded sessions."

        inspection.recordedFinalValidCount == inspection.recordedSessionCount ->
            "All ${inspection.recordedSessionCount} recordings verified."

        inspection.recordedFinalValidCount == 1 ->
            "1 of ${inspection.recordedSessionCount} recordings verified."

        else ->
            "${inspection.recordedFinalValidCount} of ${inspection.recordedSessionCount} " +
                "recordings verified."
    }

    val missingRecorded =
        inspection.recordedSessionCount - inspection.recordedFinalPresentCount
    if (missingRecorded > 0) {
        parts += if (missingRecorded == 1) {
            "1 recording missing."
        } else {
            "$missingRecorded recordings missing."
        }
    }
    val invalidRecorded =
        inspection.recordedFinalPresentCount - inspection.recordedFinalValidCount
    if (invalidRecorded > 0) {
        parts += if (invalidRecorded == 1) {
            "1 recording unverified."
        } else {
            "$invalidRecorded recordings unverified."
        }
    }
    if (inspection.extraFinalizedCandidateCount > 0) {
        parts += if (inspection.extraFinalizedCandidateCount == 1) {
            "1 additional finalized recording."
        } else {
            "${inspection.extraFinalizedCandidateCount} additional finalized recordings."
        }
    }
    if (inspection.partialFileCount > 0) {
        parts += if (inspection.partialFileCount == 1) {
            "1 partial audio file."
        } else {
            "${inspection.partialFileCount} partial audio files."
        }
    }
    if (inspection.extraUnverifiedFinalCount > 0) {
        parts += if (inspection.extraUnverifiedFinalCount == 1) {
            "1 additional finalized file unverified."
        } else {
            "${inspection.extraUnverifiedFinalCount} additional finalized files unverified."
        }
    }
    if (inspection.extraFinalizedOutsideNightCount > 0) {
        parts += if (inspection.extraFinalizedOutsideNightCount == 1) {
            "1 finalized recording outside this night's time range."
        } else {
            "${inspection.extraFinalizedOutsideNightCount} finalized recordings outside " +
                "this night's time range."
        }
    }
    if (inspection.metadataOnlyCount > 0) {
        parts += if (inspection.metadataOnlyCount == 1) {
            "1 metadata-only entry."
        } else {
            "${inspection.metadataOnlyCount} metadata-only entries."
        }
    }
    if (inspection.metadataPartialCount > 0) {
        parts += if (inspection.metadataPartialCount == 1) {
            "1 unfinished metadata entry."
        } else {
            "${inspection.metadataPartialCount} unfinished metadata entries."
        }
    }

    if (parts.size == 1) {
        parts += "No additional recordings or partial files."
    }
    return parts.joinToString(" ")
}

internal fun canInspectNightAudio(record: NightRecord): Boolean =
    record.night.endedAtEpochMillis != null &&
        record.night.captureState in setOf(
            NightCaptureState.ENDED,
            NightCaptureState.INTERRUPTED,
        )

@Composable
private fun SessionEvidenceSection(
    record: NightRecord,
    playbackState: RawSessionPlaybackState,
    playbackBlocked: Boolean,
    onPlayOrPause: (CaptureSessionEntity) -> Unit,
) {
    val mediaPlaybackSessionIds = mediaPlaybackActiveAtWakeSessionIds(record.events)
    val savedTranscriptSessionIds = record.transcripts
        .asSequence()
        .filter { it.transcript.state == ProcessingState.COMPLETE }
        .mapTo(mutableSetOf()) { it.transcript.sessionId }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Raw session evidence",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        if (playbackBlocked) {
            WarningText("Playback unavailable during capture or processing.")
        }
        if (record.sessions.isEmpty()) {
            SupportingText("No wake-triggered recordings.")
        } else {
            record.sessions.forEachIndexed { index, session ->
                SessionCard(
                    index = index,
                    record = record,
                    session = session,
                    playbackState = playbackState,
                    playbackBlocked = playbackBlocked,
                    mediaPlaybackActiveAtWake = session.sessionId in mediaPlaybackSessionIds,
                    hasSavedTranscript = session.sessionId in savedTranscriptSessionIds,
                    onPlayOrPause = { onPlayOrPause(session) },
                )
            }
        }
    }
}

@Composable
private fun RawTranscriptSection(
    record: NightRecord,
    playbackState: RawSessionPlaybackState,
    actionsBlocked: Boolean,
    transcriptionAvailable: Boolean,
    transcriptionRunning: Boolean,
    transcriptionMessage: String?,
    ownerChangesProtected: Boolean,
    onTranscribeNight: () -> Unit,
    onRetryTranscription: (String) -> Unit,
    onRetranscribe: (String) -> Unit,
    onPlayFrom: (CaptureSessionEntity, Long) -> Unit,
) {
    val sessionsById = record.sessions.associateBy(CaptureSessionEntity::sessionId)
    val transcriptSessionIds = record.transcripts
        .mapTo(mutableSetOf()) { it.transcript.sessionId }
    val hasUnstartedRetainedSession = record.sessions.any {
        it.audioState == AudioEvidenceState.RETAINED &&
            it.finalizedAtEpochMillis != null &&
            it.sessionId !in transcriptSessionIds
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Raw transcript",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        if (transcriptionRunning) {
            SupportingText(
                "Transcribing. Screen can be off.",
            )
        }
        transcriptionMessage?.let { SupportingText(it) }

        if (record.sessions.isEmpty()) {
            SupportingText("No recordings to transcribe.")
        } else if (record.transcripts.isEmpty() && !hasUnstartedRetainedSession) {
            SupportingText("No retained audio to transcribe.")
        }

        record.transcripts.forEachIndexed { index, transcript ->
            TranscriptCard(
                index = index,
                transcript = transcript,
                session = sessionsById[transcript.transcript.sessionId],
                playbackState = playbackState,
                actionsBlocked = actionsBlocked,
                retryEnabled = transcriptionAvailable && !transcriptionRunning,
                ownerChangesProtected = ownerChangesProtected,
                onRetry = { onRetryTranscription(transcript.transcript.sessionId) },
                onRetranscribe = { onRetranscribe(transcript.transcript.sessionId) },
                onPlayFrom = onPlayFrom,
            )
        }

        if (hasUnstartedRetainedSession) {
            Button(
                onClick = onTranscribeNight,
                enabled = transcriptionAvailable && !transcriptionRunning && !actionsBlocked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (transcriptionRunning) "Transcribing…" else "Transcribe night")
            }
            if (!transcriptionAvailable) {
                SupportingText("Set up transcription in Settings.")
            }
        }
    }
}

@Composable
private fun TranscriptCard(
    index: Int,
    transcript: SessionTranscriptRecord,
    session: CaptureSessionEntity?,
    playbackState: RawSessionPlaybackState,
    actionsBlocked: Boolean,
    retryEnabled: Boolean,
    ownerChangesProtected: Boolean,
    onRetry: () -> Unit,
    onRetranscribe: () -> Unit,
    onPlayFrom: (CaptureSessionEntity, Long) -> Unit,
) {
    val value = transcript.transcript
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Session ${(session?.captureOrder ?: index) + 1}",
                style = MaterialTheme.typography.titleMedium,
            )
            SummaryRow("State", processingText(value.state, value.failureDetail, "Not started"))
            when (value.state) {
                ProcessingState.COMPLETE -> {
                    val rawText = value.rawText.orEmpty()
                    Text(
                        text = rawText.ifBlank { "No speech recognized." },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    transcript.segments.chunked(TRANSCRIPT_SEGMENTS_PER_PLAYBACK_ROW)
                        .forEach { segmentGroup ->
                            val segment = segmentGroup.first()
                            val segmentText = segmentGroup.joinToString(separator = " ") {
                                it.text
                            }
                            val segmentTarget = session?.let {
                                playbackState.target?.nightId == it.nightId &&
                                    playbackState.target.audioFileName == it.audioFileName &&
                                    playbackState.target.startPositionMillis ==
                                    segment.sourceStartMillis
                            } == true
                            OutlinedButton(
                                onClick = {
                                    session?.let {
                                        onPlayFrom(it, segment.sourceStartMillis)
                                    }
                                },
                                enabled =
                                    session?.audioState == AudioEvidenceState.RETAINED &&
                                        !actionsBlocked,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (segmentTarget && playbackState.isPlaying) {
                                        "Pause at ${playbackOffset(segment.sourceStartMillis)}"
                                    } else {
                                        "${playbackOffset(segment.sourceStartMillis)} · $segmentText"
                                    },
                                )
                            }
                        }
                    SupportingText(
                        "${value.engineId} ${value.engineVersion} · " +
                            "${value.modelId} ${value.modelVersion} · " +
                            "${value.runtimeId} ${value.runtimeVersion}",
                    )
                    OutlinedButton(
                        onClick = onRetranscribe,
                        enabled =
                            retryEnabled &&
                                !ownerChangesProtected &&
                                !actionsBlocked &&
                                session?.audioState == AudioEvidenceState.RETAINED,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Re-transcribe session")
                    }
                    if (ownerChangesProtected) {
                        SupportingText(
                            "Your edits or deletions prevent re-transcription.",
                        )
                    }
                }

                ProcessingState.FAILED -> {
                    WarningText(value.failureDetail ?: "Transcription failed.")
                    if (session?.audioState == AudioEvidenceState.RETAINED) {
                        Button(
                            onClick = onRetry,
                            enabled = retryEnabled && !actionsBlocked,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Retry session")
                        }
                    } else {
                        SupportingText(
                            "Retry unavailable: no retained source audio.",
                        )
                    }
                }

                ProcessingState.RUNNING ->
                    SupportingText(
                        "Transcribing. Screen can be off.",
                    )

                else -> SupportingText("Waiting for transcription.")
            }
        }
    }
}

@Composable
private fun SessionCard(
    index: Int,
    record: NightRecord,
    session: CaptureSessionEntity,
    playbackState: RawSessionPlaybackState,
    playbackBlocked: Boolean,
    mediaPlaybackActiveAtWake: Boolean,
    hasSavedTranscript: Boolean,
    onPlayOrPause: () -> Unit,
) {
    val isTarget =
        playbackState.target?.nightId == session.nightId &&
            playbackState.target.audioFileName == session.audioFileName &&
            playbackState.target.startPositionMillis == 0L
    val playable =
        session.audioState == AudioEvidenceState.RETAINED && !playbackBlocked
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Session ${index + 1}",
                style = MaterialTheme.typography.titleMedium,
            )
            SummaryRow(
                "Started",
                HistoryFormatters.time(
                    session.startedAtEpochMillis,
                    session.startedUtcOffsetSeconds,
                ),
            )
            SummaryRow(
                "Duration",
                HistoryFormatters.duration(
                    HistoryFormatters.durationMillis(
                        session.sampleCount,
                        session.sampleRateHz,
                    ),
                ),
            )
            SummaryRow(
                "Capture",
                sessionCaptureText(record, session),
            )
            SummaryRow(
                "Audio",
                audioEvidenceText(session.audioState, hasSavedTranscript),
            )
            if (mediaPlaybackActiveAtWake) {
                WarningText(POSSIBLE_MEDIA_FALSE_WAKE_MESSAGE)
            }
            Button(
                onClick = onPlayOrPause,
                enabled = playable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(playbackButtonText(playbackState, isTarget))
            }
            if (isTarget) {
                playbackState.message?.let { WarningText(it) }
            } else if (!playable) {
                SupportingText(audioEvidenceText(session.audioState, hasSavedTranscript))
            }
        }
    }
}

@Composable
private fun HistoryCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.38f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.62f),
            style = MaterialTheme.typography.bodyMedium,
        )
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

@Composable
private fun WarningText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PrivacyDetailFooter() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        SupportingText(
            "Recordings stay on this device.",
        )
    }
}

internal fun monitoringRange(record: NightRecord): String {
    val night = record.night
    val start = HistoryFormatters.time(
        night.startedAtEpochMillis,
        night.startedUtcOffsetSeconds,
    )
    if (hasUnconfirmedRecoveryClose(record)) {
        val lastConfirmedAt = night.lastHeartbeatEpochMillis
            ?: return "$start – no heartbeat confirmed"
        val lastConfirmed = HistoryFormatters.time(
            lastConfirmedAt,
            night.lastHeartbeatUtcOffsetSeconds,
        )
        return "$start – $lastConfirmed (last confirmed)"
    }
    val end = HistoryFormatters.time(
        night.endedAtEpochMillis,
        night.endedUtcOffsetSeconds,
    )
    return if (night.endedAtEpochMillis == null) "$start – not finalized" else "$start – $end"
}

internal fun dreamCountText(record: NightRecord): String = when {
    record.dreams.size == 1 -> "1 dream"
    record.dreams.isEmpty() && record.night.enrichmentState != ProcessingState.COMPLETE ->
        "— dreams"

    else -> "${record.dreams.size} dreams"
}

internal fun wakewordCountText(record: NightRecord): String =
    if (record.sessions.size == 1) "1 wakeword" else "${record.sessions.size} wakewords"

internal fun rawAudioText(record: NightRecord): String =
    when (record.night.rawAudioState) {
        RawAudioState.NONE -> "No recordings"
        RawAudioState.RETAINED ->
            "${record.retainedSessionCount} recordings retained"

        RawAudioState.PARTIAL ->
            "${record.retainedSessionCount} retained · " +
                "${record.unavailableSessionCount} unavailable"

        RawAudioState.PENDING_RECOVERY -> "Recover before playback"
        RawAudioState.UNAVAILABLE -> when {
            record.sessions.isNotEmpty() &&
                record.sessions.all { it.audioState == AudioEvidenceState.DELETED } ->
                deletedAudioText(record)

            record.sessions.isNotEmpty() &&
                record.sessions.all { it.audioState == AudioEvidenceState.EXPIRED } ->
                expiredAudioText(record)

            else -> "Source audio unavailable"
        }
        else -> record.night.rawAudioState.replace('_', ' ')
    }

internal fun captureEvidence(record: NightRecord): String? {
    return captureEvidenceText(record)?.takeUnless {
        CaptureIssueFingerprint.isReviewed(record)
    }
}

internal fun captureEvidenceText(record: NightRecord): String? {
    val evidence = morningDiagnostics(record)
    if (evidence.isEmpty()) return null
    val prefix = if (CaptureIssueFingerprint.isReviewed(record)) {
        "Capture evidence (reviewed): "
    } else {
        "Capture evidence: "
    }
    return evidence.joinToString(prefix = prefix, separator = " · ")
}

internal fun historyProcessingFailure(record: NightRecord): String? = when {
    record.night.transcriptionState == ProcessingState.FAILED -> "Transcription failed"
    record.night.enrichmentState == ProcessingState.FAILED -> "Enrichment failed"
    else -> null
}

internal val NightRecord.hasNoRecognizedSpeech: Boolean
    get() {
        if (
            sessions.isEmpty() ||
            night.transcriptionState != ProcessingState.COMPLETE ||
            night.transcriptionFailure != null
        ) {
            return false
        }
        val sessionIds = sessions.mapTo(mutableSetOf(), CaptureSessionEntity::sessionId)
        val transcriptSessionIds = transcripts.mapTo(mutableSetOf()) {
            it.transcript.sessionId
        }
        if (
            sessionIds.size != sessions.size ||
            transcriptSessionIds.size != transcripts.size ||
            transcriptSessionIds != sessionIds
        ) {
            return false
        }
        return transcripts.all { saved ->
            saved.transcript.state == ProcessingState.COMPLETE &&
                saved.transcript.failureDetail == null &&
                saved.transcript.rawText?.isBlank() == true &&
                saved.segments.isEmpty()
        }
    }

internal fun historyStatus(record: NightRecord): String {
    historyProcessingFailure(record)?.let { return it }
    val processingStatus = when {
        record.night.captureState == NightCaptureState.STARTING -> "Starting"
        record.night.captureState == NightCaptureState.ACTIVE -> "Active"
        record.night.transcriptionState == ProcessingState.RUNNING -> "Transcribing"
        record.night.enrichmentState == ProcessingState.RUNNING -> "Enriching"
        record.night.enrichmentState == ProcessingState.COMPLETE -> "Complete"
        record.dreams.isEmpty() &&
            record.night.enrichmentState in setOf(
                ProcessingState.NOT_STARTED,
                ProcessingState.WAITING_FOR_TRANSCRIPTION,
            ) && record.hasNoRecognizedSpeech -> NO_SPEECH_RECOGNIZED_TEXT
        record.night.transcriptionState == ProcessingState.COMPLETE -> "Ready to enrich"
        record.sessions.isEmpty() &&
            record.night.captureState == NightCaptureState.ENDED &&
            record.night.enrichmentState == ProcessingState.WAITING_FOR_TRANSCRIPTION ->
            "Ready to enrich"

        else -> "Processing"
    }
    if (captureEvidence(record) == null) return processingStatus
    return if (processingStatus == "Complete" || processingStatus == "Ready to enrich") {
        "$processingStatus · Capture issue"
    } else {
        "Capture issue"
    }
}

internal fun historyStatusIsError(record: NightRecord): Boolean =
    record.night.transcriptionState == ProcessingState.FAILED ||
        record.night.enrichmentState == ProcessingState.FAILED ||
        captureEvidence(record) != null

internal fun hasOwnerFacingCaptureIssue(record: NightRecord): Boolean {
    return CaptureIssueFingerprint.hasOwnerFacingIssue(record)
}

internal fun hasUnreviewedCaptureIssue(record: NightRecord): Boolean =
    CaptureIssueFingerprint.current(record)?.let {
        it != record.night.captureIssueReviewedFingerprint
    } == true

internal fun morningDiagnostics(record: NightRecord): List<String> {
    if (!hasOwnerFacingCaptureIssue(record)) return emptyList()

    val night = record.night
    return buildList {
        if (
            night.interrupted ||
            night.captureState == NightCaptureState.INTERRUPTED ||
            night.captureState == NightCaptureState.RECOVERY_REQUIRED
        ) {
            add("monitoring interrupted; check the end reason and incomplete sessions")
        }
        val incompleteSessions = record.sessions.filter {
            CaptureIssueFingerprint.isOwnerFacingIncompleteSession(record, it)
        }
        if (incompleteSessions.isNotEmpty()) {
            add(
                "incomplete sessions: ${incompleteSessions.size}",
            )
        }
        val unavailableSessions = record.sessions.filter {
            it.audioState == AudioEvidenceState.MISSING ||
                it.audioState == AudioEvidenceState.CORRUPT ||
                it.audioState == AudioEvidenceState.PENDING_RECOVERY
        }
        if (unavailableSessions.isNotEmpty()) {
            add(
                "sessions with missing, corrupt, or unresolved audio: ${unavailableSessions.size}",
            )
        }
        val persistedIncompleteSessionCount = record.sessions.count {
            it.incompleteReason != null
        }
        if (night.reportedIncompleteSessionCount > persistedIncompleteSessionCount) {
            add("unmatched incomplete-capture evidence")
        }
        if (night.reportedSessionCount > record.sessions.size) {
            add("recorded session evidence missing")
        }
        captureFailureDiagnostics(record.events).forEach(::add)
        silencingDiagnostic(record)?.let(::add)
        audioGapDiagnostic(record.events)?.let(::add)
        heartbeatDiagnostic(record)?.let(::add)
    }
}

private fun audioGapDiagnostic(events: List<NightEventEntity>): String? {
    val affected = events.filter {
        !it.sessionId.isNullOrBlank() && CaptureIssueFingerprint.isConfirmedAudioGap(it)
    }
    if (affected.isEmpty()) return null

    val largestEstimateMillis = affected.mapNotNull { event ->
        decodePersistedEventAttributes(event.encodedAttributes)["estimated_gap_millis"]
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
    }.maxOrNull()
    return if (affected.size == 1) {
        largestEstimateMillis?.let { estimate ->
            "estimated audio-clock discontinuity: $estimate ms"
        } ?: "audio-clock discontinuity detected"
    } else {
        buildString {
            append("${affected.size} audio-clock discontinuities")
            largestEstimateMillis?.let { append("; largest estimate: $it ms") }
        }
    }
}

internal fun sessionCaptureText(
    record: NightRecord,
    session: CaptureSessionEntity,
): String = when {
    session.incompleteReason == null -> "Complete"
    session.incompleteReason == SessionIncompleteReason.AUDIO_GAP &&
        CaptureIssueFingerprint.isConfirmedAudioGap(record, session.sessionId) ->
        "May be incomplete · confirmed audio-clock deficit"

    session.incompleteReason == SessionIncompleteReason.AUDIO_GAP ->
        "Timestamp anomaly · capture gap unverified"

    else -> "Incomplete · ${humanizeReason(session.incompleteReason)}"
}

private fun silencingDiagnostic(record: NightRecord): String? {
    val intervals = observedSilencingIntervals(record.events)
    if (intervals.isEmpty() && !record.night.hadMicrophoneSilencing) return null
    val rangeText = if (intervals.isEmpty()) {
        "at an unknown time"
    } else {
        intervals.joinToString(separator = "; ") { interval ->
            val start = HistoryFormatters.dateTime(
                interval.startedAtEpochMillis,
                interval.startedUtcOffsetSeconds,
            )
            interval.endedAtEpochMillis?.let { endedAt ->
                "from $start to ${
                    HistoryFormatters.dateTime(endedAt, interval.endedUtcOffsetSeconds)
                }"
            } ?: "from $start; recovery time unconfirmed"
        }
    }
    val retainedAudio = if (
        record.sessions.any {
            it.finalizedAtEpochMillis != null &&
                it.audioState == AudioEvidenceState.RETAINED
        }
    ) {
        " Completed audio retained."
    } else {
        ""
    }
    return "Android reported the microphone silenced $rangeText. " +
        "Stop other microphone recorders before the next night.$retainedAudio"
}

private fun observedSilencingIntervals(
    events: List<NightEventEntity>,
): List<SilencingInterval> {
    val intervals = mutableListOf<SilencingInterval>()
    var activeStart: NightEventEntity? = null
    val orderedEvents = events.sortedWith(
        compareBy<NightEventEntity> { it.epochMillis }.thenBy { it.eventId },
    )
    val directStates = orderedEvents.filter { event ->
        event.type == "microphone_state" &&
            captureMicrophoneSilencedState(
                eventType = event.type,
                attributes = decodePersistedEventAttributes(event.encodedAttributes),
            ) != null
    }
    val stateEvidence = directStates.ifEmpty {
        orderedEvents.filter { event ->
            event.type == "heartbeat" &&
                captureMicrophoneSilencedState(
                    eventType = event.type,
                    attributes = decodePersistedEventAttributes(event.encodedAttributes),
                ) != null
        }
    }
    stateEvidence.forEach { event ->
        val state = captureMicrophoneSilencedState(
            eventType = event.type,
            attributes = decodePersistedEventAttributes(event.encodedAttributes),
        ) ?: return@forEach
        if (state) {
            if (activeStart == null) activeStart = event
        } else {
            activeStart?.let { start ->
                intervals += SilencingInterval(
                    startedAtEpochMillis = start.epochMillis,
                    startedUtcOffsetSeconds = start.utcOffsetSeconds,
                    endedAtEpochMillis = event.epochMillis,
                    endedUtcOffsetSeconds = event.utcOffsetSeconds,
                )
            }
            activeStart = null
        }
    }
    activeStart?.let { start ->
        intervals += SilencingInterval(
            startedAtEpochMillis = start.epochMillis,
            startedUtcOffsetSeconds = start.utcOffsetSeconds,
            endedAtEpochMillis = null,
            endedUtcOffsetSeconds = null,
        )
    }
    return intervals
}

private fun captureFailureDiagnostics(events: List<NightEventEntity>): List<String> =
    events.asSequence()
        .filter { it.type == "capture_failure" }
        .map { decodePersistedEventAttributes(it.encodedAttributes)["kind"] ?: "unknown" }
        .distinct()
        .map { kind ->
            when (kind) {
                "storage_reserve" ->
                    "storage reserve reached; free storage before the next night"

                "audio_write" ->
                    "audio write failed; check storage. Recording may be incomplete"

                "initialization" ->
                    "capture failed to start; close other recorders, test the cue, and retry"

                "audio_read" ->
                    "microphone read failed; review recordings and restart DreamLog"

                "cue_playback" ->
                    "cue failed; test it in Settings before the next night"

                "journal" ->
                    "capture evidence write failed; monitoring interrupted"

                else ->
                    "capture failed; check the end reason and recordings, then restart DreamLog"
            }
        }
        .toList()

private fun heartbeatDiagnostic(record: NightRecord): String? {
    val night = record.night
    val recoveryClose = hasUnconfirmedRecoveryClose(record)
    if (recoveryClose && night.lastHeartbeatEpochMillis == null) {
        return "No heartbeat before recovery; listening duration unconfirmed."
    }
    val heartbeatAt = night.lastHeartbeatEpochMillis ?: return null
    val formattedHeartbeat = HistoryFormatters.dateTime(
        heartbeatAt,
        night.lastHeartbeatUtcOffsetSeconds,
    )
    if (
        night.interrupted ||
        recoveryClose ||
        night.captureState == NightCaptureState.INTERRUPTED ||
        night.captureState == NightCaptureState.RECOVERY_REQUIRED
    ) {
        return "Last confirmed heartbeat: $formattedHeartbeat. Later listening is unconfirmed."
    }

    return null
}

private fun hasUnconfirmedRecoveryClose(record: NightRecord): Boolean =
    record.night.endReason == "process_interrupted" ||
        record.night.endReason == "recovery_required" ||
        record.night.captureState == NightCaptureState.RECOVERY_REQUIRED ||
        record.events.any { it.type == "capture_recovered" }

private fun expiredAudioText(record: NightRecord): String {
    val expiredSessionIds = record.sessions
        .filter { it.audioState == AudioEvidenceState.EXPIRED }
        .mapTo(mutableSetOf()) { it.sessionId }
    val savedTranscriptCount = record.transcripts.count {
        it.transcript.sessionId in expiredSessionIds &&
            it.transcript.state == ProcessingState.COMPLETE
    }
    return when {
        expiredSessionIds.isEmpty() -> "Audio expired"
        savedTranscriptCount == expiredSessionIds.size ->
            "Audio expired; transcript retained"

        savedTranscriptCount == 0 ->
            "Audio expired; no transcript"

        else ->
            "Audio expired; transcripts retained for $savedTranscriptCount/${expiredSessionIds.size} sessions"
    }
}

private fun deletedAudioText(record: NightRecord): String {
    val deletedSessionIds = record.sessions
        .filter { it.audioState == AudioEvidenceState.DELETED }
        .mapTo(mutableSetOf()) { it.sessionId }
    val savedTranscriptCount = record.transcripts.count {
        it.transcript.sessionId in deletedSessionIds &&
            it.transcript.state == ProcessingState.COMPLETE
    }
    return when {
        deletedSessionIds.isEmpty() -> "Deleted"
        savedTranscriptCount == deletedSessionIds.size ->
            "Audio deleted; transcript retained"

        savedTranscriptCount == 0 ->
            "Audio deleted; no transcript"

        else ->
            "Audio deleted; transcripts retained for $savedTranscriptCount/${deletedSessionIds.size} sessions"
    }
}

private data class SilencingInterval(
    val startedAtEpochMillis: Long,
    val startedUtcOffsetSeconds: Int,
    val endedAtEpochMillis: Long?,
    val endedUtcOffsetSeconds: Int?,
)

internal fun decodePersistedEventAttributes(encoded: String): Map<String, String> = runCatching {
    if (encoded.isBlank()) {
        emptyMap()
    } else {
        encoded.split(';').associate { item ->
            val separator = item.indexOf('=')
            require(separator > 0)
            item.substring(0, separator) to String(
                Base64.getUrlDecoder().decode(item.substring(separator + 1)),
                StandardCharsets.UTF_8,
            )
        }
    }
}.getOrDefault(emptyMap())

private fun processingText(
    state: String,
    failure: String?,
    waitingText: String,
    failedRetryable: Boolean = true,
    failedArtifactText: String? = null,
): String =
    when (state) {
        ProcessingState.NOT_STARTED,
        ProcessingState.WAITING_FOR_TRANSCRIPTION,
        -> waitingText

        ProcessingState.RUNNING -> "In progress"
        ProcessingState.COMPLETE -> "Complete"
        ProcessingState.FAILED -> buildList {
            add("Failed")
            failure?.let { add(humanizeReason(it)) }
            failedArtifactText?.let(::add)
            if (failedRetryable) add("retry available")
        }.joinToString(" · ")

        else -> state.replace('_', ' ')
    }

internal fun transcriptionProcessingText(record: NightRecord): String {
    val retainedSessionIds = record.sessions
        .asSequence()
        .filter { it.audioState == AudioEvidenceState.RETAINED }
        .mapTo(mutableSetOf()) { it.sessionId }
    val failedAudioRetained = record.transcripts.any {
        it.transcript.state == ProcessingState.FAILED &&
            it.transcript.sessionId in retainedSessionIds
    }
    return processingText(
        state = record.night.transcriptionState,
        failure = record.night.transcriptionFailure,
        waitingText = "Not started",
        failedRetryable = failedAudioRetained,
        failedArtifactText = if (record.night.transcriptionState == ProcessingState.FAILED) {
            if (failedAudioRetained) {
                "Source audio retained"
            } else {
                "Source audio unavailable; can't retry"
            }
        } else {
            null
        },
    )
}

internal fun enrichmentProcessingText(
    state: String,
    failure: String?,
    transcriptionState: String? = null,
    hasNoRecognizedSpeech: Boolean = false,
): String =
    processingText(
        state = state,
        failure = persistedEnrichmentFailureDisplayDetail(failure),
        waitingText = if (transcriptionState == ProcessingState.COMPLETE) {
            if (hasNoRecognizedSpeech) NO_SPEECH_RECOGNIZED_TEXT else "Ready to enrich"
        } else {
            "Waiting for transcription"
        },
        failedRetryable = persistedEnrichmentFailureIsRetryable(failure),
        failedArtifactText = enrichmentFailureArtifactText(state, failure),
    )

internal fun enrichmentFailureArtifactText(state: String, failure: String?): String? =
    if (state != ProcessingState.FAILED) {
        null
    } else {
        when (persistedEnrichmentFailureCode(failure)) {
            EnrichmentFailureCode.RAW_SOURCE_UNAVAILABLE.persistedValue ->
                "Raw transcript incomplete or unavailable"

            EnrichmentFailureCode.INVALID_SOURCE.persistedValue ->
                "Invalid raw transcript source"

            else -> "Raw transcript retained"
        }
    }

private fun nightStatus(
    captureState: String,
    interrupted: Boolean,
): String =
    when (captureState) {
        NightCaptureState.STARTING -> "Starting"
        NightCaptureState.ACTIVE -> "Active"
        NightCaptureState.RECOVERY_REQUIRED -> "Recovery required"
        NightCaptureState.INTERRUPTED -> "Interrupted"
        NightCaptureState.ENDED -> if (interrupted) "Interrupted" else "Completed"
        else -> captureState.replace('_', ' ')
    }

internal fun audioEvidenceText(state: String, hasSavedTranscript: Boolean? = null): String =
    when (state) {
        AudioEvidenceState.RETAINED -> "Retained"
        AudioEvidenceState.MISSING -> "Missing"
        AudioEvidenceState.CORRUPT -> "Corrupt"
        AudioEvidenceState.PENDING_RECOVERY -> "Pending recovery"
        AudioEvidenceState.DELETED -> when (hasSavedTranscript) {
            true -> "Audio deleted; transcript retained"
            false -> "Audio deleted; no transcript"
            null -> "Deleted"
        }
        AudioEvidenceState.EXPIRED -> if (hasSavedTranscript == true) {
            "Audio expired; transcript retained"
        } else {
            "Audio expired; no transcript"
        }
        else -> state.replace('_', ' ')
    }

private fun playbackButtonText(
    state: RawSessionPlaybackState,
    isTarget: Boolean,
): String {
    if (!isTarget) return "Play raw audio"
    return when (state.phase) {
        RawSessionPlaybackPhase.PREPARING -> "Cancel playback"
        RawSessionPlaybackPhase.PLAYING -> "Pause"
        RawSessionPlaybackPhase.PAUSED -> "Resume"
        RawSessionPlaybackPhase.COMPLETED -> "Play again"
        else -> "Play raw audio"
    }
}

private fun humanizeReason(reason: String): String =
    when (reason) {
        "owner_ended", "night_ended" -> "Ended manually"
        "process_interrupted" -> "App interrupted"
        "capture_failed" -> "Capture failed"
        "audio_initialization_failed" -> "Microphone initialization failed"
        "storage_reserve_reached" -> "Storage reserve reached"
        "safety_stop" -> "14-hour safety stop"
        "service_interrupted" -> "Listening service interrupted"
        "microphone_silenced" -> "Microphone silenced"
        "audio_gap" -> "Audio gap"
        "write_failed" -> "Audio write failed"
        "start_unconfirmed" -> "Listening start unconfirmed"
        "recovery_required" -> "Recovery required"
        else -> reason.replace('_', ' ')
    }

private fun playbackOffset(sourceStartMillis: Long): String {
    val totalSeconds = sourceStartMillis.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private const val TRANSCRIPT_SEGMENTS_PER_PLAYBACK_ROW = 6
