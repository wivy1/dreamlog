package com.wivy.dreamlog.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
abstract class EnrichmentDao {
    @Transaction
    @Query(
        """
        SELECT * FROM dreams
        WHERE nightId = :nightId AND deletedAtEpochMillis IS NULL
        ORDER BY dreamOrder, dreamId
        """,
    )
    abstract fun readNightDreams(nightId: String): List<DreamWithSourceSpans>

    @Query(
        """
        SELECT * FROM enrichment_runs
        WHERE nightId = :nightId
        ORDER BY attemptNumber, runId
        """,
    )
    abstract fun readNightRuns(nightId: String): List<EnrichmentRunEntity>

    @Query("SELECT * FROM enrichment_runs WHERE runId = :runId LIMIT 1")
    abstract fun readRun(runId: String): EnrichmentRunEntity?

    @Query(
        """
        SELECT
            session.nightId,
            session.sessionId,
            session.captureOrder,
            session.startedAtEpochMillis AS narrationStartedAtEpochMillis,
            session.startedUtcOffsetSeconds AS narrationStartedUtcOffsetSeconds,
            transcript.attemptCount AS transcriptAttemptCount,
            segment.segmentIndex,
            segment.sourceStartMillis,
            segment.sourceEndMillis,
            segment.text
        FROM capture_sessions AS session
        INNER JOIN session_transcripts AS transcript
            ON transcript.sessionId = session.sessionId
        INNER JOIN transcript_segments AS segment
            ON segment.sessionId = transcript.sessionId
        WHERE session.nightId = :nightId AND transcript.state = 'complete'
        ORDER BY
            session.captureOrder,
            CASE WHEN session.startedAtEpochMillis IS NULL THEN 1 ELSE 0 END,
            session.startedAtEpochMillis,
            session.sessionId,
            segment.segmentIndex
        """,
    )
    abstract fun readNightSourceSegments(nightId: String): List<EnrichmentSourceSegment>

    @Query(
        """
        SELECT
            night.nightId,
            night.captureState,
            night.endedAtEpochMillis,
            night.transcriptionState,
            (
                SELECT COUNT(*)
                FROM capture_sessions AS session
                WHERE session.nightId = night.nightId
            ) AS sessionCount,
            (
                SELECT COUNT(*)
                FROM session_transcripts AS transcript
                WHERE transcript.nightId = night.nightId
            ) AS transcriptCount,
            (
                SELECT COUNT(*)
                FROM session_transcripts AS transcript
                WHERE transcript.nightId = night.nightId AND transcript.state = 'complete'
            ) AS completeTranscriptCount
        FROM nights AS night
        WHERE night.nightId = :nightId
        LIMIT 1
        """,
    )
    protected abstract fun readNightTarget(nightId: String): NightEnrichmentTarget?

    @Query(
        """
        SELECT COALESCE(MAX(attemptNumber), 0)
        FROM enrichment_runs
        WHERE nightId = :nightId
        """,
    )
    protected abstract fun readMaxAttemptNumber(nightId: String): Int

    @Query(
        """
        SELECT * FROM enrichment_runs
        WHERE state = 'running' AND startedAtEpochMillis <= :startedBeforeEpochMillis
        ORDER BY nightId, attemptNumber, runId
        """,
    )
    protected abstract fun readStaleRunningRuns(
        startedBeforeEpochMillis: Long,
    ): List<EnrichmentRunEntity>

    @Query("SELECT * FROM dreams WHERE nightId = :nightId ORDER BY dreamOrder, dreamId")
    protected abstract fun readCurrentDreamEntities(nightId: String): List<DreamEntity>

    @Query("SELECT * FROM dreams WHERE dreamId = :dreamId LIMIT 1")
    protected abstract fun readDreamEntity(dreamId: String): DreamEntity?

    @Query(
        """
        UPDATE dreams
        SET currentTitle = :currentTitle,
            currentText = :currentText,
            ownerEdited = 1,
            editedAtEpochMillis = :editedAtEpochMillis
        WHERE dreamId = :dreamId AND deletedAtEpochMillis IS NULL
        """,
    )
    protected abstract fun updateDreamEdit(
        dreamId: String,
        currentTitle: String?,
        currentText: String,
        editedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE dreams
        SET deletedAtEpochMillis = :deletedAtEpochMillis
        WHERE dreamId = :dreamId AND deletedAtEpochMillis IS NULL
        """,
    )
    protected abstract fun markDreamDeleted(
        dreamId: String,
        deletedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE dreams
        SET deletedAtEpochMillis = NULL
        WHERE dreamId = :dreamId AND deletedAtEpochMillis IS NOT NULL
        """,
    )
    protected abstract fun restoreDeletedDream(dreamId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertRun(run: EnrichmentRunEntity)

    @Update
    protected abstract fun updateRun(run: EnrichmentRunEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertDreams(dreams: List<DreamEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertSourceSpans(spans: List<DreamSourceSpanEntity>)

    @Query("DELETE FROM dreams WHERE nightId = :nightId")
    protected abstract fun deleteCurrentDreams(nightId: String): Int

    @Query(
        """
        UPDATE enrichment_runs
        SET state = 'superseded'
        WHERE nightId = :nightId AND state = 'complete'
        """,
    )
    protected abstract fun markCompletedRunsSuperseded(nightId: String): Int

    @Query(
        """
        UPDATE nights
        SET enrichmentState = :state, enrichmentFailure = :failureDetail
        WHERE nightId = :nightId
        """,
    )
    protected abstract fun updateNightState(
        nightId: String,
        state: String,
        failureDetail: String?,
    )

    @Transaction
    open fun editDream(
        dreamId: String,
        currentTitle: String?,
        currentText: String,
        editedAtEpochMillis: Long,
    ): Boolean {
        require(dreamId.isNotBlank()) { "A dream ID is required." }
        require(currentText.isNotBlank()) { "Dream text cannot be blank." }
        require(editedAtEpochMillis >= 0L) { "The edit timestamp must be non-negative." }
        val existing = requireNotNull(readDreamEntity(dreamId)) {
            "The requested dream does not exist."
        }
        check(existing.deletedAtEpochMillis == null) { "A deleted dream cannot be edited." }
        existing.editedAtEpochMillis?.let { priorEdit ->
            require(editedAtEpochMillis >= priorEdit) {
                "The edit timestamp cannot move backward."
            }
        }
        val normalizedTitle = currentTitle?.trim()?.takeIf(String::isNotEmpty)
        if (
            existing.currentTitle == normalizedTitle &&
            existing.currentText == currentText
        ) {
            return false
        }
        check(
            updateDreamEdit(
                dreamId = dreamId,
                currentTitle = normalizedTitle,
                currentText = currentText,
                editedAtEpochMillis = editedAtEpochMillis,
            ) == 1,
        ) { "The dream edit could not be saved." }
        return true
    }

    @Transaction
    open fun deleteDream(
        dreamId: String,
        deletedAtEpochMillis: Long,
    ): Boolean {
        require(dreamId.isNotBlank()) { "A dream ID is required." }
        require(deletedAtEpochMillis >= 0L) {
            "The deletion timestamp must be non-negative."
        }
        val existing = requireNotNull(readDreamEntity(dreamId)) {
            "The requested dream does not exist."
        }
        if (existing.deletedAtEpochMillis != null) return false
        existing.editedAtEpochMillis?.let { priorEdit ->
            require(deletedAtEpochMillis >= priorEdit) {
                "The deletion timestamp cannot precede the latest edit."
            }
        }
        check(markDreamDeleted(dreamId, deletedAtEpochMillis) == 1) {
            "The dream could not be deleted."
        }
        return true
    }

    @Transaction
    open fun restoreDream(dreamId: String): Boolean {
        require(dreamId.isNotBlank()) { "A dream ID is required." }
        val existing = requireNotNull(readDreamEntity(dreamId)) {
            "The requested dream does not exist."
        }
        if (existing.deletedAtEpochMillis == null) return false
        check(restoreDeletedDream(dreamId) == 1) {
            "The deleted dream could not be restored."
        }
        return true
    }

    @Transaction
    open fun startRun(
        runId: String,
        nightId: String,
        provenance: EnrichmentProvenance,
        inputSha256: String,
        startedAtEpochMillis: Long,
    ): Boolean {
        require(runId.isNotBlank()) { "An enrichment run ID is required." }
        validateSha256(inputSha256, "The enrichment input SHA-256 is invalid.")
        validateProvenance(provenance)
        require(startedAtEpochMillis >= 0L) {
            "The enrichment attempt timestamp must be non-negative."
        }

        val target = requireReadyTarget(nightId)
        checkNoOwnerEdits(readCurrentDreamEntities(nightId))
        if (readNightRuns(nightId).any { it.state == ProcessingState.RUNNING }) return false

        insertRun(
            EnrichmentRunEntity(
                runId = runId,
                nightId = target.nightId,
                attemptNumber = Math.addExact(readMaxAttemptNumber(nightId), 1),
                state = ProcessingState.RUNNING,
                failureDetail = null,
                localeTag = provenance.localeTag,
                engineId = provenance.engineId,
                engineVersion = provenance.engineVersion,
                runtimeId = provenance.runtimeId,
                runtimeVersion = provenance.runtimeVersion,
                backendId = provenance.backendId,
                modelId = provenance.modelId,
                modelVersion = provenance.modelVersion,
                modelSha256 = provenance.modelSha256,
                modelBytes = provenance.modelBytes,
                contextWindowTokens = provenance.contextWindowTokens,
                maxTotalTokens = provenance.maxTotalTokens,
                promptId = provenance.promptId,
                promptVersion = provenance.promptVersion,
                promptSha256 = provenance.promptSha256,
                outputSchemaVersion = provenance.outputSchemaVersion,
                inputSha256 = inputSha256,
                startedAtEpochMillis = startedAtEpochMillis,
                completedAtEpochMillis = null,
            ),
        )
        updateNightState(nightId, ProcessingState.RUNNING, null)
        return true
    }

    /** Atomically installs a fully validated generated graph for this night. */
    @Transaction
    open fun completeRun(
        runId: String,
        expectedInputSha256: String,
        dreams: List<DreamDraft>,
        completedAtEpochMillis: Long,
    ): Boolean {
        validateSha256(expectedInputSha256, "The enrichment input SHA-256 is invalid.")
        val run = requireNotNull(readRun(runId)) { "The enrichment attempt does not exist." }
        if (
            run.state == ProcessingState.COMPLETE ||
            run.state == ProcessingState.SUPERSEDED
        ) {
            return false
        }
        check(run.state == ProcessingState.RUNNING) {
            "Only a running enrichment attempt can succeed."
        }
        check(run.inputSha256.equals(expectedInputSha256, ignoreCase = true)) {
            "The raw transcript input changed during enrichment."
        }
        require(completedAtEpochMillis >= run.startedAtEpochMillis) {
            "The enrichment completion timestamp precedes its start."
        }

        requireReadyTarget(run.nightId)
        checkNoOwnerEdits(readCurrentDreamEntities(run.nightId))
        val graph = validateAndResolveGraph(
            nightId = run.nightId,
            runId = runId,
            dreams = dreams,
            sourceSegments = readNightSourceSegments(run.nightId),
        )

        markCompletedRunsSuperseded(run.nightId)
        deleteCurrentDreams(run.nightId)
        if (graph.dreams.isNotEmpty()) insertDreams(graph.dreams)
        if (graph.sourceSpans.isNotEmpty()) insertSourceSpans(graph.sourceSpans)
        check(
            updateRun(
                run.copy(
                    state = ProcessingState.COMPLETE,
                    failureDetail = null,
                    completedAtEpochMillis = completedAtEpochMillis,
                ),
            ) == 1,
        ) { "The enrichment attempt could not be completed." }
        updateNightState(run.nightId, ProcessingState.COMPLETE, null)
        return true
    }

    @Transaction
    open fun markRunFailed(
        runId: String,
        failureDetail: String,
        completedAtEpochMillis: Long,
    ): Boolean {
        requireFailureDetail(failureDetail)
        val run = requireNotNull(readRun(runId)) { "The enrichment attempt does not exist." }
        if (
            run.state == ProcessingState.COMPLETE ||
            run.state == ProcessingState.SUPERSEDED
        ) {
            return false
        }
        if (run.state == ProcessingState.FAILED && run.failureDetail == failureDetail) return false
        check(run.state == ProcessingState.RUNNING) {
            "Only a running enrichment attempt can fail."
        }
        require(completedAtEpochMillis >= run.startedAtEpochMillis) {
            "The enrichment failure timestamp precedes its start."
        }

        check(
            updateRun(
                run.copy(
                    state = ProcessingState.FAILED,
                    failureDetail = failureDetail,
                    completedAtEpochMillis = completedAtEpochMillis,
                ),
            ) == 1,
        ) { "The enrichment attempt could not be failed." }
        updateNightState(run.nightId, ProcessingState.FAILED, failureDetail)
        return true
    }

    @Transaction
    open fun markStaleRunningRunsFailed(
        startedBeforeEpochMillis: Long,
        recoveredAtEpochMillis: Long,
        failureDetail: String,
    ): Int {
        requireFailureDetail(failureDetail)
        val stale = readStaleRunningRuns(startedBeforeEpochMillis)
        stale.forEach { run ->
            val completedAt = maxOf(recoveredAtEpochMillis, run.startedAtEpochMillis)
            check(
                updateRun(
                    run.copy(
                        state = ProcessingState.FAILED,
                        failureDetail = failureDetail,
                        completedAtEpochMillis = completedAt,
                    ),
                ) == 1,
            ) { "An interrupted enrichment attempt could not be recovered." }
            updateNightState(run.nightId, ProcessingState.FAILED, failureDetail)
        }
        return stale.size
    }

    private fun requireReadyTarget(nightId: String): NightEnrichmentTarget {
        val target = requireNotNull(readNightTarget(nightId)) {
            "The requested night does not exist."
        }
        check(
            target.captureState == NightCaptureState.ENDED ||
                target.captureState == NightCaptureState.INTERRUPTED,
        ) { "Enrichment can start only after the night has ended." }
        check(target.endedAtEpochMillis != null) {
            "Enrichment requires a finalized night end timestamp."
        }
        if (target.sessionCount == 0 && target.transcriptCount == 0) {
            return target
        }
        check(
            target.transcriptionState == ProcessingState.COMPLETE &&
                target.transcriptCount == target.sessionCount &&
                target.completeTranscriptCount == target.sessionCount,
        ) {
            "Enrichment requires every available raw transcript for the night to be complete."
        }
        return target
    }

    private fun checkNoOwnerEdits(dreams: List<DreamEntity>) {
        check(
            dreams.none { dream ->
                dream.ownerEdited ||
                    dream.editedAtEpochMillis != null ||
                    dream.deletedAtEpochMillis != null ||
                    dream.currentTitle != dream.generatedTitle ||
                    dream.currentText != dream.generatedText
            },
        ) {
            "Owner-edited dream text cannot be replaced without deliberate confirmation."
        }
    }

    private fun validateAndResolveGraph(
        nightId: String,
        runId: String,
        dreams: List<DreamDraft>,
        sourceSegments: List<EnrichmentSourceSegment>,
    ): ResolvedGraph {
        require(dreams.map(DreamDraft::dreamId).distinct().size == dreams.size) {
            "Dream IDs must be unique within an enrichment result."
        }
        require(dreams.isNotEmpty() || sourceSegments.isEmpty()) {
            "Non-empty transcript input must be preserved as a dream or fragment."
        }

        val sourceByKey = sourceSegments.associateBy { segment ->
            SourceKey(
                sessionId = segment.sessionId,
                transcriptAttemptCount = segment.transcriptAttemptCount,
                segmentIndex = segment.segmentIndex,
            )
        }
        check(sourceByKey.size == sourceSegments.size) {
            "Enrichment source segment identifiers are not unique."
        }

        val resolvedDreams = mutableListOf<DreamEntity>()
        val resolvedSpans = mutableListOf<DreamSourceSpanEntity>()
        val coveredSources = mutableSetOf<SourceKey>()
        dreams.forEachIndexed { dreamOrder, draft ->
            require(draft.dreamId.isNotBlank()) { "A dream ID is required." }
            require(draft.kind in DreamKind.values) { "The dream kind is invalid." }
            require(draft.generatedText.isNotBlank()) { "Generated dream text is required." }
            require(draft.sourceSpans.isNotEmpty()) { "Every dream needs a source reference." }

            resolvedDreams += DreamEntity(
                dreamId = draft.dreamId,
                nightId = nightId,
                runId = runId,
                dreamOrder = dreamOrder,
                kind = draft.kind,
                isUncertain = draft.isUncertain,
                generatedTitle = draft.generatedTitle,
                generatedText = draft.generatedText,
                currentTitle = draft.generatedTitle,
                currentText = draft.generatedText,
                ownerEdited = false,
                editedAtEpochMillis = null,
                deletedAtEpochMillis = null,
            )

            draft.sourceSpans.forEachIndexed { spanOrder, span ->
                require(span.role in DreamSourceRole.values) {
                    "The dream source role is invalid."
                }
                require(span.sourceTranscriptAttemptCount > 0) {
                    "A dream source needs a positive transcript attempt."
                }
                require(span.firstSegmentIndex >= 0) {
                    "A dream source segment index is negative."
                }
                require(span.lastSegmentIndex >= span.firstSegmentIndex) {
                    "A dream source segment range is reversed."
                }
                val expectedCount =
                    span.lastSegmentIndex.toLong() - span.firstSegmentIndex.toLong() + 1L
                require(expectedCount <= sourceSegments.size.toLong()) {
                    "A dream source segment range is not present in the raw transcript."
                }
                val range = (span.firstSegmentIndex..span.lastSegmentIndex).map { segmentIndex ->
                    val key = SourceKey(
                        sessionId = span.sessionId,
                        transcriptAttemptCount = span.sourceTranscriptAttemptCount,
                        segmentIndex = segmentIndex,
                    )
                    key to requireNotNull(sourceByKey[key]) {
                        "A dream references an unknown raw transcript segment."
                    }
                }
                check(range.all { (_, segment) -> segment.nightId == nightId }) {
                    "A dream source belongs to a different night."
                }
                range.forEach { (key, _) ->
                    check(coveredSources.add(key)) {
                        "A raw transcript segment cannot be assigned more than once."
                    }
                }
                resolvedSpans += DreamSourceSpanEntity(
                    dreamId = draft.dreamId,
                    spanOrder = spanOrder,
                    sessionId = span.sessionId,
                    sourceTranscriptAttemptCount = span.sourceTranscriptAttemptCount,
                    firstSegmentIndex = span.firstSegmentIndex,
                    lastSegmentIndex = span.lastSegmentIndex,
                    sourceStartMillis = range.first().second.sourceStartMillis,
                    sourceEndMillis = range.last().second.sourceEndMillis,
                    sourceText = range.joinToString(separator = " ") { (_, segment) ->
                        segment.text
                    },
                    role = span.role,
                )
            }
        }
        check(coveredSources == sourceByKey.keys) {
            "Every raw transcript segment must remain represented in the generated result."
        }
        return ResolvedGraph(resolvedDreams, resolvedSpans)
    }

    private fun validateProvenance(provenance: EnrichmentProvenance) {
        val textValues = listOf(
            provenance.localeTag,
            provenance.engineId,
            provenance.engineVersion,
            provenance.runtimeId,
            provenance.runtimeVersion,
            provenance.backendId,
            provenance.modelId,
            provenance.modelVersion,
            provenance.promptId,
            provenance.promptVersion,
        )
        require(textValues.all(String::isNotBlank)) {
            "Enrichment provenance fields must not be blank."
        }
        validateSha256(provenance.modelSha256, "The enrichment model SHA-256 is invalid.")
        validateSha256(provenance.promptSha256, "The enrichment prompt SHA-256 is invalid.")
        require(provenance.modelBytes > 0L) { "The enrichment model byte count must be positive." }
        require(provenance.contextWindowTokens > 0) {
            "The enrichment context window must be positive."
        }
        require(
            provenance.maxTotalTokens > 0 &&
                provenance.maxTotalTokens <= provenance.contextWindowTokens,
        ) { "The enrichment total-token limit is invalid." }
        require(provenance.outputSchemaVersion > 0) {
            "The enrichment output schema version must be positive."
        }
    }

    private fun validateSha256(value: String, message: String) {
        require(SHA_256.matches(value)) { message }
    }

    private fun requireFailureDetail(failureDetail: String) {
        require(failureDetail.isNotBlank()) { "An enrichment failure needs a detail." }
        require(failureDetail.length <= MAX_FAILURE_DETAIL_LENGTH) {
            "An enrichment failure detail is too long."
        }
    }

    private data class SourceKey(
        val sessionId: String,
        val transcriptAttemptCount: Int,
        val segmentIndex: Int,
    )

    private data class ResolvedGraph(
        val dreams: List<DreamEntity>,
        val sourceSpans: List<DreamSourceSpanEntity>,
    )

    private companion object {
        const val MAX_FAILURE_DETAIL_LENGTH = 400
        val SHA_256 = Regex("[0-9a-fA-F]{64}")
    }
}
