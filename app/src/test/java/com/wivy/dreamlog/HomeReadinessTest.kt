package com.wivy.dreamlog

import com.wivy.dreamlog.capture.CaptureRuntimeSnapshot
import com.wivy.dreamlog.capture.CueOutputRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReadinessTest {
    @Test
    fun completedHistoryDoesNotRequestEitherModel() {
        assertFalse(shouldVerifyLocalModel(true, false, false, false))
    }

    @Test
    fun pendingWorkWaitsForHistoryAndIdleCapture() {
        assertFalse(shouldVerifyLocalModel(false, false, false, true))
        assertFalse(shouldVerifyLocalModel(true, true, false, true))
        assertTrue(shouldVerifyLocalModel(true, false, false, true))
    }

    @Test
    fun modelControlsCanVerifyWhenCaptureIsIdle() {
        assertTrue(shouldVerifyLocalModel(false, false, true, false))
        assertFalse(shouldVerifyLocalModel(true, true, true, false))
    }

    @Test
    fun initialChecksDoNotShowASetupFailureOrStaleMorningAction() {
        assertEquals(
            "Getting ready",
            homePrimaryStatusTitle(
                runtime = CaptureRuntimeSnapshot(),
                morningAction = HomeMorningAction(HomeNextActionKind.ENRICH, "Ready to enrich", ""),
                startEnabled = false,
                setupNeedsAttention = true,
                checking = true,
            ),
        )
    }

    @Test
    fun routeWarningDistinguishesAnticipatedOutputFromConnectionFallback() {
        assertTrue(cueOutputWarning(CueOutputRoute.OTHER_OUTPUT).orEmpty().contains("phone speaker"))
        assertTrue(cueOutputWarning(CueOutputRoute.EXTERNAL_OUTPUT_CONNECTED).orEmpty().contains("may"))
        assertNull(cueOutputWarning(CueOutputRoute.PHONE_SPEAKER))
        assertNull(cueOutputWarning(CueOutputRoute.UNKNOWN))
    }
}
