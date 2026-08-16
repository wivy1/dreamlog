package com.wivy.dreamlog.history

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "session_transcripts",
    foreignKeys = [
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = NightEntity::class,
            parentColumns = ["nightId"],
            childColumns = ["nightId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["nightId"]),
        Index(value = ["state", "startedAtEpochMillis"]),
    ],
)
data class SessionTranscriptEntity(
    @PrimaryKey
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
)

@Entity(
    tableName = "transcript_segments",
    primaryKeys = ["sessionId", "segmentIndex"],
    foreignKeys = [
        ForeignKey(
            entity = SessionTranscriptEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TranscriptSegmentEntity(
    val sessionId: String,
    val segmentIndex: Int,
    val sourceStartMillis: Long,
    val sourceEndMillis: Long,
    val text: String,
)

data class SessionTranscriptWithSegments(
    @Embedded
    val transcript: SessionTranscriptEntity,
    @Relation(
        parentColumn = "sessionId",
        entityColumn = "sessionId",
    )
    val segments: List<TranscriptSegmentEntity>,
)

data class TranscriptionProvenance(
    val localeTag: String,
    val engineId: String,
    val engineVersion: String,
    val runtimeId: String,
    val runtimeVersion: String,
    val modelId: String,
    val modelVersion: String,
    val modelSha256: String,
)

data class TranscriptSegmentDraft(
    val sourceStartMillis: Long,
    val sourceEndMillis: Long,
    val text: String,
)

/** One fully inferred replacement held in memory until an entire night can commit atomically. */
data class SessionTranscriptReplacementDraft(
    val sessionId: String,
    val rawText: String,
    val segments: List<TranscriptSegmentDraft>,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
)
