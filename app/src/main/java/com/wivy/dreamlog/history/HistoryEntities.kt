package com.wivy.dreamlog.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "nights",
    indices = [
        Index(value = ["startedAtEpochMillis"]),
        Index(value = ["displayDate"]),
    ],
)
data class NightEntity(
    @androidx.room.PrimaryKey
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
)

@Entity(
    tableName = "capture_sessions",
    foreignKeys = [
        ForeignKey(
            entity = NightEntity::class,
            parentColumns = ["nightId"],
            childColumns = ["nightId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["nightId"]),
        Index(value = ["nightId", "captureOrder"]),
    ],
)
data class CaptureSessionEntity(
    @androidx.room.PrimaryKey
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
    val automaticSilenceTailSampleCount: Long? = null,
)

@Entity(
    tableName = "night_events",
    primaryKeys = ["nightId", "eventId"],
    foreignKeys = [
        ForeignKey(
            entity = NightEntity::class,
            parentColumns = ["nightId"],
            childColumns = ["nightId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["nightId"]),
        Index(value = ["nightId", "epochMillis"]),
        Index(value = ["sessionId"]),
    ],
)
data class NightEventEntity(
    val nightId: String,
    val eventId: String,
    val sessionId: String?,
    val epochMillis: Long,
    val utcOffsetSeconds: Int,
    val type: String,
    val encodedAttributes: String,
)
