package com.wivy.dreamlog.enrichment.persistence

import com.wivy.dreamlog.enrichment.ENRICHMENT_PROMPT_VERSION
import com.wivy.dreamlog.enrichment.ENRICHMENT_SCHEMA_VERSION
import com.wivy.dreamlog.enrichment.EnrichedDreamDraft
import com.wivy.dreamlog.enrichment.EnrichmentAttemptClaim
import com.wivy.dreamlog.enrichment.EnrichmentFailureCode
import com.wivy.dreamlog.enrichment.EnrichmentNightSource
import com.wivy.dreamlog.enrichment.EnrichmentOutputReason
import com.wivy.dreamlog.enrichment.EnrichmentPromptBuilder
import com.wivy.dreamlog.enrichment.EnrichmentRunDescriptor
import com.wivy.dreamlog.enrichment.NightEnrichmentStore
import com.wivy.dreamlog.enrichment.NightTranscriptSegment
import com.wivy.dreamlog.enrichment.OrderedNightTranscript
import com.wivy.dreamlog.enrichment.PersistedEnrichmentFailure
import com.wivy.dreamlog.enrichment.SourceSegmentId
import com.wivy.dreamlog.enrichment.ValidatedEnrichment
import com.wivy.dreamlog.history.DreamDraft
import com.wivy.dreamlog.history.DreamSourceSpanDraft
import com.wivy.dreamlog.history.EnrichmentDao
import com.wivy.dreamlog.history.EnrichmentProvenance
import com.wivy.dreamlog.history.EnrichmentRunEntity
import com.wivy.dreamlog.history.EnrichmentSourceSegment
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightDao
import com.wivy.dreamlog.history.NightWithDetails
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioState
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal fun persistedEnrichmentFailureIsRetryable(detail: String?): Boolean = detail?.let {
    PERSISTED_RETRYABLE.find(it)?.let { marker ->
        marker.groupValues[2] == "true" || marker.groupValues[1] == LEGACY_OVERSIZE_CODE
    }
} == true

internal fun persistedEnrichmentFailureDisplayDetail(detail: String?): String? =
    detail
        ?.replace(PERSISTED_RETRYABLE, "")
        ?.trim()
        ?.ifBlank { null }

private val PERSISTED_RETRYABLE = Regex(
    "\\[code=([a-z0-9_]+); retryable=(true|false)]$",
)
private const val LEGACY_OVERSIZE_CODE = "input_too_large"

/**
 * Room-backed boundary between immutable M04 transcript evidence and M05 generated readings.
 * Raw transcript rows are only read here; [EnrichmentDao] owns the transactional run/dream writes.
 */
class RoomNightEnrichmentStore internal constructor(
    private val gateway: RoomNightEnrichmentGateway,
    private val runIdFactory: () -> String,
) : NightEnrichmentStore {
    constructor(
        nightDao: NightDao,
        enrichmentDao: EnrichmentDao,
    ) : this(
        gateway = DaoRoomNightEnrichmentGateway(nightDao, enrichmentDao),
        runIdFactory = { UUID.randomUUID().toString() },
    )

    override fun loadNightSource(nightId: String): EnrichmentNightSource? {
        require(nightId.isNotBlank()) { "A night ID is required." }
        val record = gateway.readNight(nightId) ?: return null
        val captureEnded = record.night.nightId == nightId &&
            record.night.captureState in ENDED_CAPTURE_STATES &&
            record.night.endedAtEpochMillis != null
        if (!captureEnded) {
            return unavailableSource(nightId, captureEnded = false)
        }

        if (record.isGenuinelyEmptyNight()) {
            return EnrichmentNightSource(
                nightId = nightId,
                captureEnded = true,
                transcriptionComplete = true,
                rawTranscriptReviewable = true,
                segments = emptyList(),
            )
        }

        val transcriptionComplete = record.hasCompleteTranscriptGraph()
        if (!transcriptionComplete) {
            return unavailableSource(
                nightId = nightId,
                captureEnded = true,
                transcriptionComplete = false,
            )
        }
        if (!record.hasCompleteTranscriptSourceEvidence()) {
            return unavailableSource(
                nightId = nightId,
                captureEnded = true,
                transcriptionComplete = true,
            )
        }

        val ordered = readAndValidateOrderedSource(record)
            ?: return unavailableSource(
                nightId = nightId,
                captureEnded = true,
                transcriptionComplete = true,
            )
        return EnrichmentNightSource(
            nightId = nightId,
            captureEnded = true,
            transcriptionComplete = true,
            rawTranscriptReviewable = true,
            segments = ordered.segments,
        )
    }

    override fun claimAttempt(
        nightId: String,
        descriptor: EnrichmentRunDescriptor,
        startedAtEpochMillis: Long,
    ): EnrichmentAttemptClaim? {
        require(startedAtEpochMillis >= 0L) {
            "An enrichment start time cannot be negative."
        }
        if (!descriptor.isSupported()) return null

        val currentInput = loadReadyInput(nightId) ?: return null
        if (!descriptor.matches(currentInput)) return null

        val runId = canonicalRunId(runIdFactory())
        val provenance = descriptor.toProvenance()
        if (
            !gateway.startRun(
                runId = runId,
                nightId = nightId,
                provenance = provenance,
                inputSha256 = descriptor.inputFingerprintSha256,
                startedAtEpochMillis = startedAtEpochMillis,
            )
        ) {
            return null
        }

        val persisted = requireNotNull(gateway.readRun(runId)) {
            "The newly claimed enrichment attempt could not be read."
        }
        check(
            persisted.nightId == nightId &&
                persisted.runId == runId &&
                persisted.state == ProcessingState.RUNNING &&
                persisted.startedAtEpochMillis == startedAtEpochMillis &&
                persisted.completedAtEpochMillis == null &&
                persisted.inputSha256 == descriptor.inputFingerprintSha256 &&
                persisted.matches(provenance),
        ) { "The claimed enrichment attempt did not preserve its identity and provenance." }
        return EnrichmentAttemptClaim(
            nightId = nightId,
            runId = runId,
            attempt = persisted.attemptNumber,
            startedAtEpochMillis = startedAtEpochMillis,
        )
    }

    override fun completeAttempt(
        claim: EnrichmentAttemptClaim,
        descriptor: EnrichmentRunDescriptor,
        result: ValidatedEnrichment,
        completedAtEpochMillis: Long,
    ): Boolean {
        if (completedAtEpochMillis < claim.startedAtEpochMillis) return false
        if (!descriptor.isSupported()) return false
        val provenance = descriptor.toProvenance()
        val run = gateway.readRun(claim.runId) ?: return false
        if (!run.matches(claim, descriptor, provenance)) return false
        if (
            result.attempt != claim.attempt ||
            result.schemaVersion != descriptor.schemaVersion ||
            result.inputFingerprintSha256 != descriptor.inputFingerprintSha256
        ) {
            return false
        }

        // Re-read immutable raw evidence immediately before Room's completion transaction. The
        // application-wide local-operation gate prevents transcript replacement across this check.
        val currentInput = loadReadyInput(claim.nightId) ?: return false
        if (!descriptor.matches(currentInput)) return false
        val drafts = result.toDreamDrafts(currentInput) ?: return false
        return gateway.completeRun(
            runId = claim.runId,
            expectedInputSha256 = descriptor.inputFingerprintSha256,
            dreams = drafts,
            completedAtEpochMillis = completedAtEpochMillis,
        )
    }

    override fun failAttempt(
        claim: EnrichmentAttemptClaim,
        failure: PersistedEnrichmentFailure,
        completedAtEpochMillis: Long,
    ): Boolean {
        if (completedAtEpochMillis < claim.startedAtEpochMillis) return false
        val run = gateway.readRun(claim.runId) ?: return false
        if (
            run.nightId != claim.nightId ||
            run.attemptNumber != claim.attempt ||
            run.startedAtEpochMillis != claim.startedAtEpochMillis ||
            run.state != ProcessingState.RUNNING
        ) {
            return false
        }
        return gateway.markRunFailed(
            runId = claim.runId,
            failureDetail = failure.safePersistedDetail(),
            completedAtEpochMillis = completedAtEpochMillis,
        )
    }

    private fun loadReadyInput(nightId: String): OrderedNightTranscript? {
        val source = loadNightSource(nightId) ?: return null
        if (
            !source.captureEnded ||
            !source.transcriptionComplete ||
            !source.rawTranscriptReviewable
        ) {
            return null
        }
        return try {
            OrderedNightTranscript.create(nightId, source.segments)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readAndValidateOrderedSource(record: NightWithDetails): OrderedNightTranscript? {
        val nightId = record.night.nightId
        val sessionsById = record.sessions.associateBy { it.sessionId }
        if (sessionsById.size != record.sessions.size) return null
        val transcriptsBySession = record.transcripts.associateBy { it.transcript.sessionId }
        if (transcriptsBySession.size != record.transcripts.size) return null

        val expectedByKey = linkedMapOf<SourceKey, ExpectedSource>()
        record.transcripts.forEach { transcriptWithSegments ->
            val transcript = transcriptWithSegments.transcript
            val session = sessionsById[transcript.sessionId] ?: return null
            val orderedSegments = transcriptWithSegments.segments.sortedBy { it.segmentIndex }
            if (orderedSegments.map { it.segmentIndex } != orderedSegments.indices.toList()) {
                return null
            }
            if (transcript.rawText.orEmpty().isBlank() != orderedSegments.isEmpty()) return null
            orderedSegments.forEach { segment ->
                if (segment.sessionId != transcript.sessionId) return null
                val key = SourceKey(segment.sessionId, segment.segmentIndex)
                if (
                    expectedByKey.put(
                        key,
                        ExpectedSource(
                            captureOrder = session.captureOrder,
                            narrationStartedAtEpochMillis = session.startedAtEpochMillis,
                            narrationStartedUtcOffsetSeconds = session.startedUtcOffsetSeconds,
                            transcriptAttempt = transcript.attemptCount,
                            sourceStartMillis = segment.sourceStartMillis,
                            sourceEndMillis = segment.sourceEndMillis,
                            text = segment.text,
                        ),
                    ) != null
                ) {
                    return null
                }
            }
        }
        // Empty enrichment output is reserved for a truly zero-session night. A retained session
        // with no usable source segment cannot be represented by M05 source spans.
        if (expectedByKey.isEmpty()) return null

        val rows = gateway.readNightSourceSegments(nightId)
        val rowsByKey = rows.associateBy { SourceKey(it.sessionId, it.segmentIndex) }
        if (rowsByKey.size != rows.size || rowsByKey.keys != expectedByKey.keys) return null
        rows.forEach { row ->
            val expected = expectedByKey[SourceKey(row.sessionId, row.segmentIndex)] ?: return null
            if (
                row.nightId != nightId ||
                row.captureOrder != expected.captureOrder ||
                row.narrationStartedAtEpochMillis !=
                    expected.narrationStartedAtEpochMillis ||
                row.narrationStartedUtcOffsetSeconds !=
                    expected.narrationStartedUtcOffsetSeconds ||
                row.transcriptAttemptCount != expected.transcriptAttempt ||
                row.sourceStartMillis != expected.sourceStartMillis ||
                row.sourceEndMillis != expected.sourceEndMillis ||
                row.text != expected.text
            ) {
                return null
            }
        }

        val mapped = try {
            rows.map { row ->
                NightTranscriptSegment(
                    nightId = row.nightId,
                    sessionId = row.sessionId,
                    sessionOrder = row.captureOrder,
                    transcriptAttempt = row.transcriptAttemptCount,
                    segmentIndex = row.segmentIndex,
                    sourceStartMillis = row.sourceStartMillis,
                    sourceEndMillis = row.sourceEndMillis,
                    text = row.text,
                    narrationStartedAtEpochMillis = row.narrationStartedAtEpochMillis,
                    narrationStartedUtcOffsetSeconds = row.narrationStartedUtcOffsetSeconds,
                )
            }
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            OrderedNightTranscript.create(nightId, mapped)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun NightWithDetails.isGenuinelyEmptyNight(): Boolean =
        sessions.isEmpty() &&
            transcripts.isEmpty() &&
            night.reportedSessionCount == 0 &&
            night.reportedIncompleteSessionCount == 0 &&
            night.rawAudioState == RawAudioState.NONE &&
            night.transcriptionState != ProcessingState.FAILED &&
            night.transcriptionFailure == null

    private fun NightWithDetails.hasCompleteTranscriptGraph(): Boolean {
        if (
            sessions.isEmpty() ||
            night.transcriptionState != ProcessingState.COMPLETE ||
            night.transcriptionFailure != null
        ) {
            return false
        }
        val sessionIds = sessions.map { it.sessionId }
        if (sessionIds.distinct().size != sessionIds.size) return false
        val transcriptBySession = transcripts.associateBy { it.transcript.sessionId }
        if (
            transcriptBySession.size != transcripts.size ||
            transcriptBySession.keys != sessionIds.toSet()
        ) {
            return false
        }
        return transcripts.all { value ->
            val transcript = value.transcript
            transcript.nightId == night.nightId &&
                transcript.state == ProcessingState.COMPLETE &&
                transcript.failureDetail == null &&
                transcript.rawText != null &&
                transcript.attemptCount > 0 &&
                transcript.startedAtEpochMillis >= 0L &&
                transcript.completedAtEpochMillis != null &&
                transcript.completedAtEpochMillis >= transcript.startedAtEpochMillis
        }
    }

    private fun NightWithDetails.hasCompleteTranscriptSourceEvidence(): Boolean =
        night.reportedSessionCount == sessions.size &&
            sessions.isNotEmpty() &&
            sessions.all { session ->
                session.nightId == night.nightId &&
                    session.captureOrder >= 0 &&
                    session.finalizedAtEpochMillis != null
            } &&
            sessions.map { it.captureOrder }.distinct().size == sessions.size

    private fun EnrichmentRunDescriptor.isSupported(): Boolean =
        promptVersion == ENRICHMENT_PROMPT_VERSION &&
            schemaVersion == ENRICHMENT_SCHEMA_VERSION &&
            SHA_256.matches(inputFingerprintSha256)

    private fun EnrichmentRunDescriptor.matches(input: OrderedNightTranscript): Boolean =
        input.fingerprintSha256 == inputFingerprintSha256 &&
            input.isEmpty == inferenceSkippedForEmptyInput

    private fun EnrichmentRunDescriptor.toProvenance(): EnrichmentProvenance =
        EnrichmentProvenance(
            localeTag = engine.localeTag,
            engineId = engine.engineId,
            engineVersion = engine.engineVersion,
            runtimeId = engine.runtimeId,
            runtimeVersion = engine.runtimeVersion,
            backendId = engine.backendId,
            modelId = engine.modelId,
            modelVersion = engine.modelVersion,
            modelSha256 = engine.modelSha256,
            modelBytes = engine.modelBytes,
            contextWindowTokens = engine.contextWindowTokens,
            maxTotalTokens = engine.maxTotalTokens,
            promptId = PROMPT_ID,
            promptVersion = promptVersion,
            promptSha256 = enrichmentPromptSha256(promptVersion, schemaVersion),
            outputSchemaVersion = schemaVersion,
        )

    private fun EnrichmentRunEntity.matches(
        claim: EnrichmentAttemptClaim,
        descriptor: EnrichmentRunDescriptor,
        provenance: EnrichmentProvenance,
    ): Boolean =
        runId == claim.runId &&
            nightId == claim.nightId &&
            attemptNumber == claim.attempt &&
            startedAtEpochMillis == claim.startedAtEpochMillis &&
            completedAtEpochMillis == null &&
            state == ProcessingState.RUNNING &&
            inputSha256 == descriptor.inputFingerprintSha256 &&
            matches(provenance)

    private fun EnrichmentRunEntity.matches(provenance: EnrichmentProvenance): Boolean =
        localeTag == provenance.localeTag &&
            engineId == provenance.engineId &&
            engineVersion == provenance.engineVersion &&
            runtimeId == provenance.runtimeId &&
            runtimeVersion == provenance.runtimeVersion &&
            backendId == provenance.backendId &&
            modelId == provenance.modelId &&
            modelVersion == provenance.modelVersion &&
            modelSha256 == provenance.modelSha256 &&
            modelBytes == provenance.modelBytes &&
            contextWindowTokens == provenance.contextWindowTokens &&
            maxTotalTokens == provenance.maxTotalTokens &&
            promptId == provenance.promptId &&
            promptVersion == provenance.promptVersion &&
            promptSha256 == provenance.promptSha256 &&
            outputSchemaVersion == provenance.outputSchemaVersion

    private fun ValidatedEnrichment.toDreamDrafts(
        input: OrderedNightTranscript,
    ): List<DreamDraft>? {
        if (input.isEmpty) return emptyList<DreamDraft>().takeIf { dreams.isEmpty() }
        if (dreams.isEmpty()) return null
        val sourceById = input.segments.associateBy { it.id }
        val covered = mutableSetOf<SourceSegmentId>()
        val drafts = mutableListOf<DreamDraft>()
        dreams.forEach { dream ->
            val sourceIds = mutableListOf<SourceSegmentId>()
            val sourceSpans = mutableListOf<DreamSourceSpanDraft>()
            dream.sourceSpans.forEach { span ->
                val expectedIndices = (span.startSegmentIndex..span.endSegmentIndexInclusive).toList()
                if (span.segmentIds.map { it.segmentIndex } != expectedIndices) return null
                val segments = span.segmentIds.map { id -> sourceById[id] ?: return null }
                if (
                    segments.any { it.sessionId != span.sessionId } ||
                    segments.map { it.transcriptAttempt }.distinct().size != 1 ||
                    segments.first().sourceStartMillis != span.sourceStartMillis ||
                    segments.last().sourceEndMillis != span.sourceEndMillis
                ) {
                    return null
                }
                span.segmentIds.forEach { id ->
                    if (!covered.add(id)) return null
                    sourceIds += id
                }
                sourceSpans += DreamSourceSpanDraft(
                    sessionId = span.sessionId,
                    sourceTranscriptAttemptCount = segments.first().transcriptAttempt,
                    firstSegmentIndex = span.startSegmentIndex,
                    lastSegmentIndex = span.endSegmentIndexInclusive,
                    role = span.role.wireValue,
                )
            }
            drafts += DreamDraft(
                dreamId = stableDreamId(input, dream, sourceIds),
                kind = dream.kind.wireValue,
                isUncertain = dream.uncertain,
                generatedTitle = dream.generatedTitle,
                generatedText = dream.generatedText,
                sourceSpans = sourceSpans,
            )
        }
        return drafts.takeIf { covered == sourceById.keys }
    }

    private fun stableDreamId(
        input: OrderedNightTranscript,
        dream: EnrichedDreamDraft,
        sourceIds: List<SourceSegmentId>,
    ): String {
        val identity = mutableListOf(
            DREAM_ID_MANIFEST,
            input.nightId,
            input.fingerprintSha256,
            dream.order.toString(),
            sourceIds.size.toString(),
        )
        identity += sourceIds.map { it.encoded }
        return "dream_${sha256Fields(identity).take(DREAM_ID_HASH_LENGTH)}"
    }

    private fun PersistedEnrichmentFailure.safePersistedDetail(): String {
        val known = EnrichmentFailureCode.entries.firstOrNull { it.persistedValue == code }
        val safeCode = known?.persistedValue ?: code
            .lowercase(Locale.US)
            .replace(UNSAFE_FAILURE_CODE, "_")
            .trim('_')
            .take(MAX_FAILURE_CODE_LENGTH)
            .ifBlank { "unknown" }
        val safeDetail = when (known) {
            EnrichmentFailureCode.OUTPUT_INVALID ->
                EnrichmentOutputReason.entries
                    .singleOrNull { reason -> reason.safeDetail == detail }
                    ?.safeDetail
                    ?: known.safeDetail
            null -> "Local enrichment failed. The raw transcript remains reviewable."
            else -> known.safeDetail
        }
        return "$safeDetail [code=$safeCode; retryable=$retryable]"
    }

    private fun unavailableSource(
        nightId: String,
        captureEnded: Boolean,
        transcriptionComplete: Boolean = false,
    ) = EnrichmentNightSource(
        nightId = nightId,
        captureEnded = captureEnded,
        transcriptionComplete = transcriptionComplete,
        rawTranscriptReviewable = false,
        segments = emptyList(),
    )

    private fun canonicalRunId(value: String): String {
        val trimmed = value.trim()
        require(RUN_ID.matches(trimmed)) { "An enrichment run ID must be a UUID." }
        return trimmed.lowercase(Locale.US)
    }

    private data class SourceKey(
        val sessionId: String,
        val segmentIndex: Int,
    )

    private data class ExpectedSource(
        val captureOrder: Int,
        val narrationStartedAtEpochMillis: Long?,
        val narrationStartedUtcOffsetSeconds: Int?,
        val transcriptAttempt: Int,
        val sourceStartMillis: Long,
        val sourceEndMillis: Long,
        val text: String,
    )

    private companion object {
        const val PROMPT_ID = "dreamlog-whole-night-enrichment"
        const val DREAM_ID_MANIFEST = "dreamlog-enriched-dream-id-v1"
        const val DREAM_ID_HASH_LENGTH = 32
        const val MAX_FAILURE_CODE_LENGTH = 64
        val ENDED_CAPTURE_STATES = setOf(NightCaptureState.ENDED, NightCaptureState.INTERRUPTED)
        val RUN_ID = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        val SHA_256 = Regex("[0-9a-f]{64}")
        val UNSAFE_FAILURE_CODE = Regex("[^a-z0-9_-]+")
    }
}

internal interface RoomNightEnrichmentGateway {
    fun readNight(nightId: String): NightWithDetails?

    fun readNightSourceSegments(nightId: String): List<EnrichmentSourceSegment>

    fun startRun(
        runId: String,
        nightId: String,
        provenance: EnrichmentProvenance,
        inputSha256: String,
        startedAtEpochMillis: Long,
    ): Boolean

    fun readRun(runId: String): EnrichmentRunEntity?

    fun completeRun(
        runId: String,
        expectedInputSha256: String,
        dreams: List<DreamDraft>,
        completedAtEpochMillis: Long,
    ): Boolean

    fun markRunFailed(
        runId: String,
        failureDetail: String,
        completedAtEpochMillis: Long,
    ): Boolean
}

private class DaoRoomNightEnrichmentGateway(
    private val nightDao: NightDao,
    private val enrichmentDao: EnrichmentDao,
) : RoomNightEnrichmentGateway {
    override fun readNight(nightId: String): NightWithDetails? = nightDao.readNight(nightId)

    override fun readNightSourceSegments(nightId: String): List<EnrichmentSourceSegment> =
        enrichmentDao.readNightSourceSegments(nightId)

    override fun startRun(
        runId: String,
        nightId: String,
        provenance: EnrichmentProvenance,
        inputSha256: String,
        startedAtEpochMillis: Long,
    ): Boolean = enrichmentDao.startRun(
        runId = runId,
        nightId = nightId,
        provenance = provenance,
        inputSha256 = inputSha256,
        startedAtEpochMillis = startedAtEpochMillis,
    )

    override fun readRun(runId: String): EnrichmentRunEntity? = enrichmentDao.readRun(runId)

    override fun completeRun(
        runId: String,
        expectedInputSha256: String,
        dreams: List<DreamDraft>,
        completedAtEpochMillis: Long,
    ): Boolean = enrichmentDao.completeRun(
        runId = runId,
        expectedInputSha256 = expectedInputSha256,
        dreams = dreams,
        completedAtEpochMillis = completedAtEpochMillis,
    )

    override fun markRunFailed(
        runId: String,
        failureDetail: String,
        completedAtEpochMillis: Long,
    ): Boolean = enrichmentDao.markRunFailed(
        runId = runId,
        failureDetail = failureDetail,
        completedAtEpochMillis = completedAtEpochMillis,
    )
}

internal fun enrichmentPromptSha256(
    promptVersion: String,
    schemaVersion: Int,
): String = sha256Fields(
    listOf(
        "dreamlog-enrichment-prompt-manifest-v2",
        "dreamlog-whole-night-enrichment",
        promptVersion,
        schemaVersion.toString(),
        EnrichmentPromptBuilder.systemInstruction,
        EnrichmentPromptBuilder.responseContractDescription,
    ),
)

private fun sha256Fields(fields: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { value ->
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}
