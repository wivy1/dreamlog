package com.wivy.dreamlog.transcription

import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.NightCaptureState
import com.wivy.dreamlog.history.NightDao
import com.wivy.dreamlog.history.NightWithDetails
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.SessionTranscriptReplacementDraft
import com.wivy.dreamlog.history.TranscriptSegmentDraft
import com.wivy.dreamlog.history.TranscriptionDao
import com.wivy.dreamlog.history.TranscriptionProvenance
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Synchronous orchestration for foreground-owned post-night transcription.
 *
 * The caller owns the background thread and its finite user-visible lifecycle. A coordinator
 * serializes all engine calls so only one retained session file is processed at a time.
 */
class NightTranscriptionCoordinator internal constructor(
    private val nightDao: NightDao,
    private val transcriptionDao: TranscriptionDao,
    private val audioRootDirectory: File,
    private val engine: TranscriptionEngine,
    private val speechBoundaryAnalyzer: SessionSpeechBoundaryAnalyzer? =
        PcmSilenceSessionSpeechBoundaryAnalyzer(),
    private val continuationGate: TranscriptionContinuationGate =
        TranscriptionContinuationGate.ALWAYS_CONTINUE,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    val requiresAppToRemainOpen: Boolean = false

    private val runActive = AtomicBoolean(false)
    private val activeSessionId = AtomicReference<String?>(null)
    private val attemptRecovery = TranscriptionAttemptRecovery(transcriptionDao, clock)

    /** Process only retained, finalized sessions that have never been claimed. */
    fun processNight(
        nightId: String,
        onProgress: (NightTranscriptionProgress) -> Unit = {},
    ): NightTranscriptionProgress = runExclusive {
        requireEndedNight(nightId)
        recoverInterruptedAttemptsLocked()
        transcriptionDao.reconcileNightState(nightId)

        val source = requireEndedNight(nightId)
        val existingBySession = source.transcripts.associateBy { it.transcript.sessionId }
        var deferral: TranscriptionContinuationDecision.Defer? = null
        for (session in eligibleSessions(source).filter { existingBySession[it.sessionId] == null }) {
            val decision = continuationGate.evaluate()
            if (decision is TranscriptionContinuationDecision.Defer) {
                deferral = decision
                break
            }
            processSession(
                nightId = nightId,
                session = session,
                triggeringWakePhrase = triggeringWakePhrase(source, session.sessionId),
                retry = false,
                onProgress = onProgress,
            )
        }

        readProgress(nightId)
            .withDeferral(deferral)
            .also { publish(onProgress, it) }
    }

    /**
     * Explicitly resumes one paused night in capture order.
     *
     * Completed sessions are skipped, each failed session is retried in place once, and sessions
     * never claimed by the interrupted operation are claimed once. This method never loops a
     * failed local-model attempt silently.
     */
    fun resumeNight(
        nightId: String,
        onProgress: (NightTranscriptionProgress) -> Unit = {},
    ): NightTranscriptionProgress = runExclusive {
        requireEndedNight(nightId)
        recoverInterruptedAttemptsLocked()
        transcriptionDao.reconcileNightState(nightId)

        var source = requireEndedNight(nightId)
        var deferral: TranscriptionContinuationDecision.Defer? = null
        for (session in eligibleSessions(source)) {
            val existing = source.transcripts
                .firstOrNull { it.transcript.sessionId == session.sessionId }
                ?.transcript
            if (existing?.state == ProcessingState.COMPLETE) continue
            if (existing != null && existing.state != ProcessingState.FAILED) continue

            val decision = continuationGate.evaluate()
            if (decision is TranscriptionContinuationDecision.Defer) {
                deferral = decision
                break
            }
            processSession(
                nightId = nightId,
                session = session,
                triggeringWakePhrase = triggeringWakePhrase(source, session.sessionId),
                retry = existing?.state == ProcessingState.FAILED,
                onProgress = onProgress,
            )
            // Refresh durable state after each attempt; a failed attempt is not retried again in
            // this explicit resume operation, while later never-claimed sessions remain eligible.
            source = requireEndedNight(nightId)
        }

        readProgress(nightId)
            .withDeferral(deferral)
            .also { publish(onProgress, it) }
    }

    /** Retry exactly one failed session without replacing any successful transcript. */
    fun retrySession(
        nightId: String,
        sessionId: String,
        onProgress: (NightTranscriptionProgress) -> Unit = {},
    ): NightTranscriptionProgress = runExclusive {
        requireEndedNight(nightId)
        recoverInterruptedAttemptsLocked()
        transcriptionDao.reconcileNightState(nightId)

        val source = requireEndedNight(nightId)
        val session = requireNotNull(
            eligibleSessions(source).firstOrNull { it.sessionId == sessionId },
        ) {
            "Only finalized retained audio from this ended night can be retried."
        }
        val transcript = transcriptionDao.readSessionTranscript(sessionId)?.transcript
        check(transcript?.nightId == nightId && transcript.state == ProcessingState.FAILED) {
            "Only a failed transcription attempt can be retried."
        }

        processSession(
            nightId = nightId,
            session = session,
            triggeringWakePhrase = triggeringWakePhrase(source, session.sessionId),
            retry = true,
            onProgress = onProgress,
        )
        readProgress(nightId).also { publish(onProgress, it) }
    }

    /** Re-runs a completed session and keeps its prior result unless replacement succeeds. */
    fun retranscribeSession(
        nightId: String,
        sessionId: String,
        onProgress: (NightTranscriptionProgress) -> Unit = {},
    ): NightTranscriptionProgress = runExclusive {
        requireEndedNight(nightId)
        recoverInterruptedAttemptsLocked()
        transcriptionDao.reconcileNightState(nightId)

        val source = requireEndedNight(nightId)
        val session = requireNotNull(
            eligibleSessions(source).firstOrNull { it.sessionId == sessionId },
        ) {
            "Only finalized retained audio from this ended night can be re-transcribed."
        }
        val existing = transcriptionDao.readSessionTranscript(sessionId)?.transcript
        check(existing?.nightId == nightId && existing.state == ProcessingState.COMPLETE) {
            "Only a completed transcription can be re-transcribed."
        }

        val startedAt = clock().coerceAtLeast(0L)
        activeSessionId.set(sessionId)
        val activeProgress = readProgress(nightId)
        var finalProgress = activeProgress.copy(activeSessionId = null)
        publish(onProgress, activeProgress)
        try {
            val audioFile = resolveAudioFile(
                nightId = nightId,
                audioFileName = session.audioFileName,
            )
            val result = engine.transcribe(
                audioFile = audioFile,
                input = preparedTranscriptionInput(
                    audioFile = audioFile,
                    session = session,
                    triggeringWakePhrase = triggeringWakePhrase(source, session.sessionId),
                ),
            )
            check(
                transcriptionDao.replaceCompletedSession(
                    sessionId = sessionId,
                    provenance = engine.metadata.toProvenance(),
                    rawText = result.rawText,
                    segments = result.segments.map { segment ->
                        TranscriptSegmentDraft(
                            sourceStartMillis = segment.sourceStartMillis,
                            sourceEndMillis = segment.sourceEndMillis,
                            text = segment.text,
                        )
                    },
                    startedAtEpochMillis = startedAt,
                    completedAtEpochMillis = maxOf(startedAt, clock()),
                ),
            ) { "The completed transcript could not be replaced." }
        } finally {
            activeSessionId.compareAndSet(sessionId, null)
            // A status-read failure after the transaction must not misreport a successful
            // replacement as an inference failure. Counts do not change during replacement, so
            // the pre-inference snapshot is a safe fallback once its active marker is cleared.
            finalProgress = runCatching { readProgress(nightId) }
                .getOrElse { activeProgress.copy(activeSessionId = null) }
            publish(onProgress, finalProgress)
        }
        finalProgress
    }

    /**
     * Re-runs every completed retained session, then replaces the night in one Room transaction.
     * No transcript or generated Dream is changed unless all model calls finish successfully.
     */
    fun retranscribeNight(
        nightId: String,
        onProgress: (NightTranscriptionProgress) -> Unit = {},
    ): NightTranscriptionProgress = runExclusive {
        requireEndedNight(nightId)
        recoverInterruptedAttemptsLocked()
        transcriptionDao.reconcileNightState(nightId)

        val source = requireEndedNight(nightId)
        val sessions = eligibleSessions(source)
        check(sessions.isNotEmpty() && sessions.size == source.sessions.size) {
            "Every session in this night must have finalized retained audio before it can be re-transcribed."
        }
        val transcriptsBySession = source.transcripts.associateBy { it.transcript.sessionId }
        check(transcriptsBySession.keys == sessions.mapTo(mutableSetOf()) { it.sessionId }) {
            "Every retained session must already have a completed transcript."
        }
        check(transcriptsBySession.values.all { it.transcript.state == ProcessingState.COMPLETE }) {
            "Every retained session must already have a completed transcript."
        }

        var fallbackProgress: NightTranscriptionProgress? = null
        val replacements = sessions.map { session ->
            val startedAt = clock().coerceAtLeast(0L)
            activeSessionId.set(session.sessionId)
            val activeProgress = readProgress(nightId)
            fallbackProgress = activeProgress.copy(activeSessionId = null)
            publish(onProgress, activeProgress)
            val audioFile = resolveAudioFile(
                nightId = nightId,
                audioFileName = session.audioFileName,
            )
            val result = engine.transcribe(
                audioFile = audioFile,
                input = preparedTranscriptionInput(
                    audioFile = audioFile,
                    session = session,
                    triggeringWakePhrase = triggeringWakePhrase(source, session.sessionId),
                ),
            )
            activeSessionId.compareAndSet(session.sessionId, null)
            SessionTranscriptReplacementDraft(
                sessionId = session.sessionId,
                rawText = result.rawText,
                segments = result.segments.map { segment ->
                    TranscriptSegmentDraft(
                        sourceStartMillis = segment.sourceStartMillis,
                        sourceEndMillis = segment.sourceEndMillis,
                        text = segment.text,
                    )
                },
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = maxOf(startedAt, clock()),
            )
        }
        check(
            transcriptionDao.replaceCompletedNight(
                nightId = nightId,
                provenance = engine.metadata.toProvenance(),
                replacements = replacements,
            ),
        ) { "The completed night transcripts could not be replaced." }

        val finalProgress = runCatching { readProgress(nightId) }
            .getOrElse { checkNotNull(fallbackProgress) }
        publish(onProgress, finalProgress)
        finalProgress
    }

    /**
     * Convert attempts left running by an earlier app process into explicit retryable failures.
     * Call this when the app opens, before starting any transcription call.
     */
    fun recoverInterruptedAttempts(): Int = runExclusive {
        recoverInterruptedAttemptsLocked()
    }

    fun readProgress(nightId: String): NightTranscriptionProgress {
        val source = requireNotNull(nightDao.readNight(nightId)) {
            "The requested night does not exist."
        }
        val eligible = eligibleSessions(source)
        val transcriptBySession = source.transcripts.associateBy { it.transcript.sessionId }
        val states = eligible.mapNotNull { transcriptBySession[it.sessionId]?.transcript?.state }
        val completeCount = states.count { it == ProcessingState.COMPLETE }
        val failedCount = states.count { it == ProcessingState.FAILED }
        val runningCount = states.count { it == ProcessingState.RUNNING }
        val retryableSessionIds = eligible.mapNotNull { session ->
            session.sessionId.takeIf {
                transcriptBySession[it]?.transcript?.state == ProcessingState.FAILED
            }
        }
        return NightTranscriptionProgress(
            nightId = nightId,
            persistedState = source.night.transcriptionState,
            eligibleSessionCount = eligible.size,
            unavailableSessionCount = source.sessions.size - eligible.size,
            completedSessionCount = completeCount,
            failedSessionCount = failedCount,
            runningSessionCount = runningCount,
            pendingSessionCount = (
                eligible.size - completeCount - failedCount - runningCount
                ).coerceAtLeast(0),
            activeSessionId = activeSessionId.get().takeIf { active ->
                active != null && source.sessions.any { it.sessionId == active }
            },
            retryableSessionIds = retryableSessionIds,
            requiresAppToRemainOpen = requiresAppToRemainOpen,
            pauseReason = null,
            pauseMessage = null,
        )
    }

    override fun close() {
        check(!runActive.get()) { "Cannot close the transcription engine during a run." }
        try {
            speechBoundaryAnalyzer?.close()
        } finally {
            engine.close()
        }
    }

    private fun processSession(
        nightId: String,
        session: CaptureSessionEntity,
        triggeringWakePhrase: TriggeringWakePhrase?,
        retry: Boolean,
        onProgress: (NightTranscriptionProgress) -> Unit,
    ) {
        val startedAt = clock().coerceAtLeast(0L)
        val provenance = engine.metadata.toProvenance()
        val claimed = if (retry) {
            transcriptionDao.retrySession(
                sessionId = session.sessionId,
                provenance = provenance,
                startedAtEpochMillis = startedAt,
            )
        } else {
            transcriptionDao.startSession(
                sessionId = session.sessionId,
                provenance = provenance,
                startedAtEpochMillis = startedAt,
            )
        }
        if (!claimed) return

        activeSessionId.set(session.sessionId)
        publishBestEffort(onProgress, nightId)
        try {
            val audioFile = resolveAudioFile(
                nightId = nightId,
                audioFileName = session.audioFileName,
            )
            val result = engine.transcribe(
                audioFile = audioFile,
                input = preparedTranscriptionInput(
                    audioFile = audioFile,
                    session = session,
                    triggeringWakePhrase = triggeringWakePhrase,
                ),
            )
            check(
                transcriptionDao.markSessionSucceeded(
                    sessionId = session.sessionId,
                    rawText = result.rawText,
                    segments = result.segments.map { segment ->
                        TranscriptSegmentDraft(
                            sourceStartMillis = segment.sourceStartMillis,
                            sourceEndMillis = segment.sourceEndMillis,
                            text = segment.text,
                        )
                    },
                    completedAtEpochMillis = maxOf(startedAt, clock()),
                ),
            ) { "The claimed transcription attempt could not be completed." }
        } catch (failure: Exception) {
            transcriptionDao.markSessionFailed(
                sessionId = session.sessionId,
                failureDetail = failureDetail(failure),
                completedAtEpochMillis = maxOf(startedAt, clock()),
            )
        } finally {
            activeSessionId.compareAndSet(session.sessionId, null)
            publishBestEffort(onProgress, nightId)
        }
    }

    private fun requireEndedNight(nightId: String): NightWithDetails {
        val source = requireNotNull(nightDao.readNight(nightId)) {
            "The requested night does not exist."
        }
        check(
            source.night.captureState == NightCaptureState.ENDED ||
                source.night.captureState == NightCaptureState.INTERRUPTED,
        ) { "Transcription cannot run while night capture is active." }
        check(source.night.endedAtEpochMillis != null) {
            "Transcription requires a finalized night end timestamp."
        }
        return source
    }

    private fun eligibleSessions(source: NightWithDetails): List<CaptureSessionEntity> =
        source.sessions
            .asSequence()
            .filter { session ->
                session.audioState == AudioEvidenceState.RETAINED &&
                    session.finalizedAtEpochMillis != null
            }
            .sortedWith(
                compareBy<CaptureSessionEntity> { it.captureOrder }
                    .thenBy { it.startedAtEpochMillis ?: Long.MAX_VALUE }
                    .thenBy { it.sessionId },
            )
            .toList()

    private fun transcriptionInput(
        session: CaptureSessionEntity,
        triggeringWakePhrase: TriggeringWakePhrase?,
    ): TranscriptionInput {
        val sessionEnd = session.sampleCount
        val cueEnd = session.cueEndSampleExclusive?.takeIf { cueEnd ->
            cueEnd >= 0L && (sessionEnd == null || cueEnd <= sessionEnd)
        }
        if (cueEnd != null) {
            val cueStart = session.cueStartSample?.takeIf { cueStart ->
                cueStart >= 0L && cueStart <= cueEnd
            }
            val postCueSampleCount = sessionEnd?.minus(cueEnd)
            val observedOnlySilenceAfterCue =
                session.incompleteReason == null &&
                    postCueSampleCount != null &&
                    postCueSampleCount >= 0L &&
                    session.automaticSilenceTailSampleCount?.let { silenceTail ->
                        silenceTail >= postCueSampleCount
                    } == true
            if (observedOnlySilenceAfterCue) {
                return TranscriptionInput(
                    acousticRange = Pcm16WavSource.RecognitionRange(
                        startSample = cueEnd,
                        endSampleExclusive = cueEnd,
                    ),
                    contentStartSample = cueEnd,
                )
            }

            // Decode the complete retained wake context when its exact session-linked phrase is
            // known. The cue start is the conservative fallback boundary: the wake phrase is
            // before it, while narration aligned slightly into the cue interval is preserved.
            // A cue is non-speech, so it contributes acoustic context but no transcript words.
            val contextualPhrase = triggeringWakePhrase.takeIf { cueStart != null }
            val contentStart = cueStart ?: cueEnd
            return TranscriptionInput(
                acousticRange = Pcm16WavSource.RecognitionRange(
                    startSample = if (contextualPhrase == null) contentStart else 0L,
                    endSampleExclusive = sessionEnd,
                ),
                contentStartSample = contentStart,
                triggeringWakePhrase = contextualPhrase,
                openingRecoveryFloorSample = cueEnd,
            )
        }

        // Legacy sessions may not have a persisted cue-end offset. Preserve their bounded
        // fallback rather than guessing a post-cue boundary that was never recorded.
        // preRollSampleCount is the KWS-report position, not the acoustic end of the trigger.
        // With one exact session-linked phrase, decode the complete retained prefix and let the
        // recognizer remove only that marker; this preserves narration spoken immediately after
        // the phrase but before KWS reported it. Missing/ambiguous event evidence keeps the narrow
        // post-detection range. Cue start remains only a legacy fallback. Always keep the complete
        // finalized tail because a VAD threshold cannot recover quiet sleepy speech after cropping.
        val postDetectionBoundary = session.preRollSampleCount?.takeIf { it > 0L }
        val legacyStart = session.cueStartSample ?: 0L
        val contextualPhrase = triggeringWakePhrase.takeIf { postDetectionBoundary != null }
        val contentStart = postDetectionBoundary ?: legacyStart
        return TranscriptionInput(
            acousticRange = Pcm16WavSource.RecognitionRange(
                startSample = if (contextualPhrase == null) contentStart else 0L,
                endSampleExclusive = sessionEnd,
            ),
            contentStartSample = contentStart,
            triggeringWakePhrase = contextualPhrase,
        )
    }

    /** Adds non-speech cut hints only when the selected engine needs bounded serial decoding. */
    private fun preparedTranscriptionInput(
        audioFile: File,
        session: CaptureSessionEntity,
        triggeringWakePhrase: TriggeringWakePhrase?,
    ): TranscriptionInput {
        val input = transcriptionInput(session, triggeringWakePhrase)
        val maximumDecodeSamples = engine.maximumDecodeSampleCount ?: return input
        val resolvedEndSample = input.acousticRange.endSampleExclusive
            ?: Pcm16WavSource.open(audioFile).sampleCount
        if (resolvedEndSample - input.acousticRange.startSample <= maximumDecodeSamples) {
            return input
        }

        val observedRanges = try {
            speechBoundaryAnalyzer
                ?.analyze(audioFile, input.acousticRange.startSample)
                ?.nonSpeechRanges
                .orEmpty()
        } catch (_: Exception) {
            // Boundary hints improve cut placement but never own source coverage. The engine's
            // deterministic hard bound remains safe when analysis is unavailable.
            emptyList()
        }
        val boundedRanges = observedRanges.mapNotNull { range ->
            val start = maxOf(range.startSample, input.acousticRange.startSample)
            val end = minOf(range.endSampleExclusive, resolvedEndSample)
            if (end > start) SessionNonSpeechRange(start, end) else null
        }
        return input.copy(observedNonSpeechRanges = boundedRanges)
    }

    /**
     * Returns the exact approved trigger persisted for this session, or null when journal evidence
     * is missing, malformed, unknown, or ambiguous. The transcription engine then falls back to
     * the strict post-detection timestamp boundary rather than guessing from owner speech.
     */
    private fun triggeringWakePhrase(
        source: NightWithDetails,
        sessionId: String,
    ): TriggeringWakePhrase? {
        val matchingEvents = source.events.asSequence()
            .filter { event ->
                event.type == SESSION_STARTED_EVENT && event.sessionId == sessionId
            }
            .toList()
        val event = matchingEvents.singleOrNull() ?: return null
        val persistedId = decodeEventAttribute(event.encodedAttributes, PHRASE_ATTRIBUTE)
            ?: return null
        return TriggeringWakePhrase.entries.singleOrNull { it.persistedId == persistedId }
    }

    private fun decodeEventAttribute(encodedAttributes: String, key: String): String? =
        runCatching {
            encodedAttributes.split(';')
                .asSequence()
                .mapNotNull { item ->
                    val separator = item.indexOf('=')
                    if (separator <= 0 || item.substring(0, separator) != key) {
                        null
                    } else {
                        String(
                            Base64.getUrlDecoder().decode(item.substring(separator + 1)),
                            StandardCharsets.UTF_8,
                        )
                    }
                }
                .toList()
                .singleOrNull()
        }.getOrNull()

    private fun resolveAudioFile(
        nightId: String,
        audioFileName: String,
    ): File {
        val root = audioRootDirectory.canonicalFile
        check(root.isDirectory) { "The retained-audio directory is unavailable." }
        val nightDirectory = File(root, nightId).canonicalFile
        check(nightDirectory.parentFile == root && nightDirectory.isDirectory) {
            "The retained-audio night directory is unavailable."
        }
        val audioFile = File(nightDirectory, audioFileName).canonicalFile
        check(audioFile.parentFile == nightDirectory && audioFile.isFile) {
            "The retained session audio is unavailable."
        }
        return audioFile
    }

    private fun recoverInterruptedAttemptsLocked(): Int {
        return attemptRecovery.recover()
    }

    private inline fun <T> runExclusive(block: () -> T): T {
        check(runActive.compareAndSet(false, true)) {
            "Another transcription call is already running."
        }
        return try {
            block()
        } finally {
            activeSessionId.set(null)
            runActive.set(false)
        }
    }

    private fun publish(
        callback: (NightTranscriptionProgress) -> Unit,
        progress: NightTranscriptionProgress,
    ) {
        runCatching { callback(progress) }
    }

    /** Progress refresh is presentation-only and must never change a persisted attempt outcome. */
    private fun publishBestEffort(
        callback: (NightTranscriptionProgress) -> Unit,
        nightId: String,
    ) {
        try {
            publish(callback, readProgress(nightId))
        } catch (_: Exception) {
            // The durable attempt outcome must not depend on a presentation-only refresh.
        }
    }

    private fun TranscriptionEngineMetadata.toProvenance() = TranscriptionProvenance(
        localeTag = localeTag,
        engineId = engineId,
        engineVersion = engineVersion,
        runtimeId = runtimeId,
        runtimeVersion = runtimeVersion,
        modelId = modelId,
        modelVersion = modelVersion,
        modelSha256 = modelSha256,
    )

    private fun failureDetail(failure: Exception): String {
        val code = when (failure) {
            is SecurityException -> "source_access_denied"
            is IOException -> "source_read_failed"
            is IllegalArgumentException -> "input_invalid"
            is IllegalStateException -> "local_inference_failed"
            else -> "unexpected_runtime_failure"
        }
        return "Local transcription failed ($code). The retained audio was kept; " +
            "resume transcription."
    }

    private companion object {
        const val SESSION_STARTED_EVENT = "session_started"
        const val PHRASE_ATTRIBUTE = "phrase"
    }
}

data class NightTranscriptionProgress(
    val nightId: String,
    val persistedState: String,
    val eligibleSessionCount: Int,
    val unavailableSessionCount: Int,
    val completedSessionCount: Int,
    val failedSessionCount: Int,
    val runningSessionCount: Int,
    val pendingSessionCount: Int,
    val activeSessionId: String?,
    val retryableSessionIds: List<String>,
    val requiresAppToRemainOpen: Boolean,
    val pauseReason: TranscriptionPauseReason? = null,
    val pauseMessage: String? = null,
    val platformThermalStatus: Int? = null,
    val batteryTemperatureCelsius: Double? = null,
) {
    val canRetry: Boolean
        get() = retryableSessionIds.isNotEmpty()
}

private fun NightTranscriptionProgress.withDeferral(
    deferral: TranscriptionContinuationDecision.Defer?,
): NightTranscriptionProgress = if (deferral == null) {
    this
} else {
    copy(
        pauseReason = deferral.reason,
        pauseMessage = deferral.message,
        platformThermalStatus = deferral.signal?.platformStatus,
        batteryTemperatureCelsius =
            deferral.signal?.batteryTemperatureDeciCelsius?.div(10.0),
    )
}
