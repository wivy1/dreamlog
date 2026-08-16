package com.wivy.dreamlog.export

import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamEntity
import com.wivy.dreamlog.history.DreamKind
import com.wivy.dreamlog.history.DreamRecord
import com.wivy.dreamlog.history.DreamSourceRole
import com.wivy.dreamlog.history.DreamSourceSpanEntity
import com.wivy.dreamlog.history.EnrichmentRunEntity
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightEntity
import com.wivy.dreamlog.history.NightEventEntity
import com.wivy.dreamlog.history.NightRecord
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioState
import com.wivy.dreamlog.history.SessionTranscriptEntity
import com.wivy.dreamlog.history.SessionTranscriptRecord
import com.wivy.dreamlog.history.TranscriptSegmentEntity
import java.time.Instant

internal object ExportTestFixtures {
    const val OLDER_NIGHT_ID = "night-older"
    const val NEWER_NIGHT_ID = "night-newer"
    const val EDITED_DREAM_ID = "dream-edited"
    const val FRAGMENT_DREAM_ID = "dream-fragment"
    const val DELETED_DREAM_ID = "dream-deleted"
    const val EDITED_TITLE = "Owner, \"title\""
    const val EDITED_TEXT = "Line one, café\n\"Line two\""
    const val RAW_TEXT = "Raw words, with \"quotes\"\nand another line."
    const val DELETED_TEXT = "This logically deleted dream must never be exported."

    fun historyNewestFirst(): List<NightRecord> = listOf(newerNight(), olderNight())

    private fun olderNight(): NightRecord {
        val startedAt = epoch("2026-11-01T06:15:00Z")
        val endedAt = epoch("2026-11-01T07:45:00Z")
        val firstSession = session(
            sessionId = "session-old-1",
            nightId = OLDER_NIGHT_ID,
            captureOrder = 0,
            startedAtEpochMillis = epoch("2026-11-01T06:30:00Z"),
            utcOffsetSeconds = -5 * 60 * 60,
        )
        val secondSession = session(
            sessionId = "session-old-2",
            nightId = OLDER_NIGHT_ID,
            captureOrder = 1,
            startedAtEpochMillis = epoch("2026-11-01T07:30:00Z"),
            utcOffsetSeconds = -6 * 60 * 60,
        )
        return NightRecord(
            night = night(
                nightId = OLDER_NIGHT_ID,
                displayDate = "2026-10-31",
                startedAtEpochMillis = startedAt,
                startedUtcOffsetSeconds = -5 * 60 * 60,
                endedAtEpochMillis = endedAt,
                endedUtcOffsetSeconds = -6 * 60 * 60,
                reportedSessionCount = 2,
            ),
            sessions = listOf(secondSession, firstSession),
            events = listOf(
                NightEventEntity(
                    nightId = OLDER_NIGHT_ID,
                    eventId = "event-2",
                    sessionId = null,
                    epochMillis = startedAt + 2_000L,
                    utcOffsetSeconds = -5 * 60 * 60,
                    type = "heartbeat",
                    encodedAttributes = "charging=dHJ1ZQ",
                ),
                NightEventEntity(
                    nightId = OLDER_NIGHT_ID,
                    eventId = "event-1",
                    sessionId = null,
                    epochMillis = startedAt + 1_000L,
                    utcOffsetSeconds = -5 * 60 * 60,
                    type = "capture_started",
                    encodedAttributes = "",
                ),
            ),
            transcripts = listOf(
                transcript(
                    session = secondSession,
                    rawText = "A second raw transcript.",
                    segmentText = "second source",
                ),
                transcript(
                    session = firstSession,
                    rawText = RAW_TEXT,
                    segmentText = "first source",
                ),
            ),
            enrichmentRuns = listOf(
                enrichmentRun(OLDER_NIGHT_ID, "run-old-2", attemptNumber = 2),
                enrichmentRun(OLDER_NIGHT_ID, "run-old-1", attemptNumber = 1),
            ),
            dreams = listOf(
                deletedDream(OLDER_NIGHT_ID),
                editedDream(OLDER_NIGHT_ID),
            ),
            hasProtectedDreamChanges = true,
        )
    }

    private fun newerNight(): NightRecord {
        val startedAt = epoch("2026-11-03T04:00:00Z")
        val endedAt = epoch("2026-11-03T04:20:00Z")
        val session = session(
            sessionId = "session-new-1",
            nightId = NEWER_NIGHT_ID,
            captureOrder = 0,
            startedAtEpochMillis = startedAt + 60_000L,
            utcOffsetSeconds = -6 * 60 * 60,
        )
        return NightRecord(
            night = night(
                nightId = NEWER_NIGHT_ID,
                displayDate = "2026-11-02",
                startedAtEpochMillis = startedAt,
                startedUtcOffsetSeconds = -6 * 60 * 60,
                endedAtEpochMillis = endedAt,
                endedUtcOffsetSeconds = -6 * 60 * 60,
                reportedSessionCount = 1,
            ),
            sessions = listOf(session),
            events = emptyList(),
            transcripts = emptyList(),
            enrichmentRuns = listOf(
                enrichmentRun(NEWER_NIGHT_ID, "run-new-1", attemptNumber = 1),
            ),
            dreams = listOf(
                DreamRecord(
                    dream = DreamEntity(
                        dreamId = FRAGMENT_DREAM_ID,
                        nightId = NEWER_NIGHT_ID,
                        runId = "run-new-1",
                        dreamOrder = 0,
                        kind = DreamKind.FRAGMENT,
                        isUncertain = true,
                        generatedTitle = null,
                        generatedText = "A fragment in a tower.",
                        currentTitle = null,
                        currentText = "A fragment in a tower.",
                        ownerEdited = false,
                        editedAtEpochMillis = null,
                        deletedAtEpochMillis = null,
                    ),
                    sourceSpans = listOf(
                        sourceSpan(
                            dreamId = FRAGMENT_DREAM_ID,
                            spanOrder = 0,
                            sessionId = session.sessionId,
                            sourceText = "fragment source",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun night(
        nightId: String,
        displayDate: String,
        startedAtEpochMillis: Long,
        startedUtcOffsetSeconds: Int,
        endedAtEpochMillis: Long,
        endedUtcOffsetSeconds: Int,
        reportedSessionCount: Int,
    ) = NightEntity(
        nightId = nightId,
        displayDate = displayDate,
        startedAtEpochMillis = startedAtEpochMillis,
        startedUtcOffsetSeconds = startedUtcOffsetSeconds,
        endedAtEpochMillis = endedAtEpochMillis,
        endedUtcOffsetSeconds = endedUtcOffsetSeconds,
        captureState = NightCaptureState.ENDED,
        endReason = "owner_ended",
        interrupted = false,
        lastHeartbeatEpochMillis = endedAtEpochMillis - 1_000L,
        lastHeartbeatUtcOffsetSeconds = endedUtcOffsetSeconds,
        reportedSessionCount = reportedSessionCount,
        reportedIncompleteSessionCount = 0,
        hadMicrophoneSilencing = false,
        hadAudioGap = false,
        rawAudioState = RawAudioState.RETAINED,
        transcriptionState = ProcessingState.COMPLETE,
        transcriptionFailure = null,
        enrichmentState = ProcessingState.COMPLETE,
        enrichmentFailure = null,
        importWarning = null,
    )

    private fun session(
        sessionId: String,
        nightId: String,
        captureOrder: Int,
        startedAtEpochMillis: Long,
        utcOffsetSeconds: Int,
    ) = CaptureSessionEntity(
        sessionId = sessionId,
        nightId = nightId,
        captureOrder = captureOrder,
        startedAtEpochMillis = startedAtEpochMillis,
        startedUtcOffsetSeconds = utcOffsetSeconds,
        finalizedAtEpochMillis = startedAtEpochMillis + 120_000L,
        finalizedUtcOffsetSeconds = utcOffsetSeconds,
        incompleteReason = null,
        audioFileName = "a_${sessionId.replace("-", "")}.wav",
        audioState = AudioEvidenceState.RETAINED,
        sampleRateHz = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
        sampleCount = 1_920_000L,
        preRollSampleCount = 32_000L,
        cueStartSample = 32_000L,
        cueEndSampleExclusive = 40_000L,
        automaticSilenceTailSampleCount = 160_000L,
    )

    private fun transcript(
        session: CaptureSessionEntity,
        rawText: String,
        segmentText: String,
    ) = SessionTranscriptRecord(
        transcript = SessionTranscriptEntity(
            sessionId = session.sessionId,
            nightId = session.nightId,
            state = ProcessingState.COMPLETE,
            failureDetail = null,
            rawText = rawText,
            localeTag = "en-US",
            engineId = "sherpa-onnx",
            engineVersion = "1",
            runtimeId = "android",
            runtimeVersion = "37",
            modelId = "parakeet",
            modelVersion = "v1",
            modelSha256 = "a".repeat(64),
            attemptCount = 1,
            startedAtEpochMillis = requireNotNull(session.finalizedAtEpochMillis) + 1_000L,
            completedAtEpochMillis = requireNotNull(session.finalizedAtEpochMillis) + 2_000L,
        ),
        segments = listOf(
            TranscriptSegmentEntity(
                sessionId = session.sessionId,
                segmentIndex = 1,
                sourceStartMillis = 2_000L,
                sourceEndMillis = 3_000L,
                text = "$segmentText two",
            ),
            TranscriptSegmentEntity(
                sessionId = session.sessionId,
                segmentIndex = 0,
                sourceStartMillis = 1_000L,
                sourceEndMillis = 2_000L,
                text = "$segmentText one",
            ),
        ),
    )

    private fun enrichmentRun(
        nightId: String,
        runId: String,
        attemptNumber: Int,
    ) = EnrichmentRunEntity(
        runId = runId,
        nightId = nightId,
        attemptNumber = attemptNumber,
        state = ProcessingState.COMPLETE,
        failureDetail = null,
        localeTag = "en-US",
        engineId = "litert-lm",
        engineVersion = "8",
        runtimeId = "litert",
        runtimeVersion = "0.14",
        backendId = "gpu",
        modelId = "qwen",
        modelVersion = "v1",
        modelSha256 = "b".repeat(64),
        modelBytes = 2_663_000_000L,
        contextWindowTokens = 4_096,
        maxTotalTokens = 2_048,
        promptId = "dream-grouping",
        promptVersion = "14",
        promptSha256 = "c".repeat(64),
        outputSchemaVersion = 5,
        inputSha256 = "d".repeat(64),
        startedAtEpochMillis = 10_000L + attemptNumber,
        completedAtEpochMillis = 20_000L + attemptNumber,
    )

    private fun editedDream(nightId: String) = DreamRecord(
        dream = DreamEntity(
            dreamId = EDITED_DREAM_ID,
            nightId = nightId,
            runId = "run-old-2",
            dreamOrder = 0,
            kind = DreamKind.DREAM,
            isUncertain = false,
            generatedTitle = "Generated title",
            generatedText = "Generated text.",
            currentTitle = EDITED_TITLE,
            currentText = EDITED_TEXT,
            ownerEdited = true,
            editedAtEpochMillis = epoch("2026-11-01T08:00:00Z"),
            deletedAtEpochMillis = null,
        ),
        sourceSpans = listOf(
            sourceSpan(
                dreamId = EDITED_DREAM_ID,
                spanOrder = 1,
                sessionId = "session-old-2",
                sourceText = "second source",
            ),
            sourceSpan(
                dreamId = EDITED_DREAM_ID,
                spanOrder = 0,
                sessionId = "session-old-1",
                sourceText = "first source",
            ),
        ),
    )

    private fun deletedDream(nightId: String) = DreamRecord(
        dream = DreamEntity(
            dreamId = DELETED_DREAM_ID,
            nightId = nightId,
            runId = "run-old-1",
            dreamOrder = 1,
            kind = DreamKind.DREAM,
            isUncertain = false,
            generatedTitle = "Deleted title",
            generatedText = DELETED_TEXT,
            currentTitle = "Deleted title",
            currentText = DELETED_TEXT,
            ownerEdited = false,
            editedAtEpochMillis = null,
            deletedAtEpochMillis = epoch("2026-11-01T09:00:00Z"),
        ),
        sourceSpans = emptyList(),
    )

    private fun sourceSpan(
        dreamId: String,
        spanOrder: Int,
        sessionId: String,
        sourceText: String,
    ) = DreamSourceSpanEntity(
        dreamId = dreamId,
        spanOrder = spanOrder,
        sessionId = sessionId,
        sourceTranscriptAttemptCount = 1,
        firstSegmentIndex = spanOrder,
        lastSegmentIndex = spanOrder,
        sourceStartMillis = 1_000L + spanOrder * 1_000L,
        sourceEndMillis = 2_000L + spanOrder * 1_000L,
        sourceText = sourceText,
        role = if (spanOrder == 0) DreamSourceRole.NARRATIVE else DreamSourceRole.ADDITION,
    )

    private fun epoch(value: String): Long = Instant.parse(value).toEpochMilli()
}
