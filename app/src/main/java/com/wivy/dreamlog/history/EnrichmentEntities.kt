package com.wivy.dreamlog.history

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "enrichment_runs",
    foreignKeys = [
        ForeignKey(
            entity = NightEntity::class,
            parentColumns = ["nightId"],
            childColumns = ["nightId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["nightId", "attemptNumber"], unique = true),
        Index(value = ["state", "startedAtEpochMillis"]),
    ],
)
data class EnrichmentRunEntity(
    @PrimaryKey
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

@Entity(
    tableName = "dreams",
    foreignKeys = [
        ForeignKey(
            entity = NightEntity::class,
            parentColumns = ["nightId"],
            childColumns = ["nightId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EnrichmentRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["nightId", "dreamOrder"], unique = true),
        Index(value = ["runId"]),
    ],
)
data class DreamEntity(
    @PrimaryKey
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
    val deletedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "dream_source_spans",
    primaryKeys = ["dreamId", "spanOrder"],
    foreignKeys = [
        ForeignKey(
            entity = DreamEntity::class,
            parentColumns = ["dreamId"],
            childColumns = ["dreamId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class DreamSourceSpanEntity(
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

data class DreamWithSourceSpans(
    @Embedded
    val dream: DreamEntity,
    @Relation(
        parentColumn = "dreamId",
        entityColumn = "dreamId",
    )
    val sourceSpans: List<DreamSourceSpanEntity>,
)

data class EnrichmentProvenance(
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
)

data class DreamDraft(
    val dreamId: String,
    val kind: String,
    val isUncertain: Boolean,
    val generatedTitle: String?,
    val generatedText: String,
    val sourceSpans: List<DreamSourceSpanDraft>,
)

data class DreamSourceSpanDraft(
    val sessionId: String,
    val sourceTranscriptAttemptCount: Int,
    val firstSegmentIndex: Int,
    val lastSegmentIndex: Int,
    val role: String,
)

data class EnrichmentSourceSegment(
    val nightId: String,
    val sessionId: String,
    val captureOrder: Int,
    val transcriptAttemptCount: Int,
    val segmentIndex: Int,
    val sourceStartMillis: Long,
    val sourceEndMillis: Long,
    val text: String,
    val narrationStartedAtEpochMillis: Long? = null,
    val narrationStartedUtcOffsetSeconds: Int? = null,
)

data class NightEnrichmentTarget(
    val nightId: String,
    val captureState: String,
    val endedAtEpochMillis: Long?,
    val transcriptionState: String,
    val sessionCount: Int,
    val transcriptCount: Int,
    val completeTranscriptCount: Int,
)

object DreamKind {
    const val DREAM = "dream"
    const val FRAGMENT = "fragment"

    val values: Set<String> = setOf(DREAM, FRAGMENT)
}

object DreamSourceRole {
    const val NARRATIVE = "narrative"
    const val ADDITION = "addition"
    const val CORRECTION = "correction"

    val values: Set<String> = setOf(NARRATIVE, ADDITION, CORRECTION)
}
