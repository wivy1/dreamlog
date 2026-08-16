package com.wivy.dreamlog.settings

import android.content.Context
import android.content.SharedPreferences

enum class RetentionPeriod(
    val days: Int,
    val displayLabel: String,
) {
    ONE_DAY(1, "1 day"),
    SEVEN_DAYS(7, "7 days"),
    THIRTY_DAYS(30, "30 days"),
    ;

    companion object {
        val DEFAULT: RetentionPeriod = THIRTY_DAYS
        val SUPPORTED: List<RetentionPeriod> = entries.toList()

        fun fromDays(days: Int): RetentionPeriod? =
            SUPPORTED.firstOrNull { it.days == days }
    }
}

internal data class ResolvedRetentionPeriod(
    val period: RetentionPeriod,
    val repairStoredValue: Boolean,
)

internal fun resolveStoredRetentionPeriod(storedDays: Int?): ResolvedRetentionPeriod {
    val storedPeriod = storedDays?.let { RetentionPeriod.fromDays(it) }
    return ResolvedRetentionPeriod(
        period = storedPeriod ?: RetentionPeriod.DEFAULT,
        repairStoredValue = storedPeriod == null,
    )
}

internal fun isSupportedRetentionDays(days: Int): Boolean =
    RetentionPeriod.fromDays(days) != null

internal fun requireRetentionPeriod(days: Int): RetentionPeriod =
    requireNotNull(RetentionPeriod.fromDays(days)) {
        "Raw-audio retention must be 1, 7, or 30 days."
    }

internal fun retentionPeriodLabel(days: Int): String =
    requireRetentionPeriod(days).displayLabel

class AppSettingsStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun readRawAudioRetentionPeriod(): RetentionPeriod {
        val resolved = resolveStoredRetentionPeriod(readStoredRetentionDays())
        if (resolved.repairStoredValue) {
            preferences.edit()
                .putInt(RAW_AUDIO_RETENTION_DAYS_KEY, resolved.period.days)
                .apply()
        }
        return resolved.period
    }

    fun readRawAudioRetentionDays(): Int = readRawAudioRetentionPeriod().days

    @Synchronized
    fun setRawAudioRetentionPeriod(period: RetentionPeriod) {
        check(
            preferences.edit()
                .putInt(RAW_AUDIO_RETENTION_DAYS_KEY, period.days)
                .commit(),
        ) { "The raw-audio retention setting could not be saved." }
    }

    fun setRawAudioRetentionDays(days: Int) {
        setRawAudioRetentionPeriod(requireRetentionPeriod(days))
    }

    private fun readStoredRetentionDays(): Int? {
        if (!preferences.contains(RAW_AUDIO_RETENTION_DAYS_KEY)) return null
        return try {
            preferences.getInt(
                RAW_AUDIO_RETENTION_DAYS_KEY,
                RetentionPeriod.DEFAULT.days,
            )
        } catch (_: ClassCastException) {
            null
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "dreamlog_app_settings"
        const val RAW_AUDIO_RETENTION_DAYS_KEY = "raw_audio_retention_days"
    }
}
