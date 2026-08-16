package com.wivy.dreamlog.capture

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureJournalStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun journalAtomicallyTracksHeartbeatSessionAndEndRecord() {
        val journalRoot = temporaryFolder.newFolder("journal-normal")
        val audioRoot = temporaryFolder.newFolder("audio-normal")
        var now = 1_000L
        val journal = CaptureJournalStore(journalRoot, { now }) { "event0001" }
        val writer = SessionAudioWriter(audioRoot, { now }) { SESSION_ID }
        val active = journal.beginNight(
            nightId = NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = now,
        )
        assertEquals("2026-07-29", active.displayDate)
        assertNull(journal.unresolvedPriorCapture()?.existingEndRecord)

        now = 61_000L
        journal.heartbeat(
            framesRead = 960_000L,
            gapCount = 1,
            microphoneSilenced = false,
            readiness = "ready",
            charging = true,
            sessionActive = false,
            keywordStreamProgress = KeywordStreamProgress(
                acceptedFrameCount = 1_875L,
                decodeCount = 234L,
                resetCount = 2L,
                lastResetReason = "audio_discontinuity",
            ),
        )
        val session = writer.startSession(shortArrayOf(1, 2), startedAtEpochMillis = now)
        session.markCueStart()
        session.append(shortArrayOf(3, 4))
        session.markCueEnd()
        journal.checkpointSession(session.checkpoint())
        now = 62_000L
        val metadata = session.finalizeComplete(automaticSilenceTailSampleCount = 2L)
        journal.recordSessionFinalized(metadata)
        now = 63_000L
        val end = journal.endNight(
            reason = "owner_ended",
            interrupted = false,
        )

        assertNull(journal.readActive())
        assertEquals("2026-07-29", end.displayDate)
        assertEquals(1, end.sessionCount)
        assertEquals(0, end.incompleteSessionCount)
        assertEquals(listOf(metadata.audioFileName), end.audioFileNames)
        assertEquals(end, journal.readEndRecord(NIGHT_ID))
        assertEquals(end, journal.latestEndRecord())
        assertEquals(
            listOf("night_started", "heartbeat", "session_finalized", "night_ended"),
            journal.readEvents(NIGHT_ID).map(CaptureJournalEvent::type),
        )
        assertEquals(
            "2",
            journal.readEvents(NIGHT_ID)
                .single { it.type == "session_finalized" }
                .attributes["automatic_silence_tail_sample_count"],
        )
        val heartbeat = journal.readEvents(NIGHT_ID).single { it.type == "heartbeat" }
        assertEquals("ready", heartbeat.attributes["readiness"])
        assertEquals("true", heartbeat.attributes["charging"])
        assertEquals("false", heartbeat.attributes["session_active"])
        assertEquals("1875", heartbeat.attributes["kws_accepted_frame_count"])
        assertEquals("234", heartbeat.attributes["kws_decode_count"])
        assertEquals("2", heartbeat.attributes["kws_reset_count"])
        assertEquals(
            "audio_discontinuity",
            heartbeat.attributes["kws_last_reset_reason"],
        )
        val health = summarizeCaptureListeningHealth(journal.readEvents(NIGHT_ID))
        assertEquals("ready", health.latestHeartbeat?.readiness)
        assertEquals(true, health.latestHeartbeat?.charging)
        assertTrue(health.issues.isEmpty())
        assertFalse(File(journalRoot, "active.properties.part").exists())
    }

    @Test
    fun unresolvedJournalBlocksNewNightAndRecoveryPreservesPartialAudio() {
        val journalRoot = temporaryFolder.newFolder("journal-recovery")
        val audioRoot = temporaryFolder.newFolder("audio-recovery")
        var now = 10_000L
        val journal = CaptureJournalStore(journalRoot, { now }) { "event0002" }
        val writer = SessionAudioWriter(audioRoot, { now }) { RECOVERY_SESSION_ID }
        journal.beginNight(
            nightId = NIGHT_ID,
            displayDate = "2026-07-29",
            startedAtEpochMillis = now,
        )
        val session = writer.startSession(
            preRoll = shortArrayOf(10, 11, 12),
            startedAtEpochMillis = now + 50L,
        )
        session.markCueStart()
        session.append(shortArrayOf(13, 14))
        val checkpoint = session.checkpoint()
        journal.checkpointSession(checkpoint)
        now = 70_000L
        journal.heartbeat(
            framesRead = 1_120_000L,
            gapCount = 2,
            microphoneSilenced = true,
        )
        val legacyHeartbeat = journal.readEvents(NIGHT_ID).single { it.type == "heartbeat" }
        val legacyHealth = summarizeCaptureListeningHealth(listOf(legacyHeartbeat))
        assertNull(legacyHealth.latestHeartbeat?.readiness)
        assertNull(legacyHealth.latestHeartbeat?.charging)
        assertNull(legacyHealth.latestHeartbeat?.keywordResetCount)
        assertTrue(legacyHealth.issues.isEmpty())
        turnFinalizedAudioBackIntoCrashPartial(audioRoot, session)

        val restartedJournal = CaptureJournalStore(journalRoot, { now }) { "event0003" }
        val unresolved = restartedJournal.unresolvedPriorCapture()
        assertNotNull(unresolved)
        assertEquals(UnresolvedCaptureKind.ACTIVE, unresolved?.kind)
        assertEquals("2026-07-29", unresolved?.activeJournal?.displayDate)
        assertEquals(checkpoint, unresolved?.activeJournal?.activeSession)
        assertThrows(IllegalStateException::class.java) {
            restartedJournal.beginNight("anothernight", "2026-07-30")
        }

        now = 80_000L
        val recoveryWriter =
            SessionAudioWriter(audioRoot, { now }) { "ffffffffffffffffffffffffffffffff" }
        val recovery = restartedJournal.recoverUnresolved(recoveryWriter)

        assertNotNull(recovery)
        assertFalse(recovery!!.completedPreviously)
        assertEquals(SessionIncompleteReason.PROCESS_INTERRUPTED, recovery.endRecord.reason)
        assertEquals("2026-07-29", recovery.endRecord.displayDate)
        assertEquals(1, recovery.endRecord.sessionCount)
        assertEquals(1, recovery.endRecord.incompleteSessionCount)
        assertEquals(1, recovery.recoveredSessions.size)
        assertTrue(File(audioRoot, checkpoint.audioFileName).isFile)
        assertFalse(File(audioRoot, checkpoint.partialFileName).exists())
        assertEquals(
            SessionIncompleteReason.PROCESS_INTERRUPTED,
            recovery.recoveredSessions.single().incompleteReason,
        )
        assertNull(restartedJournal.unresolvedPriorCapture())
        assertNull(restartedJournal.recoverUnresolved(recoveryWriter))
        assertEquals(recovery.endRecord, restartedJournal.latestEndRecord())
        assertEquals(
            1,
            restartedJournal.readEvents(NIGHT_ID).count { it.type == "capture_recovered" },
        )
    }

    @Test
    fun unreadableActiveMarkerIsAnActionableRecoveryFailure() {
        val journalRoot = temporaryFolder.newFolder("journal-corrupt-active")
        File(journalRoot, "active.properties").writeText("state=not-active")
        val journal = CaptureJournalStore(journalRoot)

        val failure = assertThrows(UnreadableActiveJournalException::class.java) {
            journal.readActive()
        }

        assertEquals(
            "Active capture journal is unreadable; recovery is required.",
            failure.message,
        )
        assertEquals(1, journal.quarantineUnreadableActiveMarkers(5_000L))
        assertNull(journal.readActive())
        val quarantined = File(journalRoot, "quarantine").listFiles().orEmpty().single()
        assertEquals("state=not-active", quarantined.readText())
    }

    private fun turnFinalizedAudioBackIntoCrashPartial(
        audioRoot: File,
        session: SessionAudioWriter.ActiveSession,
    ) {
        val metadata = session.finalizeIncomplete(SessionIncompleteReason.WRITE_FAILED)
        val metadataFile =
            File(audioRoot, metadata.audioFileName.removeSuffix(".wav") + ".properties")
        assertTrue(metadataFile.delete())
        Files.move(
            File(audioRoot, metadata.audioFileName).toPath(),
            File(audioRoot, "${metadata.audioFileName}.part").toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private companion object {
        const val NIGHT_ID = "00000000000000000000000000000010"
        const val SESSION_ID = "00000000000000000000000000000011"
        const val RECOVERY_SESSION_ID = "00000000000000000000000000000012"
    }
}
