package com.wivy.dreamlog.capture

enum class CapturePhase {
    STOPPED,
    PREFLIGHT,
    STARTING,
    LISTENING,
    ACKNOWLEDGING,
    RECORDING,
    FINALIZING,
    ENDING,
    ENDED,
    INTERRUPTED,
}

enum class NightEndReason {
    OWNER_REQUEST,
    SAFETY_TIMEOUT,
    AUDIO_INITIALIZATION_FAILURE,
    CAPTURE_FAILURE,
    STORAGE_RESERVE_REACHED,
    SERVICE_INTERRUPTION,
}

enum class IncompleteSessionReason {
    NIGHT_ENDED,
    SAFETY_TIMEOUT,
    CAPTURE_FAILURE,
    STORAGE_RESERVE_REACHED,
    SERVICE_INTERRUPTION,
}

sealed interface CaptureEvent {
    data object BeginPreflight : CaptureEvent

    data class PreflightEvaluated(
        val evaluation: PreflightEvaluation,
    ) : CaptureEvent

    data object CancelPreflight : CaptureEvent

    data object StartNightRequested : CaptureEvent

    data object ForegroundServiceStarted : CaptureEvent

    data object NonSilencedFramesReceived : CaptureEvent

    data object WakeDetected : CaptureEvent

    data object CueFinished : CaptureEvent

    data object NarrativeBoundaryReached : CaptureEvent

    data object SessionFinalized : CaptureEvent

    data class EndNightRequested(
        val reason: NightEndReason,
    ) : CaptureEvent

    data object NightFinalized : CaptureEvent
}

data class CaptureSnapshot(
    val phase: CapturePhase = CapturePhase.STOPPED,
    val preflightEvaluation: PreflightEvaluation? = null,
    val foregroundServiceStarted: Boolean = false,
    val endReason: NightEndReason? = null,
    val incompleteSessionReason: IncompleteSessionReason? = null,
) {
    val canStart: Boolean
        get() =
            phase == CapturePhase.PREFLIGHT &&
                preflightEvaluation?.canStart == true

    val readyToSleep: Boolean
        get() = phase == CapturePhase.LISTENING

    val hasActiveSession: Boolean
        get() =
            phase == CapturePhase.ACKNOWLEDGING ||
                phase == CapturePhase.RECORDING ||
                phase == CapturePhase.FINALIZING
}

class IllegalCaptureTransitionException(
    val phase: CapturePhase,
    val event: CaptureEvent,
    detail: String? = null,
) : IllegalStateException(
        buildString {
            append("Event ")
            append(event)
            append(" is not legal in ")
            append(phase)
            if (detail != null) {
                append(": ")
                append(detail)
            }
        },
    )

class CaptureStateMachine(
    initialSnapshot: CaptureSnapshot = CaptureSnapshot(),
) {
    var snapshot: CaptureSnapshot = initialSnapshot
        private set

    fun dispatch(event: CaptureEvent): CaptureSnapshot {
        val next = reduce(snapshot, event)
        snapshot = next
        return next
    }

    private fun reduce(
        current: CaptureSnapshot,
        event: CaptureEvent,
    ): CaptureSnapshot =
        when (event) {
            CaptureEvent.BeginPreflight -> {
                current.requirePhase(
                    event,
                    CapturePhase.STOPPED,
                    CapturePhase.ENDED,
                    CapturePhase.INTERRUPTED,
                )
                CaptureSnapshot(phase = CapturePhase.PREFLIGHT)
            }

            is CaptureEvent.PreflightEvaluated -> {
                current.requirePhase(event, CapturePhase.PREFLIGHT)
                current.copy(preflightEvaluation = event.evaluation)
            }

            CaptureEvent.CancelPreflight -> {
                current.requirePhase(event, CapturePhase.PREFLIGHT)
                CaptureSnapshot()
            }

            CaptureEvent.StartNightRequested -> {
                current.requirePhase(event, CapturePhase.PREFLIGHT)
                if (!current.canStart) {
                    throw IllegalCaptureTransitionException(
                        current.phase,
                        event,
                        "required preflight checks have not passed",
                    )
                }
                current.copy(
                    phase = CapturePhase.STARTING,
                    foregroundServiceStarted = false,
                )
            }

            CaptureEvent.ForegroundServiceStarted -> {
                current.requirePhase(event, CapturePhase.STARTING)
                if (current.foregroundServiceStarted) {
                    throw IllegalCaptureTransitionException(
                        current.phase,
                        event,
                        "the foreground service is already marked started",
                    )
                }
                current.copy(foregroundServiceStarted = true)
            }

            CaptureEvent.NonSilencedFramesReceived -> {
                current.requirePhase(event, CapturePhase.STARTING)
                if (!current.foregroundServiceStarted) {
                    throw IllegalCaptureTransitionException(
                        current.phase,
                        event,
                        "the foreground service has not started",
                    )
                }
                current.copy(phase = CapturePhase.LISTENING)
            }

            CaptureEvent.WakeDetected -> {
                current.requirePhase(event, CapturePhase.LISTENING)
                current.copy(phase = CapturePhase.ACKNOWLEDGING)
            }

            CaptureEvent.CueFinished -> {
                current.requirePhase(event, CapturePhase.ACKNOWLEDGING)
                current.copy(phase = CapturePhase.RECORDING)
            }

            CaptureEvent.NarrativeBoundaryReached -> {
                current.requirePhase(event, CapturePhase.RECORDING)
                current.copy(phase = CapturePhase.FINALIZING)
            }

            CaptureEvent.SessionFinalized -> {
                current.requirePhase(event, CapturePhase.FINALIZING)
                current.copy(phase = CapturePhase.LISTENING)
            }

            is CaptureEvent.EndNightRequested -> endNight(current, event)

            CaptureEvent.NightFinalized -> {
                current.requirePhase(event, CapturePhase.ENDING)
                val endReason =
                    current.endReason
                        ?: throw IllegalCaptureTransitionException(
                            current.phase,
                            event,
                            "an ending night must have an end reason",
                        )
                current.copy(
                    phase =
                        if (endReason == NightEndReason.OWNER_REQUEST) {
                            CapturePhase.ENDED
                        } else {
                            CapturePhase.INTERRUPTED
                        },
                )
            }
        }

    private fun endNight(
        current: CaptureSnapshot,
        event: CaptureEvent.EndNightRequested,
    ): CaptureSnapshot {
        current.requirePhase(
            event,
            CapturePhase.STARTING,
            CapturePhase.LISTENING,
            CapturePhase.ACKNOWLEDGING,
            CapturePhase.RECORDING,
            CapturePhase.FINALIZING,
        )
        if (
            event.reason == NightEndReason.AUDIO_INITIALIZATION_FAILURE &&
            current.phase != CapturePhase.STARTING
        ) {
            throw IllegalCaptureTransitionException(
                current.phase,
                event,
                "audio initialization can fail only while starting",
            )
        }

        val incompleteReason =
            if (current.hasActiveSession) {
                when (event.reason) {
                    NightEndReason.OWNER_REQUEST -> IncompleteSessionReason.NIGHT_ENDED
                    NightEndReason.SAFETY_TIMEOUT -> IncompleteSessionReason.SAFETY_TIMEOUT
                    NightEndReason.AUDIO_INITIALIZATION_FAILURE ->
                        error("An active session cannot have an initialization failure.")
                    NightEndReason.CAPTURE_FAILURE -> IncompleteSessionReason.CAPTURE_FAILURE
                    NightEndReason.STORAGE_RESERVE_REACHED ->
                        IncompleteSessionReason.STORAGE_RESERVE_REACHED
                    NightEndReason.SERVICE_INTERRUPTION ->
                        IncompleteSessionReason.SERVICE_INTERRUPTION
                }
            } else {
                null
            }

        return current.copy(
            phase = CapturePhase.ENDING,
            endReason = event.reason,
            incompleteSessionReason = incompleteReason,
        )
    }

    private fun CaptureSnapshot.requirePhase(
        event: CaptureEvent,
        vararg legalPhases: CapturePhase,
    ) {
        if (phase !in legalPhases) {
            throw IllegalCaptureTransitionException(phase, event)
        }
    }
}
