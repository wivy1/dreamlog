package com.wivy.dreamlog.capture

data class PreflightInput(
    val microphonePermissionGranted: Boolean,
    val notificationPermissionGranted: Boolean,
    val microphoneAccessAllowed: Boolean,
    val foregroundServiceStartAllowed: Boolean?,
    val audioInputInitialized: Boolean?,
    val nonSilencedFramesReceived: Boolean?,
    val wakeModelValid: Boolean,
    val availableStorageBytes: Long,
    val priorCaptureStateResolved: Boolean,
    val cueVolumeReady: Boolean,
    val cuePlaybackAllowed: Boolean,
    val charging: Boolean,
    val priorBatteryInterruption: Boolean,
    val cueVolumeTested: Boolean,
    val otherRecorderConfirmedStopped: Boolean,
)

enum class PreflightIssueCode {
    MICROPHONE_PERMISSION_REQUIRED,
    NOTIFICATION_PERMISSION_REQUIRED,
    MICROPHONE_ACCESS_BLOCKED,
    FOREGROUND_SERVICE_START_UNAVAILABLE,
    AUDIO_INPUT_NOT_INITIALIZED,
    NON_SILENCED_FRAMES_MISSING,
    WAKE_MODEL_INVALID,
    STORAGE_RESERVE_REACHED,
    PRIOR_CAPTURE_UNRESOLVED,
    CUE_VOLUME_TOO_LOW,
    CUE_PLAYBACK_BLOCKED,
    NOT_CHARGING,
    PRIOR_BATTERY_INTERRUPTION,
    CUE_VOLUME_UNTESTED,
    OTHER_RECORDER_NOT_CONFIRMED_STOPPED,
}

enum class PreflightSeverity {
    BLOCKER,
    WARNING,
}

enum class PreflightRemediationCode {
    REQUEST_MICROPHONE_PERMISSION,
    REQUEST_NOTIFICATION_PERMISSION,
    ENABLE_MICROPHONE_ACCESS,
    START_FROM_VISIBLE_ACTIVITY,
    RETRY_AUDIO_INITIALIZATION,
    RETRY_WITH_OTHER_RECORDERS_STOPPED,
    REPAIR_WAKE_MODEL,
    FREE_STORAGE,
    RESOLVE_PRIOR_CAPTURE,
    ADJUST_CUE_VOLUME,
    ALLOW_CUE_PLAYBACK,
    CONNECT_CHARGER,
    REVIEW_BATTERY_SETTINGS,
    TEST_CUE_VOLUME,
    STOP_OTHER_RECORDER,
}

data class PreflightIssue(
    val code: PreflightIssueCode,
    val severity: PreflightSeverity,
    val remediation: PreflightRemediationCode,
)

data class PreflightEvaluation(
    val blockers: List<PreflightIssue>,
    val warnings: List<PreflightIssue>,
    val deferredChecks: Set<PreflightIssueCode> = emptySet(),
) {
    val canStart: Boolean
        get() = blockers.isEmpty()

    init {
        require(blockers.all { it.severity == PreflightSeverity.BLOCKER }) {
            "Every blocker must have blocker severity."
        }
        require(warnings.all { it.severity == PreflightSeverity.WARNING }) {
            "Every warning must have warning severity."
        }
    }
}

object PreflightEvaluator {
    fun evaluate(input: PreflightInput): PreflightEvaluation {
        val blockers = buildList {
            addBlockerUnless(
                input.microphonePermissionGranted,
                PreflightIssueCode.MICROPHONE_PERMISSION_REQUIRED,
                PreflightRemediationCode.REQUEST_MICROPHONE_PERMISSION,
            )
            addBlockerUnless(
                input.notificationPermissionGranted,
                PreflightIssueCode.NOTIFICATION_PERMISSION_REQUIRED,
                PreflightRemediationCode.REQUEST_NOTIFICATION_PERMISSION,
            )
            addBlockerUnless(
                input.microphoneAccessAllowed,
                PreflightIssueCode.MICROPHONE_ACCESS_BLOCKED,
                PreflightRemediationCode.ENABLE_MICROPHONE_ACCESS,
            )
            addBlockerIfFailed(
                input.foregroundServiceStartAllowed,
                PreflightIssueCode.FOREGROUND_SERVICE_START_UNAVAILABLE,
                PreflightRemediationCode.START_FROM_VISIBLE_ACTIVITY,
            )
            addBlockerIfFailed(
                input.audioInputInitialized,
                PreflightIssueCode.AUDIO_INPUT_NOT_INITIALIZED,
                PreflightRemediationCode.RETRY_AUDIO_INITIALIZATION,
            )
            addBlockerIfFailed(
                input.nonSilencedFramesReceived,
                PreflightIssueCode.NON_SILENCED_FRAMES_MISSING,
                PreflightRemediationCode.RETRY_WITH_OTHER_RECORDERS_STOPPED,
            )
            addBlockerUnless(
                input.wakeModelValid,
                PreflightIssueCode.WAKE_MODEL_INVALID,
                PreflightRemediationCode.REPAIR_WAKE_MODEL,
            )
            addBlockerUnless(
                !StorageGuard.isReserveBreached(input.availableStorageBytes),
                PreflightIssueCode.STORAGE_RESERVE_REACHED,
                PreflightRemediationCode.FREE_STORAGE,
            )
            addBlockerUnless(
                input.priorCaptureStateResolved,
                PreflightIssueCode.PRIOR_CAPTURE_UNRESOLVED,
                PreflightRemediationCode.RESOLVE_PRIOR_CAPTURE,
            )
            addBlockerUnless(
                input.cueVolumeReady,
                PreflightIssueCode.CUE_VOLUME_TOO_LOW,
                PreflightRemediationCode.ADJUST_CUE_VOLUME,
            )
            addBlockerUnless(
                input.cuePlaybackAllowed,
                PreflightIssueCode.CUE_PLAYBACK_BLOCKED,
                PreflightRemediationCode.ALLOW_CUE_PLAYBACK,
            )
        }

        val warnings = buildList {
            addWarningUnless(
                input.charging,
                PreflightIssueCode.NOT_CHARGING,
                PreflightRemediationCode.CONNECT_CHARGER,
            )
            if (input.priorBatteryInterruption) {
                add(
                    PreflightIssue(
                        code = PreflightIssueCode.PRIOR_BATTERY_INTERRUPTION,
                        severity = PreflightSeverity.WARNING,
                        remediation = PreflightRemediationCode.REVIEW_BATTERY_SETTINGS,
                    ),
                )
            }
            addWarningUnless(
                input.cueVolumeTested,
                PreflightIssueCode.CUE_VOLUME_UNTESTED,
                PreflightRemediationCode.TEST_CUE_VOLUME,
            )
            addWarningUnless(
                input.otherRecorderConfirmedStopped,
                PreflightIssueCode.OTHER_RECORDER_NOT_CONFIRMED_STOPPED,
                PreflightRemediationCode.STOP_OTHER_RECORDER,
            )
        }

        return PreflightEvaluation(
            blockers = blockers,
            warnings = warnings,
            deferredChecks =
                buildSet {
                    addIfDeferred(
                        input.foregroundServiceStartAllowed,
                        PreflightIssueCode.FOREGROUND_SERVICE_START_UNAVAILABLE,
                    )
                    addIfDeferred(
                        input.audioInputInitialized,
                        PreflightIssueCode.AUDIO_INPUT_NOT_INITIALIZED,
                    )
                    addIfDeferred(
                        input.nonSilencedFramesReceived,
                        PreflightIssueCode.NON_SILENCED_FRAMES_MISSING,
                    )
                },
        )
    }

    private fun MutableList<PreflightIssue>.addBlockerUnless(
        condition: Boolean,
        code: PreflightIssueCode,
        remediation: PreflightRemediationCode,
    ) {
        if (!condition) {
            add(
                PreflightIssue(
                    code = code,
                    severity = PreflightSeverity.BLOCKER,
                    remediation = remediation,
                ),
            )
        }
    }

    private fun MutableList<PreflightIssue>.addWarningUnless(
        condition: Boolean,
        code: PreflightIssueCode,
        remediation: PreflightRemediationCode,
    ) {
        if (!condition) {
            add(
                PreflightIssue(
                    code = code,
                    severity = PreflightSeverity.WARNING,
                    remediation = remediation,
                ),
            )
        }
    }

    private fun MutableList<PreflightIssue>.addBlockerIfFailed(
        result: Boolean?,
        code: PreflightIssueCode,
        remediation: PreflightRemediationCode,
    ) {
        if (result == false) {
            add(
                PreflightIssue(
                    code = code,
                    severity = PreflightSeverity.BLOCKER,
                    remediation = remediation,
                ),
            )
        }
    }

    private fun MutableSet<PreflightIssueCode>.addIfDeferred(
        result: Boolean?,
        code: PreflightIssueCode,
    ) {
        if (result == null) {
            add(code)
        }
    }
}
