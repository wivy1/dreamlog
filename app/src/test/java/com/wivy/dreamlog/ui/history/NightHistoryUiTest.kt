package com.wivy.dreamlog.ui.history

import com.wivy.dreamlog.capture.SessionIncompleteReason
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureIssueFingerprint
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamEntity
import com.wivy.dreamlog.history.DreamKind
import com.wivy.dreamlog.history.DreamRecord
import com.wivy.dreamlog.history.DreamSourceRole
import com.wivy.dreamlog.history.DreamSourceSpanEntity
import com.wivy.dreamlog.history.HistoryFormatters
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightAudioArtifactInspection
import com.wivy.dreamlog.history.NightEntity
import com.wivy.dreamlog.history.NightEventEntity
import com.wivy.dreamlog.history.NightRecord
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioState
import com.wivy.dreamlog.history.SessionTranscriptEntity
import com.wivy.dreamlog.history.SessionTranscriptRecord
import com.wivy.dreamlog.history.TranscriptSegmentEntity
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
    fun captureGapExplanationIdentifiesRecordingAndDoesNotClaimLostWords() {
        val base = nightRecord()
        val gapAt = base.sessions.single().startedAtEpochMillis!! + 20_000L
        val affected = base.copy(
            sessions = base.sessions.map {
                it.copy(incompleteReason = SessionIncompleteReason.AUDIO_GAP)
            },
            events = listOf(
                event(
                    eventId = "confirmed-gap",
                    epochMillis = gapAt,
                    type = "audio_gap",
                    attributes = mapOf(
                        "estimated_gap_millis" to "128",
                        "evidence" to "confirmed_persistent_timestamp_deficit",
                    ),
                    sessionId = "session-1",
                ),
            ),
        )

        val explanation = captureEvidence(affected).orEmpty()
        assertTrue(explanation.contains("Recording 1"))
        assertTrue(explanation.contains(HistoryFormatters.time(gapAt, -5 * 60 * 60)))
        assertTrue(explanation.contains("128 ms"))
        assertTrue(explanation.contains("possible gap", ignoreCase = true))
        assertTrue(explanation.contains("does not prove words were lost"))
        assertTrue(explanation.contains("Play the recording"))
        assertTrue(explanation.contains("uncertainty in your words"))

        val reviewed = affected.copy(
            night = affected.night.copy(
                captureIssueReviewedFingerprint = CaptureIssueFingerprint.current(affected),
            ),
        )
        assertNull(captureEvidence(reviewed))
        assertTrue(captureEvidenceText(reviewed).orEmpty().contains("128 ms"))
    }

    @Test
    fun captureExplanationSeparatesMissingDamagedAndUnfinishedRecordings() {
        val base = nightRecord()
        val expected = mapOf(
            AudioEvidenceState.MISSING to "Recording 1 is missing",
            AudioEvidenceState.CORRUPT to "Recording 1 could not be validated",
            AudioEvidenceState.PENDING_RECOVERY to "Recording 1 has not finished recovery",
        )
        expected.forEach { (state, description) ->
            val explanation = captureEvidence(
                base.copy(sessions = base.sessions.map { it.copy(audioState = state) }),
            ).orEmpty()
            assertTrue("$state: $explanation", explanation.contains(description))
            assertTrue(explanation.contains("Check recordings"))
        }
    }

    @Test
    fun interruptedCaptureExplainsKnownEndReasonWithoutGuessingCause() {
        val base = nightRecord()
        val explanation = captureEvidence(
            base.copy(
                night = base.night.copy(
                    interrupted = true,
                    endReason = "process_interrupted",
                    lastHeartbeatEpochMillis = base.night.startedAtEpochMillis + 60_000L,
                ),
            ),
        ).orEmpty()
        assertTrue(explanation.contains("App interrupted"))
        assertTrue(explanation.contains("Later listening is unconfirmed"))
        assertTrue(explanation.contains("Check recordings"))
        assertFalse(explanation.contains("Android killed"))
    }

    @Test
    fun savedAudioInspectionTextDistinguishesCleanAndRecoverableArtifacts() {
        assertEquals(
            "All 4 recordings verified. No additional recordings or partial files.",
            nightAudioInspectionText(
                NightAudioArtifactInspection(
                    directoryPresent = true,
                    recordedSessionCount = 4,
                    recordedFinalPresentCount = 4,
                    recordedFinalValidCount = 4,
                    extraFinalizedCandidateCount = 0,
                    extraFinalizedOutsideNightCount = 0,
                    extraUnverifiedFinalCount = 0,
                    partialFileCount = 0,
                    metadataOnlyCount = 0,
                    metadataPartialCount = 0,
                ),
            ),
        )
        val candidate = nightAudioInspectionText(
            NightAudioArtifactInspection(
                directoryPresent = true,
                recordedSessionCount = 2,
                recordedFinalPresentCount = 2,
                recordedFinalValidCount = 1,
                extraFinalizedCandidateCount = 1,
                extraFinalizedOutsideNightCount = 1,
                extraUnverifiedFinalCount = 1,
                partialFileCount = 1,
                metadataOnlyCount = 1,
                metadataPartialCount = 1,
            ),
        )

        assertTrue(candidate.contains("1 of 2 recordings verified"))
        assertTrue(candidate.contains("1 additional finalized recording"))
        assertTrue(candidate.contains("1 partial audio file"))
        assertTrue(candidate.contains("1 additional finalized file unverified"))
        assertTrue(candidate.contains("finalized recording outside this night's time range"))
        assertTrue(candidate.contains("1 metadata-only entry"))
        assertTrue(candidate.contains("1 unfinished metadata entry"))
        assertTrue(candidate.endsWith("1 unfinished metadata entry."))
    }

    @Test
    fun savedAudioInspectionAvailabilityRequiresFinalizedNight() {
        val ended = nightRecord()

        assertTrue(canInspectNightAudio(ended))
        assertTrue(
            canInspectNightAudio(
                ended.copy(
                    night = ended.night.copy(captureState = NightCaptureState.INTERRUPTED),
                ),
            ),
        )
        listOf(
            NightCaptureState.STARTING,
            NightCaptureState.ACTIVE,
            NightCaptureState.RECOVERY_REQUIRED,
        ).forEach { state ->
            assertFalse(
                canInspectNightAudio(
                    ended.copy(night = ended.night.copy(captureState = state)),
                ),
            )
        }
        assertFalse(
            canInspectNightAudio(
                ended.copy(night = ended.night.copy(endedAtEpochMillis = null)),
            ),
        )
    }

    @Test
    fun legacyOversizeFailureHidesMarkerAndShowsRetryInstructionAfterUpgrade() {
        val text = enrichmentProcessingText(
            ProcessingState.FAILED,
            "The whole-night transcript is too large. " +
                "The raw transcript remains available to retry after an app update. " +
                "[code=input_too_large; retryable=false]",
        )

        assertTrue(text.contains("Transcript exceeded the earlier enrichment limit"))
        assertFalse(text.contains("this capture"))
        assertTrue(text.contains("enrichment limit"))
        assertFalse(text.contains("after an app update"))
        assertFalse(text.contains("[code="))
        assertTrue(text.contains("retry available"))
    }

    @Test
    fun currentOversizeFailureHidesMarkerAndShowsRetryInstructionAfterUpgrade() {
        val text = enrichmentProcessingText(
            ProcessingState.FAILED,
            "One capture exceeds this local model's context budget. " +
                "The raw transcript remains available to retry after an app update. " +
                "[code=capture_input_too_large; retryable=false]",
        )

        assertTrue(text.contains("Transcript exceeded the earlier enrichment limit"))
        assertFalse(text.contains("this capture"))
        assertTrue(text.contains("enrichment limit"))
        assertFalse(text.contains("after an app update"))
        assertFalse(text.contains("[code="))
        assertTrue(text.contains("retry available"))
    }

    @Test
    fun terminalEnrichmentFailureHidesMarkerAndDoesNotPromiseRetry() {
        val text = enrichmentProcessingText(
            ProcessingState.FAILED,
            "The ordered raw transcript source is invalid. " +
                "[code=invalid_source; retryable=false]",
        )

        assertTrue(text.contains("The ordered raw transcript source is invalid"))
        assertFalse(text.contains("Raw transcript retained"))
        assertFalse(text.contains("[code="))
        assertFalse(text.contains("retry available"))
    }

    @Test
    fun terminalSourceFailuresDescribeSourceAvailabilityInEnrichmentSummary() {
        listOf(
            "raw_source_unavailable" to "Raw transcript incomplete or unavailable",
            "invalid_source" to "Invalid raw transcript source",
        ).forEach { (code, expectedDetail) ->
            val text = enrichmentProcessingText(
                ProcessingState.FAILED,
                "A source check failed. [code=$code; retryable=false]",
            )

            assertTrue(text.contains(expectedDetail))
            assertFalse(text.contains("Raw transcript retained"))
            assertFalse(text.contains("[code="))
        }
    }

    @Test
    fun enrichmentFailureWarningPromisesRetryOnlyForRetryableFailures() {
        val retryable = enrichmentFailureWarningText(
            "One capture exceeds this local model's context budget. " +
                "[code=capture_input_too_large; retryable=false]",
        )
        assertTrue(retryable.contains("Retry with Enrich on Home"))
        assertFalse(retryable.contains("[code="))

        val terminal = enrichmentFailureWarningText(
            "The ordered raw transcript source is invalid. " +
                "[code=invalid_source; retryable=false]",
        )
        assertTrue(terminal.contains("This night can't be retried"))
        assertFalse(terminal.contains("Retry with Enrich on Home"))
        assertFalse(terminal.contains("[code="))
    }

    @Test
    fun terminalSourceFailuresDoNotClaimCompletedTranscriptInNightDetail() {
        listOf(
            "raw_source_unavailable" to "raw transcript incomplete or unavailable",
            "invalid_source" to "invalid raw transcript source",
        ).forEach { (code, expectedReason) ->
            val text = enrichmentFailureWarningText(
                "A source check failed. [code=$code; retryable=false]",
            )

            assertTrue(text.contains(expectedReason))
            assertTrue(text.contains("This night can't be retried"))
            assertFalse(text.contains("Raw transcript retained"))
            assertFalse(text.contains("[code="))
        }
    }

    @Test
    fun retryableEnrichmentFailureShowsCleanRetryInstruction() {
        val text = enrichmentProcessingText(
            ProcessingState.FAILED,
            "Local enrichment was interrupted. [code=interrupted; retryable=true]",
        )

        assertTrue(text.contains("Local enrichment was interrupted"))
        assertTrue(text.contains("Raw transcript retained"))
        assertTrue(text.contains("retry available"))
        assertFalse(text.contains("[code="))
    }

    @Test
    fun enrichmentDetailShowsWhenCompletedTranscriptionIsReadyForEnrichment() {
        assertEquals(
            "Ready to enrich",
            enrichmentProcessingText(
                state = ProcessingState.NOT_STARTED,
                failure = null,
                transcriptionState = ProcessingState.COMPLETE,
            ),
        )
        assertEquals(
            "Ready to enrich",
            enrichmentProcessingText(
                state = ProcessingState.WAITING_FOR_TRANSCRIPTION,
                failure = null,
                transcriptionState = ProcessingState.COMPLETE,
            ),
        )
        assertEquals(
            "Waiting for transcription",
            enrichmentProcessingText(
                state = ProcessingState.WAITING_FOR_TRANSCRIPTION,
                failure = null,
                transcriptionState = ProcessingState.RUNNING,
            ),
        )
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

        assertTrue(diagnostics.contains("Android reported the microphone silenced"))
        assertTrue(diagnostics.contains("Stop other microphone recorders"))
        assertTrue(diagnostics.contains("Completed audio retained"))
        assertFalse(diagnostics.contains("Recovery preserved"))
        assertTrue(diagnostics.contains("Last confirmed heartbeat"))
        assertTrue(diagnostics.contains("Later listening is unconfirmed"))
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
        assertTrue(diagnostics.contains("storage reserve reached"))
        assertTrue(diagnostics.contains("audio write failed"))
        assertTrue(diagnostics.contains("capture failed to start"))
        assertTrue(diagnostics.contains("microphone read failed"))
        assertTrue(diagnostics.contains("cue failed"))
        assertTrue(diagnostics.contains("capture evidence write failed"))
        assertTrue(diagnostics.contains("capture failed; check the end reason"))
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
        assertTrue(transcriptionProcessingText(failed).contains("retry available"))
        assertTrue(
            transcriptionProcessingText(failed).contains(
                "Source audio retained",
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
            unavailableText.contains("retry available"),
        )
        assertTrue(unavailableText.contains("can't retry"))
        assertEquals(
            "Audio expired; transcript retained",
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
            "Audio expired; no transcript",
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
        assertTrue(diagnostics.contains("No heartbeat before recovery"))
        assertTrue(diagnostics.contains("listening duration unconfirmed"))
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
    fun dreamReviewStatusDistinguishesUncertainDreamsFromFragments() {
        val complete = dreamRecord(currentTitle = null, generatedTitle = null)
        val uncertainDream = dreamRecord(
            currentTitle = null,
            generatedTitle = null,
            isUncertain = true,
        )
        val fragment = dreamRecord(
            currentTitle = null,
            generatedTitle = null,
            kind = DreamKind.FRAGMENT,
        )
        val uncertainFragment = dreamRecord(
            currentTitle = null,
            generatedTitle = null,
            kind = DreamKind.FRAGMENT,
            isUncertain = true,
        )

        assertNull(dreamReviewStatusText(complete))
        assertEquals("Uncertain details", dreamReviewStatusText(uncertainDream))
        assertEquals("Fragment", dreamReviewStatusText(fragment))
        assertEquals("Uncertain fragment", dreamReviewStatusText(uncertainFragment))
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

        assertEquals("Pause", dreamPlaybackButtonText(playing, "dream-1"))
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
            ).orEmpty().contains("Your edits or deletions"),
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
            ).orEmpty().contains("raw audio for every session"),
        )
        val currentTranscriptWithoutAudio = eligible.copy(
            sessions = eligible.sessions.map {
                it.copy(audioState = AudioEvidenceState.DELETED)
            },
        )
        assertTrue(
            canReprocessNight(
                record = currentTranscriptWithoutAudio,
                requiresRetainedAudio = false,
            ),
        )
        assertNull(
            reprocessNightDataUnavailableReason(
                record = currentTranscriptWithoutAudio,
                requiresRetainedAudio = false,
            ),
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
            attributes = mapOf(
                "estimated_gap_millis" to "64",
                "evidence" to "confirmed_persistent_timestamp_deficit",
            ),
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
                .contains("incomplete sessions: 1"),
        )
        assertTrue(
            captureEvidence(affected)
                .orEmpty()
                .contains(
                    "estimated audio-clock discontinuity: 64 ms",
                ),
        )
        assertEquals(
            "May be incomplete · confirmed audio-clock deficit",
            sessionCaptureText(affected, affected.sessions.single()),
        )
    }

    @Test
    fun legacyUnconfirmedTimestampGapDoesNotClaimCaptureIssue() {
        val base = nightRecord().copy(
            night = nightRecord().night.copy(
                reportedIncompleteSessionCount = 1,
                hadAudioGap = true,
                enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION,
            ),
            sessions = nightRecord().sessions.map {
                it.copy(incompleteReason = SessionIncompleteReason.AUDIO_GAP)
            },
            events = listOf(
                event(
                    eventId = "legacy-session-gap",
                    epochMillis = 120_000L,
                    type = "audio_gap",
                    attributes = mapOf("estimated_gap_millis" to "64"),
                    sessionId = "session-1",
                ),
            ),
            dreams = emptyList(),
        )

        assertFalse(hasOwnerFacingCaptureIssue(base))
        assertNull(captureEvidence(base))
        assertEquals("Ready to enrich", historyStatus(base))
        assertEquals("— dreams", dreamCountText(base))
        assertEquals(
            "Timestamp anomaly · capture gap unverified",
            sessionCaptureText(base, base.sessions.single()),
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
                .contains("microphone silenced"),
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
                    .contains("Recording 1"),
            )
        }
    }

    @Test
    fun reviewedCaptureIssueKeepsTechnicalEvidenceAndResurfacesWhenEvidenceChanges() {
        val issue = nightRecord().copy(
            night = nightRecord().night.copy(
                captureState = NightCaptureState.INTERRUPTED,
                interrupted = true,
                endReason = "process_interrupted",
            ),
        )
        val fingerprint = CaptureIssueFingerprint.current(issue)

        assertTrue(hasUnreviewedCaptureIssue(issue))
        assertTrue(fingerprint != null)
        assertEquals(64, fingerprint?.length)

        val reviewed = issue.copy(
            night = issue.night.copy(
                captureIssueReviewedFingerprint = fingerprint,
            ),
        )
        assertFalse(hasUnreviewedCaptureIssue(reviewed))
        assertNull(captureEvidence(reviewed))
        assertEquals("Complete", historyStatus(reviewed))
        assertTrue(
            captureEvidenceText(reviewed)
                .orEmpty()
                .startsWith("Capture evidence (reviewed):"),
        )
        val processingFailure = reviewed.copy(
            night = reviewed.night.copy(
                transcriptionState = ProcessingState.FAILED,
            ),
        )
        assertFalse(hasUnreviewedCaptureIssue(processingFailure))
        assertEquals("Transcription failed", historyStatus(processingFailure))

        val changed = reviewed.copy(
            events = listOf(
                event(
                    eventId = "new-capture-failure",
                    epochMillis = 200_000L,
                    type = "capture_failure",
                    attributes = mapOf("kind" to "audio_read"),
                ),
            ),
        )
        assertTrue(hasUnreviewedCaptureIssue(changed))
        assertEquals("Complete · Capture issue", historyStatus(changed))
    }

    @Test
    fun completedBlankTranscriptShowsNoSpeechInsteadOfReadyToEnrich() {
        val blankTranscription = blankTranscriptionNight()

        assertTrue(blankTranscription.hasNoRecognizedSpeech)
        assertEquals("No speech recognized", historyStatus(blankTranscription))
        assertEquals(
            "No speech recognized",
            enrichmentProcessingText(
                state = blankTranscription.night.enrichmentState,
                failure = blankTranscription.night.enrichmentFailure,
                transcriptionState = blankTranscription.night.transcriptionState,
                hasNoRecognizedSpeech = blankTranscription.hasNoRecognizedSpeech,
            ),
        )
    }

    @Test
    fun noSpeechRequiresEverySessionToHaveEmptyRecognizedSource() {
        val blank = blankTranscriptionNight()
        val secondSession = blank.sessions.single().copy(
            sessionId = "session-2",
            captureOrder = 1,
        )
        val secondTranscript = blank.transcripts.single().copy(
            transcript = blank.transcripts.single().transcript.copy(
                sessionId = secondSession.sessionId,
                rawText = "Recognized words",
            ),
        )
        val mixed = blank.copy(
            night = blank.night.copy(reportedSessionCount = 2),
            sessions = blank.sessions + secondSession,
            transcripts = blank.transcripts + secondTranscript,
        )

        assertFalse(mixed.hasNoRecognizedSpeech)
        assertEquals("Ready to enrich", historyStatus(mixed))
        val allBlank = mixed.copy(
            transcripts = mixed.transcripts.map { saved ->
                saved.copy(transcript = saved.transcript.copy(rawText = " \n\t"))
            },
        )
        assertTrue(allBlank.hasNoRecognizedSpeech)
        assertEquals("No speech recognized", historyStatus(allBlank))
    }

    @Test
    fun noSpeechRejectsMissingDuplicateOrContradictorySourceEvidence() {
        val blank = blankTranscriptionNight()
        val saved = blank.transcripts.single()
        val incompleteSources = listOf(
            "missing transcript" to blank.copy(transcripts = emptyList()),
            "duplicate transcript" to blank.copy(transcripts = listOf(saved, saved)),
            "duplicate session" to blank.copy(sessions = blank.sessions + blank.sessions),
            "unmatched session ID" to blank.copy(
                transcripts = listOf(
                    saved.copy(transcript = saved.transcript.copy(sessionId = "other-session")),
                ),
            ),
            "missing raw text" to blank.copy(
                transcripts = listOf(saved.copy(transcript = saved.transcript.copy(rawText = null))),
            ),
            "retained transcript segment" to blank.copy(
                transcripts = listOf(
                    saved.copy(
                        segments = listOf(
                            TranscriptSegmentEntity(
                                sessionId = saved.transcript.sessionId,
                                segmentIndex = 0,
                                sourceStartMillis = 0L,
                                sourceEndMillis = 1_000L,
                                text = "Recognized words",
                            ),
                        ),
                    ),
                ),
            ),
        )
        incompleteSources.forEach { (description, record) ->
            assertFalse(description, record.hasNoRecognizedSpeech)
        }

        val emptyNight = blank.copy(
            night = blank.night.copy(reportedSessionCount = 0),
            sessions = emptyList(),
            transcripts = emptyList(),
        )
        assertFalse(emptyNight.hasNoRecognizedSpeech)
        assertEquals("Ready to enrich", historyStatus(emptyNight))
    }

    @Test
    fun noSpeechRequiresCompletedFailureFreeTranscription() {
        val blank = blankTranscriptionNight()
        val saved = blank.transcripts.single()
        listOf(ProcessingState.NOT_STARTED, ProcessingState.RUNNING, ProcessingState.FAILED)
            .forEach { state ->
                assertFalse(
                    "aggregate $state",
                    blank.copy(night = blank.night.copy(transcriptionState = state))
                        .hasNoRecognizedSpeech,
                )
                assertFalse(
                    "session $state",
                    blank.copy(
                        transcripts = listOf(saved.copy(transcript = saved.transcript.copy(state = state))),
                    ).hasNoRecognizedSpeech,
                )
            }
        assertFalse(
            blank.copy(night = blank.night.copy(transcriptionFailure = "Transcription failed."))
                .hasNoRecognizedSpeech,
        )
        assertFalse(
            blank.copy(
                transcripts = listOf(
                    saved.copy(transcript = saved.transcript.copy(failureDetail = "Session failed.")),
                ),
            ).hasNoRecognizedSpeech,
        )
    }

    @Test
    fun noSpeechDoesNotOverrideEnrichmentDreamOrCaptureIssuePriority() {
        val blank = blankTranscriptionNight()
        listOf(
            ProcessingState.RUNNING to "Enriching",
            ProcessingState.COMPLETE to "Complete",
            ProcessingState.FAILED to "Enrichment failed",
        ).forEach { (state, expected) ->
            val record = blank.copy(night = blank.night.copy(enrichmentState = state))
            assertEquals(expected, historyStatus(record))
            assertFalse(
                enrichmentProcessingText(
                    state = state,
                    failure = null,
                    transcriptionState = record.night.transcriptionState,
                    hasNoRecognizedSpeech = record.hasNoRecognizedSpeech,
                ).contains("No speech recognized"),
            )
        }
        assertEquals(
            "Ready to enrich",
            historyStatus(blank.copy(dreams = nightRecord().dreams)),
        )
        val captureIssue = blank.copy(night = blank.night.copy(interrupted = true))
        assertEquals("Capture issue", historyStatus(captureIssue))
        assertTrue(historyStatusIsError(captureIssue))
        assertEquals(
            "Transcription failed",
            historyStatus(blank.copy(night = blank.night.copy(transcriptionState = ProcessingState.FAILED))),
        )
    }

    @Test
    fun compactHistoryLabelsUseDreamsWakewordsAndPrioritizedStatus() {
        val complete = nightRecord()
        val interrupted = complete.copy(
            night = complete.night.copy(interrupted = true),
        )
        val pending = complete.copy(
            night = complete.night.copy(
                enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION,
            ),
            dreams = emptyList(),
        )
        val pendingWithCaptureIssue = pending.copy(
            night = pending.night.copy(interrupted = true),
        )
        val bothProcessingFailures = interrupted.copy(
            night = interrupted.night.copy(
                transcriptionState = ProcessingState.FAILED,
                enrichmentState = ProcessingState.FAILED,
            ),
        )

        assertEquals("1 dream", dreamCountText(complete))
        assertEquals("0 dreams", dreamCountText(complete.copy(dreams = emptyList())))
        assertEquals("— dreams", dreamCountText(pending))
        assertEquals("1 dream", dreamCountText(pending.copy(dreams = complete.dreams)))
        assertEquals("1 wakeword", wakewordCountText(complete))
        assertEquals("0 wakewords", wakewordCountText(complete.copy(sessions = emptyList())))
        assertEquals("Complete", historyStatus(complete))
        assertEquals("Complete · Capture issue", historyStatus(interrupted))
        assertTrue(historyStatusIsError(interrupted))
        assertEquals("Ready to enrich · Capture issue", historyStatus(pendingWithCaptureIssue))
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
        isUncertain: Boolean = false,
    ) = DreamRecord(
        dream = DreamEntity(
            dreamId = "dream-1",
            nightId = "night-1",
            runId = "run-1",
            dreamOrder = 0,
            kind = kind,
            isUncertain = isUncertain,
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

    private fun blankTranscriptionNight(): NightRecord {
        val base = nightRecord()
        return base.copy(
            night = base.night.copy(
                enrichmentState = ProcessingState.WAITING_FOR_TRANSCRIPTION,
            ),
            transcripts = base.transcripts.map { saved ->
                saved.copy(
                    transcript = saved.transcript.copy(rawText = ""),
                    segments = emptyList(),
                )
            },
            dreams = emptyList(),
        )
    }

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
