package com.wivy.dreamlog.capture

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build

data class AndroidPreflightSnapshot(
    val evaluation: PreflightEvaluation,
    val microphonePermissionGranted: Boolean,
    val notificationPermissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val microphoneAccessAllowed: Boolean,
    val charging: Boolean,
    val availableStorageBytes: Long,
    val visibleOtherRecorderCount: Int,
    val cueAudioStatus: CueAudioStatus?,
    val assetValidation: CaptureAssetValidation,
)

object AndroidPreflight {
    fun evaluate(
        context: Context,
        priorCaptureStateResolved: Boolean,
        priorInterruption: Boolean,
        cueTestedThisVisit: Boolean,
    ): AndroidPreflightSnapshot {
        val microphonePermissionGranted =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val notificationPermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        NightNotification.ensureChannel(context)
        val channelEnabled =
            notificationManager.getNotificationChannel(NightNotification.CHANNEL_ID)?.importance !=
                NotificationManager.IMPORTANCE_NONE
        val notificationsEnabled =
            notificationManager.areNotificationsEnabled() && channelEnabled
        val audioManager = context.getSystemService(AudioManager::class.java)
        val microphoneAccessAllowed =
            !microphonePermissionGranted || !audioManager.isMicrophoneMute
        val visibleOtherRecorderCount =
            if (microphonePermissionGranted) {
                runCatching { audioManager.activeRecordingConfigurations.size }.getOrDefault(0)
            } else {
                0
            }
        val charging = readCharging(context)
        val cueAudioStatus = runCatching { CueAudioPreflight.read(context) }.getOrNull()
        val assetValidation = CaptureAssets.validate(context)
        val availableStorageBytes = context.filesDir.usableSpace.coerceAtLeast(0L)
        val evaluation = PreflightEvaluator.evaluate(
            PreflightInput(
                microphonePermissionGranted = microphonePermissionGranted,
                notificationPermissionGranted =
                    notificationPermissionGranted && notificationsEnabled,
                microphoneAccessAllowed = microphoneAccessAllowed,
                foregroundServiceStartAllowed = null,
                audioInputInitialized = null,
                nonSilencedFramesReceived = null,
                wakeModelValid = assetValidation.valid,
                availableStorageBytes = availableStorageBytes,
                priorCaptureStateResolved = priorCaptureStateResolved,
                cueVolumeReady = cueAudioStatus?.volumeMayBeTooLow != true,
                cuePlaybackAllowed = cueAudioStatus?.mediaAllowedByActiveFilter != false,
                charging = charging,
                priorBatteryInterruption = priorInterruption,
                cueVolumeTested = cueTestedThisVisit,
                // Android cannot prove which app owns another recording client.
                // Keep the mutually-exclusive recorder guidance visible and non-blocking.
                otherRecorderConfirmedStopped = false,
            ),
        )

        return AndroidPreflightSnapshot(
            evaluation = evaluation,
            microphonePermissionGranted = microphonePermissionGranted,
            notificationPermissionGranted = notificationPermissionGranted,
            notificationsEnabled = notificationsEnabled,
            microphoneAccessAllowed = microphoneAccessAllowed,
            charging = charging,
            availableStorageBytes = availableStorageBytes,
            visibleOtherRecorderCount = visibleOtherRecorderCount,
            cueAudioStatus = cueAudioStatus,
            assetValidation = assetValidation,
        )
    }

    private fun readCharging(context: Context): Boolean {
        val battery = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return false
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }
}
