package com.wivy.dreamlog.feasibility

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wivy.dreamlog.ui.theme.DreamLogTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TranscriptionBenchmarkActivity : ComponentActivity() {
    private lateinit var fixtureStore: FixtureStore
    private val fixtureRecorder = FixtureWavRecorder()

    private var activeRecording: FixtureDefinition? = null
    private var recordingStopping = false
    private var mediaPlayer: MediaPlayer? = null
    private var playingFixtureId: String? = null
    private var screenState by mutableStateOf(BenchmarkScreenState())

    private val microphonePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        publishState(
            message = if (granted) {
                "Microphone permission granted. Record the four fixtures from top to bottom."
            } else {
                "Microphone permission was not granted. No recording was started."
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        fixtureStore = FixtureStore(applicationContext)
        publishState(
            message =
                "These neutral samples stay inside the isolated fixture app until deleted.",
        )

        setContent {
            DreamLogTheme {
                BenchmarkScreen(
                    state = screenState,
                    onRequestMicrophonePermission = ::requestMicrophonePermission,
                    onRecord = ::startRecording,
                    onStopRecording = ::stopRecording,
                    onTogglePlayback = ::togglePlayback,
                    onDelete = ::deleteFixture,
                    onSetApproved = ::setFixtureApproved,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::fixtureStore.isInitialized) publishState()
    }

    override fun onStop() {
        if (activeRecording != null) {
            recordingStopping = true
            publishState(
                message =
                    "Recording stopped because the fixture app left the foreground.",
            )
            fixtureRecorder.stop(waitForCompletion = true)
        }
        stopPlayback(publish = false)
        if (::fixtureStore.isInitialized) publishState()
        super.onStop()
    }

    override fun onDestroy() {
        fixtureRecorder.stop(waitForCompletion = true)
        stopPlayback(publish = false)
        super.onDestroy()
    }

    private fun requestMicrophonePermission() {
        microphonePermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording(definition: FixtureDefinition) {
        if (!hasMicrophonePermission()) {
            publishState(message = "Allow microphone access before recording a fixture.")
            requestMicrophonePermission()
            return
        }
        if (activeRecording != null) return

        stopPlayback(publish = false)
        val start = fixtureRecorder.start(
            recordingFile = fixtureStore.recordingFile(definition),
            completedFile = fixtureStore.audioFile(definition),
        ) { recorderOutcome ->
            val outcome = when (recorderOutcome) {
                is FixtureRecordingOutcome.Success ->
                    fixtureStore.saveSuccessfulRecording(
                        definition = definition,
                        recordedAtEpochMillis = recorderOutcome.recordedAtEpochMillis,
                    ).fold(
                        onSuccess = { recorderOutcome },
                        onFailure = { failure ->
                            FixtureRecordingOutcome.Failure(
                                failure.message ?: "Fixture metadata could not be saved.",
                            )
                        },
                    )

                is FixtureRecordingOutcome.Failure -> recorderOutcome
            }
            runOnUiThread { finishRecording(definition, outcome) }
        }

        start.fold(
            onSuccess = {
                activeRecording = definition
                recordingStopping = false
                publishState(
                    message =
                        "Recording ${definition.category}. Read only the fixed text, then tap Stop.",
                )
            },
            onFailure = { failure ->
                activeRecording = null
                recordingStopping = false
                publishState(
                    message = failure.message ?: "The microphone recorder could not start.",
                )
            },
        )
    }

    private fun stopRecording() {
        if (activeRecording == null || recordingStopping) return
        recordingStopping = true
        publishState(message = "Finishing the WAV file...")
        fixtureRecorder.stop()
    }

    private fun finishRecording(
        definition: FixtureDefinition,
        outcome: FixtureRecordingOutcome,
    ) {
        if (activeRecording?.id == definition.id) {
            activeRecording = null
            recordingStopping = false
        }
        when (outcome) {
            is FixtureRecordingOutcome.Success -> {
                val timeLimitNote = if (outcome.reachedTimeLimit) {
                    " The 60-second safety limit stopped it automatically."
                } else {
                    ""
                }
                publishState(
                    message =
                        "Saved ${definition.category} " +
                            "(${formatDuration(outcome.durationMillis)})." +
                            "$timeLimitNote Listen locally, then approve or re-record it.",
                )
            }

            is FixtureRecordingOutcome.Failure ->
                publishState(message = outcome.message)
        }
    }

    private fun togglePlayback(definition: FixtureDefinition) {
        if (activeRecording != null) return
        if (playingFixtureId == definition.id) {
            stopPlayback(
                publish = true,
                message = "Playback stopped for ${definition.category}.",
            )
            return
        }

        stopPlayback(publish = false)
        val audio = fixtureStore.audioFile(definition)
        if (!audio.isFile) {
            publishState(message = "The fixture audio is missing. Record it again.")
            return
        }

        val candidate = MediaPlayer()
        val prepared = runCatching {
            candidate.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            candidate.setDataSource(audio.absolutePath)
            candidate.setOnCompletionListener { completedPlayer ->
                if (mediaPlayer === completedPlayer) {
                    mediaPlayer = null
                    playingFixtureId = null
                    completedPlayer.release()
                    publishState(message = "Playback finished for ${definition.category}.")
                } else {
                    completedPlayer.release()
                }
            }
            candidate.setOnErrorListener { failedPlayer, _, _ ->
                if (mediaPlayer === failedPlayer) {
                    mediaPlayer = null
                    playingFixtureId = null
                    failedPlayer.release()
                    publishState(
                        message =
                            "Local playback failed. The recording remains available to re-record or delete.",
                    )
                }
                true
            }
            candidate.prepare()
            candidate.start()
        }
        prepared.fold(
            onSuccess = {
                mediaPlayer = candidate
                playingFixtureId = definition.id
                publishState(message = "Playing ${definition.category} locally.")
            },
            onFailure = { failure ->
                candidate.release()
                publishState(
                    message = failure.message ?: "Local fixture playback could not start.",
                )
            },
        )
    }

    private fun deleteFixture(definition: FixtureDefinition) {
        if (activeRecording != null) return
        if (playingFixtureId == definition.id) stopPlayback(publish = false)
        fixtureStore.delete(definition).fold(
            onSuccess = {
                publishState(
                    message =
                        "Deleted ${definition.category} audio and approval metadata.",
                )
            },
            onFailure = { failure ->
                publishState(message = failure.message ?: "The fixture could not be deleted.")
            },
        )
    }

    private fun setFixtureApproved(
        definition: FixtureDefinition,
        approved: Boolean,
    ) {
        if (activeRecording != null) return
        fixtureStore.setApproved(definition, approved).fold(
            onSuccess = {
                publishState(
                    message = if (approved) {
                        "Approved ${definition.category}."
                    } else {
                        "Approval removed from ${definition.category}."
                    },
                )
            },
            onFailure = { failure ->
                publishState(message = failure.message ?: "Approval could not be saved.")
            },
        )
    }

    private fun stopPlayback(
        publish: Boolean,
        message: String? = null,
    ) {
        mediaPlayer?.let { player ->
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        playingFixtureId = null
        if (publish && ::fixtureStore.isInitialized) publishState(message = message)
    }

    private fun publishState(message: String? = screenState.message) {
        screenState = BenchmarkScreenState(
            fixtures = if (::fixtureStore.isInitialized) fixtureStore.snapshots() else emptyList(),
            hasMicrophonePermission = hasMicrophonePermission(),
            activeRecordingId = activeRecording?.id,
            recordingStopping = recordingStopping,
            playingFixtureId = playingFixtureId,
            message = message,
        )
    }

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

private data class BenchmarkScreenState(
    val fixtures: List<FixtureSnapshot> = emptyList(),
    val hasMicrophonePermission: Boolean = false,
    val activeRecordingId: String? = null,
    val recordingStopping: Boolean = false,
    val playingFixtureId: String? = null,
    val message: String? = null,
)

@Composable
private fun BenchmarkScreen(
    state: BenchmarkScreenState,
    onRequestMicrophonePermission: () -> Unit,
    onRecord: (FixtureDefinition) -> Unit,
    onStopRecording: () -> Unit,
    onTogglePlayback: (FixtureDefinition) -> Unit,
    onDelete: (FixtureDefinition) -> Unit,
    onSetApproved: (FixtureDefinition, Boolean) -> Unit,
) {
    val approvedCount = state.fixtures.count { fixture ->
        fixture.approved && fixture.definition.id != state.activeRecordingId
    }
    val allApproved =
        state.activeRecordingId == null &&
        state.fixtures.size == TranscriptionFixtures.size &&
            state.fixtures.all(FixtureSnapshot::approved)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Transcription fixtures",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            "Record four fixed, neutral phrases on this Pixel. Work top to bottom, " +
                                "listen to each local WAV, and approve only a representative take.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            item {
                GateCard(
                    approvedCount = approvedCount,
                    allApproved = allApproved,
                )
            }

            item {
                PermissionCard(
                    granted = state.hasMicrophonePermission,
                    onRequest = onRequestMicrophonePermission,
                )
            }

            state.message?.let { message ->
                item { MessageCard(message) }
            }

            items(
                items = state.fixtures,
                key = { fixture -> fixture.definition.id },
            ) { fixture ->
                val index = state.fixtures.indexOf(fixture)
                FixtureCard(
                    number = index + 1,
                    snapshot = fixture,
                    microphoneGranted = state.hasMicrophonePermission,
                    anotherRecordingActive =
                        state.activeRecordingId != null &&
                            state.activeRecordingId != fixture.definition.id,
                    isRecording = state.activeRecordingId == fixture.definition.id,
                    isStopping =
                        state.activeRecordingId == fixture.definition.id &&
                            state.recordingStopping,
                    isPlaying = state.playingFixtureId == fixture.definition.id,
                    anyRecordingActive = state.activeRecordingId != null,
                    onRecord = { onRecord(fixture.definition) },
                    onStopRecording = onStopRecording,
                    onTogglePlayback = { onTogglePlayback(fixture.definition) },
                    onDelete = { onDelete(fixture.definition) },
                    onSetApproved = { approved ->
                        onSetApproved(fixture.definition, approved)
                    },
                )
            }

            item {
                Text(
                    text =
                        "Audio and fixture metadata stay in the app-private " +
                            "com.wivy.dreamlog.transcriptionfixture sandbox. This recorder never sends " +
                            "audio, never records in the background, and stops after 60 seconds.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun GateCard(
    approvedCount: Int,
    allApproved: Boolean,
) {
    SectionCard(title = "Fixture gate - $approvedCount of 4 approved") {
        Text(
            text = if (allApproved) {
                "All four fixtures are approved. The fixture gate is satisfied; production " +
                    "selection can now be reviewed separately. This fixture app does not " +
                    "change production settings."
            } else {
                "PRODUCTION SELECTION LOCKED. Record, review, and explicitly approve all four " +
                    "fixtures before selecting a production transcription engine or model."
            },
            color = if (allApproved) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PermissionCard(
    granted: Boolean,
    onRequest: () -> Unit,
) {
    SectionCard(title = "Microphone permission") {
        Text(
            text = if (granted) {
                "Granted for this isolated fixture app. Recording begins only when you tap Record."
            } else {
                "Required only to record these four local samples. Nothing records until permission " +
                    "is granted and you tap Record."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!granted) {
            Button(
                onClick = onRequest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow microphone")
            }
        }
    }
}

@Composable
private fun MessageCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FixtureCard(
    number: Int,
    snapshot: FixtureSnapshot,
    microphoneGranted: Boolean,
    anotherRecordingActive: Boolean,
    isRecording: Boolean,
    isStopping: Boolean,
    isPlaying: Boolean,
    anyRecordingActive: Boolean,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
    onTogglePlayback: () -> Unit,
    onDelete: () -> Unit,
    onSetApproved: (Boolean) -> Unit,
) {
    val definition = snapshot.definition
    SectionCard(title = "$number. ${definition.category}") {
        Text(
            text = definition.instruction,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Text(
                text = "\"${definition.referenceText}\"",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }

        HorizontalDivider()
        Text(
            text = fixtureStatus(snapshot, isRecording, isStopping),
            color = when {
                isRecording -> MaterialTheme.colorScheme.tertiary
                snapshot.approved -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )

        when {
            isRecording -> {
                Button(
                    onClick = onStopRecording,
                    enabled = !isStopping,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(if (isStopping) "Finishing..." else "Stop and save")
                }
            }

            !snapshot.hasRecording -> {
                Button(
                    onClick = onRecord,
                    enabled = microphoneGranted && !anotherRecordingActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("Record fixture")
                }
                if (!microphoneGranted) {
                    Text(
                        text = "Allow microphone access above to enable recording.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onTogglePlayback,
                        enabled = !anyRecordingActive,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (isPlaying) "Stop playback" else "Play locally")
                    }
                    OutlinedButton(
                        onClick = onRecord,
                        enabled = microphoneGranted && !anyRecordingActive,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Re-record")
                    }
                }
                Button(
                    onClick = { onSetApproved(!snapshot.approved) },
                    enabled = !anyRecordingActive,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (snapshot.approved) "Unapprove" else "Approve this take")
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !anyRecordingActive,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete recording")
                }
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
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

private fun fixtureStatus(
    snapshot: FixtureSnapshot,
    isRecording: Boolean,
    isStopping: Boolean,
): String = when {
    isRecording && isStopping -> "Finishing the local WAV..."
    isRecording -> "Recording now - 60-second maximum"
    snapshot.approved ->
        "Approved - ${recordingDescription(snapshot)}"
    snapshot.hasRecording ->
        "Recorded, review required - ${recordingDescription(snapshot)}"
    else -> "Not recorded"
}

private fun recordingDescription(snapshot: FixtureSnapshot): String {
    val time = snapshot.recordedAtEpochMillis?.let(::formatTimestamp) ?: "metadata unavailable"
    val duration = snapshot.durationMillis?.let(::formatDuration) ?: "unknown length"
    return "$duration - $time"
}

private fun formatTimestamp(epochMillis: Long): String =
    DISPLAY_TIME_FORMATTER.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
    )

private fun formatDuration(durationMillis: Long): String {
    val seconds = (durationMillis / 100L).coerceAtLeast(1L) / 10.0
    return "${seconds}s"
}

private val DISPLAY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a")
