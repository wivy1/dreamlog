package com.wivy.dreamlog.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionRuntimeStoreTest {
    @Test
    fun snapshotExposesPinnedModelSizeAndOperationGates() {
        val idle = TranscriptionRuntimeSnapshot(
            initialized = true,
            modelPhase = TranscriptionModelPhase.NOT_INSTALLED,
        )

        assertEquals(663_043_117L, idle.modelTotalBytes)
        assertEquals(632.327, idle.modelSizeMiB, 0.0)
        assertEquals("632.327 MiB", idle.modelSizeLabel)
        assertTrue(idle.canInstallModel)
        assertFalse(idle.canRemoveModel)
        assertFalse(idle.busy)

        val installing = idle.copy(modelPhase = TranscriptionModelPhase.INSTALLING)
        assertTrue(installing.busy)
        assertFalse(installing.canInstallModel)

        val installed = idle.copy(modelPhase = TranscriptionModelPhase.INSTALLED)
        assertFalse(installed.canInstallModel)
        assertTrue(installed.canRemoveModel)

        val transcribing = installed.copy(
            transcriptionPhase = TranscriptionRuntimePhase.RUNNING,
        )
        assertTrue(transcribing.busy)
        assertFalse(transcribing.canRemoveModel)
    }

    @Test
    fun coordinatorProgressMapsCountsAndAdvancesHistoryRevision() {
        val initial = TranscriptionRuntimeSnapshot(
            initialized = true,
            modelPhase = TranscriptionModelPhase.INSTALLED,
            historyRevision = 4L,
        )
        val progress = NightTranscriptionProgress(
            nightId = "night-1",
            persistedState = "running",
            eligibleSessionCount = 4,
            unavailableSessionCount = 1,
            completedSessionCount = 2,
            failedSessionCount = 0,
            runningSessionCount = 1,
            pendingSessionCount = 1,
            activeSessionId = "session-3",
            retryableSessionIds = listOf("session-4"),
            requiresAppToRemainOpen = true,
        )

        val updated = initial.withProgress(progress, incrementHistory = true)

        assertEquals(TranscriptionRuntimePhase.RUNNING, updated.transcriptionPhase)
        assertEquals("night-1", updated.nightId)
        assertEquals("session-3", updated.activeSessionId)
        assertEquals(4, updated.eligibleSessionCount)
        assertEquals(1, updated.unavailableSessionCount)
        assertEquals(2, updated.completedSessionCount)
        assertEquals(0, updated.failedSessionCount)
        assertEquals(1, updated.runningSessionCount)
        assertEquals(1, updated.pendingSessionCount)
        assertEquals(listOf("session-4"), updated.retryableSessionIds)
        assertEquals(5L, updated.historyRevision)
    }

    @Test
    fun pausedSnapshotExposesOneProminentNightLevelResumeAction() {
        val snapshot = TranscriptionRuntimeSnapshot(
            initialized = true,
            modelPhase = TranscriptionModelPhase.INSTALLED,
            transcriptionPhase = TranscriptionRuntimePhase.ERROR,
            nightId = "night-1",
            eligibleSessionCount = 8,
            completedSessionCount = 7,
            failedSessionCount = 1,
            pendingSessionCount = 0,
            retryableSessionIds = listOf("session-8"),
            pauseReason = TranscriptionPauseReason.PROCESS_INTERRUPTED,
            pauseMessage = "Retained audio remains available.",
        )

        assertTrue(snapshot.resumeAvailable)
        assertEquals(
            "Resume transcription — 7 of 8 complete",
            snapshot.resumeActionLabel,
        )
        assertFalse(snapshot.busy)
    }

    @Test
    fun foregroundNotificationProgressContainsNoSessionContent() {
        assertEquals(
            "7 of 8 sessions complete",
            TranscriptionNotification.progressText(completedCount = 7, eligibleCount = 8),
        )
        assertEquals(
            "Preparing the verified local model",
            TranscriptionNotification.progressText(completedCount = 0, eligibleCount = 0),
        )
    }
}
