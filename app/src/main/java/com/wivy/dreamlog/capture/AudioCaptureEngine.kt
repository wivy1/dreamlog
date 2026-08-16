package com.wivy.dreamlog.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RawRes
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun interface AudioCaptureSessionSink {
    /**
     * Starts the only kind of persisted audio DreamLog creates: one
     * wake-triggered narrative containing the ordered memory-only pre-roll.
     */
    fun startSession(
        preRollPcm16: ShortArray,
        startedAtEpochMillis: Long,
    ): SessionAudioWriter.ActiveSession
}

fun SessionAudioWriter.asAudioCaptureSessionSink(): AudioCaptureSessionSink =
    AudioCaptureSessionSink { preRoll, startedAt ->
        startSession(preRoll, startedAt)
    }

fun interface AudioCaptureListener {
    /**
     * Calls are serialized, but may originate from the capture, Android audio
     * callback, or deadline-monitor thread.
     */
    fun onCaptureEvent(event: AudioCaptureEvent)
}

sealed interface AudioCaptureEvent {
    data class ReadinessChanged(
        val readiness: CaptureReadiness,
    ) : AudioCaptureEvent

    data class MicrophoneStateChanged(
        val ownConfiguration: ObservedRecordingConfiguration?,
        val visibleOtherRecorderCount: Int,
    ) : AudioCaptureEvent

    data class Heartbeat(
        val observedAtEpochMillis: Long,
        val capturedSamplePosition: Long,
        val readiness: CaptureReadiness,
        val clientSilenced: Boolean,
        val activeSessionId: String?,
        val keywordStreamProgress: KeywordStreamProgress,
    ) : AudioCaptureEvent {
        val ready: Boolean
            get() = readiness == CaptureReadiness.READY
    }

    data class WakeDetected(
        val phrase: ApprovedWakePhrase,
        val preRollSampleCount: Int,
        val detectedAtEpochMillis: Long,
    ) : AudioCaptureEvent

    data class WakeCandidateEvaluated(
        val telemetry: LiveKitWakeCandidateTelemetry,
        val evaluatedAtEpochMillis: Long,
    ) : AudioCaptureEvent

    data class SessionStarted(
        val sessionId: String,
        val phrase: ApprovedWakePhrase,
        val preRollSampleCount: Int,
        val startedAtEpochMillis: Long,
    ) : AudioCaptureEvent

    data class CueStarted(
        val sessionId: String,
        val sampleOffset: Long,
        val requestLatencyMillis: Long,
        val detectionToCueMillis: Long,
    ) : AudioCaptureEvent

    data class CueEnded(
        val sessionId: String,
        val sampleOffsetExclusive: Long,
        val renderedMillis: Long,
    ) : AudioCaptureEvent

    data class SessionFinalized(
        val phrase: ApprovedWakePhrase,
        val metadata: SessionAudioMetadata,
    ) : AudioCaptureEvent

    data class AudioGap(
        val discrepancyFrames: Long,
        val estimatedGapMillis: Long,
        val activeSessionId: String?,
        val sessionSampleOffset: Long?,
    ) : AudioCaptureEvent

    data object SafetyDeadlineReached : AudioCaptureEvent

    data class FatalFailure(
        val kind: CaptureFailureKind,
        val message: String,
        val cause: Throwable? = null,
    ) : AudioCaptureEvent
}

enum class CaptureReadiness {
    WAITING_FOR_RECORDING_CONFIGURATION,
    WAITING_FOR_VERIFIED_FRAME,
    READY,
    MICROPHONE_SILENCED,
    RECORDING_CONFIGURATION_MISMATCH,
    AUDIO_DISCONTINUITY,
    STOPPED,
}

data class ObservedRecordingConfiguration(
    val audioSource: Int,
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
    val clientSilenced: Boolean,
    val matchesSelectedInput: Boolean,
)

enum class ApprovedWakePhrase(
    val displayName: String,
) {
    DREAM_LOG("DreamLog"),
    HEY_DREAM_LOG("Hey DreamLog"),
}

enum class CaptureFailureKind {
    INITIALIZATION,
    AUDIO_READ,
    CUE_PLAYBACK,
    STORAGE_RESERVE,
    AUDIO_WRITE,
}

internal fun capturePersistenceFailureKind(incompleteReason: String): CaptureFailureKind =
    if (incompleteReason == SessionIncompleteReason.STORAGE_RESERVE_REACHED) {
        CaptureFailureKind.STORAGE_RESERVE
    } else {
        CaptureFailureKind.AUDIO_WRITE
    }

enum class CaptureStopReason(
    internal val incompleteReason: String,
) {
    NIGHT_ENDED(SessionIncompleteReason.NIGHT_ENDED),
    STORAGE_RESERVE_REACHED(SessionIncompleteReason.STORAGE_RESERVE_REACHED),
    SERVICE_INTERRUPTED(SessionIncompleteReason.SERVICE_INTERRUPTED),
}

/**
 * Sole owner of DreamLog's MIC / 16 kHz / mono / PCM16 AudioRecord stream.
 *
 * [run] is blocking and intended for one service-owned worker thread. Stop and
 * Android recording callbacks may arrive concurrently; session-writer calls
 * are serialized at captured-sample boundaries.
 */
class AudioCaptureEngine(
    context: Context,
    @RawRes private val cueRawResourceId: Int,
    private val sessionSink: AudioCaptureSessionSink,
    private val listener: AudioCaptureListener,
    private val continuousNonSpeechSeconds: Int =
        NarrativeBoundaryDetector.DEFAULT_CONTINUOUS_NON_SPEECH_SECONDS,
) : Closeable {
    constructor(
        context: Context,
        @RawRes cueRawResourceId: Int,
        sessionWriter: SessionAudioWriter,
        listener: AudioCaptureListener,
    ) : this(
        context = context,
        cueRawResourceId = cueRawResourceId,
        sessionSink = sessionWriter.asAudioCaptureSessionSink(),
        listener = listener,
        continuousNonSpeechSeconds =
            NarrativeBoundaryDetector.DEFAULT_CONTINUOUS_NON_SPEECH_SECONDS,
    )

    private val appContext = context.applicationContext
    private val runStarted = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val callbackEventsEnabled = AtomicBoolean(false)
    private val requestedDetectorReset =
        AtomicReference<KeywordStreamResetReason?>(null)
    private val discardPartialFrameRequested = AtomicBoolean(false)
    private val completedFrameCount = AtomicLong(0L)
    private val capturedSamplePosition = AtomicLong(0L)
    private val keywordStreamHealth = KeywordStreamHealthTracker()
    private val readyAfterFrame = AtomicLong(Long.MAX_VALUE)
    private val readiness = AtomicReference(
        CaptureReadiness.WAITING_FOR_RECORDING_CONFIGURATION,
    )
    private val listenerLock = Any()
    private val microphoneLock = Any()
    private val sessionLock = Any()
    private val sessionSequence = AtomicLong(0L)

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var cuePlayer: CuePlayer? = null

    @Volatile
    private var selectedConfigurationUsable = false

    @Volatile
    private var currentOwnConfiguration: ObservedRecordingConfiguration? = null

    @Volatile
    private var visibleOtherRecorderCount = 0

    private var activeSession: ManagedSession? = null
    private var cuePlaybackToken: Long? = null
    private var lastPublishedMicrophoneState: AudioCaptureEvent.MicrophoneStateChanged? = null

    @SuppressLint("MissingPermission")
    fun run() {
        check(runStarted.compareAndSet(false, true)) {
            "AudioCaptureEngine can run only once."
        }
        check(!closed.get() && !stopping.get()) {
            "AudioCaptureEngine was stopped before it ran."
        }

        val audioManager = appContext.getSystemService(AudioManager::class.java)
        var audioRecord: AudioRecord? = null
        var wakeWordDetector: LiveKitWakeWordDetector? = null
        var vad: Vad? = null
        var player: CuePlayer? = null
        var ownCallback: AudioManager.AudioRecordingCallback? = null
        var globalCallback: AudioManager.AudioRecordingCallback? = null
        var ownCallbackRegistered = false
        var globalCallbackRegistered = false
        var monitor: ScheduledExecutorService? = null

        publishReadiness(CaptureReadiness.WAITING_FOR_RECORDING_CONFIGURATION)
        try {
            wakeWordDetector = LiveKitWakeWordDetector.createDefault(appContext)
            vad = createVad()
            player = CuePlayer(appContext, cueRawResourceId)
            cuePlayer = player
            audioRecord = createAudioRecord()
            recorder = audioRecord

            ownCallback = ownRecordingCallback(audioRecord.audioSessionId)
            globalCallback = globalRecordingCallback(audioRecord.audioSessionId)
            callbackEventsEnabled.set(true)
            audioRecord.registerAudioRecordingCallback(
                appContext.mainExecutor,
                ownCallback,
            )
            ownCallbackRegistered = true
            audioManager.registerAudioRecordingCallback(
                globalCallback,
                Handler(Looper.getMainLooper()),
            )
            globalCallbackRegistered = true

            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter the recording state."
            }
            audioRecord.activeRecordingConfiguration?.let(::handleOwnConfiguration)
            handleVisibleRecorders(
                configs = audioManager.activeRecordingConfigurations,
                ownAudioSessionId = audioRecord.audioSessionId,
            )

            monitor = startIndependentMonitor()
            captureLoop(
                audioRecord = audioRecord,
                wakeWordDetector = wakeWordDetector,
                vad = vad,
                player = player,
            )
        } catch (failure: Throwable) {
            if (!stopping.get()) {
                stopAfterFatalFailure(
                    kind = CaptureFailureKind.INITIALIZATION,
                    message = "The selected on-device audio engine could not start.",
                    cause = failure,
                    incompleteReason = SessionIncompleteReason.CAPTURE_FAILED,
                )
            }
        } finally {
            callbackEventsEnabled.set(false)
            monitor?.shutdownNow()
            if (globalCallbackRegistered && globalCallback != null) {
                runCatching {
                    audioManager.unregisterAudioRecordingCallback(globalCallback)
                }
            }
            if (ownCallbackRegistered && ownCallback != null) {
                runCatching {
                    audioRecord?.unregisterAudioRecordingCallback(ownCallback)
                }
            }
            val remainingReason = requestedIncompleteReason.get()
                ?: SessionIncompleteReason.SERVICE_INTERRUPTED
            finalizeActiveSession(remainingReason)
            runCatching {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            audioRecord?.release()
            recorder = null
            wakeWordDetector?.close()
            vad?.release()
            player?.close()
            cuePlayer = null
            publishReadiness(CaptureReadiness.STOPPED)
        }
    }

    fun requestStop(reason: CaptureStopReason) {
        requestedIncompleteReason.compareAndSet(null, reason.incompleteReason)
        stopRecorderAndFinalize(reason.incompleteReason)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requestStop(CaptureStopReason.SERVICE_INTERRUPTED)
    }

    private fun captureLoop(
        audioRecord: AudioRecord,
        wakeWordDetector: LiveKitWakeWordDetector,
        vad: Vad,
        player: CuePlayer,
    ) {
        val pcmFrame = ShortArray(FRAME_SAMPLES)
        val floatFrame = FloatArray(FRAME_SAMPLES)
        val ringBuffer = ShortRingBuffer(PRE_ROLL_SAMPLES)
        val boundary = NarrativeBoundaryDetector(continuousNonSpeechSeconds)
        val timestamps = AudioTimestampGapDetector()
        var filledSamples = 0
        var lastTimestampPollElapsedMillis = 0L

        while (!stopping.get()) {
            val read = audioRecord.read(
                pcmFrame,
                filledSamples,
                FRAME_SAMPLES - filledSamples,
                AudioRecord.READ_BLOCKING,
            )
            if (read < 0) {
                filledSamples = 0
                if (!stopping.get()) {
                    stopAfterFatalFailure(
                        kind = CaptureFailureKind.AUDIO_READ,
                        message = "AudioRecord returned fatal read error $read.",
                        cause = null,
                        incompleteReason = SessionIncompleteReason.CAPTURE_FAILED,
                    )
                }
                break
            }
            if (read == 0) {
                filledSamples = 0
                if (stopping.get()) break
                SystemClock.sleep(ZERO_READ_BACKOFF_MILLIS)
                continue
            }
            if (stopping.get()) break
            if (discardPartialFrameRequested.getAndSet(false)) {
                filledSamples = 0
                continue
            }

            filledSamples += read
            if (filledSamples < FRAME_SAMPLES) continue
            filledSamples = 0

            capturedSamplePosition.addAndGet(FRAME_SAMPLES.toLong())
            val completedFrame = completedFrameCount.incrementAndGet()
            val nowElapsedMillis = SystemClock.elapsedRealtime()

            if (
                nowElapsedMillis - lastTimestampPollElapsedMillis >=
                AUDIO_TIMESTAMP_POLL_MILLIS
            ) {
                val gap = timestamps.observe(audioTimestamp(audioRecord))
                lastTimestampPollElapsedMillis = nowElapsedMillis
                if (gap != null) {
                    handleAudioGap(gap, completedFrame)
                }
            }

            requestedDetectorReset.getAndSet(null)?.let { resetReason ->
                ringBuffer.clear()
                resetWakeWordDetector(wakeWordDetector, resetReason)
                vad.reset()
                boundary.reset()
                timestamps.reset()
            }

            maybePublishReady(completedFrame)
            if (!selectedConfigurationUsable) {
                ringBuffer.clear()
                continue
            }

            for (index in pcmFrame.indices) {
                floatFrame[index] = pcmFrame[index] / PCM16_SCALE
            }

            val activeToken = activeSessionToken()
            if (activeToken != null) {
                when (appendActiveFrame(activeToken, pcmFrame)) {
                    AppendResult.APPENDED -> {
                        if (isCuePending(activeToken)) {
                            vad.reset()
                            boundary.onCue()
                        } else {
                            val probability = vad.compute(floatFrame)
                            val narrativeComplete =
                                if (probability >= VAD_THRESHOLD) {
                                    boundary.onSpeech()
                                    false
                                } else {
                                    boundary.onNonSpeech(FRAME_SAMPLES)
                                }
                            if (narrativeComplete) {
                                val result = finalizeActiveSession(
                                    incompleteReason = null,
                                    automaticSilenceTailSampleCount =
                                        boundary.consecutiveNonSpeechSamples,
                                )
                                if (result?.failure != null) {
                                    stopForStorageFailure(
                                        message = "The completed narrative could not be finalized.",
                                        failure = result.failure,
                                    )
                                    break
                                }
                                ringBuffer.clear()
                                resetWakeWordDetector(
                                    wakeWordDetector,
                                    KeywordStreamResetReason.NARRATIVE_COMPLETED,
                                )
                                vad.reset()
                                boundary.reset()
                            }
                        }
                    }

                    AppendResult.NO_ACTIVE_SESSION -> {
                        ringBuffer.clear()
                        resetWakeWordDetector(
                            wakeWordDetector,
                            KeywordStreamResetReason.SESSION_MISSING,
                        )
                        vad.reset()
                        boundary.reset()
                    }

                    AppendResult.STORAGE_FAILURE -> break
                }
                continue
            }

            ringBuffer.append(pcmFrame)
            if (
                readiness.get() != CaptureReadiness.READY ||
                isCuePlaybackInProgress()
            ) {
                continue
            }

            keywordStreamHealth.recordAcceptedFrame()
            val evaluations = wakeWordDetector.accept(pcmFrame)
            repeat(evaluations.size) {
                keywordStreamHealth.recordDecode()
            }
            evaluations.mapNotNull(LiveKitWakeWordEvaluation::candidateTelemetry).forEach {
                emit(
                    AudioCaptureEvent.WakeCandidateEvaluated(
                        telemetry = it,
                        evaluatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
            val phrase = evaluations.firstNotNullOfOrNull(
                LiveKitWakeWordEvaluation::detectedPhrase,
            ) ?: continue

            resetWakeWordDetector(
                wakeWordDetector,
                KeywordStreamResetReason.WAKE_DETECTED,
            )
            val preRoll = ringBuffer.snapshot()
            val detectedAtElapsedRealtimeNanos =
                SystemClock.elapsedRealtimeNanos()
            val detectedAtEpochMillis = System.currentTimeMillis()
            emit(
                AudioCaptureEvent.WakeDetected(
                    phrase = phrase,
                    preRollSampleCount = preRoll.size,
                    detectedAtEpochMillis = detectedAtEpochMillis,
                ),
            )
            if (
                !startNarrativeSession(
                    phrase = phrase,
                    preRoll = preRoll,
                    player = player,
                    detectedAtEpochMillis = detectedAtEpochMillis,
                    detectedAtElapsedRealtimeNanos =
                        detectedAtElapsedRealtimeNanos,
                )
            ) {
                break
            }
            // The detection frame is already the final frame in the ordered
            // ring snapshot passed to the writer. It is never appended again.
            boundary.reset()
            vad.reset()
        }
    }

    private fun requestDetectorReset(reason: KeywordStreamResetReason) {
        requestedDetectorReset.set(reason)
    }

    private fun resetWakeWordDetector(
        wakeWordDetector: LiveKitWakeWordDetector,
        reason: KeywordStreamResetReason,
    ) {
        wakeWordDetector.reset()
        keywordStreamHealth.recordReset(reason)
    }

    private fun startNarrativeSession(
        phrase: ApprovedWakePhrase,
        preRoll: ShortArray,
        player: CuePlayer,
        detectedAtEpochMillis: Long,
        detectedAtElapsedRealtimeNanos: Long,
    ): Boolean {
        val requiredStartBytes =
            WAV_HEADER_BYTES + preRoll.size.toLong() * PCM16_BYTES
        if (
            !StorageGuard.canWrite(
                availableBytes = appContext.filesDir.usableSpace,
                requestedBytes = requiredStartBytes,
            )
        ) {
            stopForStorageFailure(
                message = "The protected 1 GiB storage reserve has been reached.",
                failure = StorageReserveReachedException(),
                incompleteReason = SessionIncompleteReason.STORAGE_RESERVE_REACHED,
            )
            return false
        }
        val writer = try {
            sessionSink.startSession(preRoll, detectedAtEpochMillis)
        } catch (failure: Throwable) {
            stopForStorageFailure(
                message = "A narrative audio file could not be started.",
                failure = failure,
            )
            return false
        }
        val token = sessionSequence.incrementAndGet()
        synchronized(sessionLock) {
            check(activeSession == null) {
                "Wake detection attempted to overlap an active narrative."
            }
            activeSession = ManagedSession(
                token = token,
                phrase = phrase,
                writer = writer,
                detectedAtElapsedRealtimeNanos =
                    detectedAtElapsedRealtimeNanos,
                cuePending = true,
            )
            cuePlaybackToken = token
        }
        emit(
            AudioCaptureEvent.SessionStarted(
                sessionId = writer.sessionId,
                phrase = phrase,
                preRollSampleCount = preRoll.size,
                startedAtEpochMillis = detectedAtEpochMillis,
            ),
        )

        if (!selectedConfigurationUsable) {
            finalizeActiveSession(SessionIncompleteReason.MICROPHONE_SILENCED)
            return true
        }

        try {
            player.play(
                onFirstFrame = { marker ->
                    markCueStarted(token, marker)
                },
                onComplete = { marker ->
                    markCueEnded(token, marker)
                },
            )
        } catch (failure: Throwable) {
            synchronized(sessionLock) {
                if (cuePlaybackToken == token) cuePlaybackToken = null
            }
            finalizeActiveSession(SessionIncompleteReason.CAPTURE_FAILED)
            stopAfterFatalFailure(
                kind = CaptureFailureKind.CUE_PLAYBACK,
                message = "The acknowledgement cue could not be played.",
                cause = failure,
                incompleteReason = SessionIncompleteReason.CAPTURE_FAILED,
            )
            return false
        }

        Handler(Looper.getMainLooper()).postDelayed(
            {
                if (isCuePending(token)) {
                    markCueEnded(
                        token = token,
                        marker = CuePlaybackMarker(
                            elapsedRealtimeNanos =
                                SystemClock.elapsedRealtimeNanos(),
                            elapsedSinceRequestMillis =
                                player.durationMillis +
                                    CUE_COMPLETION_GRACE_MILLIS,
                        ),
                    )
                }
            },
            player.durationMillis + CUE_COMPLETION_GRACE_MILLIS,
        )
        return true
    }

    private fun appendActiveFrame(
        token: Long,
        frame: ShortArray,
    ): AppendResult {
        var failure: Throwable? = null
        var finalized: FinalizationResult? = null
        var incompleteReason = SessionIncompleteReason.WRITE_FAILED
        synchronized(sessionLock) {
            val active = activeSession?.takeIf { it.token == token }
                ?: return AppendResult.NO_ACTIVE_SESSION
            try {
                if (
                    !StorageGuard.canWrite(
                        availableBytes = appContext.filesDir.usableSpace,
                        requestedBytes = frame.size.toLong() * PCM16_BYTES,
                    )
                ) {
                    incompleteReason =
                        SessionIncompleteReason.STORAGE_RESERVE_REACHED
                    throw StorageReserveReachedException()
                }
                active.writer.append(frame)
            } catch (writeFailure: Throwable) {
                activeSession = null
                failure = writeFailure
                finalized = finalizeWriterLocked(
                    active = active,
                    incompleteReason = incompleteReason,
                    automaticSilenceTailSampleCount = null,
                )
            }
        }
        finalized?.publish()
        failure?.let { writeFailure ->
            finalized?.failure?.let(writeFailure::addSuppressed)
            stopForStorageFailure(
                message =
                    if (
                        incompleteReason ==
                        SessionIncompleteReason.STORAGE_RESERVE_REACHED
                    ) {
                        "The protected 1 GiB storage reserve has been reached."
                    } else {
                        "Narrative audio writing was interrupted."
                    },
                failure = writeFailure,
                incompleteReason = incompleteReason,
            )
            return AppendResult.STORAGE_FAILURE
        }
        return AppendResult.APPENDED
    }

    private fun markCueStarted(
        token: Long,
        marker: CuePlaybackMarker,
    ) {
        var event: AudioCaptureEvent.CueStarted? = null
        var failure: Throwable? = null
        synchronized(sessionLock) {
            val active = activeSession?.takeIf { it.token == token } ?: return
            try {
                val offset = active.writer.markCueStart()
                event = AudioCaptureEvent.CueStarted(
                    sessionId = active.writer.sessionId,
                    sampleOffset = offset,
                    requestLatencyMillis = marker.elapsedSinceRequestMillis,
                    detectionToCueMillis = (
                        marker.elapsedRealtimeNanos -
                            active.detectedAtElapsedRealtimeNanos
                        ).coerceAtLeast(0L) / NANOS_PER_MILLISECOND,
                )
            } catch (writeFailure: Throwable) {
                failure = writeFailure
            }
        }
        event?.let(::emit)
        failure?.let {
            handleCueMarkerStorageFailure(
                message = "The cue start sample offset could not be recorded.",
                failure = it,
            )
        }
    }

    private fun markCueEnded(
        token: Long,
        marker: CuePlaybackMarker,
    ) {
        var event: AudioCaptureEvent.CueEnded? = null
        var failure: Throwable? = null
        synchronized(sessionLock) {
            val active = activeSession?.takeIf { it.token == token }
            if (active != null) {
                if (!active.cuePending) return
                if (cuePlaybackToken == token) cuePlaybackToken = null
                try {
                    val offset = active.writer.markCueEnd()
                    active.cuePending = false
                    event = AudioCaptureEvent.CueEnded(
                        sessionId = active.writer.sessionId,
                        sampleOffsetExclusive = offset,
                        renderedMillis = marker.elapsedSinceRequestMillis,
                    )
                } catch (writeFailure: Throwable) {
                    failure = writeFailure
                }
            } else {
                if (cuePlaybackToken == token) cuePlaybackToken = null
                requestDetectorReset(KeywordStreamResetReason.CUE_SESSION_ENDED)
            }
        }
        event?.let(::emit)
        failure?.let {
            handleCueMarkerStorageFailure(
                message = "The cue end sample offset could not be recorded.",
                failure = it,
            )
        }
    }

    private fun handleCueMarkerStorageFailure(
        message: String,
        failure: Throwable,
    ) {
        val finalized = finalizeActiveSession(SessionIncompleteReason.WRITE_FAILED)
        finalized?.failure?.let(failure::addSuppressed)
        stopForStorageFailure(message, failure)
    }

    private fun handleAudioGap(
        gap: TimestampGap,
        completedFrame: Long,
    ) {
        val activeCheckpoint = synchronized(sessionLock) {
            activeSession?.also { active ->
                active.audioGapObserved = true
            }?.writer?.checkpoint()
        }
        emit(
            AudioCaptureEvent.AudioGap(
                discrepancyFrames = gap.discrepancyFrames,
                estimatedGapMillis = gap.estimatedGapMillis,
                activeSessionId = activeCheckpoint?.sessionId,
                sessionSampleOffset = activeCheckpoint?.sampleCount,
            ),
        )
        requestDetectorReset(KeywordStreamResetReason.AUDIO_DISCONTINUITY)
        publishReadiness(CaptureReadiness.AUDIO_DISCONTINUITY)
        if (selectedConfigurationUsable) {
            readyAfterFrame.set(completedFrame + 1L)
            publishReadiness(CaptureReadiness.WAITING_FOR_VERIFIED_FRAME)
        }
    }

    private fun finalizeActiveSession(
        incompleteReason: String?,
        automaticSilenceTailSampleCount: Long? = null,
    ): FinalizationResult? {
        val result = synchronized(sessionLock) {
            val active = activeSession ?: return null
            activeSession = null
            finalizeWriterLocked(
                active = active,
                incompleteReason = incompleteReason,
                automaticSilenceTailSampleCount = automaticSilenceTailSampleCount,
            )
        }
        requestDetectorReset(KeywordStreamResetReason.SESSION_FINALIZED)
        result.publish()
        return result
    }

    private fun finalizeWriterLocked(
        active: ManagedSession,
        incompleteReason: String?,
        automaticSilenceTailSampleCount: Long?,
    ): FinalizationResult =
        try {
            val plan = resolveSessionFinalization(
                audioGapObserved = active.audioGapObserved,
                requestedIncompleteReason = incompleteReason,
                automaticSilenceTailSampleCount =
                    automaticSilenceTailSampleCount,
            )
            val metadata = if (plan.incompleteReason == null) {
                active.writer.finalizeComplete(
                    automaticSilenceTailSampleCount =
                        checkNotNull(plan.automaticSilenceTailSampleCount),
                )
            } else {
                active.writer.finalizeIncomplete(plan.incompleteReason)
            }
            FinalizationResult(
                phrase = active.phrase,
                metadata = metadata,
                failure = null,
            )
        } catch (failure: Throwable) {
            FinalizationResult(
                phrase = active.phrase,
                metadata = null,
                failure = failure,
            )
        }

    private fun FinalizationResult.publish() {
        metadata?.let {
            emit(
                AudioCaptureEvent.SessionFinalized(
                    phrase = phrase,
                    metadata = it,
                ),
            )
        }
    }

    private fun stopForStorageFailure(
        message: String,
        failure: Throwable,
        incompleteReason: String = SessionIncompleteReason.WRITE_FAILED,
    ) {
        requestedIncompleteReason.compareAndSet(
            null,
            incompleteReason,
        )
        stopping.set(true)
        stopRecorder()
        emit(
            AudioCaptureEvent.FatalFailure(
                kind = capturePersistenceFailureKind(incompleteReason),
                message = message,
                cause = failure,
            ),
        )
    }

    private fun stopAfterFatalFailure(
        kind: CaptureFailureKind,
        message: String,
        cause: Throwable?,
        incompleteReason: String,
    ) {
        requestedIncompleteReason.compareAndSet(null, incompleteReason)
        stopping.set(true)
        stopRecorder()
        val finalized = finalizeActiveSession(incompleteReason)
        val reportedCause = cause ?: finalized?.failure
        if (cause != null && finalized?.failure != null) {
            cause.addSuppressed(finalized.failure)
        }
        emit(
            AudioCaptureEvent.FatalFailure(
                kind = kind,
                message = message,
                cause = reportedCause,
            ),
        )
    }

    private fun stopRecorderAndFinalize(incompleteReason: String) {
        if (!stopping.compareAndSet(false, true)) return
        stopRecorder()
        finalizeActiveSession(incompleteReason)
    }

    private fun stopRecorder() {
        recorder?.let { activeRecorder ->
            runCatching {
                if (activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    activeRecorder.stop()
                }
            }
        }
    }

    private fun startIndependentMonitor(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, MONITOR_THREAD_NAME).apply {
                isDaemon = true
            }
        }.also { scheduler ->
            scheduler.scheduleAtFixedRate(
                { publishHeartbeat() },
                HEARTBEAT_MILLIS,
                HEARTBEAT_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            scheduler.schedule(
                {
                    if (!stopping.get()) {
                        emit(AudioCaptureEvent.SafetyDeadlineReached)
                        requestedIncompleteReason.compareAndSet(
                            null,
                            SessionIncompleteReason.SAFETY_STOP,
                        )
                        stopRecorderAndFinalize(SessionIncompleteReason.SAFETY_STOP)
                    }
                },
                SAFETY_DEADLINE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

    private fun publishHeartbeat() {
        if (stopping.get()) return
        val sessionId = synchronized(sessionLock) {
            activeSession?.writer?.sessionId
        }
        val silenced = synchronized(microphoneLock) {
            currentOwnConfiguration?.clientSilenced == true
        }
        emit(
            AudioCaptureEvent.Heartbeat(
                observedAtEpochMillis = System.currentTimeMillis(),
                capturedSamplePosition = capturedSamplePosition.get(),
                readiness = readiness.get(),
                clientSilenced = silenced,
                activeSessionId = sessionId,
                keywordStreamProgress = keywordStreamHealth.snapshot(),
            ),
        )
    }

    private fun ownRecordingCallback(
        audioSessionId: Int,
    ): AudioManager.AudioRecordingCallback =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(
                configs: MutableList<AudioRecordingConfiguration>,
            ) {
                if (!callbackEventsEnabled.get()) return
                val own = configs.firstOrNull {
                    it.clientAudioSessionId == audioSessionId
                }
                if (own == null) {
                    handleMissingOwnConfiguration()
                } else {
                    handleOwnConfiguration(own)
                }
            }
        }

    private fun globalRecordingCallback(
        ownAudioSessionId: Int,
    ): AudioManager.AudioRecordingCallback =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(
                configs: MutableList<AudioRecordingConfiguration>,
            ) {
                if (!callbackEventsEnabled.get()) return
                handleVisibleRecorders(configs, ownAudioSessionId)
            }
        }

    private fun handleOwnConfiguration(
        configuration: AudioRecordingConfiguration,
    ) {
        if (!callbackEventsEnabled.get()) return
        val clientFormat = configuration.clientFormat
        val matchesSelectedInput =
            configuration.clientAudioSource == MediaRecorder.AudioSource.MIC &&
                clientFormat.sampleRate == SAMPLE_RATE_HZ &&
                clientFormat.channelCount == CHANNEL_COUNT &&
                clientFormat.encoding == AudioFormat.ENCODING_PCM_16BIT
        val observed = ObservedRecordingConfiguration(
            audioSource = configuration.clientAudioSource,
            sampleRateHz = clientFormat.sampleRate,
            channelCount = clientFormat.channelCount,
            encoding = clientFormat.encoding,
            clientSilenced = configuration.isClientSilenced,
            matchesSelectedInput = matchesSelectedInput,
        )

        val previousUsable: Boolean
        val nowUsable = matchesSelectedInput && !configuration.isClientSilenced
        synchronized(microphoneLock) {
            previousUsable = selectedConfigurationUsable
            currentOwnConfiguration = observed
            selectedConfigurationUsable = nowUsable
        }
        publishMicrophoneState()

        when {
            configuration.isClientSilenced -> {
                discardPartialFrameRequested.set(true)
                readyAfterFrame.set(Long.MAX_VALUE)
                publishReadiness(CaptureReadiness.MICROPHONE_SILENCED)
                finalizeActiveSession(SessionIncompleteReason.MICROPHONE_SILENCED)
                requestDetectorReset(KeywordStreamResetReason.MICROPHONE_SILENCED)
            }

            !matchesSelectedInput -> {
                discardPartialFrameRequested.set(true)
                readyAfterFrame.set(Long.MAX_VALUE)
                publishReadiness(
                    CaptureReadiness.RECORDING_CONFIGURATION_MISMATCH,
                )
                finalizeActiveSession(SessionIncompleteReason.CAPTURE_FAILED)
                requestDetectorReset(
                    KeywordStreamResetReason.INPUT_CONFIGURATION_MISMATCH,
                )
            }

            !previousUsable -> {
                discardPartialFrameRequested.set(true)
                requestDetectorReset(KeywordStreamResetReason.INPUT_CONFIGURATION_READY)
                readyAfterFrame.set(completedFrameCount.get() + 1L)
                publishReadiness(CaptureReadiness.WAITING_FOR_VERIFIED_FRAME)
            }
        }
    }

    private fun handleMissingOwnConfiguration() {
        val previouslyUsable: Boolean
        synchronized(microphoneLock) {
            previouslyUsable = selectedConfigurationUsable
            selectedConfigurationUsable = false
            currentOwnConfiguration = null
        }
        publishMicrophoneState()
        discardPartialFrameRequested.set(true)
        readyAfterFrame.set(Long.MAX_VALUE)
        publishReadiness(CaptureReadiness.WAITING_FOR_RECORDING_CONFIGURATION)
        if (previouslyUsable || activeSessionToken() != null) {
            finalizeActiveSession(SessionIncompleteReason.MICROPHONE_SILENCED)
        }
        requestDetectorReset(KeywordStreamResetReason.INPUT_CONFIGURATION_MISSING)
    }

    private fun handleVisibleRecorders(
        configs: List<AudioRecordingConfiguration>,
        ownAudioSessionId: Int,
    ) {
        if (!callbackEventsEnabled.get()) return
        visibleOtherRecorderCount = configs.count {
            it.clientAudioSessionId != ownAudioSessionId
        }
        publishMicrophoneState()
    }

    private fun publishMicrophoneState() {
        val event = synchronized(microphoneLock) {
            AudioCaptureEvent.MicrophoneStateChanged(
                ownConfiguration = currentOwnConfiguration,
                visibleOtherRecorderCount = visibleOtherRecorderCount,
            )
        }
        synchronized(listenerLock) {
            if (event == lastPublishedMicrophoneState) return
            lastPublishedMicrophoneState = event
            listener.onCaptureEvent(event)
        }
    }

    private fun maybePublishReady(completedFrame: Long) {
        if (
            selectedConfigurationUsable &&
            completedFrame >= readyAfterFrame.get()
        ) {
            publishReadiness(CaptureReadiness.READY)
        }
    }

    private fun publishReadiness(newReadiness: CaptureReadiness) {
        val previous = readiness.getAndSet(newReadiness)
        if (previous != newReadiness || !runStarted.get()) {
            emit(AudioCaptureEvent.ReadinessChanged(newReadiness))
        }
    }

    private fun activeSessionToken(): Long? =
        synchronized(sessionLock) {
            activeSession?.token
        }

    private fun isCuePending(token: Long): Boolean =
        synchronized(sessionLock) {
            activeSession
                ?.takeIf { it.token == token }
                ?.cuePending == true
        }

    private fun isCuePlaybackInProgress(): Boolean =
        synchronized(sessionLock) {
            cuePlaybackToken != null
        }

    private fun emit(event: AudioCaptureEvent) {
        synchronized(listenerLock) {
            listener.onCaptureEvent(event)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBufferBytes > 0) {
            "The selected MIC / 16 kHz / mono / PCM16 format is unsupported."
        }
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(
                max(
                    minimumBufferBytes,
                    FRAME_SAMPLES * PCM16_BYTES * BUFFERED_FRAMES,
                ),
            )
            .build()
            .also { audioRecord ->
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord initialization failed for the selected MIC input."
                }
            }
    }

    private fun createVad(): Vad {
        val silero = SileroVadModelConfig(
            model = VAD_ASSET_PATH,
            threshold = VAD_THRESHOLD,
            minSilenceDuration = 0f,
            minSpeechDuration = 0f,
            windowSize = FRAME_SAMPLES,
            maxSpeechDuration = 0f,
        )
        return Vad(
            appContext.assets,
            VadModelConfig(
                sileroVadModelConfig = silero,
                sampleRate = SAMPLE_RATE_HZ,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
    }

    private fun audioTimestamp(audioRecord: AudioRecord): AudioTimestamp? {
        val timestamp = AudioTimestamp()
        return if (
            audioRecord.getTimestamp(
                timestamp,
                AudioTimestamp.TIMEBASE_MONOTONIC,
            ) == AudioRecord.SUCCESS
        ) {
            timestamp
        } else {
            null
        }
    }

    private data class ManagedSession(
        val token: Long,
        val phrase: ApprovedWakePhrase,
        val writer: SessionAudioWriter.ActiveSession,
        val detectedAtElapsedRealtimeNanos: Long,
        var cuePending: Boolean,
        var audioGapObserved: Boolean = false,
    )

    private data class FinalizationResult(
        val phrase: ApprovedWakePhrase,
        val metadata: SessionAudioMetadata?,
        val failure: Throwable?,
    )

    private enum class AppendResult {
        APPENDED,
        NO_ACTIVE_SESSION,
        STORAGE_FAILURE,
    }

    private data class TimestampGap(
        val discrepancyFrames: Long,
        val estimatedGapMillis: Long,
    )

    private class StorageReserveReachedException :
        IllegalStateException("The protected 1 GiB storage reserve has been reached.")

    private class AudioTimestampGapDetector {
        private var previousFramePosition: Long? = null
        private var previousNanos: Long? = null

        fun observe(timestamp: AudioTimestamp?): TimestampGap? {
            if (timestamp == null) return null
            val priorFrames = previousFramePosition
            val priorNanos = previousNanos
            previousFramePosition = timestamp.framePosition
            previousNanos = timestamp.nanoTime
            if (priorFrames == null || priorNanos == null) return null

            val frameDelta = timestamp.framePosition - priorFrames
            val nanosDelta = timestamp.nanoTime - priorNanos
            if (nanosDelta <= 0L) {
                return TimestampGap(
                    discrepancyFrames = TIMESTAMP_GAP_THRESHOLD_FRAMES,
                    estimatedGapMillis = TIMESTAMP_GAP_THRESHOLD_MILLIS,
                )
            }
            val expectedFrames = nanosDelta * SAMPLE_RATE_HZ / NANOS_PER_SECOND
            val discrepancy = abs(frameDelta - expectedFrames)
            if (frameDelta >= 0L && discrepancy < TIMESTAMP_GAP_THRESHOLD_FRAMES) {
                return null
            }
            return TimestampGap(
                discrepancyFrames = discrepancy.coerceAtLeast(
                    TIMESTAMP_GAP_THRESHOLD_FRAMES,
                ),
                estimatedGapMillis = (
                    discrepancy * 1_000L / SAMPLE_RATE_HZ
                    ).coerceAtLeast(TIMESTAMP_GAP_THRESHOLD_MILLIS),
            )
        }

        fun reset() {
            previousFramePosition = null
            previousNanos = null
        }
    }

    private class ShortRingBuffer(capacity: Int) {
        private val samples = ShortArray(capacity.coerceAtLeast(1))
        private var writeIndex = 0
        private var available = 0

        fun append(source: ShortArray) {
            var sourceOffset = 0
            var remaining = source.size
            while (remaining > 0) {
                val copyCount = min(remaining, samples.size - writeIndex)
                source.copyInto(
                    destination = samples,
                    destinationOffset = writeIndex,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + copyCount,
                )
                writeIndex = (writeIndex + copyCount) % samples.size
                sourceOffset += copyCount
                remaining -= copyCount
                available = min(samples.size, available + copyCount)
            }
        }

        fun snapshot(): ShortArray {
            if (available == 0) return ShortArray(0)
            val result = ShortArray(available)
            val start = (writeIndex - available + samples.size) % samples.size
            val firstCopy = min(available, samples.size - start)
            samples.copyInto(
                destination = result,
                destinationOffset = 0,
                startIndex = start,
                endIndex = start + firstCopy,
            )
            if (firstCopy < available) {
                samples.copyInto(
                    destination = result,
                    destinationOffset = firstCopy,
                    startIndex = 0,
                    endIndex = available - firstCopy,
                )
            }
            return result
        }

        fun clear() {
            writeIndex = 0
            available = 0
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val FRAME_SAMPLES = 512
        const val PCM16_BYTES = 2
        const val WAV_HEADER_BYTES = 44L
        const val PCM16_SCALE = 32_768f
        const val BUFFERED_FRAMES = 8
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE_HZ * 2
        const val ZERO_READ_BACKOFF_MILLIS = 10L
        const val CUE_COMPLETION_GRACE_MILLIS = 500L

        const val VAD_THRESHOLD = 0.5f

        const val AUDIO_TIMESTAMP_POLL_MILLIS = 1_000L
        const val TIMESTAMP_GAP_THRESHOLD_FRAMES = FRAME_SAMPLES * 2L
        const val TIMESTAMP_GAP_THRESHOLD_MILLIS =
            TIMESTAMP_GAP_THRESHOLD_FRAMES * 1_000L / SAMPLE_RATE_HZ
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val HEARTBEAT_MILLIS = 60_000L
        const val SAFETY_DEADLINE_MILLIS = 14L * 60L * 60L * 1_000L
        const val MONITOR_THREAD_NAME = "DreamLog capture monitor"

        const val VAD_ASSET_PATH = "vad/silero_vad.int8.onnx"
    }

    private val requestedIncompleteReason = AtomicReference<String?>(null)
}

internal data class SessionFinalizationPlan(
    val incompleteReason: String?,
    val automaticSilenceTailSampleCount: Long?,
)

internal fun resolveSessionFinalization(
    audioGapObserved: Boolean,
    requestedIncompleteReason: String?,
    automaticSilenceTailSampleCount: Long?,
): SessionFinalizationPlan {
    val effectiveIncompleteReason =
        requestedIncompleteReason
            ?: SessionIncompleteReason.AUDIO_GAP.takeIf { audioGapObserved }
    return if (effectiveIncompleteReason == null) {
        SessionFinalizationPlan(
            incompleteReason = null,
            automaticSilenceTailSampleCount =
                checkNotNull(automaticSilenceTailSampleCount) {
                    "A complete session requires its observed automatic silence tail."
                },
        )
    } else {
        SessionFinalizationPlan(
            incompleteReason = effectiveIncompleteReason,
            automaticSilenceTailSampleCount = null,
        )
    }
}
