package com.wivy.dreamlog.feasibility

import android.content.Context
import android.content.SharedPreferences
import java.io.File

internal data class FixtureDefinition(
    val id: String,
    val category: String,
    val referenceText: String,
    val instruction: String,
)

internal val TranscriptionFixtures = listOf(
    FixtureDefinition(
        id = "sleepy_quiet",
        category = "Sleepy / quiet",
        referenceText =
            "I woke up slowly, took a sip of water, and looked at the clock.",
        instruction =
            "In a quiet room, read once in a naturally sleepy voice without adding a story.",
    ),
    FixtureDefinition(
        id = "low_volume_quiet",
        category = "Low volume / quiet",
        referenceText = "The small lamp beside the chair is still on.",
        instruction =
            "In a quiet room, read softly at the lowest volume you would realistically use.",
    ),
    FixtureDefinition(
        id = "disfluent",
        category = "Disfluent",
        referenceText =
            "I was, um, walking to the kitchen, and then, then I remembered my keys.",
        instruction =
            "Read exactly as written, including the pause, filler word, and repeated word.",
    ),
    FixtureDefinition(
        id = "fan_noise",
        category = "Fan noise",
        referenceText = "The blue notebook is resting on the table near the window.",
        instruction =
            "Run the bedside fan at its usual setting, place the phone normally, and read once.",
    ),
)

internal data class FixtureSnapshot(
    val definition: FixtureDefinition,
    val hasRecording: Boolean,
    val recordedAtEpochMillis: Long?,
    val durationMillis: Long?,
    val approved: Boolean,
)

/** App-private fixture audio and the minimum review metadata needed by the benchmark. */
internal class FixtureStore(context: Context) {
    private val rootDirectory = File(context.filesDir, DIRECTORY_NAME)
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Fixture storage could not be created."
        }
        rootDirectory.listFiles()
            ?.filter { it.name.endsWith(RECORDING_SUFFIX) }
            ?.forEach(File::delete)
    }

    fun snapshots(): List<FixtureSnapshot> = TranscriptionFixtures.map(::snapshot)

    fun audioFile(definition: FixtureDefinition): File =
        File(rootDirectory, "${definition.id}.wav")

    fun recordingFile(definition: FixtureDefinition): File =
        File(rootDirectory, "${definition.id}$RECORDING_SUFFIX")

    fun saveSuccessfulRecording(
        definition: FixtureDefinition,
        recordedAtEpochMillis: Long,
    ): Result<Unit> = runCatching {
        check(audioFile(definition).isFile) {
            "The completed fixture audio is missing."
        }
        check(
            preferences.edit()
                .putString(key(definition, CATEGORY_SUFFIX), definition.category)
                .putString(key(definition, REFERENCE_SUFFIX), definition.referenceText)
                .putLong(key(definition, RECORDED_AT_SUFFIX), recordedAtEpochMillis)
                .putBoolean(key(definition, APPROVED_SUFFIX), false)
                .commit(),
        ) {
            "Fixture metadata could not be saved."
        }
    }

    fun setApproved(
        definition: FixtureDefinition,
        approved: Boolean,
    ): Result<Unit> = runCatching {
        val snapshot = snapshot(definition)
        check(snapshot.hasRecording && snapshot.recordedAtEpochMillis != null) {
            "Record this fixture before approving it."
        }
        check(
            preferences.edit()
                .putBoolean(key(definition, APPROVED_SUFFIX), approved)
                .commit(),
        ) {
            "Fixture approval could not be saved."
        }
    }

    fun delete(definition: FixtureDefinition): Result<Unit> = runCatching {
        val audio = audioFile(definition)
        val recording = recordingFile(definition)
        check(!audio.exists() || audio.delete()) {
            "Fixture audio could not be deleted."
        }
        check(!recording.exists() || recording.delete()) {
            "The unfinished fixture recording could not be deleted."
        }
        check(
            preferences.edit()
                .remove(key(definition, CATEGORY_SUFFIX))
                .remove(key(definition, REFERENCE_SUFFIX))
                .remove(key(definition, RECORDED_AT_SUFFIX))
                .remove(key(definition, APPROVED_SUFFIX))
                .commit(),
        ) {
            "Fixture metadata could not be deleted."
        }
    }

    private fun snapshot(definition: FixtureDefinition): FixtureSnapshot {
        val audio = audioFile(definition)
        val hasRecording = audio.isFile && audio.length() > WAV_HEADER_BYTES
        val metadataMatches =
            preferences.getString(key(definition, CATEGORY_SUFFIX), null) ==
                definition.category &&
                preferences.getString(key(definition, REFERENCE_SUFFIX), null) ==
                definition.referenceText
        val recordedAt = preferences.getLong(key(definition, RECORDED_AT_SUFFIX), 0L)
            .takeIf { hasRecording && metadataMatches && it > 0L }
        val duration = (audio.length() - WAV_HEADER_BYTES)
            .takeIf { hasRecording && it > 0L }
            ?.times(1_000L)
            ?.div(BYTES_PER_SECOND)
        return FixtureSnapshot(
            definition = definition,
            hasRecording = hasRecording,
            recordedAtEpochMillis = recordedAt,
            durationMillis = duration,
            approved = hasRecording && metadataMatches &&
                preferences.getBoolean(key(definition, APPROVED_SUFFIX), false),
        )
    }

    private fun key(definition: FixtureDefinition, suffix: String): String =
        "${definition.id}_$suffix"

    private companion object {
        const val DIRECTORY_NAME = "transcription-fixtures"
        const val PREFERENCES_NAME = "transcription-fixture-metadata"
        const val RECORDING_SUFFIX = ".recording"
        const val CATEGORY_SUFFIX = "category"
        const val REFERENCE_SUFFIX = "reference"
        const val RECORDED_AT_SUFFIX = "recorded_at"
        const val APPROVED_SUFFIX = "approved"
        const val WAV_HEADER_BYTES = 44L
        const val BYTES_PER_SECOND = 16_000L * 2L
    }
}
