package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStateMachineTest {
    @Test
    fun `happy path follows the complete legal lifecycle`() {
        val machine = CaptureStateMachine()

        assertEquals(
            CapturePhase.PREFLIGHT,
            machine.dispatch(CaptureEvent.BeginPreflight).phase,
        )
        machine.dispatch(
            CaptureEvent.PreflightEvaluated(
                PreflightEvaluator.evaluate(readyPreflightInput()),
            ),
        )
        assertTrue(machine.snapshot.canStart)

        machine.dispatch(CaptureEvent.StartNightRequested)
        assertEquals(CapturePhase.STARTING, machine.snapshot.phase)
        assertFalse(machine.snapshot.readyToSleep)

        machine.dispatch(CaptureEvent.ForegroundServiceStarted)
        assertFalse(machine.snapshot.readyToSleep)

        machine.dispatch(CaptureEvent.NonSilencedFramesReceived)
        assertEquals(CapturePhase.LISTENING, machine.snapshot.phase)
        assertTrue(machine.snapshot.readyToSleep)

        machine.dispatch(CaptureEvent.WakeDetected)
        assertEquals(CapturePhase.ACKNOWLEDGING, machine.snapshot.phase)
        assertTrue(machine.snapshot.hasActiveSession)

        machine.dispatch(CaptureEvent.CueFinished)
        assertEquals(CapturePhase.RECORDING, machine.snapshot.phase)

        machine.dispatch(CaptureEvent.NarrativeBoundaryReached)
        assertEquals(CapturePhase.FINALIZING, machine.snapshot.phase)

        machine.dispatch(CaptureEvent.SessionFinalized)
        assertEquals(CapturePhase.LISTENING, machine.snapshot.phase)

        machine.dispatch(
            CaptureEvent.EndNightRequested(NightEndReason.OWNER_REQUEST),
        )
        assertEquals(CapturePhase.ENDING, machine.snapshot.phase)
        assertNull(machine.snapshot.incompleteSessionReason)

        machine.dispatch(CaptureEvent.NightFinalized)
        assertEquals(CapturePhase.ENDED, machine.snapshot.phase)
        assertEquals(NightEndReason.OWNER_REQUEST, machine.snapshot.endReason)
    }

    @Test
    fun `required preflight blocker prevents start while warnings allow it`() {
        val blockedMachine = CaptureStateMachine()
        blockedMachine.dispatch(CaptureEvent.BeginPreflight)
        blockedMachine.dispatch(
            CaptureEvent.PreflightEvaluated(
                PreflightEvaluator.evaluate(
                    readyPreflightInput(microphonePermissionGranted = false),
                ),
            ),
        )

        assertFalse(blockedMachine.snapshot.canStart)
        assertThrows(IllegalCaptureTransitionException::class.java) {
            blockedMachine.dispatch(CaptureEvent.StartNightRequested)
        }
        assertEquals(CapturePhase.PREFLIGHT, blockedMachine.snapshot.phase)

        val warningOnlyMachine = CaptureStateMachine()
        warningOnlyMachine.dispatch(CaptureEvent.BeginPreflight)
        warningOnlyMachine.dispatch(
            CaptureEvent.PreflightEvaluated(
                PreflightEvaluator.evaluate(
                    readyPreflightInput(
                        charging = false,
                        priorBatteryInterruption = true,
                        cueVolumeTested = false,
                        otherRecorderConfirmedStopped = false,
                    ),
                ),
            ),
        )

        assertTrue(warningOnlyMachine.snapshot.canStart)
        warningOnlyMachine.dispatch(CaptureEvent.StartNightRequested)
        assertEquals(CapturePhase.STARTING, warningOnlyMachine.snapshot.phase)
    }

    @Test
    fun `ready to sleep requires service start followed by non-silenced frames`() {
        val machine = machineInStartingPhase()

        assertThrows(IllegalCaptureTransitionException::class.java) {
            machine.dispatch(CaptureEvent.NonSilencedFramesReceived)
        }
        assertEquals(CapturePhase.STARTING, machine.snapshot.phase)
        assertFalse(machine.snapshot.readyToSleep)

        machine.dispatch(CaptureEvent.ForegroundServiceStarted)
        assertFalse(machine.snapshot.readyToSleep)

        machine.dispatch(CaptureEvent.NonSilencedFramesReceived)
        assertTrue(machine.snapshot.readyToSleep)
    }

    @Test
    fun `illegal transition leaves current state unchanged`() {
        val machine = CaptureStateMachine()

        assertThrows(IllegalCaptureTransitionException::class.java) {
            machine.dispatch(CaptureEvent.WakeDetected)
        }

        assertEquals(CaptureSnapshot(), machine.snapshot)
    }

    @Test
    fun `exceptional end paths preserve a specific incomplete session reason`() {
        val expectations =
            mapOf(
                NightEndReason.OWNER_REQUEST to IncompleteSessionReason.NIGHT_ENDED,
                NightEndReason.SAFETY_TIMEOUT to IncompleteSessionReason.SAFETY_TIMEOUT,
                NightEndReason.CAPTURE_FAILURE to IncompleteSessionReason.CAPTURE_FAILURE,
                NightEndReason.STORAGE_RESERVE_REACHED to
                    IncompleteSessionReason.STORAGE_RESERVE_REACHED,
                NightEndReason.SERVICE_INTERRUPTION to
                    IncompleteSessionReason.SERVICE_INTERRUPTION,
            )

        expectations.forEach { (endReason, incompleteReason) ->
            val machine = machineInRecordingPhase()

            machine.dispatch(CaptureEvent.EndNightRequested(endReason))

            assertEquals(CapturePhase.ENDING, machine.snapshot.phase)
            assertEquals(endReason, machine.snapshot.endReason)
            assertEquals(incompleteReason, machine.snapshot.incompleteSessionReason)

            machine.dispatch(CaptureEvent.NightFinalized)
            if (endReason == NightEndReason.OWNER_REQUEST) {
                assertEquals(CapturePhase.ENDED, machine.snapshot.phase)
            } else {
                assertEquals(CapturePhase.INTERRUPTED, machine.snapshot.phase)
            }
        }
    }

    @Test
    fun `audio initialization failure is legal only while starting`() {
        val startingMachine = machineInStartingPhase()

        startingMachine.dispatch(
            CaptureEvent.EndNightRequested(NightEndReason.AUDIO_INITIALIZATION_FAILURE),
        )

        assertEquals(CapturePhase.ENDING, startingMachine.snapshot.phase)
        assertNull(startingMachine.snapshot.incompleteSessionReason)
        startingMachine.dispatch(CaptureEvent.NightFinalized)
        assertEquals(CapturePhase.INTERRUPTED, startingMachine.snapshot.phase)

        val recordingMachine = machineInRecordingPhase()
        assertThrows(IllegalCaptureTransitionException::class.java) {
            recordingMachine.dispatch(
                CaptureEvent.EndNightRequested(NightEndReason.AUDIO_INITIALIZATION_FAILURE),
            )
        }
        assertEquals(CapturePhase.RECORDING, recordingMachine.snapshot.phase)
    }

    @Test
    fun `a new preflight can begin after an interrupted night`() {
        val machine = machineInStartingPhase()
        machine.dispatch(
            CaptureEvent.EndNightRequested(NightEndReason.SERVICE_INTERRUPTION),
        )
        machine.dispatch(CaptureEvent.NightFinalized)

        assertEquals(CapturePhase.INTERRUPTED, machine.snapshot.phase)
        machine.dispatch(CaptureEvent.BeginPreflight)

        assertEquals(CapturePhase.PREFLIGHT, machine.snapshot.phase)
        assertNull(machine.snapshot.endReason)
    }

    private fun machineInStartingPhase(): CaptureStateMachine =
        CaptureStateMachine().apply {
            dispatch(CaptureEvent.BeginPreflight)
            dispatch(
                CaptureEvent.PreflightEvaluated(
                    PreflightEvaluator.evaluate(readyPreflightInput()),
                ),
            )
            dispatch(CaptureEvent.StartNightRequested)
        }

    private fun machineInRecordingPhase(): CaptureStateMachine =
        machineInStartingPhase().apply {
            dispatch(CaptureEvent.ForegroundServiceStarted)
            dispatch(CaptureEvent.NonSilencedFramesReceived)
            dispatch(CaptureEvent.WakeDetected)
            dispatch(CaptureEvent.CueFinished)
        }
}

internal fun readyPreflightInput(
    microphonePermissionGranted: Boolean = true,
    notificationPermissionGranted: Boolean = true,
    microphoneAccessAllowed: Boolean = true,
    foregroundServiceStartAllowed: Boolean? = true,
    audioInputInitialized: Boolean? = true,
    nonSilencedFramesReceived: Boolean? = true,
    wakeModelValid: Boolean = true,
    availableStorageBytes: Long = StorageGuard.PROTECTED_RESERVE_BYTES + 1,
    priorCaptureStateResolved: Boolean = true,
    cueVolumeReady: Boolean = true,
    cuePlaybackAllowed: Boolean = true,
    charging: Boolean = true,
    priorBatteryInterruption: Boolean = false,
    cueVolumeTested: Boolean = true,
    otherRecorderConfirmedStopped: Boolean = true,
): PreflightInput =
    PreflightInput(
        microphonePermissionGranted = microphonePermissionGranted,
        notificationPermissionGranted = notificationPermissionGranted,
        microphoneAccessAllowed = microphoneAccessAllowed,
        foregroundServiceStartAllowed = foregroundServiceStartAllowed,
        audioInputInitialized = audioInputInitialized,
        nonSilencedFramesReceived = nonSilencedFramesReceived,
        wakeModelValid = wakeModelValid,
        availableStorageBytes = availableStorageBytes,
        priorCaptureStateResolved = priorCaptureStateResolved,
        cueVolumeReady = cueVolumeReady,
        cuePlaybackAllowed = cuePlaybackAllowed,
        charging = charging,
        priorBatteryInterruption = priorBatteryInterruption,
        cueVolumeTested = cueVolumeTested,
        otherRecorderConfirmedStopped = otherRecorderConfirmedStopped,
    )
