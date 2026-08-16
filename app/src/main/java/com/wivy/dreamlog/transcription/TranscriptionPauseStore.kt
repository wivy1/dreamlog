package com.wivy.dreamlog.transcription

import android.content.Context

/** Stores only operational pause metadata; no audio or transcript content is written here. */
internal class TranscriptionPauseStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): StoredTranscriptionPause? {
        val nightId = preferences.getString(KEY_NIGHT_ID, null) ?: return null
        val reasonName = preferences.getString(KEY_REASON, null) ?: return null
        val reason = runCatching { TranscriptionPauseReason.valueOf(reasonName) }.getOrNull()
            ?: return null
        val message = preferences.getString(KEY_MESSAGE, null) ?: return null
        return StoredTranscriptionPause(nightId, reason, message)
    }

    fun write(
        nightId: String,
        reason: TranscriptionPauseReason,
        message: String,
    ) {
        preferences.edit()
            .putString(KEY_NIGHT_ID, nightId)
            .putString(KEY_REASON, reason.name)
            .putString(KEY_MESSAGE, message)
            .commit()
    }

    fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "transcription_pause"
        const val KEY_NIGHT_ID = "night_id"
        const val KEY_REASON = "reason"
        const val KEY_MESSAGE = "message"
    }
}

internal data class StoredTranscriptionPause(
    val nightId: String,
    val reason: TranscriptionPauseReason,
    val message: String,
)
