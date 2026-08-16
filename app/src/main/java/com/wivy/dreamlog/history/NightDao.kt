package com.wivy.dreamlog.history

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert

data class NightWithDetails(
    @Embedded
    val night: NightEntity,
    @Relation(
        parentColumn = "nightId",
        entityColumn = "nightId",
    )
    val sessions: List<CaptureSessionEntity>,
    @Relation(
        parentColumn = "nightId",
        entityColumn = "nightId",
    )
    val events: List<NightEventEntity>,
    @Relation(
        entity = SessionTranscriptEntity::class,
        parentColumn = "nightId",
        entityColumn = "nightId",
    )
    val transcripts: List<SessionTranscriptWithSegments> = emptyList(),
    @Relation(
        parentColumn = "nightId",
        entityColumn = "nightId",
    )
    val enrichmentRuns: List<EnrichmentRunEntity> = emptyList(),
    @Relation(
        entity = DreamEntity::class,
        parentColumn = "nightId",
        entityColumn = "nightId",
    )
    val dreams: List<DreamWithSourceSpans> = emptyList(),
)

@Dao
abstract class NightDao {
    @Upsert
    protected abstract fun upsertNight(night: NightEntity)

    @Upsert
    protected abstract fun upsertSessions(sessions: List<CaptureSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertEvents(events: List<NightEventEntity>)

    @Transaction
    open fun upsertCaptureGraph(
        night: NightEntity,
        sessions: List<CaptureSessionEntity>,
        events: List<NightEventEntity>,
    ) {
        upsertNight(night)
        if (sessions.isNotEmpty()) upsertSessions(sessions)
        if (events.isNotEmpty()) insertEvents(events)
    }

    @Transaction
    @Query(
        """
        SELECT * FROM nights
        ORDER BY startedAtEpochMillis DESC, nightId DESC
        """,
    )
    abstract fun readHistory(): List<NightWithDetails>

    @Transaction
    @Query("SELECT * FROM nights WHERE nightId = :nightId LIMIT 1")
    abstract fun readNight(nightId: String): NightWithDetails?

    @Query(
        """
        SELECT * FROM nights
        WHERE captureState IN ('starting', 'active')
        ORDER BY startedAtEpochMillis DESC, nightId DESC
        """,
    )
    abstract fun readUnfinishedNights(): List<NightEntity>

    @Query("UPDATE capture_sessions SET audioState = :audioState WHERE nightId = :nightId")
    protected abstract fun updateNightSessionAudioState(
        nightId: String,
        audioState: String,
    ): Int

    @Query("UPDATE nights SET rawAudioState = :rawAudioState WHERE nightId = :nightId")
    protected abstract fun updateNightRawAudioState(
        nightId: String,
        rawAudioState: String,
    ): Int

    @Transaction
    open fun markNightRawAudioDeleted(nightId: String): Boolean {
        val existing = readNight(nightId) ?: return false
        check(
            existing.night.captureState == NightCaptureState.ENDED ||
                existing.night.captureState == NightCaptureState.INTERRUPTED,
        ) { "Raw audio can be deleted only after the night has ended." }
        if (existing.sessions.isNotEmpty()) {
            updateNightSessionAudioState(nightId, AudioEvidenceState.DELETED)
        }
        check(
            updateNightRawAudioState(
                nightId = nightId,
                rawAudioState = if (existing.sessions.isEmpty()) {
                    RawAudioState.NONE
                } else {
                    RawAudioState.UNAVAILABLE
                },
            ) == 1,
        ) { "The night raw-audio state could not be updated." }
        return true
    }

    @Query("DELETE FROM nights WHERE nightId = :nightId")
    abstract fun deleteNight(nightId: String): Int
}
