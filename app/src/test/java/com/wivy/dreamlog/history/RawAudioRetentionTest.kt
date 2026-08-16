package com.wivy.dreamlog.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawAudioRetentionTest {
    @Test
    fun `thirty-day selection is inclusive and ordered by capture completion`() {
        val now = 60L * RawAudioRetentionPolicy.MILLIS_PER_DAY
        val exactlyThirtyDaysOld = endedRecord(
            nightId = "night-boundary",
            completedAt = now - RawAudioRetentionPolicy.DEFAULT_RETENTION_MILLIS,
        )
        val older = endedRecord(
            nightId = "night-older",
            completedAt = now - RawAudioRetentionPolicy.DEFAULT_RETENTION_MILLIS - 1L,
        )
        val oneMillisecondYoung = endedRecord(
            nightId = "night-young",
            completedAt = now - RawAudioRetentionPolicy.DEFAULT_RETENTION_MILLIS + 1L,
        )

        assertEquals(
            listOf("night-older", "night-boundary"),
            RawAudioRetentionPolicy.selectExpiredNightIds(
            records = listOf(exactlyThirtyDaysOld, oneMillisecondYoung, older),
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun `selection retains active ambiguous recovery and unavailable evidence`() {
        val now = 60L * RawAudioRetentionPolicy.MILLIS_PER_DAY
        val oldCompletion = now - RawAudioRetentionPolicy.DEFAULT_RETENTION_MILLIS - 1L
        val candidate = endedRecord("candidate", oldCompletion)
        val active = endedRecord("active", oldCompletion).let { record ->
            record.copy(
                night = record.night.copy(
                    captureState = NightCaptureState.ACTIVE,
                    endedAtEpochMillis = null,
                ),
            )
        }
        val future = endedRecord("future", now + 1L)
        val pendingRecovery = endedRecord(
            nightId = "pending",
            completedAt = oldCompletion,
            audioState = AudioEvidenceState.PENDING_RECOVERY,
        )
        val unfinalized = endedRecord("unfinalized", oldCompletion).let { record ->
            record.copy(
                sessions = record.sessions.map { it.copy(finalizedAtEpochMillis = null) },
            )
        }
        val mismatchedOwner = endedRecord("mismatched", oldCompletion).let { record ->
            record.copy(sessions = record.sessions.map { it.copy(nightId = "another-night") })
        }
        val deleted = endedRecord(
            nightId = "deleted",
            completedAt = oldCompletion,
            audioState = AudioEvidenceState.DELETED,
        )
        val expired = endedRecord(
            nightId = "expired",
            completedAt = oldCompletion,
            audioState = AudioEvidenceState.EXPIRED,
        )
        val duplicateCandidate = endedRecord("duplicate", oldCompletion)

        assertEquals(
            listOf("candidate"),
            RawAudioRetentionPolicy.selectExpiredNightIds(
                records = listOf(
                    candidate,
                    active,
                    future,
                    pendingRecovery,
                    unfinalized,
                    mismatchedOwner,
                    deleted,
                    expired,
                    duplicateCandidate,
                    duplicateCandidate,
                ),
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun `selection excludes in-use nights and validates its clock inputs`() {
        val record = endedRecord(nightId = "night-in-use", completedAt = 10L)
        val maximumAgeRecord = endedRecord(nightId = "night-maximum-age", completedAt = 0L)

        assertTrue(
            RawAudioRetentionPolicy.selectExpiredNightIds(
                records = listOf(record),
                nowEpochMillis = 10L,
                retentionMillis = 0L,
            ).isNotEmpty(),
        )
        assertEquals(
            listOf(maximumAgeRecord.night.nightId),
            RawAudioRetentionPolicy.selectExpiredNightIds(
                records = listOf(maximumAgeRecord),
                nowEpochMillis = Long.MAX_VALUE,
                retentionMillis = Long.MAX_VALUE,
            ),
        )
        assertTrue(
            RawAudioRetentionPolicy.selectExpiredNightIds(
                records = listOf(record),
                nowEpochMillis = 10L,
                retentionMillis = 0L,
                inUseNightIds = setOf(record.night.nightId),
            ).isEmpty(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            RawAudioRetentionPolicy.selectExpiredNightIds(
                records = listOf(record),
                nowEpochMillis = -1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawAudioRetentionPolicy.selectExpiredNightIds(
                records = listOf(record),
                nowEpochMillis = 10L,
                retentionMillis = -1L,
            )
        }
    }

    @Test
    fun `shared leases are reference counted and close idempotently`() {
        val registry = RawAudioUseRegistry()
        val first = requireNotNull(registry.tryAcquireUse("night-a"))
        val second = requireNotNull(registry.tryAcquireUse("night-a"))

        assertEquals(setOf("night-a"), registry.snapshotInUseNightIds())
        assertNull(registry.tryAcquireDeletion("night-a"))

        first.close()
        first.close()
        assertNull(registry.tryAcquireDeletion("night-a"))

        second.close()
        val deletion = requireNotNull(registry.tryAcquireDeletion("night-a"))
        assertEquals(setOf("night-a"), registry.snapshotInUseNightIds())
        deletion.close()
        deletion.close()
        assertTrue(registry.snapshotInUseNightIds().isEmpty())
    }

    @Test
    fun `exclusive leases block only their night and close idempotently`() {
        val registry = RawAudioUseRegistry()
        val deletion = requireNotNull(registry.tryAcquireDeletion("night-a"))

        assertNull(registry.tryAcquireDeletion("night-a"))
        assertNull(registry.tryAcquireUse("night-a"))
        val unrelatedUse = requireNotNull(registry.tryAcquireUse("night-b"))
        assertEquals(setOf("night-a", "night-b"), registry.snapshotInUseNightIds())

        deletion.close()
        deletion.close()
        assertFalse("night-a" in registry.snapshotInUseNightIds())
        requireNotNull(registry.tryAcquireUse("night-a")).close()
        unrelatedUse.close()
        assertTrue(registry.snapshotInUseNightIds().isEmpty())
    }

    @Test
    fun `multi-night acquisition is deduplicated and all-or-nothing`() {
        val registry = RawAudioUseRegistry()
        val blockedNight = requireNotNull(registry.tryAcquireDeletion("night-b"))

        assertNull(registry.tryAcquireUse(listOf("night-a", "night-b", "night-a")))
        assertEquals(setOf("night-b"), registry.snapshotInUseNightIds())

        blockedNight.close()
        val use = requireNotNull(
            registry.tryAcquireUse(listOf("night-b", "night-a", "night-a")),
        )
        assertEquals(setOf("night-a", "night-b"), registry.snapshotInUseNightIds())
        assertNull(registry.tryAcquireDeletion("night-a"))
        assertNull(registry.tryAcquireDeletion("night-b"))

        use.close()
        assertTrue(registry.snapshotInUseNightIds().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            registry.tryAcquireUse(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.tryAcquireDeletion(" ")
        }
    }

    private fun endedRecord(
        nightId: String,
        completedAt: Long,
        audioState: String = AudioEvidenceState.RETAINED,
    ): NightRecord {
        val startedAt = (completedAt - 1_000L).coerceAtLeast(0L)
        val finalizedAt = completedAt.coerceAtLeast(0L)
        return NightRecord(
            night = NightEntity(
                nightId = nightId,
                displayDate = "2026-08-01",
                startedAtEpochMillis = startedAt,
                startedUtcOffsetSeconds = 0,
                endedAtEpochMillis = completedAt,
                endedUtcOffsetSeconds = 0,
                captureState = NightCaptureState.ENDED,
                endReason = "owner_ended",
                interrupted = false,
                lastHeartbeatEpochMillis = null,
                lastHeartbeatUtcOffsetSeconds = null,
                reportedSessionCount = 1,
                reportedIncompleteSessionCount = 0,
                hadMicrophoneSilencing = false,
                hadAudioGap = false,
                rawAudioState = if (audioState == AudioEvidenceState.RETAINED) {
                    RawAudioState.RETAINED
                } else {
                    RawAudioState.UNAVAILABLE
                },
                transcriptionState = ProcessingState.COMPLETE,
                transcriptionFailure = null,
                enrichmentState = ProcessingState.COMPLETE,
                enrichmentFailure = null,
                importWarning = null,
            ),
            sessions = listOf(
                CaptureSessionEntity(
                    sessionId = "session-$nightId",
                    nightId = nightId,
                    captureOrder = 0,
                    startedAtEpochMillis = startedAt,
                    startedUtcOffsetSeconds = 0,
                    finalizedAtEpochMillis = finalizedAt,
                    finalizedUtcOffsetSeconds = 0,
                    incompleteReason = null,
                    audioFileName = "a_0123456789abcdef0123456789abcdef.wav",
                    audioState = audioState,
                    sampleRateHz = 16_000,
                    channelCount = 1,
                    bitsPerSample = 16,
                    sampleCount = 16_000L,
                    preRollSampleCount = 0L,
                    cueStartSample = 0L,
                    cueEndSampleExclusive = 0L,
                    automaticSilenceTailSampleCount = 1L,
                ),
            ),
            events = emptyList(),
        )
    }
}
