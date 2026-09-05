package com.wivy.dreamlog.transcription

import android.os.PowerManager
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TranscriptionThermalPolicyTest {
    @Test
    fun normalChargingWarmthPermitsTranscription() {
        for (status in PowerManager.THERMAL_STATUS_NONE..PowerManager.THERMAL_STATUS_MODERATE) {
            for (temperature in listOf(360, 400, 420)) {
                val policy = TranscriptionThermalPolicy({ signal(status, temperature) })

                assertSame(
                    "status=$status battery=$temperature",
                    TranscriptionContinuationDecision.Continue,
                    policy.evaluate(),
                )
            }
        }
    }

    @Test
    fun severeAndroidStatusDefersUntilStatusReturnsToModerate() {
        val signals = ArrayDeque(
            listOf(
                signal(PowerManager.THERMAL_STATUS_SEVERE, 330),
                signal(PowerManager.THERMAL_STATUS_CRITICAL, 330),
                signal(PowerManager.THERMAL_STATUS_EMERGENCY, 330),
                signal(PowerManager.THERMAL_STATUS_SHUTDOWN, 330),
                signal(PowerManager.THERMAL_STATUS_MODERATE, 330),
            ),
        )
        val policy = TranscriptionThermalPolicy(signals::removeFirst)

        repeat(4) {
            assertEquals(TranscriptionPauseReason.THERMAL, policy.defer().reason)
        }
        assertSame(TranscriptionContinuationDecision.Continue, policy.evaluate())
    }

    @Test
    fun batteryTemperatureHasEntryAndResumeHysteresis() {
        val signals = ArrayDeque(
            listOf(
                signal(PowerManager.THERMAL_STATUS_NONE, 449),
                signal(PowerManager.THERMAL_STATUS_NONE, 450),
                signal(PowerManager.THERMAL_STATUS_NONE, 421),
                signal(PowerManager.THERMAL_STATUS_NONE, 420),
                signal(PowerManager.THERMAL_STATUS_NONE, 449),
            ),
        )
        val policy = TranscriptionThermalPolicy(signals::removeFirst)

        assertSame(TranscriptionContinuationDecision.Continue, policy.evaluate())
        assertEquals(TranscriptionPauseReason.THERMAL, policy.defer().reason)
        assertEquals(TranscriptionPauseReason.THERMAL, policy.defer().reason)
        assertSame(TranscriptionContinuationDecision.Continue, policy.evaluate())
        assertSame(TranscriptionContinuationDecision.Continue, policy.evaluate())
    }

    @Test
    fun persistedThermalPauseClearsAtNormalChargingWarmth() {
        val resumedPolicy = TranscriptionThermalPolicy(
            currentSignal = { signal(PowerManager.THERMAL_STATUS_MODERATE, 390) },
            initiallyWarm = true,
        )

        assertSame(TranscriptionContinuationDecision.Continue, resumedPolicy.evaluate())
    }

    @Test
    fun persistedThermalPauseRequiresBothRecoverySignals() {
        val signals = ArrayDeque(
            listOf(
                signal(PowerManager.THERMAL_STATUS_NONE, 421),
                signal(PowerManager.THERMAL_STATUS_SEVERE, 420),
                signal(PowerManager.THERMAL_STATUS_MODERATE, 420),
            ),
        )
        val resumedPolicy = TranscriptionThermalPolicy(
            currentSignal = signals::removeFirst,
            initiallyWarm = true,
        )

        assertEquals(TranscriptionPauseReason.THERMAL, resumedPolicy.defer().reason)
        assertEquals(TranscriptionPauseReason.THERMAL, resumedPolicy.defer().reason)
        assertSame(TranscriptionContinuationDecision.Continue, resumedPolicy.evaluate())
    }

    @Test
    fun unavailableBatteryTemperatureUsesAndroidThermalStatus() {
        val signals = ArrayDeque(
            listOf(
                signal(PowerManager.THERMAL_STATUS_MODERATE, null),
                signal(PowerManager.THERMAL_STATUS_SEVERE, null),
                signal(PowerManager.THERMAL_STATUS_MODERATE, null),
            ),
        )
        val policy = TranscriptionThermalPolicy(signals::removeFirst, initiallyWarm = true)

        assertSame(TranscriptionContinuationDecision.Continue, policy.evaluate())
        assertEquals(TranscriptionPauseReason.THERMAL, policy.defer().reason)
        assertSame(TranscriptionContinuationDecision.Continue, policy.evaluate())
    }

    private fun TranscriptionThermalPolicy.defer(): TranscriptionContinuationDecision.Defer =
        evaluate() as TranscriptionContinuationDecision.Defer

    private fun signal(status: Int, temperatureDeciCelsius: Int?) =
        TranscriptionThermalSignal(status, temperatureDeciCelsius)
}
