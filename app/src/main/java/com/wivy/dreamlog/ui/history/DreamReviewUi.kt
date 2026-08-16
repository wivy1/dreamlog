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
import androidx.compose.ui.text.font.FontWeight
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
        Text(
            text = "Dreams",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            record.dreams.isNotEmpty() -> {
                Text(
                    text = if (record.dreams.size == 1) {
                        "1 processed dream"
                    } else {
                        "${record.dreams.size} processed dreams"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                SupportingDreamText("No processed dreams were identified for this night.")

            record.night.enrichmentState == ProcessingState.FAILED ->
                SupportingDreamText(
                    "Processed dreams are unavailable. The raw transcript remains below.",
                    warning = true,
                )

            else -> SupportingDreamText(
                "Processed dreams will appear after this night is enriched locally.",
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    fontWeight = FontWeight.SemiBold,
                )
                if (dream.dream.ownerEdited) {
                    Text(
                        text = "Edited",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (dream.dream.isUncertain || dream.dream.kind == DreamKind.FRAGMENT) {
                SupportingDreamText("Uncertain fragment", warning = true)
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
            SupportingDreamText(
                if (dream.sourceSpans.size == 1) {
                    "1 retained source mapping"
                } else {
                    "${dream.sourceSpans.size} ordered source mappings"
                },
            )
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
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
                    OutlinedButton(onClick = requestBack) { Text("Back") }
                    Text(
                        text = displayedDream?.let { dreamDisplayTitle(it, it.dream.dreamOrder) }
                            ?: "Dream unavailable",
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            when {
                record == null -> item {
                    DreamReviewCard("Dream unavailable") {
                        SupportingDreamText(
                            "The parent night is no longer present in private local history.",
                        )
                    }
                }

                recentlyDeleted && currentDream == null && deletedSnapshot != null -> item {
                    DreamReviewCard("Dream deleted") {
                        SupportingDreamText(
                            "This dream is hidden from the log and playback. Its underlying raw " +
                                "session audio remains under the night retention policy.",
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
                            Text("Undo deletion")
                        }
                    }
                }

                displayedDream == null -> item {
                    DreamReviewCard("Dream unavailable") {
                        SupportingDreamText(
                            "This dream is not present in the normal local log.",
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
                                        actionMessage = "Dream edit saved locally."
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
                                "Deleting this processed dream hides it from the normal log and " +
                                    "dream playback. It does not rewrite or delete session audio.",
                            )
                            Button(
                                onClick = { deleteConfirmationVisible = true },
                                enabled = !mutationBlocked,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
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
                        warning = message != "Dream edit saved locally." &&
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
                Text("Your unsaved title or dream text changes will be lost.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardChangesConfirmationVisible = false
                        editing = false
                        onBack()
                    },
                ) {
                    Text("Discard and leave")
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
            title = { Text("Delete this dream?") },
            text = {
                Text(
                    "The dream will disappear from the normal log and dream playback. " +
                        "Underlying session audio remains until its normal expiry or until all " +
                        "raw audio for this night is deleted. You can undo immediately.",
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
    DreamReviewCard(if (editing) "Edit dream" else "Dream") {
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
                    { Text("Dream text cannot be blank.") }
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
                    fontWeight = FontWeight.SemiBold,
                )
                if (dream.dream.ownerEdited) {
                    Text(
                        text = "Edited",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (dream.dream.isUncertain || dream.dream.kind == DreamKind.FRAGMENT) {
                SupportingDreamText("Uncertain fragment", warning = true)
            }
            if (narrationDateTimes.isNotEmpty()) {
                SupportingDreamText("Narrated ${narrationDateTimes.joinToString()}")
            }
            Text(dream.dream.currentText, style = MaterialTheme.typography.bodyLarge)
            dream.dream.editedAtEpochMillis?.let {
                SupportingDreamText("Owner edit saved locally.")
            }
            Button(
                onClick = onStartEditing,
                enabled = !mutationBlocked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Edit title and text")
            }
            if (dream.dream.ownerEdited) {
                OutlinedButton(
                    onClick = onToggleGenerated,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (generatedExpanded) "Hide generated version" else "Show generated version")
                }
                if (generatedExpanded) {
                    HorizontalDivider()
                    Text(
                        text = dream.dream.generatedTitle?.takeIf(String::isNotBlank)
                            ?: "Generated untitled dream",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = dream.dream.generatedText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SupportingDreamText(
                        "This generated baseline is preserved and will not silently replace your edit.",
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
                "1 source range links this dream to its retained raw transcript."
            } else {
                "${dream.sourceSpans.size} source ranges play in their recorded logical order."
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
                "Playback is disabled while a night is actively listening.",
                warning = true,
            )
        } else if (blocked) {
            SupportingDreamText(
                "Playback is disabled while local processing or an archive change is active.",
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
                    fontWeight = FontWeight.SemiBold,
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
        DreamSourcePlaybackPhase.PLAYING -> "Pause source audio"
        DreamSourcePlaybackPhase.PAUSED -> "Resume source audio"
        DreamSourcePlaybackPhase.COMPLETED -> "Play source audio again"
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

internal fun canReprocessNight(record: NightRecord): Boolean {
    return reprocessNightDataUnavailableReason(record) == null
}

internal fun reprocessNightDataUnavailableReason(record: NightRecord): String? {
    if (record.hasProtectedDreamChanges) {
        return "Reprocessing is disabled because an owner edit or deletion must not be overwritten."
    }
    if (record.sessions.isEmpty()) {
        return "This night has no recorded wakeword sessions to reprocess."
    }
    if (record.night.captureState !in setOf(
            NightCaptureState.ENDED,
            NightCaptureState.INTERRUPTED,
        )
    ) {
        return "End this night before reprocessing it."
    }
    if (record.sessions.any { it.audioState != AudioEvidenceState.RETAINED }) {
        return "Every wakeword session needs retained raw audio for reprocessing."
    }
    if (record.sessions.any { it.finalizedAtEpochMillis == null }) {
        return "Every wakeword session must finish recovery before reprocessing."
    }
    val completeSessionIds = record.transcripts
        .filter { it.transcript.state == ProcessingState.COMPLETE }
        .map { it.transcript.sessionId }
    if (
        completeSessionIds.size != record.sessions.size ||
        completeSessionIds.toSet() != record.sessions.map { it.sessionId }.toSet()
    ) {
        return "Every wakeword session needs one completed transcript before reprocessing."
    }
    return null
}

@Composable
fun ManageNightDataCard(
    record: NightRecord,
    blocked: Boolean,
    reprocessUnavailableReason: String?,
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
    val dataUnavailableReason = reprocessNightDataUnavailableReason(record)
    val effectiveGlobalReason = reprocessUnavailableReason ?: if (blocked) {
        "Wait for the current capture, local processing, or archive operation to finish."
    } else {
        null
    }

    DreamReviewCard("Manage night data") {
        SupportingDreamText(
            "Dream and transcript text stays in app-private history until you delete the whole night.",
        )
        OutlinedButton(
            onClick = { confirmation = NightDeleteConfirmation.REPROCESS },
            enabled = dataUnavailableReason == null && effectiveGlobalReason == null &&
                !reprocessRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (reprocessRunning) {
                    "Reprocessing this night…"
                } else {
                    "Reprocess with latest models"
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
            Text("Delete recordings only")
        }
        if (retainedAudio) {
            SupportingDreamText("The night and dreams will remain in History.")
        } else {
            SupportingDreamText("No retained raw audio remains for this night.")
        }
        Button(
            onClick = { confirmation = NightDeleteConfirmation.WHOLE_NIGHT },
            enabled = !blocked,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete night from History")
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
                        reprocess -> "Reprocess this night?"
                        rawAudioOnly -> "Delete recordings only?"
                        else -> "Delete this night from History?"
                    },
                )
            },
            text = {
                Text(
                    if (reprocess) {
                        "DreamLog will re-transcribe every retained wakeword session with the " +
                            "current high-quality speech model, then replace this night's " +
                            "generated dream grouping with the current enrichment model. Raw " +
                            "audio remains. Existing generated text will be replaced and this " +
                            "can't be undone."
                    } else if (rawAudioOnly) {
                        "All retained raw audio for this night will be permanently deleted. " +
                            "The night, dreams, raw transcripts, and source text will remain in " +
                            "History. This can't be undone."
                    } else {
                        "This permanently removes the selected History row and its dreams, " +
                            "transcripts, capture diagnostics, and recordings. Other nights are " +
                            "not affected. This can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        actionMessage = null
                        if (reprocess) {
                            actionMessage = "Reprocessing started. Keep DreamLog open."
                            onReprocessNight()
                        } else {
                            val completion: (String?) -> Unit = { error ->
                                actionMessage = error ?: if (rawAudioOnly) {
                                    "Recordings deleted. The night and dreams remain in History."
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
                    Text(if (reprocess) "Reprocess" else "Delete")
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
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
