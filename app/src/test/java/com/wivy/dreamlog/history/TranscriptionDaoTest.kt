package com.wivy.dreamlog.history

import com.wivy.dreamlog.enrichment.persistence.persistedEnrichmentFailureIsRetryable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionDaoTest {
    @Test
    fun failureRetryAndRepeatedSuccessKeepOneTranscriptAndDeterministicSegments() {
        val dao = FakeTranscriptionDao().apply {
            addTarget("session-1", "night-1")
            addTarget("session-2", "night-1")
        }

        assertTrue(dao.startSession("session-1", provenance(), 100L))
        assertTrue(
            dao.markSessionSucceeded(
                sessionId = "session-1",
                rawText = "first result",
                segments = listOf(TranscriptSegmentDraft(1_000L, 2_000L, "first result")),
                completedAtEpochMillis = 200L,
            ),
        )
        assertFalse(
            dao.markSessionSucceeded(
                sessionId = "session-1",
                rawText = "duplicate result",
                segments = listOf(TranscriptSegmentDraft(2_000L, 3_000L, "duplicate result")),
                completedAtEpochMillis = 300L,
            ),
        )
        assertEquals(1, dao.readSessionTranscript("session-1")!!.segments.size)

        assertTrue(dao.startSession("session-2", provenance(), 300L))
        assertTrue(dao.markSessionFailed("session-2", "forced_failure", 400L))
        assertEquals(ProcessingState.FAILED, dao.nightState("night-1"))

        assertTrue(dao.retrySession("session-2", provenance(), 500L))
        assertEquals(2, dao.readSessionTranscript("session-2")!!.transcript.attemptCount)
        assertEquals(ProcessingState.RUNNING, dao.nightState("night-1"))
        assertTrue(
            dao.markSessionSucceeded(
                sessionId = "session-2",
                rawText = "second result",
                segments = listOf(
                    TranscriptSegmentDraft(500L, 1_000L, "second"),
                    TranscriptSegmentDraft(1_000L, 1_500L, "result"),
                ),
                completedAtEpochMillis = 600L,
            ),
        )

        assertEquals(ProcessingState.COMPLETE, dao.nightState("night-1"))
        assertEquals(
            listOf(0, 1),
            dao.readSessionTranscript("session-2")!!.segments.map { it.segmentIndex },
        )
        assertEquals(2, dao.readNightTranscripts("night-1").size)
    }

    @Test
    fun activeOrUnfinalizedSessionsCannotStartTranscription() {
        val activeDao = FakeTranscriptionDao().apply {
            addTarget(
                sessionId = "active-session",
                nightId = "active-night",
                captureState = NightCaptureState.ACTIVE,
                endedAtEpochMillis = null,
            )
        }
        val activeFailure = runCatching {
            activeDao.startSession("active-session", provenance(), 100L)
        }.exceptionOrNull()
        assertTrue(activeFailure is IllegalStateException)

        val unfinalizedDao = FakeTranscriptionDao().apply {
            addTarget(
                sessionId = "unfinalized-session",
                nightId = "ended-night",
                finalizedAtEpochMillis = null,
            )
        }
        val unfinalizedFailure = runCatching {
            unfinalizedDao.startSession("unfinalized-session", provenance(), 100L)
        }.exceptionOrNull()
        assertTrue(unfinalizedFailure is IllegalStateException)
    }

    @Test
    fun unavailableAudioKeepsTheNightFailedAfterRetainedAudioCompletes() {
        val dao = FakeTranscriptionDao().apply {
            addTarget("retained-session", "night-with-missing-audio")
            addTarget(
                sessionId = "missing-session",
                nightId = "night-with-missing-audio",
                audioState = AudioEvidenceState.MISSING,
                finalizedAtEpochMillis = null,
            )
        }

        assertTrue(dao.startSession("retained-session", provenance(), 100L))
        assertTrue(
            dao.markSessionSucceeded(
                sessionId = "retained-session",
                rawText = "available result",
                segments = listOf(
                    TranscriptSegmentDraft(0L, 500L, "available result"),
                ),
                completedAtEpochMillis = 200L,
            ),
        )

        assertEquals(ProcessingState.FAILED, dao.nightState("night-with-missing-audio"))
        assertTrue(
            dao.nightFailure("night-with-missing-audio")!!.contains("raw-audio"),
        )
    }

    @Test
    fun reconciliationFailsUntranscribedUnavailableAudioButPreservesCompletedTranscript() {
        val unavailableDao = FakeTranscriptionDao().apply {
            addTarget(
                sessionId = "missing-session",
                nightId = "all-unavailable-night",
                audioState = AudioEvidenceState.MISSING,
                finalizedAtEpochMillis = null,
            )
        }
        unavailableDao.reconcileNightState("all-unavailable-night")
        assertEquals(
            ProcessingState.FAILED,
            unavailableDao.nightState("all-unavailable-night"),
        )

        val changedDao = FakeTranscriptionDao().apply {
            addTarget("changed-session", "changed-night")
        }
        assertTrue(changedDao.startSession("changed-session", provenance(), 100L))
        assertTrue(
            changedDao.markSessionSucceeded(
                sessionId = "changed-session",
                rawText = "completed before loss",
                segments = listOf(
                    TranscriptSegmentDraft(0L, 500L, "completed before loss"),
                ),
                completedAtEpochMillis = 200L,
            ),
        )
        assertEquals(ProcessingState.COMPLETE, changedDao.nightState("changed-night"))

        changedDao.addTarget(
            sessionId = "changed-session",
            nightId = "changed-night",
            audioState = AudioEvidenceState.MISSING,
            finalizedAtEpochMillis = null,
        )
        changedDao.reconcileNightState("changed-night")
        assertEquals(ProcessingState.COMPLETE, changedDao.nightState("changed-night"))
    }

    @Test
    fun completedReplacementUpdatesProvenanceAndSegmentsInOneRecord() {
        val dao = FakeTranscriptionDao().apply {
            addTarget("session-1", "night-1")
        }
        assertTrue(dao.startSession("session-1", provenance(), 100L))
        assertTrue(
            dao.markSessionSucceeded(
                sessionId = "session-1",
                rawText = "old result",
                segments = listOf(TranscriptSegmentDraft(0L, 500L, "old result")),
                completedAtEpochMillis = 200L,
            ),
        )

        assertTrue(
            dao.replaceCompletedSession(
                sessionId = "session-1",
                provenance = provenance(engineVersion = "4"),
                rawText = "new result",
                segments = listOf(
                    TranscriptSegmentDraft(1_000L, 1_500L, "new"),
                    TranscriptSegmentDraft(1_500L, 2_000L, "result"),
                ),
                startedAtEpochMillis = 300L,
                completedAtEpochMillis = 400L,
            ),
        )

        val replaced = dao.readSessionTranscript("session-1")!!
        assertEquals(ProcessingState.COMPLETE, replaced.transcript.state)
        assertEquals("new result", replaced.transcript.rawText)
        assertEquals("4", replaced.transcript.engineVersion)
        assertEquals(2, replaced.transcript.attemptCount)
        assertEquals(listOf(0, 1), replaced.segments.map { it.segmentIndex })
        assertEquals(ProcessingState.COMPLETE, dao.nightState("night-1"))
    }

    @Test
    fun completedReplacementInvalidatesOnlyUneditedEnrichment() {
        val dao = FakeTranscriptionDao().apply {
            addTarget("session-1", "night-1")
        }
        assertTrue(dao.startSession("session-1", provenance(), 100L))
        assertTrue(
            dao.markSessionSucceeded(
                sessionId = "session-1",
                rawText = "old result",
                segments = listOf(TranscriptSegmentDraft(0L, 500L, "old result")),
                completedAtEpochMillis = 200L,
            ),
        )
        dao.addCompletedEnrichment("night-1", ownerEdited = false)

        assertTrue(
            dao.replaceCompletedSession(
                sessionId = "session-1",
                provenance = provenance(engineVersion = "4"),
                rawText = "new result",
                segments = listOf(TranscriptSegmentDraft(0L, 500L, "new result")),
                startedAtEpochMillis = 300L,
                completedAtEpochMillis = 400L,
            ),
        )

        assertTrue(dao.dreamsWereDeleted("night-1"))
        assertEquals(ProcessingState.SUPERSEDED, dao.enrichmentRunState("night-1"))
        val enrichmentFailure = requireNotNull(dao.enrichmentFailure("night-1"))
        assertTrue(enrichmentFailure.contains("changed"))
        assertTrue(persistedEnrichmentFailureIsRetryable(enrichmentFailure))
    }

    @Test
    fun completedReplacementRefusesToInvalidateOwnerEdits() {
        val dao = FakeTranscriptionDao().apply {
            addTarget("session-1", "night-1")
        }
        assertTrue(dao.startSession("session-1", provenance(), 100L))
        assertTrue(
            dao.markSessionSucceeded(
                sessionId = "session-1",
                rawText = "old result",
                segments = listOf(TranscriptSegmentDraft(0L, 500L, "old result")),
                completedAtEpochMillis = 200L,
            ),
        )
        dao.addCompletedEnrichment("night-1", ownerEdited = true)

        val failure = runCatching {
            dao.replaceCompletedSession(
                sessionId = "session-1",
                provenance = provenance(engineVersion = "4"),
                rawText = "new result",
                segments = listOf(TranscriptSegmentDraft(0L, 500L, "new result")),
                startedAtEpochMillis = 300L,
                completedAtEpochMillis = 400L,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("old result", dao.readSessionTranscript("session-1")!!.transcript.rawText)
        assertFalse(dao.dreamsWereDeleted("night-1"))
        assertEquals(ProcessingState.COMPLETE, dao.enrichmentRunState("night-1"))
    }

    private fun provenance(engineVersion: String = "1") = TranscriptionProvenance(
        localeTag = "en-US",
        engineId = "test-engine",
        engineVersion = engineVersion,
        runtimeId = "test-runtime",
        runtimeVersion = "1",
        modelId = "test-model",
        modelVersion = "1",
        modelSha256 = "a".repeat(64),
    )

    private class FakeTranscriptionDao : TranscriptionDao() {
        private val targets = linkedMapOf<String, TranscriptionSessionTarget>()
        private val transcripts = linkedMapOf<String, SessionTranscriptEntity>()
        private val segmentsBySession = linkedMapOf<String, MutableList<TranscriptSegmentEntity>>()
        private val nightStates = linkedMapOf<String, String>()
        private val nightFailures = linkedMapOf<String, String?>()
        private val enrichmentRunStates = linkedMapOf<String, String>()
        private val ownerEditedDreams = linkedMapOf<String, Int>()
        private val deletedDreamNights = mutableSetOf<String>()
        private val enrichmentFailures = linkedMapOf<String, String>()

        fun addTarget(
            sessionId: String,
            nightId: String,
            audioState: String = AudioEvidenceState.RETAINED,
            finalizedAtEpochMillis: Long? = 10L,
            captureState: String = NightCaptureState.ENDED,
            endedAtEpochMillis: Long? = 20L,
        ) {
            targets[sessionId] = TranscriptionSessionTarget(
                sessionId = sessionId,
                nightId = nightId,
                audioState = audioState,
                finalizedAtEpochMillis = finalizedAtEpochMillis,
                captureState = captureState,
                endedAtEpochMillis = endedAtEpochMillis,
            )
        }

        fun nightState(nightId: String): String? = nightStates[nightId]

        fun nightFailure(nightId: String): String? = nightFailures[nightId]

        fun addCompletedEnrichment(nightId: String, ownerEdited: Boolean) {
            enrichmentRunStates[nightId] = ProcessingState.COMPLETE
            ownerEditedDreams[nightId] = if (ownerEdited) 1 else 0
        }

        fun enrichmentRunState(nightId: String): String? = enrichmentRunStates[nightId]

        fun dreamsWereDeleted(nightId: String): Boolean = nightId in deletedDreamNights

        fun enrichmentFailure(nightId: String): String? = enrichmentFailures[nightId]

        override fun readSessionTranscript(sessionId: String): SessionTranscriptWithSegments? =
            transcripts[sessionId]?.let { transcript ->
                SessionTranscriptWithSegments(
                    transcript = transcript,
                    segments = segmentsBySession[sessionId].orEmpty().sortedBy { it.segmentIndex },
                )
            }

        override fun readNightTranscripts(nightId: String): List<SessionTranscriptWithSegments> =
            transcripts.values
                .filter { it.nightId == nightId }
                .sortedBy { it.sessionId }
                .map { readSessionTranscript(it.sessionId)!! }

        protected override fun readSessionTarget(
            sessionId: String,
        ): TranscriptionSessionTarget? = targets[sessionId]

        protected override fun readTranscriptEntity(
            sessionId: String,
        ): SessionTranscriptEntity? = transcripts[sessionId]

        protected override fun readStaleRunningTranscripts(
            startedBeforeEpochMillis: Long,
        ): List<SessionTranscriptEntity> =
            transcripts.values.filter {
                it.state == ProcessingState.RUNNING &&
                    it.startedAtEpochMillis <= startedBeforeEpochMillis
            }

        protected override fun upsertTranscript(transcript: SessionTranscriptEntity) {
            transcripts[transcript.sessionId] = transcript
        }

        protected override fun insertSegments(segments: List<TranscriptSegmentEntity>) {
            segments.groupBy { it.sessionId }.forEach { (sessionId, grouped) ->
                val existing = segmentsBySession.getOrPut(sessionId, ::mutableListOf)
                grouped.forEach { segment ->
                    check(existing.none { it.segmentIndex == segment.segmentIndex })
                    existing += segment
                }
            }
        }

        protected override fun deleteSegments(sessionId: String) {
            segmentsBySession.remove(sessionId)
        }

        protected override fun readOwnerEditedDreamCount(nightId: String): Int =
            ownerEditedDreams[nightId] ?: 0

        protected override fun markCompletedEnrichmentRunsSuperseded(nightId: String): Int {
            if (enrichmentRunStates[nightId] != ProcessingState.COMPLETE) return 0
            enrichmentRunStates[nightId] = ProcessingState.SUPERSEDED
            return 1
        }

        protected override fun deleteDreamsAfterTranscriptReplacement(nightId: String): Int {
            deletedDreamNights += nightId
            return 1
        }

        protected override fun markEnrichmentStaleAfterTranscriptReplacement(
            nightId: String,
            failureDetail: String,
        ) {
            enrichmentFailures[nightId] = failureDetail
        }

        protected override fun readNightCounts(nightId: String): NightTranscriptionCounts {
            val eligible = targets.values.filter {
                it.nightId == nightId &&
                    it.audioState == AudioEvidenceState.RETAINED &&
                    it.finalizedAtEpochMillis != null
            }
            val unavailable = targets.values.count {
                it.nightId == nightId &&
                    (
                        it.audioState != AudioEvidenceState.RETAINED ||
                            it.finalizedAtEpochMillis == null
                    )
            }
            val states = targets.values
                .filter { it.nightId == nightId }
                .mapNotNull { transcripts[it.sessionId]?.state }
            return NightTranscriptionCounts(
                eligibleSessionCount = eligible.size,
                unavailableSessionCount = unavailable,
                runningSessionCount = states.count { it == ProcessingState.RUNNING },
                failedSessionCount = states.count { it == ProcessingState.FAILED },
                completeSessionCount = states.count { it == ProcessingState.COMPLETE },
            )
        }

        protected override fun readFirstNightFailure(nightId: String): String? =
            transcripts.values.firstOrNull {
                it.nightId == nightId && it.state == ProcessingState.FAILED
            }?.failureDetail

        protected override fun updateNightState(
            nightId: String,
            state: String,
            failureDetail: String?,
        ) {
            nightStates[nightId] = state
            nightFailures[nightId] = failureDetail
        }
    }
}
