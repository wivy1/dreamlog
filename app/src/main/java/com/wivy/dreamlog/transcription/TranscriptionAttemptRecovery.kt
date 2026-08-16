package com.wivy.dreamlog.transcription

import com.wivy.dreamlog.history.TranscriptionDao

/**
 * Engine-independent startup recovery for attempts abandoned by an earlier app process.
 *
 * Call this before constructing or checking a transcription engine. DreamLog has no background
 * transcription worker, so a persisted running attempt cannot still be executing after a fresh
 * app-process start and is safe to turn into an explicit retryable failure.
 */
class TranscriptionAttemptRecovery(
    private val transcriptionDao: TranscriptionDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun recover(): Int {
        val recoveredAt = clock().coerceAtLeast(0L)
        return transcriptionDao.markStaleRunningSessionsFailed(
            // A fresh process cannot still own any persisted RUNNING attempt. Recover all of
            // them even if the wall clock moved backwards after the attempt was claimed.
            startedBeforeEpochMillis = Long.MAX_VALUE,
            recoveredAtEpochMillis = recoveredAt,
            failureDetail = FAILURE_DETAIL,
        )
    }

    companion object {
        const val FAILURE_DETAIL =
            "Transcription stopped before completion. The retained audio was kept; " +
                "resume transcription."
    }
}
