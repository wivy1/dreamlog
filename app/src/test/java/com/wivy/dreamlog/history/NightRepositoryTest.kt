package com.wivy.dreamlog.history

import com.wivy.dreamlog.capture.CaptureJournalStore
import com.wivy.dreamlog.capture.SessionAudioMetadata
import com.wivy.dreamlog.capture.SessionAudioWriter
import com.wivy.dreamlog.capture.SessionIncompleteReason
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NightRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun endedImportRecoversEighthFinalizedPostMidnightSessionInChronologicalOrder() {
        val fixture = fixture("orphan-eighth-session")
        val startedAt = epoch("2026-08-11T04:30:00Z")
        val localMidnight = epoch("2026-08-11T05:00:00Z")
        val endedAt = epoch("2026-08-11T06:30:00Z")
        val sessionStarts = listOf(
            "2026-08-11T04:35:00Z",
            "2026-08-11T04:42:00Z",
            "2026-08-11T04:49:00Z",
            "2026-08-11T04:56:00Z",
            "2026-08-11T05:03:00Z",
            "2026-08-11T05:10:00Z",
            "2026-08-11T05:17:00Z",
            "2026-08-11T05:24:00Z",
        ).map(::epoch)
        var now = startedAt
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = ORPHAN_EIGHTH_NIGHT_ID,
            displayDate = "2026-08-10",
            startedAtEpochMillis = startedAt,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        val writer = writer(
            directory = File(fixture.audioRoot, ORPHAN_EIGHTH_NIGHT_ID),
            sessionIds = ORPHAN_EIGHTH_SESSION_IDS,
            clock = { now },
        )
        val metadata = sessionStarts.mapIndexed { index, sessionStart ->
            val session = writer.startSession(
                preRoll = shortArrayOf(index.toShort()),
                startedAtEpochMillis = sessionStart,
            )
            session.markCueStart()
            session.append(shortArrayOf((index + 1).toShort()))
            session.markCueEnd()
            now = sessionStart + 20_000L
            session.finalizeComplete(automaticSilenceTailSampleCount = 1L).also {
                if (index < 7) journal.recordSessionFinalized(it)
            }
        }
        now = endedAt
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = endedAt,
        )

        val result = fixture.repository(journal).reconcile(runtimeActiveNightId = null)
        val record = result.nights.single()

        assertEquals(1, result.warningCount)
        assertEquals(1, result.acknowledgedNightCount)
        assertEquals(8, record.sessions.size)
        assertEquals(8, record.night.reportedSessionCount)
        assertEquals(0, record.night.reportedIncompleteSessionCount)
        assertEquals(RawAudioState.RETAINED, record.night.rawAudioState)
        assertEquals(sessionStarts, record.sessions.map { it.startedAtEpochMillis })
        assertEquals((0..7).toList(), record.sessions.map { it.captureOrder })
        assertEquals(metadata.last().sessionId, record.sessions.last().sessionId)
        assertTrue(record.sessions.last().startedAtEpochMillis!! > localMidnight)
        assertTrue(record.night.importWarning!!.contains("Recovered 1 finalized session"))
    }

    @Test
    fun endedImportRejectsInvalidUnpairedAndOutOfIntervalOrphans() {
        val fixture = fixture("invalid-orphans")
        val startedAt = epoch("2026-08-11T04:30:00Z")
        val endedAt = epoch("2026-08-11T06:30:00Z")
        var now = startedAt
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = INVALID_ORPHAN_NIGHT_ID,
            displayDate = "2026-08-10",
            startedAtEpochMillis = startedAt,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        val audioDirectory = File(fixture.audioRoot, INVALID_ORPHAN_NIGHT_ID)
        val writer = writer(
            directory = audioDirectory,
            sessionIds = INVALID_ORPHAN_SESSION_IDS,
            clock = { now },
        )
        val journaled = completeSession(
            writer = writer,
            startedAtEpochMillis = epoch("2026-08-11T04:45:00Z"),
            finalizedAtEpochMillis = epoch("2026-08-11T04:45:20Z"),
            setClock = { now = it },
        )
        journal.recordSessionFinalized(journaled)

        val corrupt = completeSession(
            writer = writer,
            startedAtEpochMillis = epoch("2026-08-11T05:20:00Z"),
            finalizedAtEpochMillis = epoch("2026-08-11T05:20:20Z"),
            setClock = { now = it },
        )
        RandomAccessFile(File(audioDirectory, corrupt.audioFileName), "rw").use { audio ->
            audio.seek(0L)
            audio.writeBytes("NOPE")
        }
        val unpaired = completeSession(
            writer = writer,
            startedAtEpochMillis = epoch("2026-08-11T05:40:00Z"),
            finalizedAtEpochMillis = epoch("2026-08-11T05:40:20Z"),
            setClock = { now = it },
        )
        assertTrue(metadataFile(audioDirectory, unpaired.audioFileName).delete())
        completeSession(
            writer = writer,
            startedAtEpochMillis = endedAt + 1_000L,
            finalizedAtEpochMillis = endedAt + 21_000L,
            setClock = { now = it },
        )
        now = endedAt
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = endedAt,
        )

        val result = fixture.repository(journal).reconcile(runtimeActiveNightId = null)
        val record = result.nights.single()

        assertEquals(0, result.warningCount)
        assertEquals(listOf(journaled.sessionId), record.sessions.map { it.sessionId })
        assertEquals(1, record.night.reportedSessionCount)
        assertEquals(RawAudioState.RETAINED, record.night.rawAudioState)
        assertNull(record.night.importWarning)
    }

    @Test
    fun recoveredFinalizedSessionPreservesProtectedDreamGraph() {
        val fixture = fixture("protected-orphan")
        val startedAt = epoch("2026-08-11T04:30:00Z")
        val endedAt = epoch("2026-08-11T06:30:00Z")
        var now = startedAt
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = PROTECTED_ORPHAN_NIGHT_ID,
            displayDate = "2026-08-10",
            startedAtEpochMillis = startedAt,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        val writer = writer(
            directory = File(fixture.audioRoot, PROTECTED_ORPHAN_NIGHT_ID),
            sessionIds = PROTECTED_ORPHAN_SESSION_IDS,
            clock = { now },
        )
        val journaled = completeSession(
            writer = writer,
            startedAtEpochMillis = epoch("2026-08-11T04:45:00Z"),
            finalizedAtEpochMillis = epoch("2026-08-11T04:45:20Z"),
            setClock = { now = it },
        )
        journal.recordSessionFinalized(journaled)
        completeSession(
            writer = writer,
            startedAtEpochMillis = epoch("2026-08-11T05:20:00Z"),
            finalizedAtEpochMillis = epoch("2026-08-11T05:20:20Z"),
            setClock = { now = it },
        )
        now = endedAt
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = endedAt,
        )
        fixture.dao.seed(
            night = NightEntity(
                nightId = PROTECTED_ORPHAN_NIGHT_ID,
                displayDate = "2026-08-10",
                startedAtEpochMillis = startedAt,
                startedUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
                endedAtEpochMillis = endedAt,
                endedUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
                captureState = NightCaptureState.ENDED,
                endReason = "owner_ended",
                interrupted = false,
                lastHeartbeatEpochMillis = endedAt,
                lastHeartbeatUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
                reportedSessionCount = 1,
                reportedIncompleteSessionCount = 0,
                hadMicrophoneSilencing = false,
                hadAudioGap = false,
                rawAudioState = RawAudioState.RETAINED,
                transcriptionState = ProcessingState.COMPLETE,
                transcriptionFailure = null,
                enrichmentState = ProcessingState.COMPLETE,
                enrichmentFailure = null,
                importWarning = null,
            ),
            sessions = listOf(journaled.toEntity(PROTECTED_ORPHAN_NIGHT_ID, captureOrder = 0)),
            dreams = listOf(
                DreamWithSourceSpans(
                    dream = DreamEntity(
                        dreamId = PROTECTED_DREAM_ID,
                        nightId = PROTECTED_ORPHAN_NIGHT_ID,
                        runId = PROTECTED_RUN_ID,
                        dreamOrder = 0,
                        kind = "dream",
                        isUncertain = false,
                        generatedTitle = "Generated",
                        generatedText = "Generated text",
                        currentTitle = "My title",
                        currentText = "My protected text",
                        ownerEdited = true,
                        editedAtEpochMillis = endedAt + 1_000L,
                    ),
                    sourceSpans = emptyList(),
                ),
            ),
        )

        val record = fixture.repository(journal)
            .reconcile(runtimeActiveNightId = null)
            .nights
            .single()

        assertEquals(2, record.sessions.size)
        assertEquals(2, record.night.reportedSessionCount)
        assertEquals(ProcessingState.NOT_STARTED, record.night.transcriptionState)
        assertEquals(ProcessingState.FAILED, record.night.enrichmentState)
        assertTrue(record.night.enrichmentFailure!!.contains("retryable=false"))
        assertTrue(record.hasProtectedDreamChanges)
        assertEquals("My protected text", record.dreams.single().dream.currentText)
    }

    @Test
    fun reconcileKeepsCrossMidnightSessionWithExplicitParentAndSortsNewestFirst() {
        val fixture = fixture("ordering")
        val olderStart = epoch("2026-07-30T04:55:00Z")
        val crossMidnightSessionStart = epoch("2026-07-30T05:05:00Z")
        val olderEnd = epoch("2026-07-30T05:10:00Z")
        var now = olderStart
        val journal = journal(fixture.journalRoot) { now }

        journal.beginNight(
            nightId = OLDER_NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = olderStart,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        val writer = writer(
            directory = File(fixture.audioRoot, OLDER_NIGHT_ID),
            sessionIds = listOf(CROSS_MIDNIGHT_SESSION_ID),
            clock = { now },
        )
        val session = writer.startSession(
            preRoll = shortArrayOf(1),
            startedAtEpochMillis = crossMidnightSessionStart,
        )
        session.markCueStart()
        session.append(shortArrayOf(2))
        session.markCueEnd()
        now = olderEnd - 1_000L
        journal.recordSessionFinalized(
            session.finalizeComplete(automaticSilenceTailSampleCount = 1L),
        )
        now = olderEnd
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = olderEnd,
        )

        val newerStart = epoch("2026-07-31T04:50:00Z")
        val newerEnd = epoch("2026-07-31T05:00:00Z")
        journal.beginNight(
            nightId = NEWER_NIGHT_ID,
            displayDate = "2026-07-30",
            startedAtEpochMillis = newerStart,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        now = newerEnd
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = newerEnd,
        )

        val first = fixture.repository(journal).reconcile(runtimeActiveNightId = null)

        assertEquals(2, first.importedNightCount)
        assertEquals(2, first.acknowledgedNightCount)
        assertEquals(
            listOf(NEWER_NIGHT_ID, OLDER_NIGHT_ID),
            first.nights.map { it.night.nightId },
        )
        val older = first.nights.single { it.night.nightId == OLDER_NIGHT_ID }
        assertEquals("2026-07-29", older.night.displayDate)
        assertEquals(
            crossMidnightSessionStart,
            older.sessions.single().startedAtEpochMillis,
        )
        assertEquals(1L, older.sessions.single().automaticSilenceTailSampleCount)
        assertTrue(
            older.sessions.single().startedAtEpochMillis!! >
                epoch("2026-07-30T05:00:00Z"),
        )
        assertNull(journal.readEndRecord(OLDER_NIGHT_ID))
        assertNull(journal.readEndRecord(NEWER_NIGHT_ID))

        val second = fixture.repository(journal).reconcile(runtimeActiveNightId = null)

        assertEquals(0, second.importedNightCount)
        assertEquals(0, second.acknowledgedNightCount)
        assertEquals(
            listOf(NEWER_NIGHT_ID, OLDER_NIGHT_ID),
            second.nights.map { it.night.nightId },
        )
    }

    @Test
    fun staleActiveJournalPreservesInterruptionAndSilencingEvidence() {
        val fixture = fixture("interruption")
        val startedAt = epoch("2026-07-30T04:30:00Z")
        val heartbeatAt = epoch("2026-07-30T04:45:00Z")
        var now = startedAt
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = INTERRUPTED_NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = startedAt,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        now = heartbeatAt
        journal.heartbeat(
            framesRead = 14_400_000L,
            gapCount = 0,
            microphoneSilenced = true,
            epochMillis = heartbeatAt,
            heartbeatUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )

        val result = fixture.repository(journal).reconcile(runtimeActiveNightId = null)
        val record = result.nights.single()

        assertEquals(1, result.importedNightCount)
        assertEquals(0, result.acknowledgedNightCount)
        assertEquals(NightCaptureState.RECOVERY_REQUIRED, record.night.captureState)
        assertTrue(record.night.interrupted)
        assertEquals("recovery_required", record.night.endReason)
        assertTrue(record.night.hadMicrophoneSilencing)
        assertEquals(heartbeatAt, record.night.lastHeartbeatEpochMillis)
        assertEquals(
            DAYLIGHT_OFFSET_SECONDS,
            record.night.lastHeartbeatUtcOffsetSeconds,
        )
        assertTrue(record.events.any { it.type == "heartbeat" })
    }

    @Test
    fun explicitEffectiveFalsePreventsLegacySilencingFalsePositive() {
        val fixture = fixture("effective-silencing")
        val startedAt = epoch("2026-07-30T04:30:00Z")
        val endedAt = startedAt + 120_000L
        var now = startedAt
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = EFFECTIVE_SILENCING_NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = startedAt,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        journal.appendEvent(
            type = "microphone_state",
            attributes = mapOf(
                "effective_silenced" to "false",
                "client_silenced" to "true",
                "own_configuration" to "missing",
            ),
            epochMillis = startedAt + 60_000L,
        )
        now = endedAt
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = endedAt,
        )

        val record = fixture.repository(journal)
            .reconcile(runtimeActiveNightId = null)
            .nights
            .single()

        assertFalse(record.night.hadMicrophoneSilencing)
    }

    @Test
    fun staleActiveJournalCountsPendingSessionEvidenceAsIncomplete() {
        val fixture = fixture("pending-session")
        val startedAt = epoch("2026-07-30T04:30:00Z")
        var now = startedAt
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = PENDING_SESSION_NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = startedAt,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        val writer = writer(
            directory = File(fixture.audioRoot, PENDING_SESSION_NIGHT_ID),
            sessionIds = listOf(PENDING_SESSION_ID),
            clock = { now },
        )
        val activeSession = writer.startSession(
            preRoll = shortArrayOf(1),
            startedAtEpochMillis = startedAt + 60_000L,
        )
        activeSession.markCueStart()
        activeSession.append(shortArrayOf(2, 3))
        now = startedAt + 61_000L

        try {
            journal.checkpointSession(activeSession.checkpoint())

            val record = fixture.repository(journal)
                .reconcile(runtimeActiveNightId = null)
                .nights
                .single()

            assertEquals(NightCaptureState.RECOVERY_REQUIRED, record.night.captureState)
            assertEquals(1, record.night.reportedSessionCount)
            assertEquals(1, record.night.reportedIncompleteSessionCount)
            assertEquals(PENDING_SESSION_ID, record.sessions.single().sessionId)
            assertEquals(
                AudioEvidenceState.PENDING_RECOVERY,
                record.sessions.single().audioState,
            )
            assertEquals(RawAudioState.PENDING_RECOVERY, record.night.rawAudioState)
        } finally {
            activeSession.finalizeIncomplete(SessionIncompleteReason.PROCESS_INTERRUPTED)
        }
    }

    @Test
    fun finalizedMetadataClearsTemporaryCheckpointRecoveryReason() {
        val fixture = fixture("checkpoint-finalized")
        val startedAt = epoch("2026-08-10T04:00:00Z")
        var now = startedAt
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = CHECKPOINT_FINALIZED_NIGHT_ID,
            displayDate = "2026-08-09",
            startedAtEpochMillis = startedAt,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        val activeSession = writer(
            directory = File(fixture.audioRoot, CHECKPOINT_FINALIZED_NIGHT_ID),
            sessionIds = listOf(CHECKPOINT_FINALIZED_SESSION_ID),
            clock = { now },
        ).startSession(
            preRoll = shortArrayOf(1),
            startedAtEpochMillis = startedAt + 60_000L,
        )
        activeSession.markCueStart()
        activeSession.append(shortArrayOf(2, 3))
        activeSession.markCueEnd()
        now = startedAt + 61_000L
        journal.checkpointSession(activeSession.checkpoint())

        val whileActive = fixture.repository(journal)
            .reconcile(runtimeActiveNightId = CHECKPOINT_FINALIZED_NIGHT_ID)
            .nights
            .single()
        assertEquals("recovery_required", whileActive.sessions.single().incompleteReason)

        now = startedAt + 62_000L
        journal.recordSessionFinalized(
            activeSession.finalizeComplete(automaticSilenceTailSampleCount = 1L),
        )
        now = startedAt + 63_000L
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = now,
        )

        val finalized = fixture.repository(journal)
            .reconcile(runtimeActiveNightId = null)
            .nights
            .single()

        assertNull(finalized.sessions.single().incompleteReason)
        assertEquals(0, finalized.night.reportedIncompleteSessionCount)
        assertEquals(AudioEvidenceState.RETAINED, finalized.sessions.single().audioState)
    }

    @Test
    fun reconcileReportsMissingAndCorruptAudioEvidence() {
        val fixture = fixture("audio-evidence")
        val startedAt = epoch("2026-07-30T04:00:00Z")
        var now = startedAt
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = DAMAGED_AUDIO_NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = startedAt,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        val audioDirectory = File(fixture.audioRoot, DAMAGED_AUDIO_NIGHT_ID)
        val writer = writer(
            directory = audioDirectory,
            sessionIds = listOf(MISSING_SESSION_ID, CORRUPT_SESSION_ID),
            clock = { now },
        )

        val missingSession = writer.startSession(
            preRoll = shortArrayOf(1),
            startedAtEpochMillis = startedAt + 60_000L,
        )
        missingSession.markCueStart()
        missingSession.append(shortArrayOf(2))
        missingSession.markCueEnd()
        now += 61_000L
        val missingMetadata =
            missingSession.finalizeComplete(automaticSilenceTailSampleCount = 1L)
        journal.recordSessionFinalized(missingMetadata)

        val corruptSession = writer.startSession(
            preRoll = shortArrayOf(3),
            startedAtEpochMillis = startedAt + 120_000L,
        )
        corruptSession.markCueStart()
        corruptSession.append(shortArrayOf(4))
        corruptSession.markCueEnd()
        now = startedAt + 121_000L
        val corruptMetadata =
            corruptSession.finalizeComplete(automaticSilenceTailSampleCount = 1L)
        journal.recordSessionFinalized(corruptMetadata)

        assertTrue(File(audioDirectory, missingMetadata.audioFileName).delete())
        RandomAccessFile(File(audioDirectory, corruptMetadata.audioFileName), "rw").use { audio ->
            audio.seek(0L)
            audio.writeBytes("NOPE")
        }
        now = startedAt + 180_000L
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = now,
        )

        val record = fixture.repository(journal)
            .reconcile(runtimeActiveNightId = null)
            .nights
            .single()

        assertEquals(
            listOf(AudioEvidenceState.MISSING, AudioEvidenceState.CORRUPT),
            record.sessions.map { it.audioState },
        )
        assertEquals(RawAudioState.UNAVAILABLE, record.night.rawAudioState)
        assertEquals(2, record.unavailableSessionCount)
        assertTrue(record.night.importWarning!!.contains("missing"))
        assertTrue(record.night.importWarning!!.contains("corrupt"))
        assertFalse(File(audioDirectory, missingMetadata.audioFileName).exists())
    }

    @Test
    fun missingEventSourceWarnsAndKeepsEndSpoolUnacknowledged() {
        val fixture = fixture("missing-events")
        val startedAt = epoch("2026-07-30T04:00:00Z")
        val endedAt = epoch("2026-07-30T05:00:00Z")
        var now = startedAt
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = MISSING_EVENTS_NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = startedAt,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        val audioDirectory = File(fixture.audioRoot, MISSING_EVENTS_NIGHT_ID)
        val writer = writer(
            directory = audioDirectory,
            sessionIds = listOf(MISSING_EVENTS_SESSION_ID),
            clock = { now },
        )
        val session = writer.startSession(
            preRoll = shortArrayOf(1),
            startedAtEpochMillis = startedAt + 60_000L,
        )
        session.markCueStart()
        session.append(shortArrayOf(2))
        session.markCueEnd()
        now = endedAt - 1_000L
        journal.recordSessionFinalized(
            session.finalizeComplete(automaticSilenceTailSampleCount = 1L),
        )
        now = endedAt
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = endedAt,
        )
        assertTrue(eventFile(fixture.journalRoot, MISSING_EVENTS_NIGHT_ID).delete())

        val repository = fixture.repository(journal)
        val result = repository.reconcile(runtimeActiveNightId = null)
        val record = result.nights.single()

        assertEquals(1, result.importedNightCount)
        assertEquals(0, result.acknowledgedNightCount)
        assertEquals(1, result.warningCount)
        assertTrue(record.night.importWarning!!.contains("missing or unreadable"))
        assertTrue(
            record.events.any {
                it.eventId ==
                    "journal_event_source_unavailable_$MISSING_EVENTS_NIGHT_ID"
            },
        )
        assertTrue(endFile(fixture.journalRoot, MISSING_EVENTS_NIGHT_ID).isFile)
        val retained = record.sessions.single()
        assertEquals(AudioEvidenceState.RETAINED, retained.audioState)

        assertTrue(repository.deleteNightRawAudio(MISSING_EVENTS_NIGHT_ID))
        assertFalse(audioDirectory.exists())
        assertTrue(endFile(fixture.journalRoot, MISSING_EVENTS_NIGHT_ID).isFile)

        val repeated = repository.reconcile(runtimeActiveNightId = null)
        val reimported = repeated.nights.single().sessions.single()
        assertEquals(0, repeated.acknowledgedNightCount)
        assertEquals(AudioEvidenceState.DELETED, reimported.audioState)
        assertEquals(retained.startedAtEpochMillis, reimported.startedAtEpochMillis)
        assertEquals(retained.sampleRateHz, reimported.sampleRateHz)
        assertEquals(retained.sampleCount, reimported.sampleCount)
        assertEquals(
            retained.automaticSilenceTailSampleCount,
            reimported.automaticSilenceTailSampleCount,
        )
        assertTrue(endFile(fixture.journalRoot, MISSING_EVENTS_NIGHT_ID).isFile)
    }

    @Test
    fun corruptEndAudioReferenceDoesNotBlockValidNightReconciliation() {
        val fixture = fixture("corrupt-end")
        var now = epoch("2026-07-30T03:00:00Z")
        val journal = journal(fixture.journalRoot) { now }
        journal.beginNight(
            nightId = VALID_END_NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = now,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        now = epoch("2026-07-30T03:30:00Z")
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = now,
        )

        now = epoch("2026-07-30T04:00:00Z")
        journal.beginNight(
            nightId = CORRUPT_END_NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = now,
            startedAtUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
        )
        val writer = writer(
            directory = File(fixture.audioRoot, CORRUPT_END_NIGHT_ID),
            sessionIds = listOf(CORRUPT_END_SESSION_ID),
            clock = { now },
        )
        val session = writer.startSession(
            preRoll = shortArrayOf(1),
            startedAtEpochMillis = now + 60_000L,
        )
        session.markCueStart()
        session.append(shortArrayOf(2))
        session.markCueEnd()
        now += 61_000L
        journal.recordSessionFinalized(
            session.finalizeComplete(automaticSilenceTailSampleCount = 1L),
        )
        now = epoch("2026-07-30T04:30:00Z")
        journal.endNight(
            reason = "owner_ended",
            interrupted = false,
            endedAtEpochMillis = now,
        )
        replaceEndAudioReference(
            root = fixture.journalRoot,
            nightId = CORRUPT_END_NIGHT_ID,
            audioFileName = "not_a_writer_owned_audio_file.wav",
        )

        val repository = fixture.repository(journal)
        val first = repository.reconcile(runtimeActiveNightId = null)

        assertEquals(1, first.importedNightCount)
        assertEquals(1, first.acknowledgedNightCount)
        assertEquals(1, first.warningCount)
        assertEquals(listOf(VALID_END_NIGHT_ID), first.nights.map { it.night.nightId })
        assertFalse(endFile(fixture.journalRoot, VALID_END_NIGHT_ID).exists())
        assertTrue(endFile(fixture.journalRoot, CORRUPT_END_NIGHT_ID).isFile)
        assertTrue(eventFile(fixture.journalRoot, CORRUPT_END_NIGHT_ID).isFile)

        val repeated = repository.reconcile(runtimeActiveNightId = null)

        assertEquals(0, repeated.importedNightCount)
        assertEquals(0, repeated.acknowledgedNightCount)
        assertEquals(1, repeated.warningCount)
        assertEquals(listOf(VALID_END_NIGHT_ID), repeated.nights.map { it.night.nightId })
    }

    @Test
    fun rawAudioAndWholeNightDeletionStayInsideTheSelectedNight() {
        val fixture = fixture("archive-deletion")
        val firstNight = endedNightForDeletion(DELETE_AUDIO_NIGHT_ID, startedAt = 100L)
        val secondNight = endedNightForDeletion(DELETE_WHOLE_NIGHT_ID, startedAt = 200L)
        fixture.dao.seed(
            firstNight,
            retainedSessionForDeletion(
                nightId = firstNight.nightId,
                sessionId = DELETE_AUDIO_SESSION_ID,
                audioFileName = "a_11111111111111111111111111111111.wav",
            ),
        )
        fixture.dao.seed(
            secondNight,
            retainedSessionForDeletion(
                nightId = secondNight.nightId,
                sessionId = DELETE_WHOLE_SESSION_ID,
                audioFileName = "a_22222222222222222222222222222222.wav",
            ),
        )
        val firstDirectory = File(fixture.audioRoot, firstNight.nightId).apply { mkdirs() }
        val secondDirectory = File(fixture.audioRoot, secondNight.nightId).apply { mkdirs() }
        File(firstDirectory, "a_11111111111111111111111111111111.wav").writeText("first")
        File(firstDirectory, "orphan.properties.part").writeText("metadata")
        val secondSentinel = File(
            secondDirectory,
            "a_22222222222222222222222222222222.wav",
        ).apply { writeText("second") }
        val rootSentinel = File(fixture.audioRoot, "keep.txt").apply { writeText("keep") }
        val repository = fixture.repository(journal(fixture.journalRoot) { 300L })

        assertTrue(repository.deleteNightRawAudio(firstNight.nightId))
        assertFalse(firstDirectory.exists())
        assertTrue(secondSentinel.isFile)
        assertTrue(rootSentinel.isFile)
        val preservedFirst = requireNotNull(fixture.dao.readNight(firstNight.nightId))
        assertEquals(
            AudioEvidenceState.DELETED,
            preservedFirst.sessions.single().audioState,
        )
        assertEquals(RawAudioState.UNAVAILABLE, preservedFirst.night.rawAudioState)

        assertTrue(repository.deleteWholeNight(secondNight.nightId))
        assertNull(fixture.dao.readNight(secondNight.nightId))
        assertFalse(secondDirectory.exists())
        assertTrue(rootSentinel.isFile)
        assertTrue(fixture.dao.readNight(firstNight.nightId) != null)
    }

    @Test
    fun automaticRetentionExpiresOnlyEligibleUnusedAudioAndIsIdempotent() {
        val fixture = fixture("automatic-retention")
        val now = 60L * RawAudioRetentionPolicy.MILLIS_PER_DAY
        val eligibleEnd = now - RawAudioRetentionPolicy.DEFAULT_RETENTION_MILLIS
        val recentEnd = eligibleEnd + 1L
        val eligibleNight = endedNightForDeletion(
            nightId = RETENTION_ELIGIBLE_NIGHT_ID,
            startedAt = eligibleEnd - 50L,
        ).copy(endedAtEpochMillis = eligibleEnd)
        val recentNight = endedNightForDeletion(
            nightId = RETENTION_RECENT_NIGHT_ID,
            startedAt = recentEnd - 50L,
        ).copy(endedAtEpochMillis = recentEnd)
        fixture.dao.seed(
            eligibleNight,
            retainedSessionForDeletion(
                nightId = eligibleNight.nightId,
                sessionId = RETENTION_ELIGIBLE_SESSION_ID,
                audioFileName = "a_33333333333333333333333333333333.wav",
            ).copy(
                startedAtEpochMillis = eligibleEnd - 40L,
                finalizedAtEpochMillis = eligibleEnd - 10L,
            ),
        )
        fixture.dao.seed(
            recentNight,
            retainedSessionForDeletion(
                nightId = recentNight.nightId,
                sessionId = RETENTION_RECENT_SESSION_ID,
                audioFileName = "a_44444444444444444444444444444444.wav",
            ).copy(
                startedAtEpochMillis = recentEnd - 40L,
                finalizedAtEpochMillis = recentEnd - 10L,
            ),
        )
        val eligibleDirectory = File(fixture.audioRoot, eligibleNight.nightId).apply { mkdirs() }
        val recentDirectory = File(fixture.audioRoot, recentNight.nightId).apply { mkdirs() }
        File(eligibleDirectory, "a_33333333333333333333333333333333.wav").writeText("old")
        val recentSentinel = File(
            recentDirectory,
            "a_44444444444444444444444444444444.wav",
        ).apply { writeText("recent") }
        val modelRoot = File(fixture.audioRoot.parentFile, "retention-model-sentinels")
        val transcriptionModel = File(modelRoot, "transcription-models/model.bin").apply {
            parentFile!!.mkdirs()
            writeText("asr")
        }
        val enrichmentModel = File(modelRoot, "enrichment-models/model.bin").apply {
            parentFile!!.mkdirs()
            writeText("llm")
        }
        val synchronizedNightIds = mutableListOf<String>()
        val repository = fixture.repository(
            journal = journal(fixture.journalRoot) { now },
            clock = { now },
            transcriptionStateReconciler = synchronizedNightIds::add,
        )

        RawAudioUseRegistry.processWide.tryAcquireUse(eligibleNight.nightId)!!.use {
            assertTrue(repository.expireRawAudio(
                RawAudioRetentionPolicy.DEFAULT_RETENTION_MILLIS,
            ).expiredNightIds.isEmpty())
            assertTrue(eligibleDirectory.isDirectory)
        }

        val first = repository.expireRawAudio(RawAudioRetentionPolicy.DEFAULT_RETENTION_MILLIS)
        val repeated = repository.expireRawAudio(RawAudioRetentionPolicy.DEFAULT_RETENTION_MILLIS)

        assertEquals(listOf(eligibleNight.nightId), first.expiredNightIds)
        assertEquals(listOf(eligibleNight.nightId), synchronizedNightIds)
        assertTrue(repeated.expiredNightIds.isEmpty())
        assertFalse(eligibleDirectory.exists())
        assertTrue(recentSentinel.isFile)
        assertTrue(transcriptionModel.isFile)
        assertTrue(enrichmentModel.isFile)
        val expired = requireNotNull(fixture.dao.readNight(eligibleNight.nightId))
        assertEquals(AudioEvidenceState.EXPIRED, expired.sessions.single().audioState)
        assertEquals(RawAudioState.UNAVAILABLE, expired.night.rawAudioState)
        assertEquals(
            AudioEvidenceState.RETAINED,
            requireNotNull(fixture.dao.readNight(recentNight.nightId))
                .sessions.single().audioState,
        )
    }

    private fun endedNightForDeletion(nightId: String, startedAt: Long): NightEntity =
        NightEntity(
            nightId = nightId,
            displayDate = "2026-08-09",
            startedAtEpochMillis = startedAt,
            startedUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
            endedAtEpochMillis = startedAt + 50L,
            endedUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
            captureState = NightCaptureState.ENDED,
            endReason = "owner_ended",
            interrupted = false,
            lastHeartbeatEpochMillis = startedAt + 40L,
            lastHeartbeatUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
            reportedSessionCount = 1,
            reportedIncompleteSessionCount = 0,
            hadMicrophoneSilencing = false,
            hadAudioGap = false,
            rawAudioState = RawAudioState.RETAINED,
            transcriptionState = ProcessingState.COMPLETE,
            transcriptionFailure = null,
            enrichmentState = ProcessingState.COMPLETE,
            enrichmentFailure = null,
            importWarning = null,
        )

    private fun retainedSessionForDeletion(
        nightId: String,
        sessionId: String,
        audioFileName: String,
    ): CaptureSessionEntity =
        CaptureSessionEntity(
            sessionId = sessionId,
            nightId = nightId,
            captureOrder = 0,
            startedAtEpochMillis = 110L,
            startedUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
            finalizedAtEpochMillis = 140L,
            finalizedUtcOffsetSeconds = DAYLIGHT_OFFSET_SECONDS,
            incompleteReason = null,
            audioFileName = audioFileName,
            audioState = AudioEvidenceState.RETAINED,
            sampleRateHz = 16_000,
            channelCount = 1,
            bitsPerSample = 16,
            sampleCount = 16_000L,
            preRollSampleCount = 0L,
            cueStartSample = 0L,
            cueEndSampleExclusive = 0L,
        )

    private fun fixture(name: String): RepositoryFixture =
        RepositoryFixture(
            dao = FakeNightDao(),
            journalRoot = temporaryFolder.newFolder("$name-journal"),
            audioRoot = temporaryFolder.newFolder("$name-audio"),
        )

    private fun journal(
        directory: File,
        clock: () -> Long,
    ): CaptureJournalStore {
        var nextEventId = 0
        return CaptureJournalStore(
            rootDirectory = directory,
            clock = clock,
            utcOffsetSeconds = { DAYLIGHT_OFFSET_SECONDS },
            eventId = {
                nextEventId += 1
                "event_${nextEventId.toString().padStart(4, '0')}"
            },
        )
    }

    private fun writer(
        directory: File,
        sessionIds: List<String>,
        clock: () -> Long,
    ): SessionAudioWriter {
        val ids = sessionIds.iterator()
        return SessionAudioWriter(
            audioDirectory = directory,
            clock = clock,
            utcOffsetSeconds = { DAYLIGHT_OFFSET_SECONDS },
            opaqueId = { ids.next() },
        )
    }

    private fun completeSession(
        writer: SessionAudioWriter,
        startedAtEpochMillis: Long,
        finalizedAtEpochMillis: Long,
        setClock: (Long) -> Unit,
    ): SessionAudioMetadata {
        val session = writer.startSession(
            preRoll = shortArrayOf(1),
            startedAtEpochMillis = startedAtEpochMillis,
        )
        session.markCueStart()
        session.append(shortArrayOf(2))
        session.markCueEnd()
        setClock(finalizedAtEpochMillis)
        return session.finalizeComplete(automaticSilenceTailSampleCount = 1L)
    }

    private fun SessionAudioMetadata.toEntity(
        nightId: String,
        captureOrder: Int,
    ): CaptureSessionEntity =
        CaptureSessionEntity(
            sessionId = sessionId,
            nightId = nightId,
            captureOrder = captureOrder,
            startedAtEpochMillis = startedAtEpochMillis,
            startedUtcOffsetSeconds = startedAtUtcOffsetSeconds,
            finalizedAtEpochMillis = finalizedAtEpochMillis,
            finalizedUtcOffsetSeconds = finalizedAtUtcOffsetSeconds,
            incompleteReason = incompleteReason,
            audioFileName = audioFileName,
            audioState = AudioEvidenceState.RETAINED,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            sampleCount = sampleCount,
            preRollSampleCount = preRollSampleCount,
            cueStartSample = cueStartSample,
            cueEndSampleExclusive = cueEndSampleExclusive,
            automaticSilenceTailSampleCount = automaticSilenceTailSampleCount,
        )

    private fun metadataFile(directory: File, audioFileName: String): File =
        File(directory, audioFileName.removeSuffix(".wav") + ".properties")

    private fun epoch(value: String): Long = Instant.parse(value).toEpochMilli()

    private fun eventFile(
        root: File,
        nightId: String,
    ): File = File(root, "events/n_$nightId.events")

    private fun endFile(
        root: File,
        nightId: String,
    ): File = File(root, "ended/n_$nightId.properties")

    private fun replaceEndAudioReference(
        root: File,
        nightId: String,
        audioFileName: String,
    ) {
        val file = endFile(root, nightId)
        val properties = Properties()
        FileInputStream(file).use(properties::load)
        properties.setProperty("audio_files", audioFileName)
        FileOutputStream(file).use { output -> properties.store(output, null) }
    }

    private data class RepositoryFixture(
        val dao: FakeNightDao,
        val journalRoot: File,
        val audioRoot: File,
    ) {
        fun repository(
            journal: CaptureJournalStore,
            clock: () -> Long = { 0L },
            transcriptionStateReconciler: ((String) -> Unit)? = null,
        ): NightRepository =
            NightRepository(
                dao = dao,
                journalStore = journal,
                audioRootDirectory = audioRoot,
                offsetAtEpochMillis = { DAYLIGHT_OFFSET_SECONDS },
                clock = clock,
                transcriptionStateReconciler = transcriptionStateReconciler,
            )
    }

    private class FakeNightDao : NightDao() {
        private val nights = linkedMapOf<String, NightEntity>()
        private val sessions = linkedMapOf<String, CaptureSessionEntity>()
        private val events = linkedMapOf<Pair<String, String>, NightEventEntity>()
        private val dreams = linkedMapOf<String, DreamWithSourceSpans>()

        protected override fun upsertNight(night: NightEntity) {
            nights[night.nightId] = night
        }

        protected override fun upsertSessions(sessions: List<CaptureSessionEntity>) {
            sessions.forEach { session -> this.sessions[session.sessionId] = session }
        }

        protected override fun insertEvents(events: List<NightEventEntity>) {
            events.forEach { event ->
                this.events.putIfAbsent(event.nightId to event.eventId, event)
            }
        }

        override fun readHistory(): List<NightWithDetails> =
            nights.values
                .sortedWith(
                    compareByDescending<NightEntity> { it.startedAtEpochMillis }
                        .thenByDescending { it.nightId },
                )
                .map(::details)

        override fun readNight(nightId: String): NightWithDetails? =
            nights[nightId]?.let(::details)

        override fun readUnfinishedNights(): List<NightEntity> =
            nights.values
                .filter {
                    it.captureState == NightCaptureState.STARTING ||
                        it.captureState == NightCaptureState.ACTIVE
                }
                .sortedWith(
                    compareByDescending<NightEntity> { it.startedAtEpochMillis }
                        .thenByDescending { it.nightId },
                )

        override fun updateNightSessionAudioState(
            nightId: String,
            audioState: String,
        ): Int {
            val targets = sessions.values.filter { it.nightId == nightId }
            targets.forEach { session ->
                sessions[session.sessionId] = session.copy(audioState = audioState)
            }
            return targets.size
        }

        override fun updateNightRawAudioState(
            nightId: String,
            rawAudioState: String,
        ): Int {
            val night = nights[nightId] ?: return 0
            nights[nightId] = night.copy(rawAudioState = rawAudioState)
            return 1
        }

        override fun deleteNight(nightId: String): Int {
            if (nights.remove(nightId) == null) return 0
            sessions.entries.removeIf { it.value.nightId == nightId }
            events.entries.removeIf { it.value.nightId == nightId }
            dreams.entries.removeIf { it.value.dream.nightId == nightId }
            return 1
        }

        fun seed(night: NightEntity, session: CaptureSessionEntity) {
            nights[night.nightId] = night
            sessions[session.sessionId] = session
        }

        fun seed(
            night: NightEntity,
            sessions: List<CaptureSessionEntity>,
            dreams: List<DreamWithSourceSpans>,
        ) {
            nights[night.nightId] = night
            sessions.forEach { session -> this.sessions[session.sessionId] = session }
            dreams.forEach { dream -> this.dreams[dream.dream.dreamId] = dream }
        }

        private fun details(night: NightEntity): NightWithDetails =
            NightWithDetails(
                night = night,
                sessions = sessions.values.filter { it.nightId == night.nightId },
                events = events.values.filter { it.nightId == night.nightId },
                dreams = dreams.values.filter { it.dream.nightId == night.nightId },
            )
    }

    private companion object {
        const val DAYLIGHT_OFFSET_SECONDS = -5 * 60 * 60
        const val OLDER_NIGHT_ID = "00000000000000000000000000000031"
        const val NEWER_NIGHT_ID = "00000000000000000000000000000032"
        const val INTERRUPTED_NIGHT_ID = "00000000000000000000000000000033"
        const val DAMAGED_AUDIO_NIGHT_ID = "00000000000000000000000000000034"
        const val PENDING_SESSION_NIGHT_ID = "00000000000000000000000000000035"
        const val MISSING_EVENTS_NIGHT_ID = "00000000000000000000000000000036"
        const val VALID_END_NIGHT_ID = "00000000000000000000000000000037"
        const val CORRUPT_END_NIGHT_ID = "00000000000000000000000000000038"
        const val CHECKPOINT_FINALIZED_NIGHT_ID = "00000000000000000000000000000039"
        const val CROSS_MIDNIGHT_SESSION_ID = "00000000000000000000000000000041"
        const val MISSING_SESSION_ID = "00000000000000000000000000000042"
        const val CORRUPT_SESSION_ID = "00000000000000000000000000000043"
        const val PENDING_SESSION_ID = "00000000000000000000000000000044"
        const val CORRUPT_END_SESSION_ID = "00000000000000000000000000000045"
        const val MISSING_EVENTS_SESSION_ID = "00000000000000000000000000000046"
        const val CHECKPOINT_FINALIZED_SESSION_ID = "00000000000000000000000000000047"
        const val DELETE_AUDIO_NIGHT_ID = "00000000000000000000000000000051"
        const val DELETE_WHOLE_NIGHT_ID = "00000000000000000000000000000052"
        const val DELETE_AUDIO_SESSION_ID = "00000000000000000000000000000061"
        const val DELETE_WHOLE_SESSION_ID = "00000000000000000000000000000062"
        const val RETENTION_ELIGIBLE_NIGHT_ID = "00000000000000000000000000000053"
        const val RETENTION_RECENT_NIGHT_ID = "00000000000000000000000000000054"
        const val RETENTION_ELIGIBLE_SESSION_ID = "00000000000000000000000000000066"
        const val RETENTION_RECENT_SESSION_ID = "00000000000000000000000000000067"
        const val ORPHAN_EIGHTH_NIGHT_ID = "00000000000000000000000000000063"
        const val INVALID_ORPHAN_NIGHT_ID = "00000000000000000000000000000064"
        const val PROTECTED_ORPHAN_NIGHT_ID = "00000000000000000000000000000065"
        const val EFFECTIVE_SILENCING_NIGHT_ID = "00000000000000000000000000000068"
        val ORPHAN_EIGHTH_SESSION_IDS = listOf(
            "00000000000000000000000000000071",
            "00000000000000000000000000000072",
            "00000000000000000000000000000073",
            "00000000000000000000000000000074",
            "00000000000000000000000000000075",
            "00000000000000000000000000000076",
            "00000000000000000000000000000077",
            "00000000000000000000000000000078",
        )
        val INVALID_ORPHAN_SESSION_IDS = listOf(
            "00000000000000000000000000000081",
            "00000000000000000000000000000082",
            "00000000000000000000000000000083",
            "00000000000000000000000000000084",
        )
        val PROTECTED_ORPHAN_SESSION_IDS = listOf(
            "00000000000000000000000000000091",
            "00000000000000000000000000000092",
        )
        const val PROTECTED_DREAM_ID = "protected_dream"
        const val PROTECTED_RUN_ID = "protected_run"
    }
}
