package com.wivy.dreamlog.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentDaoTest {
    @Test
    fun successfulRunStoresOrderedBaselineAndStableSourceSnapshot() {
        val dao = FakeEnrichmentDao().apply {
            addSource("session-1", attempt = 2, index = 0, start = 100L, end = 200L, "first")
            addSource("session-1", attempt = 2, index = 1, start = 200L, end = 300L, "second")
        }

        assertTrue(dao.startRun("run-1", NIGHT_ID, provenance(), hash('a'), 100L))
        val storedRun = requireNotNull(dao.readRun("run-1"))
        assertEquals("test-enrichment-engine", storedRun.engineId)
        assertEquals("1", storedRun.engineVersion)
        assertEquals("test-runtime", storedRun.runtimeId)
        assertEquals("1", storedRun.runtimeVersion)
        assertEquals("cpu", storedRun.backendId)
        assertEquals("test-model", storedRun.modelId)
        assertEquals("1", storedRun.modelVersion)
        assertEquals(1_024L, storedRun.modelBytes)
        assertEquals(2_048, storedRun.contextWindowTokens)
        assertEquals(512, storedRun.maxTotalTokens)
        assertTrue(
            dao.completeRun(
                runId = "run-1",
                expectedInputSha256 = hash('a'),
                dreams = listOf(
                    DreamDraft(
                        dreamId = "dream-1",
                        kind = DreamKind.DREAM,
                        isUncertain = false,
                        generatedTitle = null,
                        generatedText = "First second.",
                        sourceSpans = listOf(
                            DreamSourceSpanDraft(
                                sessionId = "session-1",
                                sourceTranscriptAttemptCount = 2,
                                firstSegmentIndex = 0,
                                lastSegmentIndex = 1,
                                role = DreamSourceRole.NARRATIVE,
                            ),
                        ),
                    ),
                ),
                completedAtEpochMillis = 200L,
            ),
        )

        val result = dao.readNightDreams(NIGHT_ID).single()
        assertNull(result.dream.generatedTitle)
        assertNull(result.dream.currentTitle)
        assertEquals("First second.", result.dream.generatedText)
        assertEquals(result.dream.generatedText, result.dream.currentText)
        assertFalse(result.dream.ownerEdited)
        assertEquals(2, result.sourceSpans.single().sourceTranscriptAttemptCount)
        assertEquals("first second", result.sourceSpans.single().sourceText)
        assertEquals(100L, result.sourceSpans.single().sourceStartMillis)
        assertEquals(300L, result.sourceSpans.single().sourceEndMillis)
        assertEquals(ProcessingState.COMPLETE, dao.nightState)
        assertFalse(dao.completeRun("run-1", hash('a'), emptyList(), 300L))
    }

    @Test
    fun failureAndRecoveryRetainThePriorSuccessfulGraph() {
        val dao = FakeEnrichmentDao().apply {
            addSource("session-1", attempt = 1, index = 0, start = 0L, end = 100L, "source")
        }
        val firstDream = dream("dream-1", "First reading")

        assertTrue(dao.startRun("run-1", NIGHT_ID, provenance(), hash('a'), 100L))
        assertTrue(dao.completeRun("run-1", hash('a'), listOf(firstDream), 200L))
        assertTrue(dao.startRun("run-2", NIGHT_ID, provenance(), hash('a'), 300L))
        assertTrue(dao.markRunFailed("run-2", "malformed_output", 400L))
        assertEquals("First reading", dao.readNightDreams(NIGHT_ID).single().dream.currentText)

        assertTrue(dao.startRun("run-3", NIGHT_ID, provenance(), hash('a'), 500L))
        assertEquals(
            1,
            dao.markStaleRunningRunsFailed(
                startedBeforeEpochMillis = Long.MAX_VALUE,
                recoveredAtEpochMillis = 600L,
                failureDetail = "process_interrupted",
            ),
        )
        assertEquals("First reading", dao.readNightDreams(NIGHT_ID).single().dream.currentText)
        assertEquals(ProcessingState.FAILED, dao.nightState)
        assertEquals(listOf(ProcessingState.COMPLETE, ProcessingState.FAILED, ProcessingState.FAILED),
            dao.readNightRuns(NIGHT_ID).map(EnrichmentRunEntity::state))
    }

    @Test
    fun successfulReprocessingSupersedesThePriorCompletedRun() {
        val dao = FakeEnrichmentDao().apply {
            addSource("session-1", attempt = 1, index = 0, start = 0L, end = 100L, "source")
        }

        assertTrue(dao.startRun("run-1", NIGHT_ID, provenance(), hash('a'), 100L))
        assertTrue(
            dao.completeRun(
                "run-1",
                hash('a'),
                listOf(dream("dream-1", "First reading")),
                200L,
            ),
        )
        assertTrue(dao.startRun("run-2", NIGHT_ID, provenance(), hash('a'), 300L))
        assertTrue(
            dao.completeRun(
                "run-2",
                hash('a'),
                listOf(dream("dream-2", "Replacement reading")),
                400L,
            ),
        )

        assertEquals(
            listOf(ProcessingState.SUPERSEDED, ProcessingState.COMPLETE),
            dao.readNightRuns(NIGHT_ID).map(EnrichmentRunEntity::state),
        )
        assertEquals(
            "Replacement reading",
            dao.readNightDreams(NIGHT_ID).single().dream.currentText,
        )
    }

    @Test
    fun ownerEditBlocksClaimAndConcurrentReplacement() {
        val dao = FakeEnrichmentDao().apply {
            addSource("session-1", attempt = 1, index = 0, start = 0L, end = 100L, "source")
        }
        assertTrue(dao.startRun("run-1", NIGHT_ID, provenance(), hash('a'), 100L))
        assertTrue(dao.completeRun("run-1", hash('a'), listOf(dream("dream-1", "First")), 200L))

        assertTrue(dao.startRun("run-2", NIGHT_ID, provenance(), hash('a'), 300L))
        dao.markOwnerEdited("dream-1", "Owner version")
        val completionFailure = runCatching {
            dao.completeRun("run-2", hash('a'), listOf(dream("dream-2", "Replacement")), 400L)
        }.exceptionOrNull()
        assertTrue(completionFailure is IllegalStateException)
        assertTrue(dao.markRunFailed("run-2", "owner_edit_protected", 400L))

        val claimFailure = runCatching {
            dao.startRun("run-3", NIGHT_ID, provenance(), hash('a'), 500L)
        }.exceptionOrNull()

        assertTrue(claimFailure is IllegalStateException)
        assertEquals("Owner version", dao.readNightDreams(NIGHT_ID).single().dream.currentText)
        assertEquals(2, dao.readNightRuns(NIGHT_ID).size)
    }

    @Test
    fun duplicateCoverageIsRejectedAndWritesNothing() {
        val dao = FakeEnrichmentDao().apply {
            addSource("session-1", attempt = 1, index = 0, start = 0L, end = 100L, "source")
        }
        assertTrue(dao.startRun("run-1", NIGHT_ID, provenance(), hash('a'), 100L))
        val duplicate = dream("dream-1", "First").copy(
            sourceSpans = listOf(source(), source(role = DreamSourceRole.ADDITION)),
        )

        val failure = runCatching {
            dao.completeRun("run-1", hash('a'), listOf(duplicate), 200L)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(dao.readNightDreams(NIGHT_ID).isEmpty())
        assertEquals(ProcessingState.RUNNING, dao.readRun("run-1")!!.state)
    }

    @Test
    fun endedNightWithNoEligibleOrRawSessionsAcceptsEmptyResult() {
        val dao = FakeEnrichmentDao(
            target = target(
                transcriptionState = ProcessingState.NOT_STARTED,
                sessionCount = 0,
                transcriptCount = 0,
                completeTranscriptCount = 0,
            ),
        )

        assertTrue(dao.startRun("run-empty", NIGHT_ID, provenance(), hash('a'), 100L))
        assertTrue(dao.completeRun("run-empty", hash('a'), emptyList(), 200L))
        assertEquals(ProcessingState.COMPLETE, dao.nightState)
        assertTrue(dao.readNightDreams(NIGHT_ID).isEmpty())
    }

    @Test
    fun incompleteNonEmptyTranscriptionCannotBeClaimed() {
        val dao = FakeEnrichmentDao(
            target = target(
                transcriptionState = ProcessingState.RUNNING,
                sessionCount = 1,
                transcriptCount = 1,
                completeTranscriptCount = 0,
            ),
        )

        val failure = runCatching {
            dao.startRun("run-1", NIGHT_ID, provenance(), hash('a'), 100L)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(dao.readNightRuns(NIGHT_ID).isEmpty())
    }

    @Test
    fun ownerEditPreservesGeneratedBaselineAndSurvivesVisibleReads() {
        val dao = FakeEnrichmentDao().apply {
            addSource("session-1", attempt = 1, index = 0, start = 0L, end = 100L, "source")
        }
        assertTrue(dao.startRun("run-1", NIGHT_ID, provenance(), hash('a'), 100L))
        assertTrue(
            dao.completeRun(
                "run-1",
                hash('a'),
                listOf(dream("dream-1", "Generated reading")),
                200L,
            ),
        )

        assertTrue(
            dao.editDream(
                dreamId = "dream-1",
                currentTitle = "  Owner title  ",
                currentText = "Owner reading",
                editedAtEpochMillis = 300L,
            ),
        )
        assertFalse(
            dao.editDream(
                dreamId = "dream-1",
                currentTitle = "Owner title",
                currentText = "Owner reading",
                editedAtEpochMillis = 400L,
            ),
        )

        val edited = dao.readNightDreams(NIGHT_ID).single().dream
        assertEquals("Generated reading", edited.generatedText)
        assertEquals("Owner reading", edited.currentText)
        assertEquals("Owner title", edited.currentTitle)
        assertTrue(edited.ownerEdited)
        assertEquals(300L, edited.editedAtEpochMillis)
        assertTrue(
            runCatching {
                dao.startRun("run-2", NIGHT_ID, provenance(), hash('a'), 500L)
            }.exceptionOrNull() is IllegalStateException,
        )
    }

    @Test
    fun logicalDeletionFiltersPlaybackGraphBlocksReplacementAndCanBeUndone() {
        val dao = FakeEnrichmentDao().apply {
            addSource("session-1", attempt = 1, index = 0, start = 0L, end = 100L, "source")
        }
        assertTrue(dao.startRun("run-1", NIGHT_ID, provenance(), hash('a'), 100L))
        assertTrue(
            dao.completeRun(
                "run-1",
                hash('a'),
                listOf(dream("dream-1", "Generated reading")),
                200L,
            ),
        )

        assertTrue(dao.deleteDream("dream-1", deletedAtEpochMillis = 300L))
        assertTrue(dao.readNightDreams(NIGHT_ID).isEmpty())
        assertTrue(
            runCatching {
                dao.startRun("run-2", NIGHT_ID, provenance(), hash('a'), 400L)
            }.exceptionOrNull() is IllegalStateException,
        )
        assertTrue(dao.restoreDream("dream-1"))
        val restored = dao.readNightDreams(NIGHT_ID).single()
        assertEquals("Generated reading", restored.dream.currentText)
        assertEquals("source", restored.sourceSpans.single().sourceText)
        assertNull(restored.dream.deletedAtEpochMillis)
    }

    private fun dream(dreamId: String, text: String) = DreamDraft(
        dreamId = dreamId,
        kind = DreamKind.DREAM,
        isUncertain = false,
        generatedTitle = null,
        generatedText = text,
        sourceSpans = listOf(source()),
    )

    private fun source(role: String = DreamSourceRole.NARRATIVE) = DreamSourceSpanDraft(
        sessionId = "session-1",
        sourceTranscriptAttemptCount = 1,
        firstSegmentIndex = 0,
        lastSegmentIndex = 0,
        role = role,
    )

    private fun provenance() = EnrichmentProvenance(
        localeTag = "en-US",
        engineId = "test-enrichment-engine",
        engineVersion = "1",
        runtimeId = "test-runtime",
        runtimeVersion = "1",
        backendId = "cpu",
        modelId = "test-model",
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

    private class FakeEnrichmentDao(
        private val target: NightEnrichmentTarget = target(),
    ) : EnrichmentDao() {
        private val runs = linkedMapOf<String, EnrichmentRunEntity>()
        private val dreams = linkedMapOf<String, DreamEntity>()
        private val spans = linkedMapOf<Pair<String, Int>, DreamSourceSpanEntity>()
        private val sources = mutableListOf<EnrichmentSourceSegment>()
        var nightState: String = target.transcriptionState
            private set
        var nightFailure: String? = null
            private set

        fun addSource(
            sessionId: String,
            attempt: Int,
            index: Int,
            start: Long,
            end: Long,
            text: String,
        ) {
            sources += EnrichmentSourceSegment(
                nightId = target.nightId,
                sessionId = sessionId,
                captureOrder = 0,
                transcriptAttemptCount = attempt,
                segmentIndex = index,
                sourceStartMillis = start,
                sourceEndMillis = end,
                text = text,
            )
        }

        fun markOwnerEdited(dreamId: String, text: String) {
            dreams[dreamId] = dreams.getValue(dreamId).copy(
                currentText = text,
                ownerEdited = true,
                editedAtEpochMillis = 1_000L,
            )
        }

        override fun readNightDreams(nightId: String): List<DreamWithSourceSpans> =
            dreams.values
                .filter { it.nightId == nightId && it.deletedAtEpochMillis == null }
                .sortedBy(DreamEntity::dreamOrder)
                .map { dream ->
                    DreamWithSourceSpans(
                        dream = dream,
                        sourceSpans = spans.values
                            .filter { it.dreamId == dream.dreamId }
                            .sortedBy(DreamSourceSpanEntity::spanOrder),
                    )
                }

        override fun readNightRuns(nightId: String): List<EnrichmentRunEntity> =
            runs.values.filter { it.nightId == nightId }.sortedBy { it.attemptNumber }

        override fun readRun(runId: String): EnrichmentRunEntity? = runs[runId]

        override fun readNightSourceSegments(nightId: String): List<EnrichmentSourceSegment> =
            sources.filter { it.nightId == nightId }

        override fun readNightTarget(nightId: String): NightEnrichmentTarget? =
            target.takeIf { it.nightId == nightId }

        override fun readMaxAttemptNumber(nightId: String): Int =
            runs.values.filter { it.nightId == nightId }.maxOfOrNull { it.attemptNumber } ?: 0

        override fun readStaleRunningRuns(
            startedBeforeEpochMillis: Long,
        ): List<EnrichmentRunEntity> = runs.values.filter {
            it.state == ProcessingState.RUNNING &&
                it.startedAtEpochMillis <= startedBeforeEpochMillis
        }

        override fun readCurrentDreamEntities(nightId: String): List<DreamEntity> =
            dreams.values.filter { it.nightId == nightId }

        override fun readDreamEntity(dreamId: String): DreamEntity? = dreams[dreamId]

        override fun updateDreamEdit(
            dreamId: String,
            currentTitle: String?,
            currentText: String,
            editedAtEpochMillis: Long,
        ): Int {
            val existing = dreams[dreamId]
                ?.takeIf { it.deletedAtEpochMillis == null }
                ?: return 0
            dreams[dreamId] = existing.copy(
                currentTitle = currentTitle,
                currentText = currentText,
                ownerEdited = true,
                editedAtEpochMillis = editedAtEpochMillis,
            )
            return 1
        }

        override fun markDreamDeleted(dreamId: String, deletedAtEpochMillis: Long): Int {
            val existing = dreams[dreamId]
                ?.takeIf { it.deletedAtEpochMillis == null }
                ?: return 0
            dreams[dreamId] = existing.copy(deletedAtEpochMillis = deletedAtEpochMillis)
            return 1
        }

        override fun restoreDeletedDream(dreamId: String): Int {
            val existing = dreams[dreamId]
                ?.takeIf { it.deletedAtEpochMillis != null }
                ?: return 0
            dreams[dreamId] = existing.copy(deletedAtEpochMillis = null)
            return 1
        }

        override fun insertRun(run: EnrichmentRunEntity) {
            check(runs.putIfAbsent(run.runId, run) == null)
        }

        override fun updateRun(run: EnrichmentRunEntity): Int {
            if (runs[run.runId] == null) return 0
            runs[run.runId] = run
            return 1
        }

        override fun insertDreams(dreams: List<DreamEntity>) {
            dreams.forEach { dream -> check(this.dreams.putIfAbsent(dream.dreamId, dream) == null) }
        }

        override fun insertSourceSpans(spans: List<DreamSourceSpanEntity>) {
            spans.forEach { span ->
                check(this.spans.putIfAbsent(span.dreamId to span.spanOrder, span) == null)
            }
        }

        override fun deleteCurrentDreams(nightId: String): Int {
            val dreamIds = dreams.values.filter { it.nightId == nightId }.map { it.dreamId }.toSet()
            dreamIds.forEach(dreams::remove)
            spans.keys.filter { it.first in dreamIds }.forEach(spans::remove)
            return dreamIds.size
        }

        override fun markCompletedRunsSuperseded(nightId: String): Int {
            val completed = runs.values.filter {
                it.nightId == nightId && it.state == ProcessingState.COMPLETE
            }
            completed.forEach { run ->
                runs[run.runId] = run.copy(state = ProcessingState.SUPERSEDED)
            }
            return completed.size
        }

        override fun updateNightState(nightId: String, state: String, failureDetail: String?) {
            check(nightId == target.nightId)
            nightState = state
            nightFailure = failureDetail
        }
    }

    private companion object {
        const val NIGHT_ID = "night-1"

        fun hash(character: Char): String = character.toString().repeat(64)

        fun target(
            transcriptionState: String = ProcessingState.COMPLETE,
            sessionCount: Int = 1,
            transcriptCount: Int = 1,
            completeTranscriptCount: Int = 1,
        ) = NightEnrichmentTarget(
            nightId = NIGHT_ID,
            captureState = NightCaptureState.ENDED,
            endedAtEpochMillis = 10L,
            transcriptionState = transcriptionState,
            sessionCount = sessionCount,
            transcriptCount = transcriptCount,
            completeTranscriptCount = completeTranscriptCount,
        )
    }
}
