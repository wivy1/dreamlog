package com.wivy.dreamlog.capture

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlin.math.roundToInt

enum class CueOutputRoute {
    PHONE_SPEAKER,
    OTHER_OUTPUT,
    EXTERNAL_OUTPUT_CONNECTED,
    UNKNOWN,
    ;

    val mayBypassPhoneSpeaker: Boolean
        get() = this == OTHER_OUTPUT || this == EXTERNAL_OUTPUT_CONNECTED
}

data class CueAudioStatus(
    val streamType: Int,
    val streamName: String,
    val volumeIndex: Int,
    val minVolumeIndex: Int,
    val maxVolumeIndex: Int,
    val streamMuted: Boolean,
    val interruptionFilter: Int,
    val mediaAllowedByActiveFilter: Boolean?,
    val outputRoute: CueOutputRoute = CueOutputRoute.UNKNOWN,
) {
    val volumePercent: Int
        get() {
            val range = maxVolumeIndex - minVolumeIndex
            if (range <= 0) return 0
            return (
                (volumeIndex - minVolumeIndex).coerceIn(0, range) * 100f / range
            ).roundToInt()
        }

    val volumeMayBeTooLow: Boolean
        get() =
            streamMuted ||
                volumePercent <= CueAudioPreflight.LOW_VOLUME_WARNING_PERCENT

    val interruptionFilterName: String
        get() = when (interruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "No interruption filtering"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Priority only"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Alarms only"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "No interruptions"
            else -> "Unknown"
        }
}

/**
 * Reads the anticipated cue route, current volume, and interruption policy.
 *
 * The cue always uses unity track gain. This status intentionally does not
 * claim to identify a particular Do Not Disturb rule or change owner settings.
 */
object CueAudioPreflight {
    const val TRACK_GAIN_PERCENT = 100
    const val LOW_VOLUME_WARNING_PERCENT = 25

    fun audioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    fun volumeControlStream(): Int =
        if (Build.VERSION.SDK_INT >= 37) {
            AudioManager.STREAM_ASSISTANT
        } else {
            audioAttributes().volumeControlStream
        }

    fun read(context: Context): CueAudioStatus {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val streamType = volumeControlStream()
        val interruptionFilter = notificationManager.currentInterruptionFilter
        val mediaAllowed = when (interruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> {
                val categories = notificationManager
                    .consolidatedNotificationPolicy
                    .priorityCategories
                categories and NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA != 0
            }

            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            -> false

            else -> null
        }

        return CueAudioStatus(
            streamType = streamType,
            streamName = if (streamType == AudioManager.STREAM_ASSISTANT) {
                "Assistant"
            } else {
                "Media"
            },
            volumeIndex = audioManager.getStreamVolume(streamType),
            minVolumeIndex = audioManager.getStreamMinVolume(streamType),
            maxVolumeIndex = audioManager.getStreamMaxVolume(streamType),
            streamMuted = audioManager.isStreamMute(streamType),
            interruptionFilter = interruptionFilter,
            mediaAllowedByActiveFilter = mediaAllowed,
            outputRoute = readOutputRoute(audioManager),
        )
    }

    private fun readOutputRoute(audioManager: AudioManager): CueOutputRoute {
        val routedDeviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                audioManager.getAudioDevicesForAttributes(audioAttributes())
                    .map { it.type }
                    .toSet()
            }.getOrNull()
        } else {
            null
        }
        val anticipatedRoute = classifyOutputRoute(routedDeviceTypes)
        if (anticipatedRoute != CueOutputRoute.UNKNOWN) return anticipatedRoute

        // Connected devices cannot establish the route on older Android versions
        // or after a failed query. Preserve that uncertainty in the warning.
        val connectedDeviceTypes = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map { it.type }
                .toSet()
        }.getOrDefault(emptySet())
        return classifyOutputRoute(routedDeviceTypes, connectedDeviceTypes)
    }

    internal fun classifyOutputRoute(
        routedDeviceTypes: Set<Int>?,
        connectedDeviceTypes: Set<Int> = emptySet(),
    ): CueOutputRoute {
        if (routedDeviceTypes.orEmpty().any {
                it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
            }
        ) {
            return CueOutputRoute.PHONE_SPEAKER
        }
        if (routedDeviceTypes.orEmpty().any { it != AudioDeviceInfo.TYPE_UNKNOWN }) {
            return CueOutputRoute.OTHER_OUTPUT
        }
        if (connectedDeviceTypes.any { it in EXTERNAL_CUE_OUTPUT_TYPES }) {
            return CueOutputRoute.EXTERNAL_OUTPUT_CONNECTED
        }
        return CueOutputRoute.UNKNOWN
    }

    private val EXTERNAL_CUE_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
    )
}
