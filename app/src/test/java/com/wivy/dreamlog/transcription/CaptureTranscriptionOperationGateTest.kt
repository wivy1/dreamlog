package com.wivy.dreamlog.transcription

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTranscriptionOperationGateTest {
    @After
    fun releaseClaims() {
        CaptureTranscriptionOperationGate.cancelCaptureStartReservation()
        CaptureTranscriptionOperationGate.releaseLocalOperation()
    }

    @Test
    fun captureReservationBlocksLocalWorkAcrossPersistenceWindow() {
        assertTrue(
            CaptureTranscriptionOperationGate.tryReserveCaptureStart(captureActive = { false }),
        )

        assertFalse(
            CaptureTranscriptionOperationGate.tryClaimLocalOperation(captureActive = { false }),
        )

        var capturePrepared = false
        CaptureTranscriptionOperationGate.finishReservedCaptureStart {
            capturePrepared = true
        }
        assertTrue(capturePrepared)
        assertTrue(
            CaptureTranscriptionOperationGate.tryClaimLocalOperation(captureActive = { false }),
        )
    }

    @Test
    fun localWorkBlocksCaptureReservationUntilReleased() {
        assertTrue(
            CaptureTranscriptionOperationGate.tryClaimLocalOperation(captureActive = { false }),
        )

        assertFalse(
            CaptureTranscriptionOperationGate.tryReserveCaptureStart(captureActive = { false }),
        )

        CaptureTranscriptionOperationGate.releaseLocalOperation()
        assertTrue(
            CaptureTranscriptionOperationGate.tryReserveCaptureStart(captureActive = { false }),
        )
    }
}
