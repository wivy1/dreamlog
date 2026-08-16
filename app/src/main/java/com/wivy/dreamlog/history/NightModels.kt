package com.wivy.dreamlog.history

object NightCaptureState {
    const val STARTING = "starting"
    const val ACTIVE = "active"
    const val ENDED = "ended"
    const val INTERRUPTED = "interrupted"
    const val RECOVERY_REQUIRED = "recovery_required"
}

object AudioEvidenceState {
    const val RETAINED = "retained"
    const val MISSING = "missing"
    const val CORRUPT = "corrupt"
    const val PENDING_RECOVERY = "pending_recovery"
    const val DELETED = "deleted"
    const val EXPIRED = "expired"
}

object RawAudioState {
    const val NONE = "none"
    const val RETAINED = "retained"
    const val PARTIAL = "partial"
    const val UNAVAILABLE = "unavailable"
    const val PENDING_RECOVERY = "pending_recovery"
}

object ProcessingState {
    const val NOT_STARTED = "not_started"
    const val WAITING_FOR_TRANSCRIPTION = "waiting_for_transcription"
    const val RUNNING = "running"
    const val COMPLETE = "complete"
    const val FAILED = "failed"
    const val SUPERSEDED = "superseded"
}

data class NightRecord(
    val night: NightEntity,
    val sessions: List<CaptureSessionEntity>,
    val events: List<NightEventEntity>,
    val transcripts: List<SessionTranscriptRecord> = emptyList(),
    val enrichmentRuns: List<EnrichmentRunEntity> = emptyList(),
    val dreams: List<DreamRecord> = emptyList(),
    val hasProtectedDreamChanges: Boolean = false,
) {
    val retainedSessionCount: Int
        get() = sessions.count { it.audioState == AudioEvidenceState.RETAINED }

    val unavailableSessionCount: Int
        get() = sessions.count {
            it.audioState == AudioEvidenceState.MISSING ||
                it.audioState == AudioEvidenceState.CORRUPT ||
                it.audioState == AudioEvidenceState.DELETED ||
                it.audioState == AudioEvidenceState.EXPIRED
        }
}

data class SessionTranscriptRecord(
    val transcript: SessionTranscriptEntity,
    val segments: List<TranscriptSegmentEntity>,
)

data class DreamRecord(
    val dream: DreamEntity,
    val sourceSpans: List<DreamSourceSpanEntity>,
)

data class HistoryLoadResult(
    val nights: List<NightRecord>,
    val importedNightCount: Int = 0,
    val acknowledgedNightCount: Int = 0,
    val warningCount: Int = 0,
)
