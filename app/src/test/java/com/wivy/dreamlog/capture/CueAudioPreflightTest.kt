package com.wivy.dreamlog.capture

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueAudioPreflightTest {
    @Test
    fun `volume at warning threshold may be too low`() {
        assertTrue(status(volumePercent = 25).volumeMayBeTooLow)
    }

    @Test
    fun `volume above warning threshold is not flagged`() {
        assertFalse(status(volumePercent = 26).volumeMayBeTooLow)
    }

    @Test
    fun `muted stream is flagged above warning threshold`() {
        assertTrue(status(volumePercent = 80, streamMuted = true).volumeMayBeTooLow)
    }

    @Test
    fun `bluetooth acknowledgement route warns that the phone speaker may be bypassed`() {
        for (deviceType in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )) {
            val route = CueAudioPreflight.classifyOutputRoute(setOf(deviceType))

            assertEquals(CueOutputRoute.OTHER_OUTPUT, route)
            assertTrue(route.mayBypassPhoneSpeaker)
        }
    }

    @Test
    fun `phone speaker route takes precedence over a connected bluetooth device`() {
        val route = CueAudioPreflight.classifyOutputRoute(
            routedDeviceTypes = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            connectedDeviceTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
        )

        assertEquals(CueOutputRoute.PHONE_SPEAKER, route)
        assertFalse(route.mayBypassPhoneSpeaker)
    }

    @Test
    fun `duplicated route including the phone speaker does not warn`() {
        for (speakerType in listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        )) {
            val route = CueAudioPreflight.classifyOutputRoute(
                setOf(speakerType, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            )

            assertEquals(CueOutputRoute.PHONE_SPEAKER, route)
            assertFalse(route.mayBypassPhoneSpeaker)
        }
    }

    @Test
    fun `wired usb and earpiece acknowledgement routes also bypass the phone speaker`() {
        for (deviceType in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_HDMI,
        )) {
            val route = CueAudioPreflight.classifyOutputRoute(setOf(deviceType))

            assertEquals(CueOutputRoute.OTHER_OUTPUT, route)
            assertTrue(route.mayBypassPhoneSpeaker)
        }
    }

    @Test
    fun `unavailable route query falls back to an explicitly uncertain connected device warning`() {
        for (routedTypes in listOf(null, emptySet(), setOf(AudioDeviceInfo.TYPE_UNKNOWN))) {
            val route = CueAudioPreflight.classifyOutputRoute(
                routedDeviceTypes = routedTypes,
                connectedDeviceTypes = setOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                ),
            )

            assertEquals(CueOutputRoute.EXTERNAL_OUTPUT_CONNECTED, route)
            assertTrue(route.mayBypassPhoneSpeaker)
        }
    }

    @Test
    fun `unavailable route without an external output stays unknown`() {
        val route = CueAudioPreflight.classifyOutputRoute(
            routedDeviceTypes = null,
            connectedDeviceTypes = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_TELEPHONY,
            ),
        )

        assertEquals(CueOutputRoute.UNKNOWN, route)
        assertFalse(route.mayBypassPhoneSpeaker)
    }

    private fun status(
        volumePercent: Int,
        streamMuted: Boolean = false,
    ): CueAudioStatus =
        CueAudioStatus(
            streamType = 0,
            streamName = "Assistant",
            volumeIndex = volumePercent,
            minVolumeIndex = 0,
            maxVolumeIndex = 100,
            streamMuted = streamMuted,
            interruptionFilter = 0,
            mediaAllowedByActiveFilter = true,
        )
}
