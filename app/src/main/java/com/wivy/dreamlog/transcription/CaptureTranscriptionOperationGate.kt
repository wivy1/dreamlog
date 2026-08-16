package com.wivy.dreamlog.transcription

/**
 * Makes capture start and every finite local model, transcription, or enrichment claim mutually
 * exclusive.
 *
 * Both sides still own their durable state and normal locks. This narrow process-local gate only
 * closes the check-then-act window where each side could otherwise observe the other as idle.
 */
internal object CaptureTranscriptionOperationGate {
    private val lock = Any()
    private var localOperationClaimed = false
    private var captureStartReserved = false

    fun tryClaimLocalOperation(captureActive: () -> Boolean): Boolean = synchronized(lock) {
        if (localOperationClaimed || captureStartReserved || captureActive()) {
            return@synchronized false
        }
        localOperationClaimed = true
        true
    }

    fun releaseLocalOperation() = synchronized(lock) {
        localOperationClaimed = false
    }

    fun tryReserveCaptureStart(captureActive: () -> Boolean): Boolean = synchronized(lock) {
        if (localOperationClaimed || captureStartReserved || captureActive()) {
            return@synchronized false
        }
        captureStartReserved = true
        true
    }

    fun cancelCaptureStartReservation() = synchronized(lock) {
        captureStartReserved = false
    }

    fun <T> finishReservedCaptureStart(block: () -> T): T = synchronized(lock) {
        check(captureStartReserved) {
            "DreamLog no longer owns the pending capture-start reservation."
        }
        try {
            check(!localOperationClaimed) {
                "Finish the current local model, transcription, or enrichment task before starting a night."
            }
            block()
        } finally {
            captureStartReserved = false
        }
    }
}
