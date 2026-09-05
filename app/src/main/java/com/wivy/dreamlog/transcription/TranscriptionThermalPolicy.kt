package com.wivy.dreamlog.transcription

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

enum class TranscriptionPauseReason {
    THERMAL,
    PROCESS_INTERRUPTED,
    SESSION_FAILURE,
    FOREGROUND_TIMEOUT,
}

internal sealed interface TranscriptionContinuationDecision {
    data object Continue : TranscriptionContinuationDecision

    data class Defer(
        val reason: TranscriptionPauseReason,
        val message: String,
        val signal: TranscriptionThermalSignal? = null,
    ) : TranscriptionContinuationDecision
}

internal fun interface TranscriptionContinuationGate {
    fun evaluate(): TranscriptionContinuationDecision

    companion object {
        val ALWAYS_CONTINUE = TranscriptionContinuationGate {
            TranscriptionContinuationDecision.Continue
        }
    }
}

/** Public Android thermal signals behind a deterministic, unit-testable policy. */
internal class AndroidTranscriptionThermalStatusSource(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun currentSignal(): TranscriptionThermalSignal {
        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val temperatureDeciCelsius = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        return TranscriptionThermalSignal(
            platformStatus = powerManager.currentThermalStatus,
            batteryTemperatureDeciCelsius = temperatureDeciCelsius,
        )
    }
}

internal data class TranscriptionThermalSignal(
    val platformStatus: Int,
    val batteryTemperatureDeciCelsius: Int?,
)

/**
 * Defers at SEVERE platform pressure or 45.0 C battery temperature. Recovery requires
 * MODERATE-or-lower status and at most 42.0 C, including after a persisted thermal pause.
 *
 * Android's public thermal status is primary. The battery boundary is an app backstop, not a
 * device safety limit. Battery hysteresis permits ordinary charging warmth while avoiding
 * repeated model loads near the pause threshold.
 */
internal class TranscriptionThermalPolicy(
    private val currentSignal: () -> TranscriptionThermalSignal,
    initiallyWarm: Boolean = false,
) : TranscriptionContinuationGate {
    private var warmLatch = initiallyWarm

    override fun evaluate(): TranscriptionContinuationDecision {
        val signal = currentSignal()
        val batteryTemperature = signal.batteryTemperatureDeciCelsius
        if (
            signal.platformStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
            batteryTemperature?.let { it >= DEFER_BATTERY_TEMPERATURE_DECI_CELSIUS } == true
        ) {
            warmLatch = true
        } else if (
            signal.platformStatus <= PowerManager.THERMAL_STATUS_MODERATE &&
            (batteryTemperature == null ||
                batteryTemperature <= RESUME_BATTERY_TEMPERATURE_DECI_CELSIUS)
        ) {
            warmLatch = false
        }
        return if (warmLatch) {
            TranscriptionContinuationDecision.Defer(
                reason = TranscriptionPauseReason.THERMAL,
                message = THERMAL_DEFER_MESSAGE,
                signal = signal,
            )
        } else {
            TranscriptionContinuationDecision.Continue
        }
    }

    companion object {
        const val THERMAL_DEFER_MESSAGE =
            "Transcription paused for heat. Resume when the phone cools."
        const val DEFER_BATTERY_TEMPERATURE_DECI_CELSIUS = 450
        const val RESUME_BATTERY_TEMPERATURE_DECI_CELSIUS = 420
    }
}
