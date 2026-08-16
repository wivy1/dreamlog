package com.wivy.dreamlog.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureRuntimeSnapshot(
    val capture: CaptureSnapshot = CaptureSnapshot(),
    val nightId: String? = null,
    val displayDate: String? = null,
    val startedAtEpochMillis: Long? = null,
    val sessionCount: Int = 0,
    val incompleteSessionCount: Int = 0,
    val activeSessionId: String? = null,
    val lastSessionFileName: String? = null,
    val lastSessionReason: String? = null,
    val microphoneSilenced: Boolean = false,
    val visibleOtherRecorderCount: Int = 0,
    val gapCount: Int = 0,
    val readErrorCount: Int = 0,
    val lastHeartbeatEpochMillis: Long? = null,
    val charging: Boolean = false,
    val message: String = "Complete the checks below before starting a night.",
    val recentEvents: List<String> = emptyList(),
) {
    val phase: CapturePhase
        get() = capture.phase

    val active: Boolean
        get() =
            phase == CapturePhase.STARTING ||
                phase == CapturePhase.LISTENING ||
                phase == CapturePhase.ACKNOWLEDGING ||
                phase == CapturePhase.RECORDING ||
                phase == CapturePhase.FINALIZING ||
                phase == CapturePhase.ENDING
}

/**
 * Process-local presentation state backed by the legal capture state machine.
 *
 * Durable recovery truth remains in [CaptureJournalStore]. This store deliberately
 * does not pretend that Android will keep this process alive overnight.
 */
object CaptureRuntimeStore {
    private val lock = Any()
    private var machine = CaptureStateMachine()
    private val mutableSnapshots = MutableStateFlow(CaptureRuntimeSnapshot())

    val snapshots: StateFlow<CaptureRuntimeSnapshot> = mutableSnapshots.asStateFlow()

    fun prepareStart(
        evaluation: PreflightEvaluation,
        nightId: String,
        displayDate: String,
        startedAtEpochMillis: Long,
        charging: Boolean,
    ): CaptureRuntimeSnapshot = synchronized(lock) {
        when (machine.snapshot.phase) {
            CapturePhase.STOPPED,
            CapturePhase.ENDED,
            CapturePhase.INTERRUPTED,
            -> machine.dispatch(CaptureEvent.BeginPreflight)

            CapturePhase.PREFLIGHT -> Unit
            else -> throw IllegalStateException("A night is already active.")
        }
        machine.dispatch(CaptureEvent.PreflightEvaluated(evaluation))
        machine.dispatch(CaptureEvent.StartNightRequested)
        publish(
            CaptureRuntimeSnapshot(
                capture = machine.snapshot,
                nightId = nightId,
                displayDate = displayDate,
                startedAtEpochMillis = startedAtEpochMillis,
                charging = charging,
                message = "Starting the supported microphone service.",
                recentEvents = listOf("Start requested from the visible DreamLog screen."),
            ),
        )
    }

    fun markForegroundServiceStarted(): CaptureRuntimeSnapshot = synchronized(lock) {
        if (
            machine.snapshot.phase == CapturePhase.STARTING &&
            !machine.snapshot.foregroundServiceStarted
        ) {
            machine.dispatch(CaptureEvent.ForegroundServiceStarted)
        }
        updateLocked(
            message = "Starting the microphone and local wake model.",
            event = "The foreground microphone service started.",
        )
    }

    fun markReady(): CaptureRuntimeSnapshot = synchronized(lock) {
        if (machine.snapshot.phase == CapturePhase.STARTING) {
            machine.dispatch(CaptureEvent.NonSilencedFramesReceived)
        }
        updateLocked(
            microphoneSilenced = false,
            message = "Ready to sleep",
            event = "A fresh non-silenced microphone frame was verified.",
        )
    }

    fun markWakeDetected(
        sessionId: String,
        phrase: String,
    ): CaptureRuntimeSnapshot = synchronized(lock) {
        if (machine.snapshot.phase == CapturePhase.LISTENING) {
            machine.dispatch(CaptureEvent.WakeDetected)
        }
        updateLocked(
            activeSessionId = sessionId,
            message = "Recording",
            event = "Approved wake phrase detected: $phrase.",
        )
    }

    fun markCueFinished(): CaptureRuntimeSnapshot = synchronized(lock) {
        if (machine.snapshot.phase == CapturePhase.ACKNOWLEDGING) {
            machine.dispatch(CaptureEvent.CueFinished)
        }
        updateLocked(message = "Recording")
    }

    fun markSessionFinalizing(reason: String): CaptureRuntimeSnapshot = synchronized(lock) {
        if (machine.snapshot.phase == CapturePhase.ACKNOWLEDGING) {
            machine.dispatch(CaptureEvent.CueFinished)
        }
        if (machine.snapshot.phase == CapturePhase.RECORDING) {
            machine.dispatch(CaptureEvent.NarrativeBoundaryReached)
        }
        updateLocked(
            message = if (machine.snapshot.phase == CapturePhase.ENDING) {
                "Ending the night"
            } else {
                "Saving the recollection"
            },
            event = "Finalizing the current session: $reason.",
        )
    }

    fun markSessionFinalized(
        fileName: String,
        reason: String,
        incomplete: Boolean,
    ): CaptureRuntimeSnapshot = synchronized(lock) {
        if (machine.snapshot.phase == CapturePhase.FINALIZING) {
            machine.dispatch(CaptureEvent.SessionFinalized)
        }
        val current = mutableSnapshots.value
        publish(
            current.copy(
                capture = machine.snapshot,
                sessionCount = current.sessionCount + 1,
                incompleteSessionCount =
                    current.incompleteSessionCount + if (incomplete) 1 else 0,
                activeSessionId = null,
                lastSessionFileName = fileName,
                lastSessionReason = reason,
                message = if (machine.snapshot.phase == CapturePhase.LISTENING) {
                    "Ready to sleep"
                } else {
                    current.message
                },
                recentEvents = addEvent(
                    current.recentEvents,
                    if (incomplete) {
                        "Usable session audio was preserved as incomplete: $reason."
                    } else {
                        "Session audio finalized after the " +
                            "${NarrativeBoundaryDetector.DEFAULT_CONTINUOUS_NON_SPEECH_SECONDS}-second " +
                            "non-speech threshold."
                    },
                ),
            ),
        )
    }

    fun requestEnd(reason: NightEndReason): CaptureRuntimeSnapshot = synchronized(lock) {
        if (
            machine.snapshot.phase != CapturePhase.ENDING &&
            machine.snapshot.phase != CapturePhase.ENDED &&
            machine.snapshot.phase != CapturePhase.INTERRUPTED
        ) {
            machine.dispatch(CaptureEvent.EndNightRequested(reason))
        }
        updateLocked(
            message = "Ending the night",
            event = "Night end requested: ${reason.name.lowercase()}.",
        )
    }

    fun markNightFinalized(summary: String): CaptureRuntimeSnapshot = synchronized(lock) {
        if (machine.snapshot.phase == CapturePhase.ENDING) {
            machine.dispatch(CaptureEvent.NightFinalized)
        }
        val phase = machine.snapshot.phase
        updateLocked(
            activeSessionId = null,
            message = if (phase == CapturePhase.INTERRUPTED) {
                "Night interrupted"
            } else {
                "Night ended"
            },
            event = summary,
        )
    }

    fun restoreInterrupted(
        summary: String,
        sessionCount: Int,
        incompleteSessionCount: Int,
    ): CaptureRuntimeSnapshot = synchronized(lock) {
        machine = CaptureStateMachine(
            CaptureSnapshot(
                phase = CapturePhase.INTERRUPTED,
                endReason = NightEndReason.SERVICE_INTERRUPTION,
            ),
        )
        publish(
            CaptureRuntimeSnapshot(
                capture = machine.snapshot,
                sessionCount = sessionCount,
                incompleteSessionCount = incompleteSessionCount,
                message = "Night interrupted",
                recentEvents = listOf(summary),
            ),
        )
    }

    fun updateMicrophoneState(
        silenced: Boolean,
        visibleOtherRecorderCount: Int,
    ): CaptureRuntimeSnapshot = synchronized(lock) {
        val current = mutableSnapshots.value
        val changed =
            current.microphoneSilenced != silenced ||
                current.visibleOtherRecorderCount != visibleOtherRecorderCount
        publish(
            current.copy(
                microphoneSilenced = silenced,
                visibleOtherRecorderCount = visibleOtherRecorderCount,
                message = when {
                    silenced -> "Not listening — Android is silencing the microphone"
                    machine.snapshot.phase == CapturePhase.LISTENING -> "Ready to sleep"
                    else -> current.message
                },
                recentEvents = if (!changed) {
                    current.recentEvents
                } else {
                    addEvent(
                        current.recentEvents,
                        when {
                            silenced -> "Android reported that DreamLog's input is silenced."
                            visibleOtherRecorderCount > 0 ->
                                "Another recorder is visible; Android may silence one app."
                            else -> "Normal microphone input is available again."
                        },
                    )
                },
            ),
        )
    }

    fun recordGap(reason: String): CaptureRuntimeSnapshot = synchronized(lock) {
        val current = mutableSnapshots.value
        publish(
            current.copy(
                gapCount = current.gapCount + 1,
                recentEvents = addEvent(current.recentEvents, reason),
            ),
        )
    }

    fun recordReadError(reason: String): CaptureRuntimeSnapshot = synchronized(lock) {
        val current = mutableSnapshots.value
        publish(
            current.copy(
                readErrorCount = current.readErrorCount + 1,
                recentEvents = addEvent(current.recentEvents, reason),
            ),
        )
    }

    fun recordHeartbeat(
        epochMillis: Long,
        charging: Boolean,
    ): CaptureRuntimeSnapshot = synchronized(lock) {
        val current = mutableSnapshots.value
        publish(
            current.copy(
                lastHeartbeatEpochMillis = epochMillis,
                charging = charging,
            ),
        )
    }

    fun updateCharging(
        charging: Boolean,
        event: String,
    ): CaptureRuntimeSnapshot = synchronized(lock) {
        val current = mutableSnapshots.value
        publish(
            current.copy(
                charging = charging,
                recentEvents = addEvent(current.recentEvents, event),
            ),
        )
    }

    fun recordEvent(message: String): CaptureRuntimeSnapshot = synchronized(lock) {
        updateLocked(event = message)
    }

    private fun updateLocked(
        message: String? = null,
        event: String? = null,
        activeSessionId: String? = mutableSnapshots.value.activeSessionId,
        microphoneSilenced: Boolean = mutableSnapshots.value.microphoneSilenced,
    ): CaptureRuntimeSnapshot {
        val current = mutableSnapshots.value
        return publish(
            current.copy(
                capture = machine.snapshot,
                activeSessionId = activeSessionId,
                microphoneSilenced = microphoneSilenced,
                message = message ?: current.message,
                recentEvents =
                    if (event == null) current.recentEvents else addEvent(current.recentEvents, event),
            ),
        )
    }

    private fun publish(snapshot: CaptureRuntimeSnapshot): CaptureRuntimeSnapshot {
        mutableSnapshots.value = snapshot
        return snapshot
    }

    private fun addEvent(
        events: List<String>,
        event: String,
    ): List<String> = (listOf(event) + events).take(MAX_VISIBLE_EVENTS)

    private const val MAX_VISIBLE_EVENTS = 6
}
