package com.wivy.dreamlog.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

data class TranscriptionSessionTarget(
    val sessionId: String,
    val nightId: String,
    val audioState: String,
    val finalizedAtEpochMillis: Long?,
    val captureState: String,
    val endedAtEpochMillis: Long?,
)

data class NightTranscriptionCounts(
    val eligibleSessionCount: Int,
    val unavailableSessionCount: Int,
    val runningSessionCount: Int,
    val failedSessionCount: Int,
    val completeSessionCount: Int,
)

@Dao
abstract class TranscriptionDao {
    @Transaction
    @Query("SELECT * FROM session_transcripts WHERE sessionId = :sessionId LIMIT 1")
    abstract fun readSessionTranscript(sessionId: String): SessionTranscriptWithSegments?

    @Transaction
    @Query(
        """
        SELECT * FROM session_transcripts
        WHERE nightId = :nightId
        ORDER BY sessionId
        """,
    )
    abstract fun readNightTranscripts(nightId: String): List<SessionTranscriptWithSegments>

    @Query(
        """
        SELECT
            session.sessionId,
            session.nightId,
            session.audioState,
            session.finalizedAtEpochMillis,
            night.captureState,
            night.endedAtEpochMillis
        FROM capture_sessions AS session
        INNER JOIN nights AS night ON night.nightId = session.nightId
        WHERE session.sessionId = :sessionId
        LIMIT 1
        """,
    )
    protected abstract fun readSessionTarget(sessionId: String): TranscriptionSessionTarget?

    @Query("SELECT * FROM session_transcripts WHERE sessionId = :sessionId LIMIT 1")
    protected abstract fun readTranscriptEntity(sessionId: String): SessionTranscriptEntity?

    @Query(
        """
        SELECT * FROM session_transcripts
        WHERE state = 'running' AND startedAtEpochMillis <= :startedBeforeEpochMillis
        ORDER BY nightId, sessionId
        """,
    )
    protected abstract fun readStaleRunningTranscripts(
        startedBeforeEpochMillis: Long,
    ): List<SessionTranscriptEntity>

    @Upsert
    protected abstract fun upsertTranscript(transcript: SessionTranscriptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertSegments(segments: List<TranscriptSegmentEntity>)

    @Query("DELETE FROM transcript_segments WHERE sessionId = :sessionId")
    protected abstract fun deleteSegments(sessionId: String)

    @Query(
        """
        SELECT COUNT(*)
        FROM dreams
        WHERE nightId = :nightId AND (
            ownerEdited = 1 OR
            editedAtEpochMillis IS NOT NULL OR
            deletedAtEpochMillis IS NOT NULL OR
            currentTitle IS NOT generatedTitle OR
            currentText != generatedText
        )
        """,
    )
    protected abstract fun readOwnerEditedDreamCount(nightId: String): Int

    @Query(
        """
        UPDATE enrichment_runs
        SET state = 'superseded'
        WHERE nightId = :nightId AND state = 'complete'
        """,
    )
    protected abstract fun markCompletedEnrichmentRunsSuperseded(nightId: String): Int

    @Query("DELETE FROM dreams WHERE nightId = :nightId")
    protected abstract fun deleteDreamsAfterTranscriptReplacement(nightId: String): Int

    @Query(
        """
        UPDATE nights
        SET enrichmentState = 'failed', enrichmentFailure = :failureDetail
        WHERE nightId = :nightId
        """,
    )
    protected abstract fun markEnrichmentStaleAfterTranscriptReplacement(
        nightId: String,
        failureDetail: String,
    )

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE
                WHEN session.audioState = 'retained'
                    AND session.finalizedAtEpochMillis IS NOT NULL THEN 1
                ELSE 0
            END), 0) AS eligibleSessionCount,
            COALESCE(SUM(CASE
                WHEN session.audioState != 'retained'
                    OR session.finalizedAtEpochMillis IS NULL THEN 1
                ELSE 0
            END), 0) AS unavailableSessionCount,
            COALESCE(SUM(CASE WHEN transcript.state = 'running' THEN 1 ELSE 0 END), 0)
                AS runningSessionCount,
            COALESCE(SUM(CASE WHEN transcript.state = 'failed' THEN 1 ELSE 0 END), 0)
                AS failedSessionCount,
            COALESCE(SUM(CASE WHEN transcript.state = 'complete' THEN 1 ELSE 0 END), 0)
                AS completeSessionCount
        FROM capture_sessions AS session
        LEFT JOIN session_transcripts AS transcript
            ON transcript.sessionId = session.sessionId
        WHERE session.nightId = :nightId
        """,
    )
    protected abstract fun readNightCounts(nightId: String): NightTranscriptionCounts

    @Query(
        """
        SELECT transcript.failureDetail
        FROM session_transcripts AS transcript
        INNER JOIN capture_sessions AS session
            ON session.sessionId = transcript.sessionId
        WHERE transcript.nightId = :nightId AND transcript.state = 'failed'
        ORDER BY session.captureOrder, transcript.sessionId
        LIMIT 1
        """,
    )
    protected abstract fun readFirstNightFailure(nightId: String): String?

    @Query(
        """
        UPDATE nights
        SET transcriptionState = :state, transcriptionFailure = :failureDetail
        WHERE nightId = :nightId
        """,
    )
    protected abstract fun updateNightState(
        nightId: String,
        state: String,
        failureDetail: String?,
    )

    @Transaction
    open fun reconcileNightState(nightId: String) {
        synchronizeNightState(nightId)
    }

    @Transaction
    open fun startSession(
        sessionId: String,
        provenance: TranscriptionProvenance,
        startedAtEpochMillis: Long,
    ): Boolean {
        val target = requireRetainedTarget(sessionId)
        val existing = readTranscriptEntity(sessionId)
        if (existing != null) return false

        upsertTranscript(
            newRunningTranscript(
                target = target,
                provenance = provenance,
                startedAtEpochMillis = startedAtEpochMillis,
                attemptCount = 1,
            ),
        )
        synchronizeNightState(target.nightId)
        return true
    }

    @Transaction
    open fun retrySession(
        sessionId: String,
        provenance: TranscriptionProvenance,
        startedAtEpochMillis: Long,
    ): Boolean {
        val target = requireRetainedTarget(sessionId)
        val existing = readTranscriptEntity(sessionId) ?: return false
        if (existing.state != ProcessingState.FAILED) return false

        deleteSegments(sessionId)
        upsertTranscript(
            newRunningTranscript(
                target = target,
                provenance = provenance,
                startedAtEpochMillis = startedAtEpochMillis,
                attemptCount = existing.attemptCount + 1,
            ),
        )
        synchronizeNightState(target.nightId)
        return true
    }

    /** Atomically replaces a completed result after new inference has already succeeded. */
    @Transaction
    open fun replaceCompletedSession(
        sessionId: String,
        provenance: TranscriptionProvenance,
        rawText: String,
        segments: List<TranscriptSegmentDraft>,
        startedAtEpochMillis: Long,
        completedAtEpochMillis: Long,
    ): Boolean {
        val target = requireRetainedTarget(sessionId)
        val existing = readTranscriptEntity(sessionId) ?: return false
        if (existing.state != ProcessingState.COMPLETE) return false
        check(existing.nightId == target.nightId) {
            "The completed transcript belongs to a different night."
        }
        validateProvenance(provenance)
        validateSegments(rawText, segments)
        require(startedAtEpochMillis >= 0L) {
            "The replacement timestamp must be non-negative."
        }
        require(completedAtEpochMillis >= startedAtEpochMillis) {
            "The replacement completion timestamp precedes its start."
        }

        check(readOwnerEditedDreamCount(target.nightId) == 0) {
            "Re-transcription cannot replace raw evidence while owner-modified dreams depend on it."
        }

        persistCompletedReplacement(
            existing = existing,
            provenance = provenance,
            replacement = SessionTranscriptReplacementDraft(
                sessionId = sessionId,
                rawText = rawText,
                segments = segments,
                startedAtEpochMillis = startedAtEpochMillis,
                completedAtEpochMillis = completedAtEpochMillis,
            ),
        )
        if (markCompletedEnrichmentRunsSuperseded(target.nightId) > 0) {
            deleteDreamsAfterTranscriptReplacement(target.nightId)
            markEnrichmentStaleAfterTranscriptReplacement(
                nightId = target.nightId,
                failureDetail = ENRICHMENT_STALE_AFTER_TRANSCRIPT_REPLACEMENT,
            )
        }
        synchronizeNightState(target.nightId)
        return true
    }

    /**
     * Atomically replaces every completed transcript in one night after all inference succeeded.
     * A failure while decoding any session therefore leaves the prior transcript/dream graph
     * untouched instead of producing a partially upgraded night.
     */
    @Transaction
    open fun replaceCompletedNight(
        nightId: String,
        provenance: TranscriptionProvenance,
        replacements: List<SessionTranscriptReplacementDraft>,
    ): Boolean {
        require(nightId.isNotBlank()) { "A night ID is required." }
        if (replacements.isEmpty()) return false
        validateProvenance(provenance)
        val replacementBySession = replacements.associateBy { it.sessionId }
        require(replacementBySession.size == replacements.size) {
            "A session transcript replacement is duplicated."
        }

        val existing = readNightTranscripts(nightId)
        if (existing.isEmpty()) return false
        if (existing.any { it.transcript.state != ProcessingState.COMPLETE }) return false
        val existingBySession = existing.associateBy { it.transcript.sessionId }
        if (existingBySession.keys != replacementBySession.keys) return false

        check(readOwnerEditedDreamCount(nightId) == 0) {
            "Re-transcription cannot replace raw evidence while owner-modified dreams depend on it."
        }

        replacements.forEach { replacement ->
            require(replacement.sessionId.isNotBlank()) { "A session ID is required." }
            val target = requireRetainedTarget(replacement.sessionId)
            check(target.nightId == nightId) {
                "A completed transcript belongs to a different night."
            }
            val transcript = checkNotNull(existingBySession[replacement.sessionId]?.transcript)
            validateSegments(replacement.rawText, replacement.segments)
            require(replacement.startedAtEpochMillis >= 0L) {
                "The replacement timestamp must be non-negative."
            }
            require(replacement.completedAtEpochMillis >= replacement.startedAtEpochMillis) {
                "The replacement completion timestamp precedes its start."
            }
            check(transcript.nightId == nightId)
        }

        replacements.forEach { replacement ->
            persistCompletedReplacement(
                existing = checkNotNull(existingBySession[replacement.sessionId]?.transcript),
                provenance = provenance,
                replacement = replacement,
            )
        }
        if (markCompletedEnrichmentRunsSuperseded(nightId) > 0) {
            deleteDreamsAfterTranscriptReplacement(nightId)
            markEnrichmentStaleAfterTranscriptReplacement(
                nightId = nightId,
                failureDetail = ENRICHMENT_STALE_AFTER_TRANSCRIPT_REPLACEMENT,
            )
        }
        synchronizeNightState(nightId)
        return true
    }

    private fun persistCompletedReplacement(
        existing: SessionTranscriptEntity,
        provenance: TranscriptionProvenance,
        replacement: SessionTranscriptReplacementDraft,
    ) {
        deleteSegments(replacement.sessionId)
        upsertTranscript(
            existing.copy(
                state = ProcessingState.COMPLETE,
                failureDetail = null,
                rawText = replacement.rawText,
                localeTag = provenance.localeTag,
                engineId = provenance.engineId,
                engineVersion = provenance.engineVersion,
                runtimeId = provenance.runtimeId,
                runtimeVersion = provenance.runtimeVersion,
                modelId = provenance.modelId,
                modelVersion = provenance.modelVersion,
                modelSha256 = provenance.modelSha256,
                attemptCount = Math.addExact(existing.attemptCount, 1),
                startedAtEpochMillis = replacement.startedAtEpochMillis,
                completedAtEpochMillis = replacement.completedAtEpochMillis,
            ),
        )
        if (replacement.segments.isNotEmpty()) {
            insertSegments(
                replacement.segments.mapIndexed { index, segment ->
                    TranscriptSegmentEntity(
                        sessionId = replacement.sessionId,
                        segmentIndex = index,
                        sourceStartMillis = segment.sourceStartMillis,
                        sourceEndMillis = segment.sourceEndMillis,
                        text = segment.text,
                    )
                },
            )
        }
    }

    @Transaction
    open fun markSessionSucceeded(
        sessionId: String,
        rawText: String,
        segments: List<TranscriptSegmentDraft>,
        completedAtEpochMillis: Long,
    ): Boolean {
        validateSegments(rawText, segments)
        val existing = requireNotNull(readTranscriptEntity(sessionId)) {
            "The transcription attempt does not exist."
        }
        if (existing.state == ProcessingState.COMPLETE) return false
        check(existing.state == ProcessingState.RUNNING) {
            "Only a running transcription attempt can succeed."
        }
        require(completedAtEpochMillis >= existing.startedAtEpochMillis) {
            "The completion timestamp precedes the attempt start."
        }

        deleteSegments(sessionId)
        if (segments.isNotEmpty()) {
            insertSegments(
                segments.mapIndexed { index, segment ->
                    TranscriptSegmentEntity(
                        sessionId = sessionId,
                        segmentIndex = index,
                        sourceStartMillis = segment.sourceStartMillis,
                        sourceEndMillis = segment.sourceEndMillis,
                        text = segment.text,
                    )
                },
            )
        }
        upsertTranscript(
            existing.copy(
                state = ProcessingState.COMPLETE,
                failureDetail = null,
                rawText = rawText,
                completedAtEpochMillis = completedAtEpochMillis,
            ),
        )
        synchronizeNightState(existing.nightId)
        return true
    }

    @Transaction
    open fun markSessionFailed(
        sessionId: String,
        failureDetail: String,
        completedAtEpochMillis: Long,
    ): Boolean {
        require(failureDetail.isNotBlank()) { "A transcription failure needs a detail." }
        val existing = requireNotNull(readTranscriptEntity(sessionId)) {
            "The transcription attempt does not exist."
        }
        if (existing.state == ProcessingState.COMPLETE) return false
        if (
            existing.state == ProcessingState.FAILED &&
            existing.failureDetail == failureDetail
        ) {
            return false
        }
        check(existing.state == ProcessingState.RUNNING) {
            "Only a running transcription attempt can fail."
        }
        require(completedAtEpochMillis >= existing.startedAtEpochMillis) {
            "The failure timestamp precedes the attempt start."
        }

        deleteSegments(sessionId)
        upsertTranscript(
            existing.copy(
                state = ProcessingState.FAILED,
                failureDetail = failureDetail,
                rawText = null,
                completedAtEpochMillis = completedAtEpochMillis,
            ),
        )
        synchronizeNightState(existing.nightId)
        return true
    }

    @Transaction
    open fun markStaleRunningSessionsFailed(
        startedBeforeEpochMillis: Long,
        recoveredAtEpochMillis: Long,
        failureDetail: String,
    ): Int {
        require(failureDetail.isNotBlank()) { "A transcription failure needs a detail." }
        val stale = readStaleRunningTranscripts(startedBeforeEpochMillis)
        stale.forEach { transcript ->
            val effectiveRecoveredAt = maxOf(
                recoveredAtEpochMillis,
                transcript.startedAtEpochMillis,
            )
            deleteSegments(transcript.sessionId)
            upsertTranscript(
                transcript.copy(
                    state = ProcessingState.FAILED,
                    failureDetail = failureDetail,
                    rawText = null,
                    completedAtEpochMillis = effectiveRecoveredAt,
                ),
            )
        }
        stale.map(SessionTranscriptEntity::nightId)
            .distinct()
            .forEach(::synchronizeNightState)
        return stale.size
    }

    private fun requireRetainedTarget(sessionId: String): TranscriptionSessionTarget {
        val target = requireNotNull(readSessionTarget(sessionId)) {
            "The capture session does not exist."
        }
        check(target.audioState == AudioEvidenceState.RETAINED) {
            "Only a retained audio session can be transcribed."
        }
        check(target.finalizedAtEpochMillis != null) {
            "Only a finalized audio session can be transcribed."
        }
        check(
            target.captureState == NightCaptureState.ENDED ||
                target.captureState == NightCaptureState.INTERRUPTED,
        ) {
            "Transcription can start only after the night has ended."
        }
        check(target.endedAtEpochMillis != null) {
            "Transcription can start only after the night has an end timestamp."
        }
        return target
    }

    private fun newRunningTranscript(
        target: TranscriptionSessionTarget,
        provenance: TranscriptionProvenance,
        startedAtEpochMillis: Long,
        attemptCount: Int,
    ): SessionTranscriptEntity {
        validateProvenance(provenance)
        require(startedAtEpochMillis >= 0L) { "The attempt timestamp must be non-negative." }
        return SessionTranscriptEntity(
            sessionId = target.sessionId,
            nightId = target.nightId,
            state = ProcessingState.RUNNING,
            failureDetail = null,
            rawText = null,
            localeTag = provenance.localeTag,
            engineId = provenance.engineId,
            engineVersion = provenance.engineVersion,
            runtimeId = provenance.runtimeId,
            runtimeVersion = provenance.runtimeVersion,
            modelId = provenance.modelId,
            modelVersion = provenance.modelVersion,
            modelSha256 = provenance.modelSha256,
            attemptCount = attemptCount,
            startedAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = null,
        )
    }

    private fun synchronizeNightState(nightId: String) {
        val counts = readNightCounts(nightId)
        val sessionCount = counts.eligibleSessionCount + counts.unavailableSessionCount
        val state = when {
            counts.failedSessionCount > 0 -> ProcessingState.FAILED
            counts.runningSessionCount > 0 -> ProcessingState.RUNNING
            sessionCount > 0 && counts.completeSessionCount == sessionCount ->
                ProcessingState.COMPLETE

            counts.unavailableSessionCount > 0 -> ProcessingState.FAILED
            counts.completeSessionCount > 0 -> ProcessingState.RUNNING
            else -> ProcessingState.NOT_STARTED
        }
        updateNightState(
            nightId = nightId,
            state = state,
            failureDetail = if (state == ProcessingState.FAILED) {
                readFirstNightFailure(nightId)
                    ?: "A captured session does not have finalized retained audio and cannot " +
                    "be transcribed. Review its raw-audio diagnostic."
            } else {
                null
            },
        )
    }

    private fun validateProvenance(provenance: TranscriptionProvenance) {
        val values = listOf(
            provenance.localeTag,
            provenance.engineId,
            provenance.engineVersion,
            provenance.runtimeId,
            provenance.runtimeVersion,
            provenance.modelId,
            provenance.modelVersion,
            provenance.modelSha256,
        )
        require(values.all(String::isNotBlank)) {
            "Transcription provenance fields must not be blank."
        }
    }

    private fun validateSegments(
        rawText: String,
        segments: List<TranscriptSegmentDraft>,
    ) {
        require(rawText.isBlank() || segments.isNotEmpty()) {
            "A non-empty transcript needs timestamped segments."
        }
        var previousStartMillis = -1L
        segments.forEach { segment ->
            require(segment.sourceStartMillis >= 0L) {
                "A transcript segment starts before its source audio."
            }
            require(segment.sourceEndMillis > segment.sourceStartMillis) {
                "A transcript segment must have positive duration."
            }
            require(segment.sourceStartMillis >= previousStartMillis) {
                "Transcript segments must be ordered by source timestamp."
            }
            require(segment.text.isNotBlank()) { "A transcript segment must contain text." }
            previousStartMillis = segment.sourceStartMillis
        }
    }

    private companion object {
        const val ENRICHMENT_STALE_AFTER_TRANSCRIPT_REPLACEMENT =
            "The raw transcript changed. Run local enrichment again; the earlier generated " +
                "reading was removed. [code=transcript_replaced; retryable=true]"
    }
}
