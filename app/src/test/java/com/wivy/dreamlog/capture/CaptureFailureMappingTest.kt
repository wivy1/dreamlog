package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFailureMappingTest {
    @Test
    fun reserveAndAudioWriteFailuresKeepDistinctDurableReasons() {
        assertEquals(
            CaptureFailureKind.STORAGE_RESERVE,
            capturePersistenceFailureKind(SessionIncompleteReason.STORAGE_RESERVE_REACHED),
        )
        assertEquals(
            CaptureFailureKind.AUDIO_WRITE,
            capturePersistenceFailureKind(SessionIncompleteReason.WRITE_FAILED),
        )
        assertEquals(
            NightEndReason.STORAGE_RESERVE_REACHED,
            CaptureFailureKind.STORAGE_RESERVE.nightEndReason(),
        )
        assertEquals(
            NightEndReason.CAPTURE_FAILURE,
            CaptureFailureKind.AUDIO_WRITE.nightEndReason(),
        )
    }
}
