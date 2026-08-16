package com.wivy.dreamlog.transcription

import android.os.PowerManager
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TranscriptionThermalPolicyTest {
    @Test
    fun moderateAndroidStatusDefersUntilStatusReturnsToLight() {
        val signals = ArrayDeque(
            listOf(
                signal(PowerManager.THERMAL_STATUS_MODERATE, 330),
                signal(PowerManager.THERMAL_STATUS_MODERATE - 1, 356),
                signal(PowerManager.THERMAL_STATUS_LIGHT, 355),
            ),
        )
        val policy = TranscriptionThermalPolicy(signals::removeFirst)

        assertEquals(TranscriptionPauseReason.THERMAL, policy.defer().reason)
        assertEquals(TranscriptionPauseReason.THERMAL, policy.defer().reason)
        assertSame(TranscriptionContinuationDecision.Continue, policy.evaluate())
    }

    @Test
    fun batteryTemperatureHasConservativeEntryAndResumeHysteresis() {
        val signals = ArrayDeque(
            listOf(
                signal(PowerManager.THERMAL_STATUS_NONE, 359),
                signal(PowerManager.THERMAL_STATUS_NONE, 360),
                signal(PowerManager.THERMAL_STATUS_NONE, 357),
                signal(PowerManager.THERMAL_STATUS_NONE, 355),
            ),
        )
        val policy = TranscriptionThermalPolicy(signals::removeFirst)

        assertSame(TranscriptionContinuationDecision.Continue, policy.evaluate())
        assertEquals(TranscriptionPauseReason.THERMAL, policy.defer().reason)
        assertEquals(TranscriptionPauseReason.THERMAL, policy.defer().reason)
        assertSame(TranscriptionContinuationDecision.Continue, policy.evaluate())
    }

    @Test
    fun resumedThermalPauseReseedsLatchUntilTheCoolThreshold() {
        val signals = ArrayDeque(
            listOf(
                signal(PowerManager.THERMAL_STATUS_NONE, 357),
                signal(PowerManager.THERMAL_STATUS_LIGHT, 355),
            ),
        )
        val resumedPolicy = TranscriptionThermalPolicy(
            currentSignal = signals::removeFirst,
            initiallyWarm = true,
        )

        assertEquals(TranscriptionPauseReason.THERMAL, resumedPolicy.defer().reason)
        assertSame(TranscriptionContinuationDecision.Continue, resumedPolicy.evaluate())
    }

    private fun TranscriptionThermalPolicy.defer(): TranscriptionContinuationDecision.Defer =
        evaluate() as TranscriptionContinuationDecision.Defer

    private fun signal(status: Int, temperatureDeciCelsius: Int) =
        TranscriptionThermalSignal(status, temperatureDeciCelsius)
}
