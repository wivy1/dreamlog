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
 * Defers at MODERATE platform pressure or 36.0 C battery temperature, then requires both public
 * signals to recover (LIGHT-or-lower and at most 35.5 C) before clearing the latch.
 *
 * Android's public thermal status is the primary signal. The battery-temperature boundary is a
 * measured Pixel backstop for the Night 1 charging-warmth failure, not a substitute estimate of
 * platform thermal pressure. Both signals use hysteresis so a warm/cool boundary cannot repeatedly
 * load and release the 663 MB ASR model.
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
            signal.platformStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
            batteryTemperature?.let { it >= DEFER_BATTERY_TEMPERATURE_DECI_CELSIUS } == true
        ) {
            warmLatch = true
        } else if (
            signal.platformStatus <= PowerManager.THERMAL_STATUS_LIGHT &&
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
            "Device temperature is elevated. Transcription is paused until the battery cools " +
                "and Android reports light or no thermal pressure; retained audio is safe."
        const val DEFER_BATTERY_TEMPERATURE_DECI_CELSIUS = 360
        const val RESUME_BATTERY_TEMPERATURE_DECI_CELSIUS = 355
    }
}
