package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightEvaluatorTest {
    @Test
    fun `each required failure is a blocking issue with a remediation`() {
        val evaluation =
            PreflightEvaluator.evaluate(
                PreflightInput(
                    microphonePermissionGranted = false,
                    notificationPermissionGranted = false,
                    microphoneAccessAllowed = false,
                    foregroundServiceStartAllowed = false,
                    audioInputInitialized = false,
                    nonSilencedFramesReceived = false,
                    wakeModelValid = false,
                    availableStorageBytes = StorageGuard.PROTECTED_RESERVE_BYTES,
                    priorCaptureStateResolved = false,
                    cueVolumeReady = false,
                    cuePlaybackAllowed = false,
                    charging = true,
                    priorBatteryInterruption = false,
                    cueVolumeTested = true,
                    otherRecorderConfirmedStopped = true,
                ),
            )

        assertFalse(evaluation.canStart)
        assertEquals(
            setOf(
                PreflightIssueCode.MICROPHONE_PERMISSION_REQUIRED,
                PreflightIssueCode.NOTIFICATION_PERMISSION_REQUIRED,
                PreflightIssueCode.MICROPHONE_ACCESS_BLOCKED,
                PreflightIssueCode.FOREGROUND_SERVICE_START_UNAVAILABLE,
                PreflightIssueCode.AUDIO_INPUT_NOT_INITIALIZED,
                PreflightIssueCode.NON_SILENCED_FRAMES_MISSING,
                PreflightIssueCode.WAKE_MODEL_INVALID,
                PreflightIssueCode.STORAGE_RESERVE_REACHED,
                PreflightIssueCode.PRIOR_CAPTURE_UNRESOLVED,
                PreflightIssueCode.CUE_VOLUME_TOO_LOW,
                PreflightIssueCode.CUE_PLAYBACK_BLOCKED,
            ),
            evaluation.blockers.map { it.code }.toSet(),
        )
        assertTrue(evaluation.blockers.all { it.severity == PreflightSeverity.BLOCKER })
        assertEquals(
            evaluation.blockers.size,
            evaluation.blockers.map { it.remediation }.toSet().size,
        )
        assertTrue(evaluation.warnings.isEmpty())
    }

    @Test
    fun `recommendations are warnings and never block start`() {
        val evaluation =
            PreflightEvaluator.evaluate(
                readyPreflightInput(
                    charging = false,
                    priorBatteryInterruption = true,
                    cueVolumeTested = false,
                    otherRecorderConfirmedStopped = false,
                ),
            )

        assertTrue(evaluation.canStart)
        assertTrue(evaluation.blockers.isEmpty())
        assertEquals(
            setOf(
                PreflightIssueCode.NOT_CHARGING,
                PreflightIssueCode.PRIOR_BATTERY_INTERRUPTION,
                PreflightIssueCode.CUE_VOLUME_UNTESTED,
                PreflightIssueCode.OTHER_RECORDER_NOT_CONFIRMED_STOPPED,
            ),
            evaluation.warnings.map { it.code }.toSet(),
        )
        assertTrue(evaluation.warnings.all { it.severity == PreflightSeverity.WARNING })
    }

    @Test
    fun `inaudible acknowledgement cue blocks start`() {
        val lowVolume = PreflightEvaluator.evaluate(
            readyPreflightInput(cueVolumeReady = false),
        )
        val blockedByMode = PreflightEvaluator.evaluate(
            readyPreflightInput(cuePlaybackAllowed = false),
        )

        assertFalse(lowVolume.canStart)
        assertEquals(
            PreflightIssueCode.CUE_VOLUME_TOO_LOW,
            lowVolume.blockers.single().code,
        )
        assertFalse(blockedByMode.canStart)
        assertEquals(
            PreflightIssueCode.CUE_PLAYBACK_BLOCKED,
            blockedByMode.blockers.single().code,
        )
    }

    @Test
    fun `fully ready input has no issues`() {
        val evaluation = PreflightEvaluator.evaluate(readyPreflightInput())

        assertTrue(evaluation.canStart)
        assertTrue(evaluation.blockers.isEmpty())
        assertTrue(evaluation.warnings.isEmpty())
        assertTrue(evaluation.deferredChecks.isEmpty())
    }

    @Test
    fun `starting checks can be explicitly deferred without hiding a failure`() {
        val evaluation =
            PreflightEvaluator.evaluate(
                readyPreflightInput(
                    foregroundServiceStartAllowed = null,
                    audioInputInitialized = null,
                    nonSilencedFramesReceived = null,
                ),
            )

        assertTrue(evaluation.canStart)
        assertTrue(evaluation.blockers.isEmpty())
        assertEquals(
            setOf(
                PreflightIssueCode.FOREGROUND_SERVICE_START_UNAVAILABLE,
                PreflightIssueCode.AUDIO_INPUT_NOT_INITIALIZED,
                PreflightIssueCode.NON_SILENCED_FRAMES_MISSING,
            ),
            evaluation.deferredChecks,
        )
    }
}
