package com.wivy.dreamlog.capture

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.media.AudioManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import com.wivy.dreamlog.R
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NightListeningService : Service(), AudioCaptureListener {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DreamLog night capture")
    }
    private val evidenceWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DreamLog capture evidence")
    }
    private val finishing = AtomicBoolean(false)
    private val endReason = AtomicReference(NightEndReason.SERVICE_INTERRUPTION)

    @Volatile
    private var runActive = false

    @Volatile
    private var engine: AudioCaptureEngine? = null

    @Volatile
    private var writer: SessionAudioWriter? = null

    @Volatile
    private var journal: CaptureJournalStore? = null

    @Volatile
    private var activeWriterSession: SessionAudioWriter.ActiveSession? = null

    @Volatile
    private var pendingWakeDetected: AudioCaptureEvent.WakeDetected? = null

    @Volatile
    private var pendingMediaPlaybackActiveAtWake: Boolean? = null

    @Volatile
    private var pendingSessionStarted: AudioCaptureEvent.SessionStarted? = null

    @Volatile
    private var pendingCueStartCheckpoint: SessionAudioCheckpoint? = null

    @Volatile
    private var pendingCueStarted: AudioCaptureEvent.CueStarted? = null

    @Volatile
    private var pendingCueStartEvidence: Future<*>? = null

    private var latestStartId = 0
    private var powerReceiverRegistered = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?,
        ) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED ->
                    recordPowerChange(charging = true, type = "power_connected")

                Intent.ACTION_POWER_DISCONNECTED ->
                    recordPowerChange(charging = false, type = "power_disconnected")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START_NIGHT -> startNight(intent.toNightStartRequest())
            ACTION_END_NIGHT -> requestOwnerEnd()
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onCaptureEvent(event: AudioCaptureEvent) {
        try {
            if (event !is AudioCaptureEvent.CueStarted) {
                awaitPendingCueStartEvidence()
            }
            handleCaptureEvent(event)
        } catch (failure: Throwable) {
            handleOperationalFailure(
                message = "Capture evidence could not be preserved.",
                cause = failure,
            )
        }
    }

    override fun onDestroy() {
        unregisterPowerReceiver()
        if (runActive && !finishing.get()) {
            endReason.set(NightEndReason.SERVICE_INTERRUPTION)
            runCatching {
                CaptureRuntimeStore.requestEnd(NightEndReason.SERVICE_INTERRUPTION)
            }
            runCatching {
                journal?.appendEvent(
                    type = "service_destroyed",
                    attributes = emptyMap(),
                )
            }
            engine?.requestStop(CaptureStopReason.SERVICE_INTERRUPTED)
        }
        worker.shutdown()
        evidenceWorker.shutdown()
        super.onDestroy()
    }

    private fun startNight(request: NightStartRequest) {
        if (runActive || finishing.get()) {
            CaptureRuntimeStore.recordEvent("Ignored a duplicate start request.")
            return
        }

        runActive = true
        endReason.set(NightEndReason.SERVICE_INTERRUPTION)
        NightNotification.ensureChannel(applicationContext)
        startForeground(
            NightNotification.NOTIFICATION_ID,
            NightNotification.build(
                applicationContext,
                CaptureRuntimeStore.snapshots.value,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        try {
            val journalStore = CaptureJournalStore(journalDirectory())
            val audioWriter = SessionAudioWriter(audioDirectory(request.nightId))
            journalStore.beginNight(
                nightId = request.nightId,
                displayDate = request.displayDate,
                startedAtEpochMillis = request.startedAtEpochMillis,
                startedAtUtcOffsetSeconds = request.startedAtUtcOffsetSeconds,
            )
            journal = journalStore
            writer = audioWriter
            registerPowerReceiver()
            CaptureRuntimeStore.markForegroundServiceStarted()
            updateNotification()

            val captureEngine = AudioCaptureEngine(
                context = applicationContext,
                cueRawResourceId = R.raw.m01_cue,
                sessionSink = AudioCaptureSessionSink { preRoll, startedAt ->
                    startWriterSession(audioWriter, preRoll, startedAt)
                },
                listener = this,
                continuousNonSpeechSeconds =
                    NarrativeBoundaryDetector.DEFAULT_CONTINUOUS_NON_SPEECH_SECONDS,
            )
            engine = captureEngine
            worker.execute {
                try {
                    captureEngine.use { it.run() }
                } finally {
                    engine = null
                    finishNight()
                }
            }
        } catch (failure: Throwable) {
            handleOperationalFailure(
                message = "The active night could not start.",
                cause = failure,
                initializationFailure = true,
            )
            finishNight()
        }
    }

    private fun startWriterSession(
        audioWriter: SessionAudioWriter,
        preRoll: ShortArray,
        startedAtEpochMillis: Long,
    ): SessionAudioWriter.ActiveSession {
        val requestedBytes =
            WAV_HEADER_BYTES + preRoll.size.toLong() * PCM16_BYTES
        check(StorageGuard.canWrite(filesDir.usableSpace, requestedBytes)) {
            "The protected 1 GiB storage reserve has been reached."
        }
        val session = audioWriter.startSession(preRoll, startedAtEpochMillis)
        activeWriterSession = session
        return session
    }

    private fun requestOwnerEnd() {
        if (!runActive || finishing.get()) {
            stopSelfResult(latestStartId)
            return
        }
        endReason.set(NightEndReason.OWNER_REQUEST)
        runCatching {
            CaptureRuntimeStore.requestEnd(NightEndReason.OWNER_REQUEST)
        }
        runCatching {
            journal?.appendEvent(
                type = "owner_end_requested",
                attributes = emptyMap(),
            )
        }
        updateNotification()
        engine?.requestStop(CaptureStopReason.NIGHT_ENDED)
    }

    private fun handleCaptureEvent(event: AudioCaptureEvent) {
        when (event) {
            is AudioCaptureEvent.ReadinessChanged -> handleReadiness(event.readiness)

            is AudioCaptureEvent.MicrophoneStateChanged -> {
                val own = event.ownConfiguration
                val ownMissingDuringListening =
                    own == null &&
                        CaptureRuntimeStore.snapshots.value.phase != CapturePhase.STARTING
                CaptureRuntimeStore.updateMicrophoneState(
                    silenced = own?.clientSilenced == true || ownMissingDuringListening,
                    visibleOtherRecorderCount = event.visibleOtherRecorderCount,
                )
                journal?.appendEvent(
                    type = "microphone_state",
                    attributes = buildMap {
                        put("own_configuration", if (own == null) "missing" else "observed")
                        put("client_silenced", (own?.clientSilenced == true).toString())
                        put(
                            "effective_silenced",
                            (own?.clientSilenced == true || ownMissingDuringListening).toString(),
                        )
                        put("selected_format", (own?.matchesSelectedInput == true).toString())
                        put(
                            "other_recorder_count",
                            event.visibleOtherRecorderCount.toString(),
                        )
                    },
                )
                updateNotification()
            }

            is AudioCaptureEvent.Heartbeat -> {
                activeWriterSession?.let { active ->
                    journal?.checkpointSession(active.checkpoint())
                }
                val current = CaptureRuntimeStore.snapshots.value
                val charging = readCharging()
                journal?.heartbeat(
                    framesRead = event.capturedSamplePosition,
                    gapCount = current.gapCount,
                    microphoneSilenced = current.microphoneSilenced,
                    readiness = event.readiness.journalCode,
                    charging = charging,
                    sessionActive = event.activeSessionId != null,
                    keywordStreamProgress = event.keywordStreamProgress,
                    epochMillis = event.observedAtEpochMillis,
                )
                CaptureRuntimeStore.recordHeartbeat(
                    epochMillis = event.observedAtEpochMillis,
                    charging = charging,
                )
            }

            is AudioCaptureEvent.WakeDetected -> {
                pendingWakeDetected = event
                pendingMediaPlaybackActiveAtWake = runCatching {
                    (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
                }.getOrNull()
            }

            is AudioCaptureEvent.WakeCandidateEvaluated -> {
                val telemetry = event.telemetry
                journal?.appendEvent(
                    type = "wake_candidate_episode",
                    attributes = buildMap {
                        put("max_dream_log_score", telemetry.maxDreamLogScore.toString())
                        put("max_hey_dream_log_score", telemetry.maxHeyDreamLogScore.toString())
                        put(
                            "max_dream_log_threshold_ratio",
                            telemetry.maxDreamLogThresholdRatio.toString(),
                        )
                        put(
                            "max_hey_dream_log_threshold_ratio",
                            telemetry.maxHeyDreamLogThresholdRatio.toString(),
                        )
                        put(
                            "dream_log_threshold_margin",
                            telemetry.dreamLogThresholdMargin.toString(),
                        )
                        put(
                            "hey_dream_log_threshold_margin",
                            telemetry.heyDreamLogThresholdMargin.toString(),
                        )
                        put("observed_hop_count", telemetry.observedHopCount.toString())
                        put(
                            "max_adjacent_qualifying_hop_count",
                            telemetry.maxAdjacentQualifyingHopCount.toString(),
                        )
                        put("guard_outcome", telemetry.guardOutcome.name.lowercase())
                        put("accepted", telemetry.accepted.toString())
                        put("reason", telemetry.reason.name.lowercase())
                        telemetry.acceptedPhrase?.let { phrase ->
                            put("accepted_phrase", phrase.name.lowercase())
                        }
                    },
                    epochMillis = event.evaluatedAtEpochMillis,
                )
            }

            is AudioCaptureEvent.SessionStarted -> {
                pendingSessionStarted = event
                CaptureRuntimeStore.markWakeDetected(
                    sessionId = event.sessionId,
                    phrase = event.phrase.displayName,
                )
            }

            is AudioCaptureEvent.CueStarted -> {
                if (queueCueStartEvidence(event)) {
                    updateNotification()
                }
            }

            is AudioCaptureEvent.CueEnded -> {
                activeWriterSession?.let { active ->
                    journal?.checkpointSession(active.checkpoint())
                }
                CaptureRuntimeStore.markCueFinished()
                journal?.appendEvent(
                    type = "cue_ended",
                    attributes = mapOf(
                        "session_id" to event.sessionId,
                        "sample_offset_exclusive" to
                            event.sampleOffsetExclusive.toString(),
                        "rendered_millis" to event.renderedMillis.toString(),
                    ),
                )
                updateNotification()
            }

            is AudioCaptureEvent.SessionFinalized -> {
                persistPendingCueStartEvidence()
                val metadata = event.metadata
                CaptureRuntimeStore.markSessionFinalizing(
                    reason = metadata.incompleteReason ?: NORMAL_SESSION_REASON,
                )
                journal?.recordSessionFinalized(metadata)
                if (activeWriterSession?.sessionId == metadata.sessionId) {
                    activeWriterSession = null
                }
                CaptureRuntimeStore.markSessionFinalized(
                    fileName = metadata.audioFileName,
                    reason = metadata.incompleteReason ?: NORMAL_SESSION_REASON,
                    incomplete = !metadata.isComplete,
                )
                updateNotification()
            }

            is AudioCaptureEvent.AudioGap -> {
                CaptureRuntimeStore.recordGap(
                    "Audio discontinuity observed (${event.estimatedGapMillis} ms).",
                )
                journal?.appendEvent(
                    type = "audio_gap",
                    attributes = buildMap {
                        put(
                            "discrepancy_frames",
                            event.discrepancyFrames.toString(),
                        )
                        put(
                            "estimated_gap_millis",
                            event.estimatedGapMillis.toString(),
                        )
                        event.activeSessionId?.let { sessionId ->
                            put("session_id", sessionId)
                        }
                        event.sessionSampleOffset?.let { sampleOffset ->
                            put("session_sample_offset", sampleOffset.toString())
                        }
                    },
                )
            }

            AudioCaptureEvent.SafetyDeadlineReached -> {
                endReason.set(NightEndReason.SAFETY_TIMEOUT)
                CaptureRuntimeStore.requestEnd(NightEndReason.SAFETY_TIMEOUT)
                journal?.appendEvent(
                    type = "safety_stop",
                    attributes = mapOf("limit_hours" to "14"),
                )
                updateNotification()
            }

            is AudioCaptureEvent.FatalFailure -> {
                persistPendingCueStartEvidence()
                val reason = event.kind.nightEndReason()
                endReason.set(reason)
                CaptureRuntimeStore.requestEnd(reason)
                journal?.appendEvent(
                    type = "capture_failure",
                    attributes = mapOf(
                        "kind" to event.kind.name.lowercase(),
                        "reason" to safeFailure(event.message, event.cause),
                    ),
                )
                CaptureRuntimeStore.recordEvent(
                    safeFailure(event.message, event.cause),
                )
                updateNotification()
            }
        }
    }

    private fun handleReadiness(readiness: CaptureReadiness) {
        when (readiness) {
            CaptureReadiness.READY -> {
                CaptureRuntimeStore.markReady()
                journal?.appendEvent(
                    type = "capture_ready",
                    attributes = emptyMap(),
                )
                updateNotification()
            }

            CaptureReadiness.MICROPHONE_SILENCED -> {
                CaptureRuntimeStore.updateMicrophoneState(
                    silenced = true,
                    visibleOtherRecorderCount =
                        CaptureRuntimeStore.snapshots.value.visibleOtherRecorderCount,
                )
                updateNotification()
            }

            CaptureReadiness.RECORDING_CONFIGURATION_MISMATCH -> {
                CaptureRuntimeStore.recordEvent(
                    "Android did not provide the selected MIC / 16 kHz / mono / PCM16 input.",
                )
                updateNotification()
            }

            CaptureReadiness.AUDIO_DISCONTINUITY ->
                CaptureRuntimeStore.recordEvent(
                    "Waiting for a fresh frame after an audio discontinuity.",
                )

            CaptureReadiness.WAITING_FOR_RECORDING_CONFIGURATION,
            CaptureReadiness.WAITING_FOR_VERIFIED_FRAME,
            CaptureReadiness.STOPPED,
            -> Unit
        }
    }

    private fun persistPendingSessionStart() {
        val journalStore = journal ?: return
        pendingWakeDetected?.let { event ->
            journalStore.appendEvent(
                type = "wake_detected",
                attributes = mapOf(
                    "phrase" to event.phrase.name.lowercase(),
                    "pre_roll_samples" to event.preRollSampleCount.toString(),
                ),
                epochMillis = event.detectedAtEpochMillis,
            )
            pendingWakeDetected = null
        }
        pendingSessionStarted?.let { event ->
            journalStore.appendEvent(
                type = "session_started",
                attributes = buildMap {
                    put("session_id", event.sessionId)
                    put("phrase", event.phrase.name.lowercase())
                    put("pre_roll_samples", event.preRollSampleCount.toString())
                    pendingMediaPlaybackActiveAtWake?.let { active ->
                        put("music_playback_active_at_wake", active.toString())
                    }
                },
                epochMillis = event.startedAtEpochMillis,
            )
            pendingSessionStarted = null
            pendingMediaPlaybackActiveAtWake = null
        }
    }

    @Synchronized
    private fun queueCueStartEvidence(event: AudioCaptureEvent.CueStarted): Boolean {
        if (
            finishing.get() ||
            activeWriterSession?.sessionId != event.sessionId
        ) {
            return false
        }
        check(pendingCueStartEvidence == null) {
            "Cue-start evidence is already pending."
        }
        pendingCueStartCheckpoint = activeWriterSession?.checkpoint()
        pendingCueStarted = event
        pendingCueStartEvidence = evidenceWorker.submit {
            persistPendingCueStartEvidence()
        }
        return true
    }

    @Synchronized
    private fun persistPendingCueStartEvidence() {
        val journalStore = journal ?: return
        pendingCueStartCheckpoint?.let { checkpoint ->
            journalStore.checkpointSession(checkpoint)
            pendingCueStartCheckpoint = null
        }
        persistPendingSessionStart()
        pendingCueStarted?.let { event ->
            journalStore.appendEvent(
                type = "cue_started",
                attributes = mapOf(
                    "session_id" to event.sessionId,
                    "sample_offset" to event.sampleOffset.toString(),
                    "latency_millis" to event.requestLatencyMillis.toString(),
                    "detection_to_cue_millis" to
                        event.detectionToCueMillis.toString(),
                ),
            )
            pendingCueStarted = null
        }
    }

    private fun awaitPendingCueStartEvidence() {
        val pending = pendingCueStartEvidence ?: return
        try {
            pending.get()
        } catch (failure: ExecutionException) {
            try {
                persistPendingCueStartEvidence()
            } catch (retryFailure: Throwable) {
                val originalFailure = failure.cause ?: failure
                if (retryFailure !== originalFailure) {
                    retryFailure.addSuppressed(originalFailure)
                }
                throw retryFailure
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } finally {
            if (pendingCueStartEvidence === pending) {
                pendingCueStartEvidence = null
            }
        }
    }

    private fun handleOperationalFailure(
        message: String,
        cause: Throwable,
        initializationFailure: Boolean = false,
    ) {
        val reason = if (initializationFailure) {
            NightEndReason.AUDIO_INITIALIZATION_FAILURE
        } else {
            NightEndReason.CAPTURE_FAILURE
        }
        endReason.set(reason)
        runCatching { CaptureRuntimeStore.requestEnd(reason) }
        val safeMessage = safeFailure(message, cause)
        CaptureRuntimeStore.recordEvent(safeMessage)
        runCatching {
            journal?.appendEvent(
                type = "capture_failure",
                attributes = mapOf(
                    "kind" to if (initializationFailure) "initialization" else "journal",
                    "reason" to safeMessage,
                ),
            )
        }
        engine?.requestStop(CaptureStopReason.SERVICE_INTERRUPTED)
        updateNotification()
    }

    private fun finishNight() {
        if (!finishing.compareAndSet(false, true)) return

        val reason = endReason.get()
        val reasonCode = reason.journalReason()
        val interrupted = reason != NightEndReason.OWNER_REQUEST
        val journalStore = journal
        val audioWriter = writer
        var endRecord: NightEndRecord? = null
        var evidenceFailure: Throwable? = null
        var finishFailure: Throwable? = null

        if (journalStore != null && audioWriter != null) {
            evidenceFailure = runCatching {
                awaitPendingCueStartEvidence()
                persistPendingCueStartEvidence()
            }.exceptionOrNull()
            try {
                val dangling = journalStore.readActive()?.activeSession
                val recoveryReferences = listOfNotNull(
                    dangling,
                    activeWriterSession?.checkpoint(),
                ).distinctBy(SessionAudioCheckpoint::sessionId)
                audioWriter
                    .recoverInterrupted(
                        references = recoveryReferences,
                        reason = reason.sessionIncompleteReason(),
                    )
                    .forEach(journalStore::recordSessionFinalized)
                activeWriterSession = null
                endRecord = journalStore.endNight(
                    reason = reasonCode,
                    interrupted = interrupted,
                )
            } catch (failure: Throwable) {
                evidenceFailure?.let { evidenceWriteFailure ->
                    if (failure !== evidenceWriteFailure) {
                        failure.addSuppressed(evidenceWriteFailure)
                    }
                }
                finishFailure = failure
            }
        }

        if (
            CaptureRuntimeStore.snapshots.value.phase != CapturePhase.ENDING &&
            CaptureRuntimeStore.snapshots.value.active
        ) {
            runCatching { CaptureRuntimeStore.requestEnd(reason) }
        }
        val summary = when {
            finishFailure != null ->
                "The night stopped, but its recovery marker remains: " +
                    safeFailure("finalization failed.", finishFailure)

            endRecord == null ->
                "The night stopped before a capture journal could be created."

            evidenceFailure != null ->
                "Night ended and usable audio was preserved, but some operational " +
                    "timing evidence could not be written."

            interrupted ->
                "Monitoring ended with ${endRecord.reason}; usable audio was preserved."

            else ->
                "Night ended with ${endRecord.sessionCount} captured session" +
                    if (endRecord.sessionCount == 1) "." else "s."
        }
        runCatching { CaptureRuntimeStore.markNightFinalized(summary) }

        runActive = false
        activeWriterSession = null
        pendingWakeDetected = null
        pendingMediaPlaybackActiveAtWake = null
        pendingSessionStarted = null
        pendingCueStartCheckpoint = null
        pendingCueStarted = null
        pendingCueStartEvidence = null
        unregisterPowerReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }

    private fun recordPowerChange(
        charging: Boolean,
        type: String,
    ) {
        if (!runActive || finishing.get()) return
        runCatching {
            journal?.appendEvent(type = type, attributes = emptyMap())
            CaptureRuntimeStore.updateCharging(
                charging = charging,
                event = if (charging) {
                    "Power connected; the active night continued."
                } else {
                    "Power disconnected; the active night continued."
                },
            )
        }.onFailure { failure ->
            handleOperationalFailure(
                message = "A power-change event could not be preserved.",
                cause = failure,
            )
        }
    }

    private fun registerPowerReceiver() {
        if (powerReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(powerReceiver, filter)
        }
        powerReceiverRegistered = true
    }

    private fun unregisterPowerReceiver() {
        if (!powerReceiverRegistered) return
        runCatching { unregisterReceiver(powerReceiver) }
        powerReceiverRegistered = false
    }

    private fun updateNotification() {
        if (!runActive || finishing.get()) return
        getSystemService(NotificationManager::class.java).notify(
            NightNotification.NOTIFICATION_ID,
            NightNotification.build(
                applicationContext,
                CaptureRuntimeStore.snapshots.value,
            ),
        )
    }

    private fun readCharging(): Boolean {
        val battery = registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return false
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun safeFailure(
        message: String,
        cause: Throwable?,
    ): String {
        val detail = cause?.message
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.take(MAX_ERROR_DETAIL_LENGTH)
        return if (detail.isNullOrBlank()) {
            message
        } else {
            "$message ${cause.javaClass.simpleName}: $detail"
        }
    }

    private fun journalDirectory(): File = File(filesDir, "capture/journal")

    private fun audioDirectory(nightId: String): File =
        File(filesDir, "capture/audio/$nightId")

    private fun NightEndReason.journalReason(): String =
        when (this) {
            NightEndReason.OWNER_REQUEST -> "owner_ended"
            NightEndReason.SAFETY_TIMEOUT -> SessionIncompleteReason.SAFETY_STOP
            NightEndReason.AUDIO_INITIALIZATION_FAILURE -> "audio_initialization_failed"
            NightEndReason.CAPTURE_FAILURE -> SessionIncompleteReason.CAPTURE_FAILED
            NightEndReason.STORAGE_RESERVE_REACHED ->
                SessionIncompleteReason.STORAGE_RESERVE_REACHED

            NightEndReason.SERVICE_INTERRUPTION ->
                SessionIncompleteReason.SERVICE_INTERRUPTED
        }

    private fun NightEndReason.sessionIncompleteReason(): String =
        when (this) {
            NightEndReason.OWNER_REQUEST -> SessionIncompleteReason.NIGHT_ENDED
            NightEndReason.SAFETY_TIMEOUT -> SessionIncompleteReason.SAFETY_STOP
            NightEndReason.AUDIO_INITIALIZATION_FAILURE,
            NightEndReason.CAPTURE_FAILURE,
            -> SessionIncompleteReason.CAPTURE_FAILED

            NightEndReason.STORAGE_RESERVE_REACHED ->
                SessionIncompleteReason.STORAGE_RESERVE_REACHED

            NightEndReason.SERVICE_INTERRUPTION ->
                SessionIncompleteReason.SERVICE_INTERRUPTED
        }

    companion object {
        const val ACTION_END_NIGHT = "com.wivy.dreamlog.capture.END_NIGHT"
        private const val ACTION_START_NIGHT = "com.wivy.dreamlog.capture.START_NIGHT"
        private const val EXTRA_NIGHT_ID = "night_id"
        private const val EXTRA_DISPLAY_DATE = "display_date"
        private const val EXTRA_STARTED_AT = "started_at"
        private const val EXTRA_STARTED_AT_OFFSET = "started_at_offset"
        private val NORMAL_SESSION_REASON =
            "continuous_non_speech_" +
                "${NarrativeBoundaryDetector.DEFAULT_CONTINUOUS_NON_SPEECH_SECONDS * 1_000}ms"
        private const val WAV_HEADER_BYTES = 44L
        private const val PCM16_BYTES = 2L
        private const val MAX_ERROR_DETAIL_LENGTH = 240

        fun startNight(
            context: Context,
            request: NightStartRequest,
        ) {
            require(
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            ) {
                "Microphone permission must be granted from the visible activity."
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                require(
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED,
                ) {
                    "Notification permission is required for the overnight status."
                }
            }
            context.startForegroundService(
                Intent(context, NightListeningService::class.java)
                    .setAction(ACTION_START_NIGHT)
                    .putExtra(EXTRA_NIGHT_ID, request.nightId)
                    .putExtra(EXTRA_DISPLAY_DATE, request.displayDate)
                    .putExtra(EXTRA_STARTED_AT, request.startedAtEpochMillis)
                    .putExtra(
                        EXTRA_STARTED_AT_OFFSET,
                        request.startedAtUtcOffsetSeconds,
                    ),
            )
        }

        fun endNight(context: Context) {
            context.startService(
                Intent(context, NightListeningService::class.java)
                    .setAction(ACTION_END_NIGHT),
            )
        }

        private fun Intent.toNightStartRequest(): NightStartRequest =
            NightStartRequest(
                nightId = requireNotNull(getStringExtra(EXTRA_NIGHT_ID)) {
                    "The night ID is missing."
                },
                displayDate = requireNotNull(getStringExtra(EXTRA_DISPLAY_DATE)) {
                    "The display date is missing."
                },
                startedAtEpochMillis = getLongExtra(EXTRA_STARTED_AT, -1L).also {
                    require(it >= 0L) { "The start time is missing." }
                },
                startedAtUtcOffsetSeconds = getIntExtra(
                    EXTRA_STARTED_AT_OFFSET,
                    Int.MIN_VALUE,
                ).also {
                    require(it != Int.MIN_VALUE) { "The start UTC offset is missing." }
                    requireUtcOffsetSeconds(it)
                },
            )
    }
}

internal fun CaptureFailureKind.nightEndReason(): NightEndReason = when (this) {
    CaptureFailureKind.INITIALIZATION -> NightEndReason.AUDIO_INITIALIZATION_FAILURE
    CaptureFailureKind.STORAGE_RESERVE -> NightEndReason.STORAGE_RESERVE_REACHED
    CaptureFailureKind.AUDIO_READ,
    CaptureFailureKind.CUE_PLAYBACK,
    CaptureFailureKind.AUDIO_WRITE,
    -> NightEndReason.CAPTURE_FAILURE
}
