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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wivy.dreamlog.capture.CueAudioPreflight
import com.wivy.dreamlog.capture.SessionIncompleteReason
import com.wivy.dreamlog.capture.captureMicrophoneSilencedState
import com.wivy.dreamlog.enrichment.persistence.persistedEnrichmentFailureDisplayDetail
import com.wivy.dreamlog.enrichment.persistence.persistedEnrichmentFailureIsRetryable
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.HistoryFormatters
import com.wivy.dreamlog.history.NightCaptureState
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

@Composable
fun MorningSummaryCard(
    record: NightRecord,
    onOpenNight: (String) -> Unit,
) {
    val night = record.night
    HistoryCard(title = "Morning summary") {
        SummaryRow("Night", HistoryFormatters.date(night.displayDate))
        SummaryRow("Status", nightStatus(night.captureState, night.interrupted))
        SummaryRow(
            "Monitoring",
            monitoringRange(record),
        )
        SummaryRow(
            "Wakewords",
            wakewordCountText(record),
        )
        night.endReason?.let { SummaryRow("End reason", humanizeReason(it)) }
        if (
            night.lastHeartbeatEpochMillis != null &&
            (
                night.interrupted ||
                    night.hadMicrophoneSilencing ||
                    night.hadAudioGap
                )
        ) {
            SummaryRow(
                "Last heartbeat",
                HistoryFormatters.dateTime(
                    night.lastHeartbeatEpochMillis,
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
            enrichmentProcessingText(night.enrichmentState, night.enrichmentFailure),
        )

        if (night.reportedSessionCount == 0) {
            SupportingText(
                "No wake-triggered narratives were captured. DreamLog did not store " +
                    "full-night room audio.",
            )
        }
        captureEvidence(record)?.let { WarningText(it) }
        night.importWarning?.let { WarningText(it) }

        OutlinedButton(
            onClick = { onOpenNight(night.nightId) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open night details")
        }
    }
}

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
            fontWeight = FontWeight.SemiBold,
        )
        when {
            loading -> SupportingText("Loading private local history…")

            error != null -> {
                WarningText(error)
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry history")
                }
            }

            else -> {
                if (warningCount > 0) {
                    WarningText(
                        "Some capture evidence could not be fully reconciled automatically.",
                    )
                }
                if (nights.isEmpty()) {
                    SupportingText("Completed and interrupted nights will appear here.")
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
    reprocessRunning: Boolean = false,
    reprocessMessage: String? = null,
    onTranscribeNight: (String) -> Unit = {},
    onResumeTranscription: (String) -> Unit = {},
    onRetryTranscription: (String, String) -> Unit = { _, _ -> },
    onRetranscribe: (String, String) -> Unit = { _, _ -> },
    onReprocessNight: (String) -> Unit = {},
    onExportNight: (String) -> Unit = {},
    onDeleteNightRawAudio: (String, (String?) -> Unit) -> Unit = { _, completion ->
        completion("Raw-audio deletion is unavailable.")
    },
    onDeleteWholeNight: (String, (String?) -> Unit) -> Unit = { _, completion ->
        completion("Whole-night deletion is unavailable.")
    },
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var playbackState by remember { mutableStateOf(RawSessionPlaybackState()) }
    val player = remember(context) {
        RawSessionPlayer(context) { playbackState = it }
    }
    var technicalDetailsExpanded by remember(record?.night?.nightId) { mutableStateOf(false) }
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
                    OutlinedButton(onClick = onBack) {
                        Text("Back")
                    }
                    Text(
                        text = record?.night?.displayDate ?: "Night unavailable",
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(
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
                            "This night is no longer present in private local history.",
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
                        NightDetailSummary(record)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = HistoryFormatters.date(night.displayDate),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = dreamCountText(record),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = status,
                color = if (historyStatusIsError(record)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.labelLarge,
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

    HistoryCard(title = "Night outcome") {
        SummaryRow("Night", HistoryFormatters.date(record.night.displayDate))
        SummaryRow(
            "Dreams",
            when {
                record.dreams.size == 1 -> "1 generated dream"
                record.dreams.isNotEmpty() -> "${record.dreams.size} generated dreams"
                record.night.enrichmentState == ProcessingState.COMPLETE ->
                    "No generated dreams identified"
                else -> "Not generated yet"
            },
        )
        when {
            transcriptionRunning -> SupportingText(
                "Transcribing $completedCount/${record.sessions.size}. You may turn off the " +
                    "screen; follow progress in the ongoing notification.",
            )

            resumeAction != null -> {
                WarningText(
                    "Transcription stopped before every retained session finished. Resume to " +
                        "continue from the preserved result.",
                )
                Button(
                    onClick = onResumeTranscription,
                    enabled = transcriptionAvailable && !actionsBlocked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Resume transcription — ${resumeAction.completedCount} of " +
                            "${resumeAction.totalCount} complete",
                    )
                }
                if (!transcriptionAvailable) {
                    SupportingText("Set up the local transcription model in Settings first.")
                }
            }

            record.night.enrichmentState == ProcessingState.FAILED -> WarningText(
                "Dream generation failed. Return Home and choose Enrich to retry; the completed " +
                    "raw transcript was preserved.",
            )

            captureEvidence(record) != null -> WarningText(
                "A capture issue was recorded. Technical details contain the preserved evidence.",
            )

            record.dreams.isNotEmpty() ->
                SupportingText("Open a dream above to view its generated text and sources.")

            record.night.enrichmentState == ProcessingState.COMPLETE ->
                SupportingText("Processing completed without identifying a dream.")

            else -> SupportingText("Finish the morning processing steps from Home.")
        }
    }
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
private fun NightDetailSummary(record: NightRecord) {
    val night = record.night
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
            if (record.dreams.size == 1) "1 processed dream" else "${record.dreams.size} processed dreams",
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
            enrichmentProcessingText(night.enrichmentState, night.enrichmentFailure),
        )
        captureEvidence(record)?.let { WarningText(it) }
        night.importWarning?.let { WarningText(it) }
        if (night.reportedSessionCount == 0) {
            SupportingText(
                "This night is empty because no approved wake phrase produced a retained " +
                    "narrative. Idle room audio was never stored.",
            )
        }
    }
}

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
            fontWeight = FontWeight.SemiBold,
        )
        if (playbackBlocked) {
            WarningText("Playback is disabled while capture or local processing is active.")
        }
        if (record.sessions.isEmpty()) {
            SupportingText("No wake-triggered session audio belongs to this night.")
        } else {
            record.sessions.forEachIndexed { index, session ->
                SessionCard(
                    index = index,
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
            fontWeight = FontWeight.SemiBold,
        )
        if (transcriptionRunning) {
            SupportingText(
                "Local transcription may continue with the screen off; follow progress in the " +
                    "ongoing notification.",
            )
        }
        transcriptionMessage?.let { SupportingText(it) }

        if (record.sessions.isEmpty()) {
            SupportingText("This night has no retained narrative to transcribe.")
        } else if (record.transcripts.isEmpty() && !hasUnstartedRetainedSession) {
            SupportingText("No retained session audio is available for transcription.")
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
                Text(if (transcriptionRunning) "Transcribing…" else "Transcribe this night")
            }
            if (!transcriptionAvailable) {
                SupportingText("Install the local transcription model from the home screen first.")
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Session ${(session?.captureOrder ?: index) + 1}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SummaryRow("State", processingText(value.state, value.failureDetail, "Not started"))
            when (value.state) {
                ProcessingState.COMPLETE -> {
                    val rawText = value.rawText.orEmpty()
                    Text(
                        text = rawText.ifBlank { "No speech was recognized." },
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
                        Text("Re-transcribe this session")
                    }
                    if (ownerChangesProtected) {
                        SupportingText(
                            "Re-transcription is disabled because owner edits or deletions depend " +
                                "on this raw evidence. DreamLog will not replace them silently.",
                        )
                    }
                }

                ProcessingState.FAILED -> {
                    WarningText(value.failureDetail ?: "Local transcription failed.")
                    if (session?.audioState == AudioEvidenceState.RETAINED) {
                        Button(
                            onClick = onRetry,
                            enabled = retryEnabled && !actionsBlocked,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Retry this session")
                        }
                    } else {
                        SupportingText(
                            "Retry is unavailable because this session no longer has retained " +
                                "source audio.",
                        )
                    }
                }

                ProcessingState.RUNNING ->
                    SupportingText(
                        "Transcribing locally. You may turn off the screen and follow the ongoing " +
                            "notification.",
                    )

                else -> SupportingText("Waiting for local transcription.")
            }
        }
    }
}

@Composable
private fun SessionCard(
    index: Int,
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Session ${index + 1}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
                session.incompleteReason?.let {
                    "Incomplete · ${humanizeReason(it)}"
                } ?: "Complete",
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider()
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
            fontWeight = FontWeight.Medium,
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
            "Raw audio remains in DreamLog's app-private local storage. Playback is visible " +
                "and user initiated.",
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

internal fun dreamCountText(record: NightRecord): String =
    if (record.dreams.size == 1) "1 dream" else "${record.dreams.size} dreams"

internal fun wakewordCountText(record: NightRecord): String =
    if (record.sessions.size == 1) "1 wakeword" else "${record.sessions.size} wakewords"

internal fun rawAudioText(record: NightRecord): String =
    when (record.night.rawAudioState) {
        RawAudioState.NONE -> "No triggered session audio"
        RawAudioState.RETAINED ->
            "${record.retainedSessionCount} retained app-private file(s)"

        RawAudioState.PARTIAL ->
            "${record.retainedSessionCount} retained · " +
                "${record.unavailableSessionCount} unavailable"

        RawAudioState.PENDING_RECOVERY -> "Recovery required before playback"
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
    val evidence = morningDiagnostics(record)
    return evidence.takeIf(List<String>::isNotEmpty)?.joinToString(
        prefix = "Capture evidence: ",
        separator = " · ",
    )
}

internal fun historyProcessingFailure(record: NightRecord): String? = when {
    record.night.transcriptionState == ProcessingState.FAILED -> "Transcription failed"
    record.night.enrichmentState == ProcessingState.FAILED -> "Enrichment failed"
    else -> null
}

internal fun historyStatus(record: NightRecord): String = when {
    record.night.transcriptionState == ProcessingState.FAILED -> "Transcription failed"
    record.night.enrichmentState == ProcessingState.FAILED -> "Enrichment failed"
    captureEvidence(record) != null -> "Capture issue"
    record.night.captureState == NightCaptureState.STARTING -> "Starting"
    record.night.captureState == NightCaptureState.ACTIVE -> "Active"
    record.night.transcriptionState == ProcessingState.RUNNING -> "Transcribing"
    record.night.enrichmentState == ProcessingState.RUNNING -> "Enriching"
    record.night.enrichmentState == ProcessingState.COMPLETE -> "Complete"
    record.night.transcriptionState == ProcessingState.COMPLETE -> "Ready to enrich"
    record.sessions.isEmpty() &&
        record.night.captureState == NightCaptureState.ENDED &&
        record.night.enrichmentState == ProcessingState.WAITING_FOR_TRANSCRIPTION ->
        "Ready to enrich"

    else -> "Processing"
}

internal fun historyStatusIsError(record: NightRecord): Boolean =
    record.night.transcriptionState == ProcessingState.FAILED ||
        record.night.enrichmentState == ProcessingState.FAILED ||
        captureEvidence(record) != null

internal fun hasOwnerFacingCaptureIssue(record: NightRecord): Boolean {
    val night = record.night
    val persistedIncompleteSessionCount = record.sessions.count {
        it.incompleteReason != null
    }
    return night.interrupted ||
        night.captureState == NightCaptureState.INTERRUPTED ||
        night.captureState == NightCaptureState.RECOVERY_REQUIRED ||
        record.events.any { it.type == "capture_failure" } ||
        record.sessions.any { session ->
            session.audioState == AudioEvidenceState.MISSING ||
                session.audioState == AudioEvidenceState.CORRUPT ||
                session.audioState == AudioEvidenceState.PENDING_RECOVERY ||
                (
                    session.incompleteReason != null &&
                        session.incompleteReason != SessionIncompleteReason.NIGHT_ENDED
                    )
        } ||
        night.reportedIncompleteSessionCount > persistedIncompleteSessionCount ||
        night.reportedSessionCount > record.sessions.size
}

internal fun morningDiagnostics(record: NightRecord): List<String> {
    if (!hasOwnerFacingCaptureIssue(record)) return emptyList()

    val night = record.night
    return buildList {
        if (
            night.interrupted ||
            night.captureState == NightCaptureState.INTERRUPTED ||
            night.captureState == NightCaptureState.RECOVERY_REQUIRED
        ) {
            add("monitoring was interrupted; review the end reason and incomplete sessions below")
        }
        val incompleteSessions = record.sessions.filter {
            it.incompleteReason != null &&
                it.incompleteReason != SessionIncompleteReason.NIGHT_ENDED
        }
        if (incompleteSessions.isNotEmpty()) {
            add(
                "${incompleteSessions.size} captured recollection(s) did not finish cleanly",
            )
        }
        val unavailableSessions = record.sessions.filter {
            it.audioState == AudioEvidenceState.MISSING ||
                it.audioState == AudioEvidenceState.CORRUPT ||
                it.audioState == AudioEvidenceState.PENDING_RECOVERY
        }
        if (unavailableSessions.isNotEmpty()) {
            add(
                "${unavailableSessions.size} captured recollection(s) have missing, corrupt, " +
                    "or unresolved source audio",
            )
        }
        val persistedIncompleteSessionCount = record.sessions.count {
            it.incompleteReason != null
        }
        if (night.reportedIncompleteSessionCount > persistedIncompleteSessionCount) {
            add("the night summary reports incomplete capture evidence that could not be matched")
        }
        if (night.reportedSessionCount > record.sessions.size) {
            add("the night summary reports captured recollection evidence that is missing")
        }
        captureFailureDiagnostics(record.events).forEach(::add)
        silencingDiagnostic(record)?.let(::add)
        if (record.events.any { it.type == "audio_gap" && !it.sessionId.isNullOrBlank() }) {
            add("an audio gap was observed during an affected recollection")
        }
        heartbeatDiagnostic(record)?.let(::add)
    }
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
            } ?: "from $start; no recovery time was confirmed"
        }
    }
    val retainedAudio = if (
        record.sessions.any {
            it.finalizedAtEpochMillis != null &&
                it.audioState == AudioEvidenceState.RETAINED
        }
    ) {
        " Completed session audio was preserved."
    } else {
        ""
    }
    return "Android reported DreamLog's microphone input as silenced $rangeText. " +
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
                    "capture reached the protected storage reserve; free device storage " +
                        "before the next night, and review any finalized audio below"

                "audio_write" ->
                    "DreamLog could not continue writing capture audio; check available " +
                        "storage, and treat the affected narration as possibly incomplete"

                "initialization" ->
                    "the on-device capture engine could not start; close other microphone " +
                        "recorders, test the cue, refresh the start checks, and retry"

                "audio_read" ->
                    "DreamLog could not continue reading microphone audio; review any finalized " +
                        "audio below, then restart DreamLog before the next night"

                "cue_playback" ->
                    "the acknowledgement cue could not be played; review any finalized audio " +
                        "below, then test the cue in Settings before the next night"

                "journal" ->
                    "capture evidence could not be written reliably; monitoring was interrupted " +
                        "and finalized sessions remain listed below"

                else ->
                    "capture failed unexpectedly; review the end reason and any finalized audio " +
                        "below, then restart DreamLog before the next night"
            }
        }
        .toList()

private fun heartbeatDiagnostic(record: NightRecord): String? {
    val night = record.night
    val recoveryClose = hasUnconfirmedRecoveryClose(record)
    if (recoveryClose && night.lastHeartbeatEpochMillis == null) {
        return "No heartbeat was recorded before recovery. DreamLog cannot confirm how long " +
            "listening continued after the start time."
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
        return "The last confirmed heartbeat was $formattedHeartbeat. Monitoring may have " +
            "continued afterward, but no later heartbeat was recorded."
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
            "Audio expired; saved transcript text remains"

        savedTranscriptCount == 0 ->
            "Audio expired before transcription; no transcript was saved"

        else ->
            "Audio expired; saved transcript text remains for $savedTranscriptCount of " +
                "${expiredSessionIds.size} sessions"
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
        deletedSessionIds.isEmpty() -> "Deleted by owner"
        savedTranscriptCount == deletedSessionIds.size ->
            "Deleted by owner; saved transcript text remains"

        savedTranscriptCount == 0 ->
            "Deleted by owner before transcription; no transcript was saved"

        else ->
            "Deleted by owner; saved transcript text remains for $savedTranscriptCount of " +
                "${deletedSessionIds.size} sessions"
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
            if (failedRetryable) add("retry from this night")
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
                "Retained source audio remains available"
            } else {
                "Source audio for the failed session is unavailable; retry cannot run"
            }
        } else {
            null
        },
    )
}

internal fun enrichmentProcessingText(state: String, failure: String?): String =
    processingText(
        state = state,
        failure = persistedEnrichmentFailureDisplayDetail(failure),
        waitingText = "Waiting for transcription",
        failedRetryable = persistedEnrichmentFailureIsRetryable(failure),
        failedArtifactText = if (state == ProcessingState.FAILED) {
            "The raw transcript was preserved"
        } else {
            null
        },
    )

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
        AudioEvidenceState.RETAINED -> "Retained locally"
        AudioEvidenceState.MISSING -> "Missing"
        AudioEvidenceState.CORRUPT -> "Corrupt"
        AudioEvidenceState.PENDING_RECOVERY -> "Pending recovery"
        AudioEvidenceState.DELETED -> when (hasSavedTranscript) {
            true -> "Deleted by owner; saved transcript text remains"
            false -> "Deleted by owner before transcription; no transcript was saved"
            null -> "Deleted by owner"
        }
        AudioEvidenceState.EXPIRED -> if (hasSavedTranscript == true) {
            "Audio expired; saved transcript text remains"
        } else {
            "Audio expired before transcription; no transcript was saved"
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
        "owner_ended", "night_ended" -> "Ended by owner"
        "process_interrupted" -> "App process interrupted"
        "capture_failed" -> "Capture failed"
        "audio_initialization_failed" -> "Microphone initialization failed"
        "storage_reserve_reached" -> "Protected storage reserve reached"
        "safety_stop" -> "14-hour safety stop"
        "service_interrupted" -> "Listening service interrupted"
        "microphone_silenced" -> "Microphone silenced"
        "audio_gap" -> "Audio gap"
        "write_failed" -> "Audio write failed"
        "start_unconfirmed" -> "Listening start was not confirmed"
        "recovery_required" -> "Interrupted night needs recovery"
        else -> reason.replace('_', ' ')
    }

private fun playbackOffset(sourceStartMillis: Long): String {
    val totalSeconds = sourceStartMillis.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private const val TRANSCRIPT_SEGMENTS_PER_PLAYBACK_ROW = 6
