package com.wivy.dreamlog.export

import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamRecord
import com.wivy.dreamlog.history.DreamSourceSpanEntity
import com.wivy.dreamlog.history.EnrichmentRunEntity
import com.wivy.dreamlog.history.NightEntity
import com.wivy.dreamlog.history.NightEventEntity
import com.wivy.dreamlog.history.NightRecord
import com.wivy.dreamlog.history.SessionTranscriptRecord
import com.wivy.dreamlog.history.TranscriptSegmentEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DreamLogExportV1(
    val schemaVersion: Int,
    val nights: List<ExportNightV1>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported DreamLog export schema version."
        }
    }

    fun normalized(): DreamLogExportV1 = copy(
        nights = nights
            .map(ExportNightV1::normalized)
            .sortedWith(
                compareBy<ExportNightV1> { it.startedAtEpochMillis }
                    .thenBy { it.nightId },
            ),
    )

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class ExportNightV1(
    val nightId: String,
    val displayDate: String,
    val startedAtEpochMillis: Long,
    val startedUtcOffsetSeconds: Int,
    val endedAtEpochMillis: Long?,
    val endedUtcOffsetSeconds: Int?,
    val captureState: String,
    val endReason: String?,
    val interrupted: Boolean,
    val lastHeartbeatEpochMillis: Long?,
    val lastHeartbeatUtcOffsetSeconds: Int?,
    val reportedSessionCount: Int,
    val reportedIncompleteSessionCount: Int,
    val hadMicrophoneSilencing: Boolean,
    val hadAudioGap: Boolean,
    val rawAudioState: String,
    val transcriptionState: String,
    val transcriptionFailure: String?,
    val enrichmentState: String,
    val enrichmentFailure: String?,
    val importWarning: String?,
    val sessions: List<ExportSessionV1>,
    val events: List<ExportNightEventV1>,
    val transcripts: List<ExportSessionTranscriptV1>,
    val enrichmentRuns: List<ExportEnrichmentRunV1>,
    val dreams: List<ExportDreamV1>,
) {
    internal fun normalized(): ExportNightV1 {
        val captureOrders = sessions.associate { it.sessionId to it.captureOrder }
        return copy(
            sessions = sessions.sortedWith(
                compareBy<ExportSessionV1> { it.captureOrder }
                    .thenBy { it.startedAtEpochMillis ?: Long.MAX_VALUE }
                    .thenBy { it.sessionId },
            ),
            events = events.sortedWith(
                compareBy<ExportNightEventV1> { it.epochMillis }
                    .thenBy { it.eventId },
            ),
            transcripts = transcripts
                .map(ExportSessionTranscriptV1::normalized)
                .sortedWith(
                    compareBy<ExportSessionTranscriptV1> {
                        captureOrders[it.sessionId] ?: Int.MAX_VALUE
                    }.thenBy { it.sessionId },
                ),
            enrichmentRuns = enrichmentRuns.sortedWith(
                compareBy<ExportEnrichmentRunV1> { it.attemptNumber }
                    .thenBy { it.runId },
            ),
            dreams = dreams
                .filter { it.deletedAtEpochMillis == null }
                .map(ExportDreamV1::normalized)
                .sortedWith(
                    compareBy<ExportDreamV1> { it.dreamOrder }
                        .thenBy { it.dreamId },
                ),
        )
    }
}

@Serializable
data class ExportSessionV1(
    val sessionId: String,
    val nightId: String,
    val captureOrder: Int,
    val startedAtEpochMillis: Long?,
    val startedUtcOffsetSeconds: Int?,
    val finalizedAtEpochMillis: Long?,
    val finalizedUtcOffsetSeconds: Int?,
    val incompleteReason: String?,
    val audioFileName: String,
    val audioState: String,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val bitsPerSample: Int?,
    val sampleCount: Long?,
    val preRollSampleCount: Long?,
    val cueStartSample: Long?,
    val cueEndSampleExclusive: Long?,
    val automaticSilenceTailSampleCount: Long?,
)

@Serializable
data class ExportNightEventV1(
    val nightId: String,
    val eventId: String,
    val sessionId: String?,
    val epochMillis: Long,
    val utcOffsetSeconds: Int,
    val type: String,
    val encodedAttributes: String,
)

@Serializable
data class ExportSessionTranscriptV1(
    val sessionId: String,
    val nightId: String,
    val state: String,
    val failureDetail: String?,
    val rawText: String?,
    val localeTag: String,
    val engineId: String,
    val engineVersion: String,
    val runtimeId: String,
    val runtimeVersion: String,
    val modelId: String,
    val modelVersion: String,
    val modelSha256: String,
    val attemptCount: Int,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val segments: List<ExportTranscriptSegmentV1>,
) {
    internal fun normalized(): ExportSessionTranscriptV1 = copy(
        segments = segments.sortedWith(
            compareBy<ExportTranscriptSegmentV1> { it.segmentIndex }
                .thenBy { it.sourceStartMillis }
                .thenBy { it.sourceEndMillis },
        ),
    )
}

@Serializable
data class ExportTranscriptSegmentV1(
    val sessionId: String,
    val segmentIndex: Int,
    val sourceStartMillis: Long,
    val sourceEndMillis: Long,
    val text: String,
)

@Serializable
data class ExportEnrichmentRunV1(
    val runId: String,
    val nightId: String,
    val attemptNumber: Int,
    val state: String,
    val failureDetail: String?,
    val localeTag: String,
    val engineId: String,
    val engineVersion: String,
    val runtimeId: String,
    val runtimeVersion: String,
    val backendId: String,
    val modelId: String,
    val modelVersion: String,
    val modelSha256: String,
    val modelBytes: Long,
    val contextWindowTokens: Int,
    val maxTotalTokens: Int,
    val promptId: String,
    val promptVersion: String,
    val promptSha256: String,
    val outputSchemaVersion: Int,
    val inputSha256: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
)

@Serializable
data class ExportDreamV1(
    val dreamId: String,
    val nightId: String,
    val runId: String,
    val dreamOrder: Int,
    val kind: String,
    val isUncertain: Boolean,
    val generatedTitle: String?,
    val generatedText: String,
    val currentTitle: String?,
    val currentText: String,
    val ownerEdited: Boolean,
    val editedAtEpochMillis: Long?,
    val deletedAtEpochMillis: Long?,
    val sourceSpans: List<ExportDreamSourceSpanV1>,
) {
    internal fun normalized(): ExportDreamV1 = copy(
        sourceSpans = sourceSpans.sortedWith(
            compareBy<ExportDreamSourceSpanV1> { it.spanOrder }
                .thenBy { it.sessionId }
                .thenBy { it.firstSegmentIndex },
        ),
    )
}

@Serializable
data class ExportDreamSourceSpanV1(
    val dreamId: String,
    val spanOrder: Int,
    val sessionId: String,
    val sourceTranscriptAttemptCount: Int,
    val firstSegmentIndex: Int,
    val lastSegmentIndex: Int,
    val sourceStartMillis: Long,
    val sourceEndMillis: Long,
    val sourceText: String,
    val role: String,
)

object DreamLogJsonV1 {
    private val codec = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(export: DreamLogExportV1): String =
        codec.encodeToString(export.normalized())

    fun decode(encoded: String): DreamLogExportV1 =
        codec.decodeFromString<DreamLogExportV1>(encoded).normalized()
}

fun createDreamLogExportV1(
    availableNights: List<NightRecord>,
    selection: DreamLogExportSelection,
): DreamLogExportV1 = DreamLogExportV1(
    schemaVersion = DreamLogExportV1.SCHEMA_VERSION,
    nights = selectNightsForExport(availableNights, selection).map(NightRecord::toExportV1),
).normalized()

private fun NightRecord.toExportV1(): ExportNightV1 = night.toExportV1(
    sessions = sessions.map(CaptureSessionEntity::toExportV1),
    events = events.map(NightEventEntity::toExportV1),
    transcripts = transcripts.map(SessionTranscriptRecord::toExportV1),
    enrichmentRuns = enrichmentRuns.map(EnrichmentRunEntity::toExportV1),
    dreams = dreams.map(DreamRecord::toExportV1),
)

private fun NightEntity.toExportV1(
    sessions: List<ExportSessionV1>,
    events: List<ExportNightEventV1>,
    transcripts: List<ExportSessionTranscriptV1>,
    enrichmentRuns: List<ExportEnrichmentRunV1>,
    dreams: List<ExportDreamV1>,
) = ExportNightV1(
    nightId = nightId,
    displayDate = displayDate,
    startedAtEpochMillis = startedAtEpochMillis,
    startedUtcOffsetSeconds = startedUtcOffsetSeconds,
    endedAtEpochMillis = endedAtEpochMillis,
    endedUtcOffsetSeconds = endedUtcOffsetSeconds,
    captureState = captureState,
    endReason = endReason,
    interrupted = interrupted,
    lastHeartbeatEpochMillis = lastHeartbeatEpochMillis,
    lastHeartbeatUtcOffsetSeconds = lastHeartbeatUtcOffsetSeconds,
    reportedSessionCount = reportedSessionCount,
    reportedIncompleteSessionCount = reportedIncompleteSessionCount,
    hadMicrophoneSilencing = hadMicrophoneSilencing,
    hadAudioGap = hadAudioGap,
    rawAudioState = rawAudioState,
    transcriptionState = transcriptionState,
    transcriptionFailure = transcriptionFailure,
    enrichmentState = enrichmentState,
    enrichmentFailure = enrichmentFailure,
    importWarning = importWarning,
    sessions = sessions,
    events = events,
    transcripts = transcripts,
    enrichmentRuns = enrichmentRuns,
    dreams = dreams,
)

private fun CaptureSessionEntity.toExportV1() = ExportSessionV1(
    sessionId = sessionId,
    nightId = nightId,
    captureOrder = captureOrder,
    startedAtEpochMillis = startedAtEpochMillis,
    startedUtcOffsetSeconds = startedUtcOffsetSeconds,
    finalizedAtEpochMillis = finalizedAtEpochMillis,
    finalizedUtcOffsetSeconds = finalizedUtcOffsetSeconds,
    incompleteReason = incompleteReason,
    audioFileName = audioFileName,
    audioState = audioState,
    sampleRateHz = sampleRateHz,
    channelCount = channelCount,
    bitsPerSample = bitsPerSample,
    sampleCount = sampleCount,
    preRollSampleCount = preRollSampleCount,
    cueStartSample = cueStartSample,
    cueEndSampleExclusive = cueEndSampleExclusive,
    automaticSilenceTailSampleCount = automaticSilenceTailSampleCount,
)

private fun NightEventEntity.toExportV1() = ExportNightEventV1(
    nightId = nightId,
    eventId = eventId,
    sessionId = sessionId,
    epochMillis = epochMillis,
    utcOffsetSeconds = utcOffsetSeconds,
    type = type,
    encodedAttributes = encodedAttributes,
)

private fun SessionTranscriptRecord.toExportV1() = ExportSessionTranscriptV1(
    sessionId = transcript.sessionId,
    nightId = transcript.nightId,
    state = transcript.state,
    failureDetail = transcript.failureDetail,
    rawText = transcript.rawText,
    localeTag = transcript.localeTag,
    engineId = transcript.engineId,
    engineVersion = transcript.engineVersion,
    runtimeId = transcript.runtimeId,
    runtimeVersion = transcript.runtimeVersion,
    modelId = transcript.modelId,
    modelVersion = transcript.modelVersion,
    modelSha256 = transcript.modelSha256,
    attemptCount = transcript.attemptCount,
    startedAtEpochMillis = transcript.startedAtEpochMillis,
    completedAtEpochMillis = transcript.completedAtEpochMillis,
    segments = segments.map(TranscriptSegmentEntity::toExportV1),
)

private fun TranscriptSegmentEntity.toExportV1() = ExportTranscriptSegmentV1(
    sessionId = sessionId,
    segmentIndex = segmentIndex,
    sourceStartMillis = sourceStartMillis,
    sourceEndMillis = sourceEndMillis,
    text = text,
)

private fun EnrichmentRunEntity.toExportV1() = ExportEnrichmentRunV1(
    runId = runId,
    nightId = nightId,
    attemptNumber = attemptNumber,
    state = state,
    failureDetail = failureDetail,
    localeTag = localeTag,
    engineId = engineId,
    engineVersion = engineVersion,
    runtimeId = runtimeId,
    runtimeVersion = runtimeVersion,
    backendId = backendId,
    modelId = modelId,
    modelVersion = modelVersion,
    modelSha256 = modelSha256,
    modelBytes = modelBytes,
    contextWindowTokens = contextWindowTokens,
    maxTotalTokens = maxTotalTokens,
    promptId = promptId,
    promptVersion = promptVersion,
    promptSha256 = promptSha256,
    outputSchemaVersion = outputSchemaVersion,
    inputSha256 = inputSha256,
    startedAtEpochMillis = startedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
)

private fun DreamRecord.toExportV1() = ExportDreamV1(
    dreamId = dream.dreamId,
    nightId = dream.nightId,
    runId = dream.runId,
    dreamOrder = dream.dreamOrder,
    kind = dream.kind,
    isUncertain = dream.isUncertain,
    generatedTitle = dream.generatedTitle,
    generatedText = dream.generatedText,
    currentTitle = dream.currentTitle,
    currentText = dream.currentText,
    ownerEdited = dream.ownerEdited,
    editedAtEpochMillis = dream.editedAtEpochMillis,
    deletedAtEpochMillis = dream.deletedAtEpochMillis,
    sourceSpans = sourceSpans.map(DreamSourceSpanEntity::toExportV1),
)

private fun DreamSourceSpanEntity.toExportV1() = ExportDreamSourceSpanV1(
    dreamId = dreamId,
    spanOrder = spanOrder,
    sessionId = sessionId,
    sourceTranscriptAttemptCount = sourceTranscriptAttemptCount,
    firstSegmentIndex = firstSegmentIndex,
    lastSegmentIndex = lastSegmentIndex,
    sourceStartMillis = sourceStartMillis,
    sourceEndMillis = sourceEndMillis,
    sourceText = sourceText,
    role = role,
)
