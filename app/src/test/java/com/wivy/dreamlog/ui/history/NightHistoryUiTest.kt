package com.wivy.dreamlog.ui.history

import com.wivy.dreamlog.capture.SessionIncompleteReason
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamEntity
import com.wivy.dreamlog.history.DreamKind
import com.wivy.dreamlog.history.DreamRecord
import com.wivy.dreamlog.history.DreamSourceRole
import com.wivy.dreamlog.history.DreamSourceSpanEntity
import com.wivy.dreamlog.history.HistoryFormatters
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightEntity
import com.wivy.dreamlog.history.NightEventEntity
import com.wivy.dreamlog.history.NightRecord
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioState
import com.wivy.dreamlog.history.SessionTranscriptEntity
import com.wivy.dreamlog.history.SessionTranscriptRecord
import com.wivy.dreamlog.playback.DreamSourcePlaybackPhase
import com.wivy.dreamlog.playback.DreamSourcePlaybackState
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightHistoryUiTest {
    @Test
    fun nonretryableEnrichmentFailureHidesMarkerAndRetryInstruction() {
        val text = enrichmentProcessingText(
            ProcessingState.FAILED,
            "The whole-night transcript is too large. " +
                "[code=input_too_large; retryable=false]",
        )

        assertTrue(text.contains("whole-night transcript"))
        assertFalse(text.contains("[code="))
        assertFalse(text.contains("retry from this night"))
    }

    @Test
    fun retryableEnrichmentFailureShowsCleanRetryInstruction() {
        val text = enrichmentProcessingText(
            ProcessingState.FAILED,
            "Local enrichment was interrupted. [code=interrupted; retryable=true]",
        )

        assertTrue(text.contains("Local enrichment was interrupted"))
        assertTrue(text.contains("raw transcript was preserved"))
        assertTrue(text.contains("retry from this night"))
        assertFalse(text.contains("[code="))
    }

    @Test
    fun morningDiagnosticsReportBreakingSilencingAndHeartbeatButNotSuccessfulRecovery() {
        val base = nightRecord()
        val silencedAt = base.night.startedAtEpochMillis + 60_000L
        val recoveredAt = silencedAt + 120_000L
        val diagnostics = morningDiagnostics(
            base.copy(
                night = base.night.copy(
                    captureState = NightCaptureState.INTERRUPTED,
                    interrupted = true,
                    lastHeartbeatEpochMillis = recoveredAt,
                    lastHeartbeatUtcOffsetSeconds = -5 * 60 * 60,
                    hadMicrophoneSilencing = true,
                ),
                events = listOf(
                    event(
                        "silenced",
                        silencedAt,
                        "microphone_state",
                        mapOf("effective_silenced" to "true"),
                    ),
                    event(
                        "heartbeat-while-silenced",
                        silencedAt + 60_000L,
                        "heartbeat",
                        mapOf("microphone_silenced" to "false"),
                    ),
                    event(
                        "restored",
                        recoveredAt,
                        "microphone_state",
                        mapOf("effective_silenced" to "false"),
                    ),
                    event(
                        "recovery",
                        recoveredAt,
                        "capture_recovered",
                        mapOf("recovered_session_count" to "1"),
                    ),
                ),
            ),
        ).joinToString(" ")

        assertTrue(diagnostics.contains("Android reported DreamLog's microphone input as silenced"))
        assertTrue(diagnostics.contains("Stop other microphone recorders"))
        assertTrue(diagnostics.contains("Completed session audio was preserved"))
        assertFalse(diagnostics.contains("Recovery preserved"))
        assertTrue(diagnostics.contains("last confirmed heartbeat"))
        assertTrue(diagnostics.contains("may have continued afterward"))
        assertTrue(
            diagnostics.contains(
                "from ${HistoryFormatters.dateTime(silencedAt, -5 * 60 * 60)} to " +
                    HistoryFormatters.dateTime(recoveredAt, -5 * 60 * 60),
            ),
        )
        assertFalse(
            diagnostics.contains(
                "to ${HistoryFormatters.dateTime(silencedAt + 60_000L, -5 * 60 * 60)}",
            ),
        )
    }

    @Test
    fun fatalCaptureKindsHaveActionableMorningExplanations() {
        val base = nightRecord()
        val diagnostics = morningDiagnostics(
            base.copy(
                events = listOf(
                    "storage_reserve",
                    "audio_write",
                    "initialization",
                    "audio_read",
                    "cue_playback",
                    "journal",
                    "future_kind",
                ).mapIndexed { index, kind ->
                    event(
                        eventId = "failure-$index",
                        epochMillis = base.night.startedAtEpochMillis + index,
                        type = "capture_failure",
                        attributes = mapOf("kind" to kind),
                    )
                },
            ),
        ).joinToString(" ")

        assertTrue(diagnostics.isNotBlank())
        assertTrue(diagnostics.contains("protected storage reserve"))
        assertTrue(diagnostics.contains("writing capture audio"))
        assertTrue(diagnostics.contains("capture engine could not start"))
        assertTrue(diagnostics.contains("reading microphone audio"))
        assertTrue(diagnostics.contains("acknowledgement cue could not be played"))
        assertTrue(diagnostics.contains("evidence could not be written reliably"))
        assertTrue(diagnostics.contains("capture failed unexpectedly"))
    }

    @Test
    fun normalEndedNightDoesNotTurnStaleHeartbeatTelemetryIntoCaptureIssue() {
        val base = nightRecord()
        val stale = base.copy(
            night = base.night.copy(
                lastHeartbeatEpochMillis = base.night.endedAtEpochMillis!! - 180_001L,
                lastHeartbeatUtcOffsetSeconds = -5 * 60 * 60,
            ),
        )
        val recent = stale.copy(
            night = stale.night.copy(
                lastHeartbeatEpochMillis = base.night.endedAtEpochMillis!! - 180_000L,
            ),
        )

        assertTrue(morningDiagnostics(stale).isEmpty())
        assertTrue(morningDiagnostics(recent).isEmpty())
        assertNull(captureEvidence(stale))
    }

    @Test
    fun retryAndExpiryCopyDependOnRetainedAudioAndSavedTranscript() {
        val base = nightRecord()
        val failedTranscript = base.transcripts.single().copy(
            transcript = base.transcripts.single().transcript.copy(
                state = ProcessingState.FAILED,
                failureDetail = "Offline transcription failed.",
                rawText = null,
            ),
        )
        val failed = base.copy(
            night = base.night.copy(
                transcriptionState = ProcessingState.FAILED,
                transcriptionFailure = "Offline transcription failed.",
            ),
            transcripts = listOf(failedTranscript),
        )

        assertEquals(
            NightOutcomeResumeAction(completedCount = 0, totalCount = 1),
            nightOutcomeResumeAction(failed),
        )
        assertTrue(transcriptionProcessingText(failed).contains("retry from this night"))
        assertTrue(
            transcriptionProcessingText(failed).contains(
                "Retained source audio remains available",
            ),
        )
        val unavailableText = transcriptionProcessingText(
            failed.copy(
                sessions = failed.sessions.map {
                    it.copy(audioState = AudioEvidenceState.EXPIRED)
                },
            ),
        )
        assertFalse(
            unavailableText.contains("retry from this night"),
        )
        assertTrue(unavailableText.contains("retry cannot run"))
        assertEquals(
            "Audio expired; saved transcript text remains",
            rawAudioText(
                base.copy(
                    night = base.night.copy(rawAudioState = RawAudioState.UNAVAILABLE),
                    sessions = base.sessions.map {
                        it.copy(audioState = AudioEvidenceState.EXPIRED)
                    },
                ),
            ),
        )
        assertEquals(
            "Audio expired before transcription; no transcript was saved",
            rawAudioText(
                base.copy(
                    night = base.night.copy(rawAudioState = RawAudioState.UNAVAILABLE),
                    sessions = base.sessions.map {
                        it.copy(audioState = AudioEvidenceState.EXPIRED)
                    },
                    transcripts = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun recoveredMonitoringRangeEndsAtLastConfirmedHeartbeat() {
        val base = nightRecord()
        val heartbeatAt = base.night.startedAtEpochMillis + 60_000L
        val recoveredAt = heartbeatAt + 5 * 60_000L
        val recovered = base.copy(
            night = base.night.copy(
                endedAtEpochMillis = recoveredAt,
                endReason = "process_interrupted",
                interrupted = true,
                lastHeartbeatEpochMillis = heartbeatAt,
                lastHeartbeatUtcOffsetSeconds = base.night.startedUtcOffsetSeconds,
            ),
        )

        val range = monitoringRange(recovered)

        assertTrue(range.contains("last confirmed"))
        assertTrue(range.contains(HistoryFormatters.time(heartbeatAt, -5 * 60 * 60)))
        assertFalse(range.contains(HistoryFormatters.time(recoveredAt, -5 * 60 * 60)))
    }

    @Test
    fun recoveredMonitoringRangeDoesNotUseRecoveryTimeWithoutHeartbeat() {
        val base = nightRecord()
        val recoveredAt = base.night.startedAtEpochMillis + 5 * 60_000L
        val recovered = base.copy(
            night = base.night.copy(
                endedAtEpochMillis = recoveredAt,
                endReason = "process_interrupted",
                interrupted = true,
                lastHeartbeatEpochMillis = null,
                lastHeartbeatUtcOffsetSeconds = null,
            ),
        )

        val range = monitoringRange(recovered)
        val diagnostics = morningDiagnostics(recovered).joinToString(" ")

        assertTrue(range.endsWith("no heartbeat confirmed"))
        assertFalse(range.contains(HistoryFormatters.time(recoveredAt, -5 * 60 * 60)))
        assertTrue(diagnostics.contains("No heartbeat was recorded before recovery"))
        assertTrue(diagnostics.contains("cannot confirm how long listening continued"))
    }

    @Test
    fun dreamTitleUsesCurrentThenGeneratedThenStableFallback() {
        val generated = dreamRecord(currentTitle = null, generatedTitle = "Generated title")
        val edited = dreamRecord(currentTitle = "Owner title", generatedTitle = "Generated title")
        val fragment = dreamRecord(
            currentTitle = null,
            generatedTitle = null,
            kind = DreamKind.FRAGMENT,
        )

        assertEquals("Generated title", dreamDisplayTitle(generated, 0))
        assertEquals("Owner title", dreamDisplayTitle(edited, 0))
        assertEquals("Fragment 3", dreamDisplayTitle(fragment, 2))
    }

    @Test
    fun dreamDraftChangesIgnoreOptionalTitleWhitespaceButDetectContentEdits() {
        val untitled = dreamRecord(currentTitle = null, generatedTitle = "Generated title")
        val titled = dreamRecord(currentTitle = "Owner title", generatedTitle = "Generated title")

        assertFalse(dreamDraftHasChanges(untitled, "   ", "Current text"))
        assertFalse(dreamDraftHasChanges(titled, " Owner title ", "Current text"))
        assertTrue(dreamDraftHasChanges(titled, "New title", "Current text"))
        assertTrue(dreamDraftHasChanges(titled, "Owner title", "Changed text"))
    }

    @Test
    fun sourcePlaybackButtonTracksOnlyItsOwnDream() {
        val playing = DreamSourcePlaybackState(
            phase = DreamSourcePlaybackPhase.PLAYING,
            dreamId = "dream-1",
            currentSpanIndex = 0,
            spanCount = 2,
        )

        assertEquals("Pause source audio", dreamPlaybackButtonText(playing, "dream-1"))
        assertEquals("Play source audio", dreamPlaybackButtonText(playing, "dream-2"))
    }

    @Test
    fun narrationTimeUsesSourceOffsetAndPersistedSessionOffset() {
        val session = session(
            startedAtEpochMillis = 1_786_250_400_000L,
            startedUtcOffsetSeconds = -5 * 60 * 60,
            preRollSampleCount = 32_000L,
        )
        val dream = dreamRecord(currentTitle = null, generatedTitle = null).copy(
            sourceSpans = listOf(sourceSpan(sourceStartMillis = 90_000L)),
        )
        val record = nightRecord(session = session, dream = dream)

        assertEquals(
            listOf("2026-08-08 23:41:28"),
            dreamNarrationDateTimes(dream, record),
        )
        assertEquals(
            "2026-08-08 23:41:28",
            sourceWallClockDateTime(session, 90_000L),
        )
    }

    @Test
    fun reprocessRequiresCompleteRetainedUneditedNight() {
        val eligible = nightRecord()

        assertTrue(canReprocessNight(eligible))
        assertNull(reprocessNightDataUnavailableReason(eligible))
        assertFalse(canReprocessNight(eligible.copy(hasProtectedDreamChanges = true)))
        assertTrue(
            reprocessNightDataUnavailableReason(
                eligible.copy(hasProtectedDreamChanges = true),
            ).orEmpty().contains("owner edit or deletion"),
        )
        assertFalse(
            canReprocessNight(
                eligible.copy(
                    sessions = eligible.sessions.map {
                        it.copy(audioState = AudioEvidenceState.DELETED)
                    },
                ),
            ),
        )
        assertTrue(
            reprocessNightDataUnavailableReason(
                eligible.copy(
                    sessions = eligible.sessions.map {
                        it.copy(audioState = AudioEvidenceState.DELETED)
                    },
                ),
            ).orEmpty().contains("retained raw audio"),
        )
        assertFalse(canReprocessNight(eligible.copy(transcripts = emptyList())))
        assertTrue(
            reprocessNightDataUnavailableReason(
                eligible.copy(transcripts = emptyList()),
            ).orEmpty().contains("completed transcript"),
        )
    }

    @Test
    fun captureEvidenceSuppressesKeywordAndLatestReadinessTelemetry() {
        val first = heartbeatEvent(60_000L, 960_000L, 1_875L, 230L)
        val stalled = heartbeatEvent(120_000L, 1_920_000L, 1_875L, 230L)
        val notReady = event(
            eventId = "not-ready",
            epochMillis = 180_000L,
            type = "heartbeat",
            attributes = mapOf("readiness" to "not_ready"),
        )

        val evidence = captureEvidence(
            nightRecord().copy(events = listOf(first, stalled, notReady)),
        )

        assertNull(evidence)
    }

    @Test
    fun ownerEndedActiveSessionIsNotACaptureIssue() {
        val ownerEnded = nightRecord(
            session = session().copy(
                incompleteReason = SessionIncompleteReason.NIGHT_ENDED,
            ),
        ).let { record ->
            record.copy(
                night = record.night.copy(reportedIncompleteSessionCount = 1),
            )
        }

        assertFalse(hasOwnerFacingCaptureIssue(ownerEnded))
        assertNull(captureEvidence(ownerEnded))
        assertEquals("Complete", historyStatus(ownerEnded))
    }

    @Test
    fun captureEvidenceSuppressesGapTelemetryUnlessSessionIsDurablyIncomplete() {
        val base = nightRecord().copy(
            night = nightRecord().night.copy(hadAudioGap = true),
        )
        val idleGap = event(
            eventId = "idle-gap",
            epochMillis = 60_000L,
            type = "audio_gap",
            attributes = emptyMap(),
        )
        val sessionGap = event(
            eventId = "session-gap",
            epochMillis = 120_000L,
            type = "audio_gap",
            attributes = emptyMap(),
            sessionId = "session-1",
        )

        assertNull(captureEvidence(base.copy(events = listOf(idleGap))))
        assertNull(captureEvidence(base.copy(events = listOf(sessionGap))))
        val affected = base.copy(
            night = base.night.copy(reportedIncompleteSessionCount = 1),
            sessions = base.sessions.map {
                it.copy(incompleteReason = SessionIncompleteReason.AUDIO_GAP)
            },
            events = listOf(sessionGap),
        )
        assertTrue(
            captureEvidence(affected)
                .orEmpty()
                .contains("did not finish cleanly"),
        )
        assertTrue(
            captureEvidence(affected)
                .orEmpty()
                .contains("audio gap was observed during an affected recollection"),
        )
    }

    @Test
    fun captureEvidenceSuppressesSilencingTelemetryUnlessSessionIsDurablyIncomplete() {
        val silencedAt = 60_000L
        val silencing = event(
            eventId = "silenced",
            epochMillis = silencedAt,
            type = "microphone_state",
            attributes = mapOf("effective_silenced" to "true"),
        )
        val telemetryOnly = nightRecord().copy(
            night = nightRecord().night.copy(hadMicrophoneSilencing = true),
            events = listOf(silencing),
        )
        val affected = telemetryOnly.copy(
            night = telemetryOnly.night.copy(reportedIncompleteSessionCount = 1),
            sessions = telemetryOnly.sessions.map {
                it.copy(incompleteReason = SessionIncompleteReason.MICROPHONE_SILENCED)
            },
        )

        assertNull(captureEvidence(telemetryOnly))
        assertTrue(hasOwnerFacingCaptureIssue(affected))
        assertTrue(
            captureEvidence(affected)
                .orEmpty()
                .contains("microphone input as silenced"),
        )
    }

    @Test
    fun missingCorruptAndPendingAudioRemainCaptureIssues() {
        listOf(
            AudioEvidenceState.MISSING,
            AudioEvidenceState.CORRUPT,
            AudioEvidenceState.PENDING_RECOVERY,
        ).forEach { state ->
            val affected = nightRecord(
                session = session().copy(audioState = state),
            )

            assertTrue("$state should need attention", hasOwnerFacingCaptureIssue(affected))
            assertTrue(
                captureEvidence(affected)
                    .orEmpty()
                    .contains("missing, corrupt, or unresolved source audio"),
            )
        }
    }

    @Test
    fun compactHistoryLabelsUseDreamsWakewordsAndPrioritizedStatus() {
        val complete = nightRecord()
        val interrupted = complete.copy(
            night = complete.night.copy(interrupted = true),
        )
        val bothProcessingFailures = interrupted.copy(
            night = interrupted.night.copy(
                transcriptionState = ProcessingState.FAILED,
                enrichmentState = ProcessingState.FAILED,
            ),
        )

        assertEquals("1 dream", dreamCountText(complete))
        assertEquals("0 dreams", dreamCountText(complete.copy(dreams = emptyList())))
        assertEquals("1 wakeword", wakewordCountText(complete))
        assertEquals("0 wakewords", wakewordCountText(complete.copy(sessions = emptyList())))
        assertEquals("Complete", historyStatus(complete))
        assertEquals("Capture issue", historyStatus(interrupted))
        assertTrue(historyStatusIsError(interrupted))
        assertEquals("Transcription failed", historyStatus(bothProcessingFailures))
        assertTrue(historyStatusIsError(bothProcessingFailures))
        assertEquals(
            "Enrichment failed",
            historyStatus(
                interrupted.copy(
                    night = interrupted.night.copy(
                        transcriptionState = ProcessingState.COMPLETE,
                        enrichmentState = ProcessingState.FAILED,
                    ),
                ),
            ),
        )
        assertEquals(
            "Active",
            historyStatus(
                complete.copy(
                    night = complete.night.copy(
                        captureState = NightCaptureState.ACTIVE,
                        transcriptionState = ProcessingState.NOT_STARTED,
                        enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION,
                    ),
                ),
            ),
        )
        assertEquals(
            "Transcribing",
            historyStatus(
                complete.copy(
                    night = complete.night.copy(
                        transcriptionState = ProcessingState.RUNNING,
                        enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION,
                    ),
                ),
            ),
        )
        assertEquals(
            "Enriching",
            historyStatus(
                complete.copy(
                    night = complete.night.copy(enrichmentState = ProcessingState.RUNNING),
                ),
            ),
        )
        assertEquals(
            "Ready to enrich",
            historyStatus(
                complete.copy(
                    night = complete.night.copy(enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION),
                ),
            ),
        )
    }

    @Test
    fun historyShowsOnlySpecificProcessingFailures() {
        val complete = nightRecord()

        assertNull(historyProcessingFailure(complete))
        assertNull(
            historyProcessingFailure(
                complete.copy(night = complete.night.copy(hadAudioGap = true)),
            ),
        )
        assertEquals(
            "Transcription failed",
            historyProcessingFailure(
                complete.copy(
                    night = complete.night.copy(transcriptionState = ProcessingState.FAILED),
                ),
            ),
        )
        assertEquals(
            "Enrichment failed",
            historyProcessingFailure(
                complete.copy(
                    night = complete.night.copy(enrichmentState = ProcessingState.FAILED),
                ),
            ),
        )
    }

    private fun dreamRecord(
        currentTitle: String?,
        generatedTitle: String?,
        kind: String = DreamKind.DREAM,
    ) = DreamRecord(
        dream = DreamEntity(
            dreamId = "dream-1",
            nightId = "night-1",
            runId = "run-1",
            dreamOrder = 0,
            kind = kind,
            isUncertain = false,
            generatedTitle = generatedTitle,
            generatedText = "Generated text",
            currentTitle = currentTitle,
            currentText = "Current text",
            ownerEdited = currentTitle != null,
            editedAtEpochMillis = null,
        ),
        sourceSpans = emptyList(),
    )

    private fun sourceSpan(sourceStartMillis: Long = 0L) = DreamSourceSpanEntity(
        dreamId = "dream-1",
        spanOrder = 0,
        sessionId = "session-1",
        sourceTranscriptAttemptCount = 1,
        firstSegmentIndex = 0,
        lastSegmentIndex = 0,
        sourceStartMillis = sourceStartMillis,
        sourceEndMillis = sourceStartMillis + 1_000L,
        sourceText = "Private source",
        role = DreamSourceRole.NARRATIVE,
    )

    private fun heartbeatEvent(
        epochMillis: Long,
        framesRead: Long,
        acceptedFrames: Long,
        decodes: Long,
    ) = NightEventEntity(
        nightId = "night-1",
        eventId = "heartbeat-$epochMillis",
        sessionId = null,
        epochMillis = epochMillis,
        utcOffsetSeconds = -5 * 60 * 60,
        type = "heartbeat",
        encodedAttributes = encodeAttributes(
            mapOf(
                "frames_read" to framesRead.toString(),
                "gap_count" to "0",
                "microphone_silenced" to "false",
                "readiness" to "ready",
                "charging" to "true",
                "session_active" to "false",
                "kws_accepted_frame_count" to acceptedFrames.toString(),
                "kws_decode_count" to decodes.toString(),
                "kws_reset_count" to "0",
            ),
        ),
    )

    private fun event(
        eventId: String,
        epochMillis: Long,
        type: String,
        attributes: Map<String, String>,
        sessionId: String? = null,
    ) = NightEventEntity(
        nightId = "night-1",
        eventId = eventId,
        sessionId = sessionId,
        epochMillis = epochMillis,
        utcOffsetSeconds = -5 * 60 * 60,
        type = type,
        encodedAttributes = encodeAttributes(attributes),
    )

    private fun encodeAttributes(attributes: Map<String, String>): String =
        attributes.toSortedMap().entries.joinToString(";") { (key, value) ->
            "$key=${
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    value.toByteArray(StandardCharsets.UTF_8),
                )
            }"
        }

    private fun session(
        startedAtEpochMillis: Long = 1_786_250_400_000L,
        startedUtcOffsetSeconds: Int = -5 * 60 * 60,
        preRollSampleCount: Long = 0L,
    ) = CaptureSessionEntity(
        sessionId = "session-1",
        nightId = "night-1",
        captureOrder = 0,
        startedAtEpochMillis = startedAtEpochMillis,
        startedUtcOffsetSeconds = startedUtcOffsetSeconds,
        finalizedAtEpochMillis = startedAtEpochMillis + 120_000L,
        finalizedUtcOffsetSeconds = startedUtcOffsetSeconds,
        incompleteReason = null,
        audioFileName = "session.wav",
        audioState = AudioEvidenceState.RETAINED,
        sampleRateHz = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
        sampleCount = 1_920_000L,
        preRollSampleCount = preRollSampleCount,
        cueStartSample = 0L,
        cueEndSampleExclusive = 8_000L,
        automaticSilenceTailSampleCount = 160_000L,
    )

    private fun nightRecord(
        session: CaptureSessionEntity = session(),
        dream: DreamRecord = dreamRecord(null, null).copy(
            sourceSpans = listOf(sourceSpan()),
        ),
    ) = NightRecord(
        night = NightEntity(
            nightId = "night-1",
            displayDate = "2026-08-08",
            startedAtEpochMillis = session.startedAtEpochMillis ?: 0L,
            startedUtcOffsetSeconds = session.startedUtcOffsetSeconds ?: 0,
            endedAtEpochMillis = session.finalizedAtEpochMillis,
            endedUtcOffsetSeconds = session.finalizedUtcOffsetSeconds,
            captureState = NightCaptureState.ENDED,
            endReason = "owner_ended",
            interrupted = false,
            lastHeartbeatEpochMillis = null,
            lastHeartbeatUtcOffsetSeconds = null,
            reportedSessionCount = 1,
            reportedIncompleteSessionCount = 0,
            hadMicrophoneSilencing = false,
            hadAudioGap = false,
            rawAudioState = RawAudioState.RETAINED,
            transcriptionState = ProcessingState.COMPLETE,
            transcriptionFailure = null,
            enrichmentState = ProcessingState.COMPLETE,
            enrichmentFailure = null,
            importWarning = null,
        ),
        sessions = listOf(session),
        events = emptyList(),
        transcripts = listOf(
            SessionTranscriptRecord(
                transcript = SessionTranscriptEntity(
                    sessionId = session.sessionId,
                    nightId = session.nightId,
                    state = ProcessingState.COMPLETE,
                    failureDetail = null,
                    rawText = "Private transcript",
                    localeTag = "en-US",
                    engineId = "engine",
                    engineVersion = "1",
                    runtimeId = "runtime",
                    runtimeVersion = "1",
                    modelId = "model",
                    modelVersion = "1",
                    modelSha256 = "a".repeat(64),
                    attemptCount = 1,
                    startedAtEpochMillis = 1L,
                    completedAtEpochMillis = 2L,
                ),
                segments = emptyList(),
            ),
        ),
        dreams = listOf(dream),
    )
}
