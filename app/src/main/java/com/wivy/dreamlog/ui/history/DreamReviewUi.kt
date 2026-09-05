package com.wivy.dreamlog.ui.history

import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wivy.dreamlog.capture.CueAudioPreflight
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamKind
import com.wivy.dreamlog.history.DreamRecord
import com.wivy.dreamlog.history.DreamSourceRole
import com.wivy.dreamlog.history.HistoryFormatters
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightRecord
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.playback.DreamSourcePlaybackPhase
import com.wivy.dreamlog.playback.DreamSourcePlaybackPlan
import com.wivy.dreamlog.playback.DreamSourcePlaybackState
import com.wivy.dreamlog.playback.DreamSourcePlayer
import com.wivy.dreamlog.playback.buildDreamSourcePlaybackPlan

@Composable
fun ProcessedDreamSection(
    record: NightRecord,
    onOpenDream: (String) -> Unit,
) {
    val mediaPlaybackSessionIds = mediaPlaybackActiveAtWakeSessionIds(record.events)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Dreams",
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            if (record.dreams.isNotEmpty()) {
                Text(
                    text = dreamCountText(record),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        when {
            record.dreams.isNotEmpty() -> {
                record.dreams.forEachIndexed { index, dream ->
                    DreamCard(
                        dream = dream,
                        index = index,
                        record = record,
                        mediaPlaybackActiveAtWake =
                            dream.hasMediaPlaybackActiveAtWake(mediaPlaybackSessionIds),
                        onOpen = { onOpenDream(dream.dream.dreamId) },
                    )
                }
            }

            record.night.enrichmentState == ProcessingState.COMPLETE ->
                SupportingDreamText("No dreams identified.")

            record.night.enrichmentState == ProcessingState.FAILED ->
                SupportingDreamText(
                    "Enrichment failed. See the raw transcript in Technical details.",
                    warning = true,
                )

            record.night.enrichmentState in setOf(
                ProcessingState.NOT_STARTED,
                ProcessingState.WAITING_FOR_TRANSCRIPTION,
            ) && record.hasNoRecognizedSpeech -> SupportingDreamText(NO_SPEECH_RECOGNIZED_TEXT)

            else -> SupportingDreamText(
                "Dreams appear after enrichment.",
            )
        }
    }
}

@Composable
private fun DreamCard(
    dream: DreamRecord,
    index: Int,
    record: NightRecord,
    mediaPlaybackActiveAtWake: Boolean,
    onOpen: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dreamDisplayTitle(dream, index),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (dream.dream.ownerEdited) {
                    Text(
                        text = "Edited",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            dreamReviewStatusText(dream)?.let { status ->
                SupportingDreamText(status, warning = true)
            }
            if (mediaPlaybackActiveAtWake) {
                SupportingDreamText(POSSIBLE_MEDIA_FALSE_WAKE_MESSAGE, warning = true)
            }
            dreamNarrationDateTimes(dream, record).takeIf(List<String>::isNotEmpty)?.let {
                SupportingDreamText("Narrated ${it.joinToString()}")
            }
            Text(
                text = dream.dream.currentText,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Review dream")
            }
        }
    }
}

internal fun dreamDisplayTitle(dream: DreamRecord, index: Int): String =
    dream.dream.currentTitle
        ?.takeIf(String::isNotBlank)
        ?: dream.dream.generatedTitle?.takeIf(String::isNotBlank)
        ?: if (dream.dream.kind == DreamKind.FRAGMENT) {
            "Fragment ${index + 1}"
        } else {
            "Dream ${index + 1}"
        }

internal fun dreamReviewStatusText(dream: DreamRecord): String? = when {
    dream.dream.kind == DreamKind.FRAGMENT && dream.dream.isUncertain -> "Uncertain fragment"
    dream.dream.kind == DreamKind.FRAGMENT -> "Fragment"
    dream.dream.isUncertain -> "Uncertain details"
    else -> null
}

internal fun dreamDraftHasChanges(
    dream: DreamRecord,
    titleDraft: String,
    bodyDraft: String,
): Boolean =
    titleDraft.trim().takeIf(String::isNotEmpty) != dream.dream.currentTitle ||
        bodyDraft != dream.dream.currentText

@Composable
fun DreamDetailScreen(
    record: NightRecord?,
    dreamId: String,
    captureActive: Boolean,
    localProcessingActive: Boolean,
    archiveMutationRunning: Boolean,
    onBack: () -> Unit,
    onSaveDream: (String, String?, String, (String?) -> Unit) -> Unit,
    onDeleteDream: (String, (String?) -> Unit) -> Unit,
    onRestoreDream: (String, (String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val currentDream = record?.dreams?.firstOrNull { it.dream.dreamId == dreamId }
    var deletedSnapshot by remember(dreamId) { mutableStateOf<DreamRecord?>(null) }
    var recentlyDeleted by remember(dreamId) { mutableStateOf(false) }
    val displayedDream = currentDream ?: deletedSnapshot
    val mediaPlaybackSessionIds = record?.let { mediaPlaybackActiveAtWakeSessionIds(it.events) }
        .orEmpty()
    var playbackState by remember { mutableStateOf(DreamSourcePlaybackState()) }
    val player = remember(context) {
        DreamSourcePlayer(context) { playbackState = it }
    }
    var editing by remember(dreamId) { mutableStateOf(false) }
    var titleDraft by remember(dreamId) {
        mutableStateOf(displayedDream?.dream?.currentTitle.orEmpty())
    }
    var bodyDraft by remember(dreamId) {
        mutableStateOf(displayedDream?.dream?.currentText.orEmpty())
    }
    var generatedExpanded by remember(dreamId) { mutableStateOf(false) }
    var sourceExpanded by remember(dreamId) { mutableStateOf(false) }
    var deleteConfirmationVisible by remember(dreamId) { mutableStateOf(false) }
    var discardChangesConfirmationVisible by remember(dreamId) { mutableStateOf(false) }
    var actionMessage by remember(dreamId) { mutableStateOf<String?>(null) }
    val mutationBlocked = captureActive || localProcessingActive || archiveMutationRunning
    val draftHasChanges = editing && displayedDream?.let {
        dreamDraftHasChanges(
            dream = it,
            titleDraft = titleDraft,
            bodyDraft = bodyDraft,
        )
    } == true
    val requestBack: () -> Unit = {
        when {
            draftHasChanges -> discardChangesConfirmationVisible = true
            editing -> {
                titleDraft = displayedDream?.dream?.currentTitle.orEmpty()
                bodyDraft = displayedDream?.dream?.currentText.orEmpty()
                editing = false
            }

            else -> onBack()
        }
    }

    BackHandler(enabled = editing, onBack = requestBack)

    DisposableEffect(player, activity) {
        activity?.setVolumeControlStream(AudioManager.STREAM_MUSIC)
        onDispose {
            player.release()
            activity?.setVolumeControlStream(CueAudioPreflight.volumeControlStream())
        }
    }
    LaunchedEffect(
        captureActive,
        localProcessingActive,
        archiveMutationRunning,
        currentDream?.dream?.dreamId,
        record?.night?.rawAudioState,
    ) {
        if (mutationBlocked || currentDream == null) player.stop()
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
                    TextButton(onClick = requestBack) { Text("Back") }
                    Text(
                        text = if (displayedDream != null) "Dream" else "Dream unavailable",
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }

            when {
                record == null -> item {
                    DreamReviewCard("Dream unavailable") {
                        SupportingDreamText(
                            "Night not found.",
                        )
                    }
                }

                recentlyDeleted && currentDream == null && deletedSnapshot != null -> item {
                    DreamReviewCard("Dream deleted") {
                        SupportingDreamText(
                            "Recordings remain until expiry.",
                        )
                        Button(
                            onClick = {
                                onRestoreDream(dreamId) { error ->
                                    if (error == null) {
                                        recentlyDeleted = false
                                        deletedSnapshot = null
                                        actionMessage = "Dream restored."
                                    } else {
                                        actionMessage = error
                                    }
                                }
                            },
                            enabled = !mutationBlocked,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Undo")
                        }
                    }
                }

                displayedDream == null -> item {
                    DreamReviewCard("Dream unavailable") {
                        SupportingDreamText(
                            "Dream not found.",
                        )
                    }
                }

                else -> {
                    if (displayedDream.hasMediaPlaybackActiveAtWake(mediaPlaybackSessionIds)) {
                        item {
                            DreamReviewCard("Possible false wake") {
                                SupportingDreamText(
                                    POSSIBLE_MEDIA_FALSE_WAKE_MESSAGE,
                                    warning = true,
                                )
                            }
                        }
                    }
                    item {
                        DreamEditCard(
                            dream = displayedDream,
                            narrationDateTimes = dreamNarrationDateTimes(
                                displayedDream,
                                record,
                            ),
                            editing = editing,
                            titleDraft = titleDraft,
                            bodyDraft = bodyDraft,
                            generatedExpanded = generatedExpanded,
                            mutationBlocked = mutationBlocked,
                            onTitleChanged = { titleDraft = it },
                            onBodyChanged = { bodyDraft = it },
                            onToggleGenerated = { generatedExpanded = !generatedExpanded },
                            onStartEditing = {
                                titleDraft = displayedDream.dream.currentTitle.orEmpty()
                                bodyDraft = displayedDream.dream.currentText
                                actionMessage = null
                                editing = true
                            },
                            onCancelEditing = {
                                titleDraft = displayedDream.dream.currentTitle.orEmpty()
                                bodyDraft = displayedDream.dream.currentText
                                editing = false
                            },
                            onSave = {
                                onSaveDream(
                                    dreamId,
                                    titleDraft,
                                    bodyDraft,
                                ) { error ->
                                    if (error == null) {
                                        editing = false
                                        actionMessage = "Saved."
                                    } else {
                                        actionMessage = error
                                    }
                                }
                            },
                        )
                    }
                    item {
                        DreamSourceCard(
                            record = record,
                            dream = displayedDream,
                            playbackState = playbackState,
                            sourceExpanded = sourceExpanded,
                            blocked = mutationBlocked,
                            captureActive = captureActive,
                            onToggleSource = { sourceExpanded = !sourceExpanded },
                            onPlayOrPause = { plan ->
                                player.playOrPause(
                                    dreamId = dreamId,
                                    plan = plan,
                                    captureActive = mutationBlocked,
                                )
                            },
                        )
                    }
                    item {
                        DreamReviewCard("Delete dream") {
                            SupportingDreamText(
                                "Recordings remain.",
                            )
                            OutlinedButton(
                                onClick = { deleteConfirmationVisible = true },
                                enabled = !mutationBlocked,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Delete dream")
                            }
                        }
                    }
                }
            }
            actionMessage?.let { message ->
                item {
                    SupportingDreamText(
                        text = message,
                        warning = message != "Saved." &&
                            message != "Dream restored.",
                    )
                }
            }
        }
    }

    if (discardChangesConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { discardChangesConfirmationVisible = false },
            title = { Text("Discard changes?") },
            text = {
                Text("Unsaved changes will be lost.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardChangesConfirmationVisible = false
                        editing = false
                        onBack()
                    },
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { discardChangesConfirmationVisible = false }) {
                    Text("Keep editing")
                }
            },
        )
    }

    if (deleteConfirmationVisible && displayedDream != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmationVisible = false },
            title = { Text("Delete dream?") },
            text = {
                Text(
                    "Hide this dream from History and playback. Recordings remain. " +
                        "Undo is available immediately after deletion.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmationVisible = false
                        player.stop()
                        deletedSnapshot = displayedDream
                        onDeleteDream(dreamId) { error ->
                            if (error == null) {
                                recentlyDeleted = true
                                editing = false
                                actionMessage = null
                            } else {
                                deletedSnapshot = null
                                actionMessage = error
                            }
                        }
                    },
                    enabled = !mutationBlocked,
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmationVisible = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun DreamEditCard(
    dream: DreamRecord,
    narrationDateTimes: List<String>,
    editing: Boolean,
    titleDraft: String,
    bodyDraft: String,
    generatedExpanded: Boolean,
    mutationBlocked: Boolean,
    onTitleChanged: (String) -> Unit,
    onBodyChanged: (String) -> Unit,
    onToggleGenerated: () -> Unit,
    onStartEditing: () -> Unit,
    onCancelEditing: () -> Unit,
    onSave: () -> Unit,
) {
    DreamReviewCard(if (editing) "Edit dream" else null) {
        if (editing) {
            OutlinedTextField(
                value = titleDraft,
                onValueChange = onTitleChanged,
                label = { Text("Title (optional)") },
                enabled = !mutationBlocked,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = bodyDraft,
                onValueChange = onBodyChanged,
                label = { Text("Dream text") },
                enabled = !mutationBlocked,
                isError = bodyDraft.isBlank(),
                supportingText = if (bodyDraft.isBlank()) {
                    { Text("Enter dream text.") }
                } else {
                    null
                },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCancelEditing,
                    enabled = !mutationBlocked,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onSave,
                    enabled = !mutationBlocked && bodyDraft.isNotBlank() &&
                        dreamDraftHasChanges(dream, titleDraft, bodyDraft),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dream.dream.currentTitle?.takeIf(String::isNotBlank)
                        ?: "Untitled dream",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (dream.dream.ownerEdited) {
                    Text(
                        text = "Edited",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            dreamReviewStatusText(dream)?.let { status ->
                SupportingDreamText(status, warning = true)
            }
            if (narrationDateTimes.isNotEmpty()) {
                SupportingDreamText("Narrated ${narrationDateTimes.joinToString()}")
            }
            Text(dream.dream.currentText, style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = onStartEditing,
                enabled = !mutationBlocked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Edit dream")
            }
            if (dream.dream.ownerEdited) {
                OutlinedButton(
                    onClick = onToggleGenerated,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (generatedExpanded) "Hide generated text" else "Show generated text")
                }
                if (generatedExpanded) {
                    HorizontalDivider()
                    Text(
                        text = dream.dream.generatedTitle?.takeIf(String::isNotBlank)
                            ?: "Untitled dream",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = dream.dream.generatedText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SupportingDreamText(
                        "Generated original.",
                    )
                }
            }
        }
    }
}

@Composable
private fun DreamSourceCard(
    record: NightRecord,
    dream: DreamRecord,
    playbackState: DreamSourcePlaybackState,
    sourceExpanded: Boolean,
    blocked: Boolean,
    captureActive: Boolean,
    onToggleSource: () -> Unit,
    onPlayOrPause: (DreamSourcePlaybackPlan) -> Unit,
) {
    val plan = remember(record.sessions, dream.sourceSpans) {
        buildDreamSourcePlaybackPlan(
            nightId = record.night.nightId,
            sourceSpans = dream.sourceSpans,
            sessions = record.sessions,
        )
    }
    val availabilityMessage = (plan as? DreamSourcePlaybackPlan.Unavailable)?.message
    val sessionsById = record.sessions.associateBy { it.sessionId }
    DreamReviewCard("Source") {
        SupportingDreamText(
            if (dream.sourceSpans.size == 1) {
                "1 source range"
            } else {
                "${dream.sourceSpans.size} source ranges"
            },
        )
        Button(
            onClick = { onPlayOrPause(plan) },
            enabled = plan is DreamSourcePlaybackPlan.Ready && !blocked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(dreamPlaybackButtonText(playbackState, dream.dream.dreamId))
        }
        if (captureActive) {
            SupportingDreamText(
                "End listening to play audio.",
                warning = true,
            )
        } else if (blocked) {
            SupportingDreamText(
                "Playback unavailable during processing or an archive change.",
                warning = true,
            )
        } else if (availabilityMessage != null) {
            SupportingDreamText(availabilityMessage, warning = true)
        }
        if (playbackState.dreamId == dream.dream.dreamId) {
            playbackState.message?.let { SupportingDreamText(it, warning = true) }
            playbackState.currentSpanIndex?.let { index ->
                if (playbackState.phase in setOf(
                        DreamSourcePlaybackPhase.PREPARING,
                        DreamSourcePlaybackPhase.PLAYING,
                        DreamSourcePlaybackPhase.PAUSED,
                    )
                ) {
                    SupportingDreamText("Source ${index + 1} of ${playbackState.spanCount}")
                }
            }
        }
        OutlinedButton(
            onClick = onToggleSource,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (sourceExpanded) "Hide source transcript" else "Show source transcript")
        }
        if (sourceExpanded) {
            dream.sourceSpans.sortedBy { it.spanOrder }.forEach { span ->
                HorizontalDivider()
                val sessionNumber = sessionsById[span.sessionId]
                    ?.captureOrder
                    ?.plus(1)
                    ?.toString()
                    ?: "?"
                val session = sessionsById[span.sessionId]
                val capturedAt = sourceWallClockDateTime(session, span.sourceStartMillis)
                Text(
                    text = "Source ${span.spanOrder + 1} · Session $sessionNumber · " +
                        capturedAt?.let { "$it · " }.orEmpty() +
                        "${sourceRangeText(span.sourceStartMillis, span.sourceEndMillis)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                SupportingDreamText(sourceRoleText(span.role))
                Text(span.sourceText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal fun dreamPlaybackButtonText(
    state: DreamSourcePlaybackState,
    dreamId: String,
): String {
    if (state.dreamId != dreamId) return "Play source audio"
    return when (state.phase) {
        DreamSourcePlaybackPhase.PREPARING -> "Cancel playback"
        DreamSourcePlaybackPhase.PLAYING -> "Pause"
        DreamSourcePlaybackPhase.PAUSED -> "Resume"
        DreamSourcePlaybackPhase.COMPLETED -> "Play again"
        else -> "Play source audio"
    }
}

private fun sourceRoleText(role: String): String =
    when (role) {
        DreamSourceRole.NARRATIVE -> "Narrative"
        DreamSourceRole.ADDITION -> "Later addition"
        DreamSourceRole.CORRECTION -> "Later correction"
        else -> role.replace('_', ' ')
    }

private fun sourceRangeText(startMillis: Long, endMillis: Long): String =
    "${sourceOffset(startMillis)}–${sourceOffset(endMillis)}"

private fun sourceOffset(value: Long): String {
    val totalSeconds = value.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

internal fun dreamNarrationDateTimes(
    dream: DreamRecord,
    record: NightRecord,
): List<String> {
    val sessionsById = record.sessions.associateBy(CaptureSessionEntity::sessionId)
    return dream.sourceSpans
        .sortedBy { it.spanOrder }
        .mapNotNull { span ->
            sourceWallClockDateTime(sessionsById[span.sessionId], span.sourceStartMillis)
        }
        .distinct()
}

internal fun sourceWallClockDateTime(
    session: CaptureSessionEntity?,
    sourceStartMillis: Long,
): String? {
    val startedAt = session?.startedAtEpochMillis ?: return null
    val offset = session.startedUtcOffsetSeconds ?: return null
    val preRollMillis = session.preRollDurationMillis()
    val sourceEpochMillis = runCatching {
        Math.addExact(
            startedAt,
            Math.subtractExact(sourceStartMillis.coerceAtLeast(0L), preRollMillis),
        )
    }.getOrNull() ?: return null
    return HistoryFormatters.dateTime(sourceEpochMillis, offset)
        .takeUnless { it == "Unknown" }
}

private fun CaptureSessionEntity.preRollDurationMillis(): Long {
    val samples = preRollSampleCount ?: return 0L
    val rate = sampleRateHz?.takeIf { it > 0 } ?: return 0L
    return runCatching {
        Math.multiplyExact(samples.coerceAtLeast(0L), 1_000L) / rate
    }.getOrDefault(0L)
}

internal fun canReprocessNight(
    record: NightRecord,
    requiresRetainedAudio: Boolean = true,
): Boolean {
    return reprocessNightDataUnavailableReason(record, requiresRetainedAudio) == null
}

internal fun reprocessNightDataUnavailableReason(
    record: NightRecord,
    requiresRetainedAudio: Boolean = true,
): String? {
    if (record.hasProtectedDreamChanges) {
        return "Your edits or deletions prevent reprocessing."
    }
    if (record.sessions.isEmpty()) {
        return "No sessions to reprocess."
    }
    if (record.night.captureState !in setOf(
            NightCaptureState.ENDED,
            NightCaptureState.INTERRUPTED,
        )
    ) {
        return "End this night before reprocessing."
    }
    if (
        requiresRetainedAudio &&
        record.sessions.any { it.audioState != AudioEvidenceState.RETAINED }
    ) {
        return "Reprocessing needs raw audio for every session."
    }
    if (record.sessions.any { it.finalizedAtEpochMillis == null }) {
        return "Finish session recovery before reprocessing."
    }
    val completeSessionIds = record.transcripts
        .filter { it.transcript.state == ProcessingState.COMPLETE }
        .map { it.transcript.sessionId }
    if (
        completeSessionIds.size != record.sessions.size ||
        completeSessionIds.toSet() != record.sessions.map { it.sessionId }.toSet()
    ) {
        return "Reprocessing needs one completed transcript per session."
    }
    return null
}

@Composable
fun ManageNightDataCard(
    record: NightRecord,
    blocked: Boolean,
    reprocessUnavailableReason: String?,
    reprocessRequiresTranscription: Boolean,
    reprocessRunning: Boolean,
    reprocessMessage: String?,
    onReprocessNight: () -> Unit,
    onDeleteRawAudio: ((String?) -> Unit) -> Unit,
    onDeleteWholeNight: ((String?) -> Unit) -> Unit,
) {
    var confirmation by remember(record.night.nightId) {
        mutableStateOf<NightDeleteConfirmation?>(null)
    }
    var actionMessage by remember(record.night.nightId) { mutableStateOf<String?>(null) }
    val retainedAudio = record.sessions.any { it.audioState == AudioEvidenceState.RETAINED }
    val dataUnavailableReason = reprocessNightDataUnavailableReason(
        record = record,
        requiresRetainedAudio = reprocessRequiresTranscription,
    )
    val effectiveGlobalReason = reprocessUnavailableReason ?: if (blocked) {
        "Wait for capture, processing, or the archive change to finish."
    } else {
        null
    }

    DreamReviewCard("Manage night") {
        OutlinedButton(
            onClick = { confirmation = NightDeleteConfirmation.REPROCESS },
            enabled = dataUnavailableReason == null && effectiveGlobalReason == null &&
                !reprocessRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (reprocessRunning) {
                    if (reprocessRequiresTranscription) {
                        "Reprocessing…"
                    } else {
                        "Regrouping…"
                    }
                } else {
                    if (reprocessRequiresTranscription) {
                        "Reprocess night"
                    } else {
                        "Regroup dreams"
                    }
                },
            )
        }
        when {
            dataUnavailableReason != null -> SupportingDreamText(dataUnavailableReason)
            reprocessRunning -> Unit
            effectiveGlobalReason != null -> SupportingDreamText(effectiveGlobalReason)
        }
        reprocessMessage?.let { message ->
            SupportingDreamText(
                message,
                warning = !message.startsWith("Reprocessing complete"),
            )
        }
        OutlinedButton(
            onClick = { confirmation = NightDeleteConfirmation.RAW_AUDIO },
            enabled = retainedAudio && !blocked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete recordings")
        }
        if (retainedAudio) {
            SupportingDreamText("Keeps dreams and transcripts.")
        } else {
            SupportingDreamText("No recordings remain.")
        }
        OutlinedButton(
            onClick = { confirmation = NightDeleteConfirmation.WHOLE_NIGHT },
            enabled = !blocked,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete night")
        }
        actionMessage?.let { SupportingDreamText(it, warning = true) }
    }

    confirmation?.let { selected ->
        val rawAudioOnly = selected == NightDeleteConfirmation.RAW_AUDIO
        val reprocess = selected == NightDeleteConfirmation.REPROCESS
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = {
                Text(
                    when {
                        reprocess && reprocessRequiresTranscription -> "Reprocess night?"
                        reprocess -> "Regroup dreams?"
                        rawAudioOnly -> "Delete recordings?"
                        else -> "Delete night?"
                    },
                )
            },
            text = {
                Text(
                    if (reprocess && reprocessRequiresTranscription) {
                        "Replace transcripts and generated dreams using current models. " +
                            "Recordings remain. This can't be undone."
                    } else if (reprocess) {
                        "Replace generated dreams using the current model. Raw transcripts " +
                            "remain. This can't be undone."
                    } else if (rawAudioOnly) {
                        "Delete this night's recordings. Dreams, raw transcripts, and source " +
                            "text remain. This can't be undone."
                    } else {
                        "Delete this night and its dreams, transcripts, diagnostics, and " +
                            "recordings. This can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        actionMessage = null
                        if (reprocess) {
                            actionMessage = if (reprocessRequiresTranscription) {
                                "Reprocessing. Keep DreamLog open."
                            } else {
                                "Regrouping. Keep DreamLog open."
                            }
                            onReprocessNight()
                        } else {
                            val completion: (String?) -> Unit = { error ->
                                actionMessage = error ?: if (rawAudioOnly) {
                                    "Recordings deleted."
                                } else {
                                    null
                                }
                            }
                            if (rawAudioOnly) {
                                onDeleteRawAudio(completion)
                            } else {
                                onDeleteWholeNight(completion)
                            }
                        }
                    },
                    enabled = !blocked && (!reprocess || !reprocessRunning),
                ) {
                    Text(
                        when {
                            reprocess && reprocessRequiresTranscription -> "Reprocess"
                            reprocess -> "Regroup"
                            else -> "Delete"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text("Cancel") }
            },
        )
    }
}

private enum class NightDeleteConfirmation {
    REPROCESS,
    RAW_AUDIO,
    WHOLE_NIGHT,
}

@Composable
private fun DreamReviewCard(
    title: String?,
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
            if (title != null) {
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            content()
        }
    }
}

@Composable
private fun SupportingDreamText(text: String, warning: Boolean = false) {
    Text(
        text = text,
        color = if (warning) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}
