package com.wivy.dreamlog.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DreamLogDatabaseTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: DreamLogDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "dreamlog-persistence-test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        try {
            database?.close()
        } finally {
            database = null
            if (::context.isInitialized && ::databaseName.isInitialized) {
                context.deleteDatabase(databaseName)
            }
        }
    }

    @Test
    fun captureGraphSurvivesReopenAcrossMidnightAndHistoryIsNewestFirst() {
        val localMidnightEpochMillis = epochMillis("2026-07-30T05:00:00Z")
        val acrossMidnightNight = NightEntity(
            nightId = "night-across-midnight",
            displayDate = "2026-07-29",
            startedAtEpochMillis = epochMillis("2026-07-30T04:58:00Z"),
            startedUtcOffsetSeconds = -18_000,
            endedAtEpochMillis = epochMillis("2026-07-30T05:06:00Z"),
            endedUtcOffsetSeconds = -18_000,
            captureState = NightCaptureState.ENDED,
            endReason = "owner_stopped",
            interrupted = false,
            lastHeartbeatEpochMillis = epochMillis("2026-07-30T05:05:30Z"),
            lastHeartbeatUtcOffsetSeconds = -18_000,
            reportedSessionCount = 2,
            reportedIncompleteSessionCount = 0,
            hadMicrophoneSilencing = false,
            hadAudioGap = false,
            rawAudioState = RawAudioState.RETAINED,
            transcriptionState = ProcessingState.NOT_STARTED,
            transcriptionFailure = null,
            enrichmentState = ProcessingState.NOT_STARTED,
            enrichmentFailure = null,
            importWarning = null,
        )
        val acrossMidnightSessions = listOf(
            CaptureSessionEntity(
                sessionId = "session-before-midnight",
                nightId = acrossMidnightNight.nightId,
                captureOrder = 0,
                startedAtEpochMillis = epochMillis("2026-07-30T04:59:00Z"),
                startedUtcOffsetSeconds = -18_000,
                finalizedAtEpochMillis = epochMillis("2026-07-30T04:59:45Z"),
                finalizedUtcOffsetSeconds = -18_000,
                incompleteReason = null,
                audioFileName = "session-before-midnight.wav",
                audioState = AudioEvidenceState.RETAINED,
                sampleRateHz = 16_000,
                channelCount = 1,
                bitsPerSample = 16,
                sampleCount = 720_000,
                preRollSampleCount = 16_000,
                cueStartSample = 16_000,
                cueEndSampleExclusive = 24_000,
                automaticSilenceTailSampleCount = 160_256L,
            ),
            CaptureSessionEntity(
                sessionId = "session-after-midnight",
                nightId = acrossMidnightNight.nightId,
                captureOrder = 1,
                startedAtEpochMillis = epochMillis("2026-07-30T05:02:00Z"),
                startedUtcOffsetSeconds = -18_000,
                finalizedAtEpochMillis = epochMillis("2026-07-30T05:03:15Z"),
                finalizedUtcOffsetSeconds = -18_000,
                incompleteReason = null,
                audioFileName = "session-after-midnight.wav",
                audioState = AudioEvidenceState.RETAINED,
                sampleRateHz = 16_000,
                channelCount = 1,
                bitsPerSample = 16,
                sampleCount = 1_200_000,
                preRollSampleCount = 16_000,
                cueStartSample = 16_000,
                cueEndSampleExclusive = 24_000,
                automaticSilenceTailSampleCount = 160_256L,
            ),
        )
        val acrossMidnightEvents = listOf(
            NightEventEntity(
                nightId = acrossMidnightNight.nightId,
                eventId = "event-before-midnight",
                sessionId = "session-before-midnight",
                epochMillis = epochMillis("2026-07-30T04:59:05Z"),
                utcOffsetSeconds = -18_000,
                type = "session_started",
                encodedAttributes = "{}",
            ),
            NightEventEntity(
                nightId = acrossMidnightNight.nightId,
                eventId = "event-after-midnight",
                sessionId = "session-after-midnight",
                epochMillis = epochMillis("2026-07-30T05:02:05Z"),
                utcOffsetSeconds = -18_000,
                type = "session_started",
                encodedAttributes = "{}",
            ),
            NightEventEntity(
                nightId = acrossMidnightNight.nightId,
                eventId = "event-night-ended",
                sessionId = null,
                epochMillis = epochMillis("2026-07-30T05:06:00Z"),
                utcOffsetSeconds = -18_000,
                type = "night_ended",
                encodedAttributes = """{"reason":"owner_stopped"}""",
            ),
        )
        val newerNight = acrossMidnightNight.copy(
            nightId = "newer-night",
            displayDate = "2026-07-31",
            startedAtEpochMillis = epochMillis("2026-07-31T03:00:00Z"),
            startedUtcOffsetSeconds = 19_800,
            endedAtEpochMillis = epochMillis("2026-07-31T03:05:00Z"),
            endedUtcOffsetSeconds = 19_800,
            lastHeartbeatEpochMillis = epochMillis("2026-07-31T03:04:30Z"),
            lastHeartbeatUtcOffsetSeconds = 19_800,
            reportedSessionCount = 0,
            rawAudioState = RawAudioState.NONE,
        )

        database = openDatabase()
        database!!.nightDao().apply {
            upsertCaptureGraph(
                night = acrossMidnightNight,
                sessions = acrossMidnightSessions,
                events = acrossMidnightEvents,
            )
            upsertCaptureGraph(
                night = newerNight,
                sessions = emptyList(),
                events = emptyList(),
            )
        }

        database!!.close()
        database = null
        database = openDatabase()

        val reopenedDao = database!!.nightDao()
        val restoredGraph = requireNotNull(reopenedDao.readNight(acrossMidnightNight.nightId))
        assertEquals(acrossMidnightNight, restoredGraph.night)
        assertEquals(
            acrossMidnightSessions.associateBy(CaptureSessionEntity::sessionId),
            restoredGraph.sessions.associateBy(CaptureSessionEntity::sessionId),
        )
        assertEquals(
            acrossMidnightEvents.associateBy(NightEventEntity::eventId),
            restoredGraph.events.associateBy(NightEventEntity::eventId),
        )

        assertTrue(
            restoredGraph.events.single { it.eventId == "event-before-midnight" }.epochMillis <
                localMidnightEpochMillis,
        )
        assertTrue(
            restoredGraph.events.single { it.eventId == "event-after-midnight" }.epochMillis >=
                localMidnightEpochMillis,
        )
        assertTrue(restoredGraph.sessions.all { it.nightId == acrossMidnightNight.nightId })
        assertTrue(restoredGraph.events.all { it.nightId == acrossMidnightNight.nightId })
        assertEquals(-18_000, restoredGraph.night.startedUtcOffsetSeconds)
        assertEquals(-18_000, restoredGraph.night.endedUtcOffsetSeconds)
        assertTrue(restoredGraph.sessions.all { it.startedUtcOffsetSeconds == -18_000 })
        assertTrue(restoredGraph.events.all { it.utcOffsetSeconds == -18_000 })

        val history = reopenedDao.readHistory()
        assertEquals(
            listOf(newerNight.nightId, acrossMidnightNight.nightId),
            history.map { it.night.nightId },
        )
        assertEquals(19_800, history.first().night.startedUtcOffsetSeconds)
        assertEquals(19_800, history.first().night.endedUtcOffsetSeconds)
    }

    @Test
    fun versionOneCaptureGraphMigratesWithoutLossAndAcceptsProcessingGraphs() {
        createVersionOneDatabase()

        database = openDatabaseWithMigration()
        val restored = requireNotNull(
            database!!.nightDao().readNight(MIGRATION_NIGHT_ID),
        )
        assertEquals("2026-07-30", restored.night.displayDate)
        assertEquals(NightCaptureState.ENDED, restored.night.captureState)
        assertEquals(
            listOf(MIGRATION_SESSION_ID),
            restored.sessions.map(CaptureSessionEntity::sessionId),
        )
        assertEquals(
            null,
            restored.sessions.single().automaticSilenceTailSampleCount,
        )
        assertEquals(
            listOf(MIGRATION_EVENT_ID),
            restored.events.map(NightEventEntity::eventId),
        )

        val transcriptionDao = database!!.transcriptionDao()
        assertTrue(
            transcriptionDao.startSession(
                sessionId = MIGRATION_SESSION_ID,
                provenance = TranscriptionProvenance(
                    localeTag = "en-US",
                    engineId = "migration-test-engine",
                    engineVersion = "1",
                    runtimeId = "migration-test-runtime",
                    runtimeVersion = "1",
                    modelId = "migration-test-model",
                    modelVersion = "1",
                    modelSha256 = "a".repeat(64),
                ),
                startedAtEpochMillis = 400L,
            ),
        )
        assertTrue(
            transcriptionDao.markSessionSucceeded(
                sessionId = MIGRATION_SESSION_ID,
                rawText = "neutral fixture",
                segments = listOf(
                    TranscriptSegmentDraft(
                        sourceStartMillis = 0L,
                        sourceEndMillis = 500L,
                        text = "neutral fixture",
                    ),
                ),
                completedAtEpochMillis = 500L,
            ),
        )
        val enrichmentDao = database!!.enrichmentDao()
        assertTrue(
            enrichmentDao.startRun(
                runId = "migration-enrichment-run",
                nightId = MIGRATION_NIGHT_ID,
                provenance = EnrichmentProvenance(
                    localeTag = "en-US",
                    engineId = "migration-test-enrichment-engine",
                    engineVersion = "1",
                    runtimeId = "migration-test-runtime",
                    runtimeVersion = "1",
                    backendId = "cpu",
                    modelId = "migration-test-enrichment-model",
                    modelVersion = "1",
                    modelSha256 = "b".repeat(64),
                    modelBytes = 1_024L,
                    contextWindowTokens = 2_048,
                    maxTotalTokens = 512,
                    promptId = "whole-night",
                    promptVersion = "1",
                    promptSha256 = "c".repeat(64),
                    outputSchemaVersion = 1,
                ),
                inputSha256 = "d".repeat(64),
                startedAtEpochMillis = 600L,
            ),
        )
        assertTrue(
            enrichmentDao.completeRun(
                runId = "migration-enrichment-run",
                expectedInputSha256 = "d".repeat(64),
                dreams = listOf(
                    DreamDraft(
                        dreamId = "migration-dream",
                        kind = DreamKind.DREAM,
                        isUncertain = false,
                        generatedTitle = null,
                        generatedText = "Neutral fixture.",
                        sourceSpans = listOf(
                            DreamSourceSpanDraft(
                                sessionId = MIGRATION_SESSION_ID,
                                sourceTranscriptAttemptCount = 1,
                                firstSegmentIndex = 0,
                                lastSegmentIndex = 0,
                                role = DreamSourceRole.NARRATIVE,
                            ),
                        ),
                    ),
                ),
                completedAtEpochMillis = 700L,
            ),
        )

        database!!.close()
        database = null
        database = openDatabaseWithMigration()

        val transcript = requireNotNull(
            database!!.transcriptionDao().readSessionTranscript(MIGRATION_SESSION_ID),
        )
        assertEquals(ProcessingState.COMPLETE, transcript.transcript.state)
        assertEquals("neutral fixture", transcript.transcript.rawText)
        assertEquals(1, transcript.segments.size)
        assertEquals(
            ProcessingState.COMPLETE,
            database!!.nightDao().readNight(MIGRATION_NIGHT_ID)!!.night.transcriptionState,
        )
        val dream = database!!.enrichmentDao().readNightDreams(MIGRATION_NIGHT_ID).single()
        assertEquals("Neutral fixture.", dream.dream.currentText)
        assertEquals("neutral fixture", dream.sourceSpans.single().sourceText)
        assertEquals(1, dream.sourceSpans.single().sourceTranscriptAttemptCount)
        assertEquals(
            ProcessingState.COMPLETE,
            database!!.nightDao().readNight(MIGRATION_NIGHT_ID)!!.night.enrichmentState,
        )
    }

    @Test
    fun versionThreeProcessingGraphMigratesWithoutLossAndMarksLegacyTailUnknown() {
        createVersionThreeDatabaseWithProcessingGraph()

        database = openDatabaseWithMigration()

        val restoredNight = requireNotNull(
            database!!.nightDao().readNight(V3_MIGRATION_NIGHT_ID),
        )
        assertEquals(ProcessingState.COMPLETE, restoredNight.night.transcriptionState)
        assertEquals(ProcessingState.COMPLETE, restoredNight.night.enrichmentState)
        assertEquals(
            null,
            restoredNight.sessions.single().automaticSilenceTailSampleCount,
        )

        val transcript = requireNotNull(
            database!!.transcriptionDao().readSessionTranscript(V3_MIGRATION_SESSION_ID),
        )
        assertEquals(ProcessingState.COMPLETE, transcript.transcript.state)
        assertEquals(V3_MIGRATION_TEXT, transcript.transcript.rawText)
        assertEquals(V3_MIGRATION_TEXT, transcript.segments.single().text)

        val run = requireNotNull(
            database!!.enrichmentDao().readRun(V3_MIGRATION_RUN_ID),
        )
        assertEquals(ProcessingState.COMPLETE, run.state)
        val dream = database!!.enrichmentDao()
            .readNightDreams(V3_MIGRATION_NIGHT_ID)
            .single()
        assertEquals(V3_MIGRATION_DREAM_TEXT, dream.dream.currentText)
        assertEquals(null, dream.dream.deletedAtEpochMillis)
        assertEquals(V3_MIGRATION_TEXT, dream.sourceSpans.single().sourceText)
        assertEquals(V3_MIGRATION_SESSION_ID, dream.sourceSpans.single().sessionId)
    }

    @Test
    fun completedEnrichmentRunsBecomeSupersededWhenTheirGraphOrTranscriptIsReplaced() {
        val nightId = "superseded-night"
        val sessionId = "superseded-session"
        val night = endedNight(nightId)
        val session = retainedSession(sessionId, nightId)
        database = openDatabase()
        database!!.nightDao().upsertCaptureGraph(night, listOf(session), emptyList())

        val transcriptionDao = database!!.transcriptionDao()
        assertTrue(
            transcriptionDao.startSession(
                sessionId,
                transcriptionProvenance(engineVersion = "1"),
                100L,
            ),
        )
        assertTrue(
            transcriptionDao.markSessionSucceeded(
                sessionId = sessionId,
                rawText = "old source",
                segments = listOf(TranscriptSegmentDraft(0L, 500L, "old source")),
                completedAtEpochMillis = 200L,
            ),
        )

        val enrichmentDao = database!!.enrichmentDao()
        assertTrue(
            enrichmentDao.startRun(
                "superseded-run-1",
                nightId,
                enrichmentProvenance(),
                hash('d'),
                300L,
            ),
        )
        assertTrue(
            enrichmentDao.completeRun(
                "superseded-run-1",
                hash('d'),
                listOf(dreamDraft("superseded-dream-1", sessionId, 1, "Old source.")),
                400L,
            ),
        )
        assertTrue(
            enrichmentDao.startRun(
                "superseded-run-2",
                nightId,
                enrichmentProvenance(),
                hash('d'),
                500L,
            ),
        )
        assertTrue(
            enrichmentDao.completeRun(
                "superseded-run-2",
                hash('d'),
                listOf(dreamDraft("superseded-dream-2", sessionId, 1, "Old source.")),
                600L,
            ),
        )
        assertEquals(
            listOf(ProcessingState.SUPERSEDED, ProcessingState.COMPLETE),
            enrichmentDao.readNightRuns(nightId).map(EnrichmentRunEntity::state),
        )

        assertTrue(
            transcriptionDao.replaceCompletedSession(
                sessionId = sessionId,
                provenance = transcriptionProvenance(engineVersion = "2"),
                rawText = "new source",
                segments = listOf(TranscriptSegmentDraft(0L, 500L, "new source")),
                startedAtEpochMillis = 700L,
                completedAtEpochMillis = 800L,
            ),
        )

        assertEquals(
            listOf(ProcessingState.SUPERSEDED, ProcessingState.SUPERSEDED),
            enrichmentDao.readNightRuns(nightId).map(EnrichmentRunEntity::state),
        )
        assertTrue(enrichmentDao.readNightDreams(nightId).isEmpty())
        val persistedNight = requireNotNull(database!!.nightDao().readNight(nightId)).night
        assertEquals(ProcessingState.FAILED, persistedNight.enrichmentState)
        assertTrue(persistedNight.enrichmentFailure.orEmpty().contains("changed"))
        assertTrue(
            persistedNight.enrichmentFailure.orEmpty()
                .endsWith("[code=transcript_replaced; retryable=true]"),
        )
    }

    @Test
    fun completedTranscriptRemainsEnrichmentReadyAfterAudioBecomesUnavailable() {
        val nightId = "audio-unavailable-night"
        val sessionId = "audio-unavailable-session"
        val night = endedNight(nightId)
        val retained = retainedSession(sessionId, nightId)
        database = openDatabase()
        database!!.nightDao().upsertCaptureGraph(night, listOf(retained), emptyList())

        val transcriptionDao = database!!.transcriptionDao()
        assertTrue(
            transcriptionDao.startSession(
                sessionId,
                transcriptionProvenance(engineVersion = "1"),
                100L,
            ),
        )
        assertTrue(
            transcriptionDao.markSessionSucceeded(
                sessionId = sessionId,
                rawText = "durable transcript",
                segments = listOf(TranscriptSegmentDraft(0L, 500L, "durable transcript")),
                completedAtEpochMillis = 200L,
            ),
        )

        val persistedBeforeLoss = requireNotNull(database!!.nightDao().readNight(nightId))
        database!!.nightDao().upsertCaptureGraph(
            night = persistedBeforeLoss.night.copy(rawAudioState = RawAudioState.UNAVAILABLE),
            sessions = listOf(
                persistedBeforeLoss.sessions.single().copy(
                    audioState = AudioEvidenceState.MISSING,
                ),
            ),
            events = emptyList(),
        )
        transcriptionDao.reconcileNightState(nightId)
        assertEquals(
            ProcessingState.COMPLETE,
            requireNotNull(database!!.nightDao().readNight(nightId)).night.transcriptionState,
        )

        val enrichmentDao = database!!.enrichmentDao()
        assertTrue(
            enrichmentDao.startRun(
                "audio-unavailable-run",
                nightId,
                enrichmentProvenance(),
                hash('e'),
                300L,
            ),
        )
        assertTrue(
            enrichmentDao.completeRun(
                "audio-unavailable-run",
                hash('e'),
                listOf(
                    dreamDraft(
                        "audio-unavailable-dream",
                        sessionId,
                        1,
                        "Durable transcript.",
                    ),
                ),
                400L,
            ),
        )
        assertEquals(
            ProcessingState.COMPLETE,
            requireNotNull(database!!.nightDao().readNight(nightId)).night.enrichmentState,
        )
    }

    @Test
    fun archiveEditsDeletionFilteringRawRemovalAndNightCascadePersist() {
        val firstNightId = "archive-first-night"
        val secondNightId = "archive-second-night"
        val firstSessionId = "archive-first-session"
        val secondSessionId = "archive-second-session"
        database = openDatabase()
        database!!.nightDao().upsertCaptureGraph(
            endedNight(firstNightId),
            listOf(retainedSession(firstSessionId, firstNightId)),
            emptyList(),
        )
        database!!.nightDao().upsertCaptureGraph(
            endedNight(secondNightId).copy(startedAtEpochMillis = 30L, endedAtEpochMillis = 40L),
            listOf(retainedSession(secondSessionId, secondNightId)),
            emptyList(),
        )

        listOf(
            Triple(firstNightId, firstSessionId, "archive-first-dream"),
            Triple(secondNightId, secondSessionId, "archive-second-dream"),
        ).forEachIndexed { index, (nightId, sessionId, dreamId) ->
            val startedAt = 100L + index * 1_000L
            assertTrue(
                database!!.transcriptionDao().startSession(
                    sessionId,
                    transcriptionProvenance(engineVersion = "1"),
                    startedAt,
                ),
            )
            assertTrue(
                database!!.transcriptionDao().markSessionSucceeded(
                    sessionId = sessionId,
                    rawText = "source $index",
                    segments = listOf(
                        TranscriptSegmentDraft(0L, 500L, "source $index"),
                    ),
                    completedAtEpochMillis = startedAt + 100L,
                ),
            )
            assertTrue(
                database!!.enrichmentDao().startRun(
                    runId = "archive-run-$index",
                    nightId = nightId,
                    provenance = enrichmentProvenance(),
                    inputSha256 = hash(('d'.code + index).toChar()),
                    startedAtEpochMillis = startedAt + 200L,
                ),
            )
            assertTrue(
                database!!.enrichmentDao().completeRun(
                    runId = "archive-run-$index",
                    expectedInputSha256 = hash(('d'.code + index).toChar()),
                    dreams = listOf(
                        dreamDraft(dreamId, sessionId, 1, "Generated $index."),
                    ),
                    completedAtEpochMillis = startedAt + 300L,
                ),
            )
        }

        assertTrue(
            database!!.enrichmentDao().editDream(
                dreamId = "archive-first-dream",
                currentTitle = "Owner title",
                currentText = "Owner text.",
                editedAtEpochMillis = 2_000L,
            ),
        )
        database!!.close()
        database = null
        database = openDatabase()

        val edited = database!!.enrichmentDao().readNightDreams(firstNightId).single().dream
        assertEquals("Generated 0.", edited.generatedText)
        assertEquals("Owner text.", edited.currentText)
        assertEquals("Owner title", edited.currentTitle)
        assertTrue(edited.ownerEdited)
        assertEquals(2_000L, edited.editedAtEpochMillis)

        assertTrue(
            database!!.enrichmentDao().deleteDream(
                dreamId = edited.dreamId,
                deletedAtEpochMillis = 2_100L,
            ),
        )
        assertTrue(database!!.enrichmentDao().readNightDreams(firstNightId).isEmpty())
        val tombstone = database!!.nightDao().readNight(firstNightId)!!.dreams.single()
        assertEquals(2_100L, tombstone.dream.deletedAtEpochMillis)
        assertEquals("source 0", tombstone.sourceSpans.single().sourceText)
        assertTrue(database!!.enrichmentDao().restoreDream(edited.dreamId))

        assertTrue(database!!.nightDao().markNightRawAudioDeleted(firstNightId))
        val textPreserved = database!!.nightDao().readNight(firstNightId)!!
        assertEquals(AudioEvidenceState.DELETED, textPreserved.sessions.single().audioState)
        assertEquals("source 0", textPreserved.transcripts.single().transcript.rawText)
        assertEquals("Owner text.", textPreserved.dreams.single().dream.currentText)
        assertEquals("source 0", textPreserved.dreams.single().sourceSpans.single().sourceText)

        assertEquals(1, database!!.nightDao().deleteNight(firstNightId))
        assertNull(database!!.nightDao().readNight(firstNightId))
        val untouched = requireNotNull(database!!.nightDao().readNight(secondNightId))
        assertEquals(AudioEvidenceState.RETAINED, untouched.sessions.single().audioState)
        assertEquals("source 1", untouched.transcripts.single().transcript.rawText)
        assertEquals("Generated 1.", untouched.dreams.single().dream.currentText)
    }

    private fun openDatabase(): DreamLogDatabase =
        Room.databaseBuilder(
            context,
            DreamLogDatabase::class.java,
            databaseName,
        ).build()

    private fun openDatabaseWithMigration(): DreamLogDatabase =
        Room.databaseBuilder(
            context,
            DreamLogDatabase::class.java,
            databaseName,
        ).addMigrations(
            DreamLogDatabase.MIGRATION_1_2,
            DreamLogDatabase.MIGRATION_2_3,
            DreamLogDatabase.MIGRATION_3_4,
            DreamLogDatabase.MIGRATION_4_5,
        )
            .build()

    private fun endedNight(nightId: String) = NightEntity(
        nightId = nightId,
        displayDate = "2026-07-31",
        startedAtEpochMillis = 10L,
        startedUtcOffsetSeconds = -18_000,
        endedAtEpochMillis = 20L,
        endedUtcOffsetSeconds = -18_000,
        captureState = NightCaptureState.ENDED,
        endReason = "owner_stopped",
        interrupted = false,
        lastHeartbeatEpochMillis = 15L,
        lastHeartbeatUtcOffsetSeconds = -18_000,
        reportedSessionCount = 1,
        reportedIncompleteSessionCount = 0,
        hadMicrophoneSilencing = false,
        hadAudioGap = false,
        rawAudioState = RawAudioState.RETAINED,
        transcriptionState = ProcessingState.NOT_STARTED,
        transcriptionFailure = null,
        enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION,
        enrichmentFailure = null,
        importWarning = null,
    )

    private fun retainedSession(sessionId: String, nightId: String) = CaptureSessionEntity(
        sessionId = sessionId,
        nightId = nightId,
        captureOrder = 0,
        startedAtEpochMillis = 11L,
        startedUtcOffsetSeconds = -18_000,
        finalizedAtEpochMillis = 19L,
        finalizedUtcOffsetSeconds = -18_000,
        incompleteReason = null,
        audioFileName = "$sessionId.wav",
        audioState = AudioEvidenceState.RETAINED,
        sampleRateHz = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
        sampleCount = 16_000L,
        preRollSampleCount = 0L,
        cueStartSample = 1_000L,
        cueEndSampleExclusive = 2_000L,
    )

    private fun transcriptionProvenance(engineVersion: String) = TranscriptionProvenance(
        localeTag = "en-US",
        engineId = "room-test-transcription-engine",
        engineVersion = engineVersion,
        runtimeId = "room-test-runtime",
        runtimeVersion = "1",
        modelId = "room-test-transcription-model",
        modelVersion = "1",
        modelSha256 = hash('a'),
    )

    private fun enrichmentProvenance() = EnrichmentProvenance(
        localeTag = "en-US",
        engineId = "room-test-enrichment-engine",
        engineVersion = "1",
        runtimeId = "room-test-runtime",
        runtimeVersion = "1",
        backendId = "cpu",
        modelId = "room-test-enrichment-model",
        modelVersion = "1",
        modelSha256 = hash('b'),
        modelBytes = 1_024L,
        contextWindowTokens = 2_048,
        maxTotalTokens = 512,
        promptId = "whole-night",
        promptVersion = "1",
        promptSha256 = hash('c'),
        outputSchemaVersion = 1,
    )

    private fun dreamDraft(
        dreamId: String,
        sessionId: String,
        transcriptAttempt: Int,
        text: String,
    ) = DreamDraft(
        dreamId = dreamId,
        kind = DreamKind.DREAM,
        isUncertain = false,
        generatedTitle = null,
        generatedText = text,
        sourceSpans = listOf(
            DreamSourceSpanDraft(
                sessionId = sessionId,
                sourceTranscriptAttemptCount = transcriptAttempt,
                firstSegmentIndex = 0,
                lastSegmentIndex = 0,
                role = DreamSourceRole.NARRATIVE,
            ),
        ),
    )

    private fun createVersionThreeDatabaseWithProcessingGraph() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            versionOneSchemaSql.forEach(db::execSQL)
                            DreamLogDatabase.MIGRATION_1_2.migrate(db)
                            DreamLogDatabase.MIGRATION_2_3.migrate(db)
                            seedVersionThreeProcessingGraph(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            error(
                                "Unexpected schema upgrade while creating the version-three fixture: " +
                                    "$oldVersion to $newVersion.",
                            )
                        }
                    },
                )
                .build(),
        )
        try {
            helper.writableDatabase
        } finally {
            helper.close()
        }
    }

    private fun seedVersionThreeProcessingGraph(db: SupportSQLiteDatabase) {
        db.insertOrFail(
            table = "nights",
            values = ContentValues().apply {
                put("nightId", V3_MIGRATION_NIGHT_ID)
                put("displayDate", "2026-08-01")
                put("startedAtEpochMillis", 1_000L)
                put("startedUtcOffsetSeconds", -18_000)
                put("endedAtEpochMillis", 10_000L)
                put("endedUtcOffsetSeconds", -18_000)
                put("captureState", NightCaptureState.ENDED)
                put("endReason", "owner_stopped")
                put("interrupted", 0)
                put("lastHeartbeatEpochMillis", 9_000L)
                put("lastHeartbeatUtcOffsetSeconds", -18_000)
                put("reportedSessionCount", 1)
                put("reportedIncompleteSessionCount", 0)
                put("hadMicrophoneSilencing", 0)
                put("hadAudioGap", 0)
                put("rawAudioState", RawAudioState.RETAINED)
                put("transcriptionState", ProcessingState.COMPLETE)
                putNull("transcriptionFailure")
                put("enrichmentState", ProcessingState.COMPLETE)
                putNull("enrichmentFailure")
                putNull("importWarning")
            },
        )
        db.insertOrFail(
            table = "capture_sessions",
            values = ContentValues().apply {
                put("sessionId", V3_MIGRATION_SESSION_ID)
                put("nightId", V3_MIGRATION_NIGHT_ID)
                put("captureOrder", 0)
                put("startedAtEpochMillis", 2_000L)
                put("startedUtcOffsetSeconds", -18_000)
                put("finalizedAtEpochMillis", 8_000L)
                put("finalizedUtcOffsetSeconds", -18_000)
                putNull("incompleteReason")
                put("audioFileName", "legacy-session.wav")
                put("audioState", AudioEvidenceState.RETAINED)
                put("sampleRateHz", 16_000)
                put("channelCount", 1)
                put("bitsPerSample", 16)
                put("sampleCount", 378_000L)
                put("preRollSampleCount", 32_000L)
                put("cueStartSample", 35_000L)
                put("cueEndSampleExclusive", 58_000L)
            },
        )
        db.insertOrFail(
            table = "session_transcripts",
            values = ContentValues().apply {
                put("sessionId", V3_MIGRATION_SESSION_ID)
                put("nightId", V3_MIGRATION_NIGHT_ID)
                put("state", ProcessingState.COMPLETE)
                putNull("failureDetail")
                put("rawText", V3_MIGRATION_TEXT)
                put("localeTag", "en-US")
                put("engineId", "migration-engine")
                put("engineVersion", "1")
                put("runtimeId", "migration-runtime")
                put("runtimeVersion", "1")
                put("modelId", "migration-asr-model")
                put("modelVersion", "1")
                put("modelSha256", hash('a'))
                put("attemptCount", 1)
                put("startedAtEpochMillis", 11_000L)
                put("completedAtEpochMillis", 12_000L)
            },
        )
        db.insertOrFail(
            table = "transcript_segments",
            values = ContentValues().apply {
                put("sessionId", V3_MIGRATION_SESSION_ID)
                put("segmentIndex", 0)
                put("sourceStartMillis", 0L)
                put("sourceEndMillis", 500L)
                put("text", V3_MIGRATION_TEXT)
            },
        )
        db.insertOrFail(
            table = "enrichment_runs",
            values = ContentValues().apply {
                put("runId", V3_MIGRATION_RUN_ID)
                put("nightId", V3_MIGRATION_NIGHT_ID)
                put("attemptNumber", 1)
                put("state", ProcessingState.COMPLETE)
                putNull("failureDetail")
                put("localeTag", "en-US")
                put("engineId", "migration-enrichment-engine")
                put("engineVersion", "1")
                put("runtimeId", "migration-runtime")
                put("runtimeVersion", "1")
                put("backendId", "gpu")
                put("modelId", "migration-enrichment-model")
                put("modelVersion", "1")
                put("modelSha256", hash('b'))
                put("modelBytes", 1_024L)
                put("contextWindowTokens", 2_048)
                put("maxTotalTokens", 512)
                put("promptId", "whole-night")
                put("promptVersion", "1")
                put("promptSha256", hash('c'))
                put("outputSchemaVersion", 1)
                put("inputSha256", hash('d'))
                put("startedAtEpochMillis", 13_000L)
                put("completedAtEpochMillis", 14_000L)
            },
        )
        db.insertOrFail(
            table = "dreams",
            values = ContentValues().apply {
                put("dreamId", V3_MIGRATION_DREAM_ID)
                put("nightId", V3_MIGRATION_NIGHT_ID)
                put("runId", V3_MIGRATION_RUN_ID)
                put("dreamOrder", 0)
                put("kind", DreamKind.DREAM)
                put("isUncertain", 0)
                putNull("generatedTitle")
                put("generatedText", V3_MIGRATION_DREAM_TEXT)
                putNull("currentTitle")
                put("currentText", V3_MIGRATION_DREAM_TEXT)
                put("ownerEdited", 0)
                putNull("editedAtEpochMillis")
            },
        )
        db.insertOrFail(
            table = "dream_source_spans",
            values = ContentValues().apply {
                put("dreamId", V3_MIGRATION_DREAM_ID)
                put("spanOrder", 0)
                put("sessionId", V3_MIGRATION_SESSION_ID)
                put("sourceTranscriptAttemptCount", 1)
                put("firstSegmentIndex", 0)
                put("lastSegmentIndex", 0)
                put("sourceStartMillis", 0L)
                put("sourceEndMillis", 500L)
                put("sourceText", V3_MIGRATION_TEXT)
                put("role", DreamSourceRole.NARRATIVE)
            },
        )
    }

    private fun SupportSQLiteDatabase.insertOrFail(
        table: String,
        values: ContentValues,
    ) {
        check(insert(table, SQLiteDatabase.CONFLICT_ABORT, values) != -1L) {
            "Could not seed the version-three migration fixture table: $table"
        }
    }

    private fun createVersionOneDatabase() {
        val databaseFile = context.getDatabasePath(databaseName)
        check(databaseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqlite ->
            versionOneSchemaSql.forEach(sqlite::execSQL)
            sqlite.insertOrThrow(
                "nights",
                null,
                ContentValues().apply {
                    put("nightId", MIGRATION_NIGHT_ID)
                    put("displayDate", "2026-07-30")
                    put("startedAtEpochMillis", 100L)
                    put("startedUtcOffsetSeconds", -18_000)
                    put("endedAtEpochMillis", 300L)
                    put("endedUtcOffsetSeconds", -18_000)
                    put("captureState", NightCaptureState.ENDED)
                    put("endReason", "owner_stopped")
                    put("interrupted", 0)
                    put("lastHeartbeatEpochMillis", 250L)
                    put("lastHeartbeatUtcOffsetSeconds", -18_000)
                    put("reportedSessionCount", 1)
                    put("reportedIncompleteSessionCount", 0)
                    put("hadMicrophoneSilencing", 0)
                    put("hadAudioGap", 0)
                    put("rawAudioState", RawAudioState.RETAINED)
                    put("transcriptionState", ProcessingState.NOT_STARTED)
                    putNull("transcriptionFailure")
                    put("enrichmentState", ProcessingState.NOT_STARTED)
                    putNull("enrichmentFailure")
                    putNull("importWarning")
                },
            )
            sqlite.insertOrThrow(
                "capture_sessions",
                null,
                ContentValues().apply {
                    put("sessionId", MIGRATION_SESSION_ID)
                    put("nightId", MIGRATION_NIGHT_ID)
                    put("captureOrder", 0)
                    put("startedAtEpochMillis", 120L)
                    put("startedUtcOffsetSeconds", -18_000)
                    put("finalizedAtEpochMillis", 220L)
                    put("finalizedUtcOffsetSeconds", -18_000)
                    putNull("incompleteReason")
                    put("audioFileName", "neutral-session.wav")
                    put("audioState", AudioEvidenceState.RETAINED)
                    put("sampleRateHz", 16_000)
                    put("channelCount", 1)
                    put("bitsPerSample", 16)
                    put("sampleCount", 16_000L)
                    put("preRollSampleCount", 0L)
                    put("cueStartSample", 1_000L)
                    put("cueEndSampleExclusive", 2_000L)
                },
            )
            sqlite.insertOrThrow(
                "night_events",
                null,
                ContentValues().apply {
                    put("nightId", MIGRATION_NIGHT_ID)
                    put("eventId", MIGRATION_EVENT_ID)
                    put("sessionId", MIGRATION_SESSION_ID)
                    put("epochMillis", 120L)
                    put("utcOffsetSeconds", -18_000)
                    put("type", "session_started")
                    put("encodedAttributes", "{}")
                },
            )
            sqlite.version = 1
        }
    }

    private fun epochMillis(instant: String): Long = Instant.parse(instant).toEpochMilli()

    private companion object {
        const val MIGRATION_NIGHT_ID = "migration-night"
        const val MIGRATION_SESSION_ID = "migration-session"
        const val MIGRATION_EVENT_ID = "migration-event"
        const val V3_MIGRATION_NIGHT_ID = "version-three-migration-night"
        const val V3_MIGRATION_SESSION_ID = "version-three-migration-session"
        const val V3_MIGRATION_RUN_ID = "version-three-migration-run"
        const val V3_MIGRATION_DREAM_ID = "version-three-migration-dream"
        const val V3_MIGRATION_TEXT = "neutral migration fixture"
        const val V3_MIGRATION_DREAM_TEXT = "Neutral migration fixture."

        fun hash(character: Char): String = character.toString().repeat(64)

        val versionOneSchemaSql = listOf(
            """
            CREATE TABLE IF NOT EXISTS `nights` (
                `nightId` TEXT NOT NULL,
                `displayDate` TEXT NOT NULL,
                `startedAtEpochMillis` INTEGER NOT NULL,
                `startedUtcOffsetSeconds` INTEGER NOT NULL,
                `endedAtEpochMillis` INTEGER,
                `endedUtcOffsetSeconds` INTEGER,
                `captureState` TEXT NOT NULL,
                `endReason` TEXT,
                `interrupted` INTEGER NOT NULL,
                `lastHeartbeatEpochMillis` INTEGER,
                `lastHeartbeatUtcOffsetSeconds` INTEGER,
                `reportedSessionCount` INTEGER NOT NULL,
                `reportedIncompleteSessionCount` INTEGER NOT NULL,
                `hadMicrophoneSilencing` INTEGER NOT NULL,
                `hadAudioGap` INTEGER NOT NULL,
                `rawAudioState` TEXT NOT NULL,
                `transcriptionState` TEXT NOT NULL,
                `transcriptionFailure` TEXT,
                `enrichmentState` TEXT NOT NULL,
                `enrichmentFailure` TEXT,
                `importWarning` TEXT,
                PRIMARY KEY(`nightId`)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_nights_startedAtEpochMillis` " +
                "ON `nights` (`startedAtEpochMillis`)",
            "CREATE INDEX IF NOT EXISTS `index_nights_displayDate` " +
                "ON `nights` (`displayDate`)",
            """
            CREATE TABLE IF NOT EXISTS `capture_sessions` (
                `sessionId` TEXT NOT NULL,
                `nightId` TEXT NOT NULL,
                `captureOrder` INTEGER NOT NULL,
                `startedAtEpochMillis` INTEGER,
                `startedUtcOffsetSeconds` INTEGER,
                `finalizedAtEpochMillis` INTEGER,
                `finalizedUtcOffsetSeconds` INTEGER,
                `incompleteReason` TEXT,
                `audioFileName` TEXT NOT NULL,
                `audioState` TEXT NOT NULL,
                `sampleRateHz` INTEGER,
                `channelCount` INTEGER,
                `bitsPerSample` INTEGER,
                `sampleCount` INTEGER,
                `preRollSampleCount` INTEGER,
                `cueStartSample` INTEGER,
                `cueEndSampleExclusive` INTEGER,
                PRIMARY KEY(`sessionId`),
                FOREIGN KEY(`nightId`) REFERENCES `nights`(`nightId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_capture_sessions_nightId` " +
                "ON `capture_sessions` (`nightId`)",
            "CREATE INDEX IF NOT EXISTS `index_capture_sessions_nightId_captureOrder` " +
                "ON `capture_sessions` (`nightId`, `captureOrder`)",
            """
            CREATE TABLE IF NOT EXISTS `night_events` (
                `nightId` TEXT NOT NULL,
                `eventId` TEXT NOT NULL,
                `sessionId` TEXT,
                `epochMillis` INTEGER NOT NULL,
                `utcOffsetSeconds` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `encodedAttributes` TEXT NOT NULL,
                PRIMARY KEY(`nightId`, `eventId`),
                FOREIGN KEY(`nightId`) REFERENCES `nights`(`nightId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_night_events_nightId` " +
                "ON `night_events` (`nightId`)",
            "CREATE INDEX IF NOT EXISTS `index_night_events_nightId_epochMillis` " +
                "ON `night_events` (`nightId`, `epochMillis`)",
            "CREATE INDEX IF NOT EXISTS `index_night_events_sessionId` " +
                "ON `night_events` (`sessionId`)",
        )
    }
}
