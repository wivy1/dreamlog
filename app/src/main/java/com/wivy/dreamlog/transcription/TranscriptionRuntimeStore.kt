package com.wivy.dreamlog.transcription

import android.content.Context
import com.wivy.dreamlog.capture.CaptureRuntimeStore
import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.DreamLogDatabase
import com.wivy.dreamlog.history.NightDao
import com.wivy.dreamlog.history.NightWithDetails
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.history.RawAudioUseRegistry
import com.wivy.dreamlog.history.TranscriptionDao
import com.wivy.dreamlog.transcription.model.InstalledLocalAsrModel
import com.wivy.dreamlog.transcription.model.LocalAsrInstallCancelledException
import com.wivy.dreamlog.transcription.model.LocalAsrInstallProgress
import com.wivy.dreamlog.transcription.model.LocalAsrModelManager
import com.wivy.dreamlog.transcription.model.LocalAsrModelStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TranscriptionModelPhase {
    UNINITIALIZED,
    VERIFYING,
    VERIFICATION_DEFERRED,
    NOT_INSTALLED,
    INSTALLING,
    CANCELLING,
    INSTALLED,
    REMOVING,
    INVALID,
    ERROR,
}

enum class TranscriptionRuntimePhase {
    IDLE,
    RUNNING,
    ERROR,
}

data class TranscriptionRuntimeSnapshot(
    val initialized: Boolean = false,
    val modelPhase: TranscriptionModelPhase = TranscriptionModelPhase.UNINITIALIZED,
    val modelDownloadedBytes: Long = 0L,
    val modelTotalBytes: Long = SELECTED_MODEL_BYTES,
    val modelCurrentFile: String? = null,
    val modelMessage: String = "Checking the local transcription model.",
    val modelError: String? = null,
    val transcriptionPhase: TranscriptionRuntimePhase = TranscriptionRuntimePhase.IDLE,
    val nightId: String? = null,
    val activeSessionId: String? = null,
    val eligibleSessionCount: Int = 0,
    val unavailableSessionCount: Int = 0,
    val completedSessionCount: Int = 0,
    val failedSessionCount: Int = 0,
    val runningSessionCount: Int = 0,
    val pendingSessionCount: Int = 0,
    val retryableSessionIds: List<String> = emptyList(),
    val pauseReason: TranscriptionPauseReason? = null,
    val pauseMessage: String? = null,
    val platformThermalStatus: Int? = null,
    val batteryTemperatureCelsius: Double? = null,
    val appOpenMessage: String = APP_OPEN_MESSAGE,
    val transcriptionError: String? = null,
    val historyRevision: Long = 0L,
) {
    val modelSizeMiB: Double
        get() = MODEL_SIZE_MIB

    val modelSizeLabel: String
        get() = MODEL_SIZE_LABEL

    val busy: Boolean
        get() =
            modelPhase == TranscriptionModelPhase.VERIFYING ||
                modelPhase == TranscriptionModelPhase.INSTALLING ||
                modelPhase == TranscriptionModelPhase.CANCELLING ||
                modelPhase == TranscriptionModelPhase.REMOVING ||
                transcriptionPhase == TranscriptionRuntimePhase.RUNNING

    val canInstallModel: Boolean
        get() = initialized && !busy && modelPhase != TranscriptionModelPhase.INSTALLED

    val canRemoveModel: Boolean
        get() = initialized && !busy && (
            modelPhase == TranscriptionModelPhase.INSTALLED ||
                modelPhase == TranscriptionModelPhase.INVALID
            )

    val resumeAvailable: Boolean
        get() =
            transcriptionPhase != TranscriptionRuntimePhase.RUNNING &&
                nightId != null &&
                eligibleSessionCount > completedSessionCount &&
                (pendingSessionCount > 0 || retryableSessionIds.isNotEmpty())

    val resumeActionLabel: String?
        get() = if (resumeAvailable) {
            "Resume transcription — $completedSessionCount of $eligibleSessionCount complete"
        } else {
            null
        }
}

/**
 * Application-process owner for the finite local ASR model and transcription operations.
 *
 * The store never retains an Activity. Durable transcript truth remains in Room; this StateFlow
 * only keeps one operation and its presentation state stable across Activity recreation. Android
 * process death still stops transcription, and startup recovery turns any abandoned attempt into a
 * retryable failure before a model is inspected or an engine is constructed.
 */
object TranscriptionRuntimeStore {
    private val lock = Any()
    private val mutableSnapshots = MutableStateFlow(TranscriptionRuntimeSnapshot())

    internal val processInstanceId: String = UUID.randomUUID().toString()
    val snapshots: StateFlow<TranscriptionRuntimeSnapshot> = mutableSnapshots.asStateFlow()

    private var initializationStarted = false
    private var dependencies: RuntimeDependencies? = null
    private var installedModel: InstalledLocalAsrModel? = null
    private var activeOperation: RuntimeOperation? = null
    private var installCancellation: AtomicBoolean? = null
    private val transcriptionStopDeferral =
        AtomicReference<TranscriptionContinuationDecision.Defer?>(null)

    /** Initializes database recovery and model status exactly once in this app process. */
    fun initialize(context: Context): Boolean {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (initializationStarted) return false
            initializationStarted = true
            activeOperation = RuntimeOperation.INITIALIZE
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = TranscriptionModelPhase.VERIFYING,
                    modelMessage = "Checking the local transcription model.",
                    modelError = null,
                    transcriptionError = null,
                ),
            )
        }

        return launchFiniteThread("DreamLog transcription startup") {
            initializeOnThread(appContext)
        }
    }

    /** Re-hashes the installed manifest and files without installing anything. */
    fun refreshModelStatus(): Boolean {
        val runtime = synchronized(lock) {
            val ready = requireDependenciesLocked(modelOperation = true) ?: return false
            if (!claimOperationLocked(RuntimeOperation.VERIFY_MODEL, modelOperation = true)) {
                return false
            }
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = TranscriptionModelPhase.VERIFYING,
                    modelMessage = "Verifying the installed local model.",
                    modelError = null,
                    modelCurrentFile = null,
                    modelDownloadedBytes = 0L,
                ),
            )
            ready
        }
        return launchFiniteThread("DreamLog model verification") {
            verifyModelOnThread(runtime, RuntimeOperation.VERIFY_MODEL)
        }
    }

    /** Starts an owner-requested install of the one pinned local ASR model. */
    fun installModel(): Boolean {
        val runtime: RuntimeDependencies
        val cancellation = AtomicBoolean(false)
        synchronized(lock) {
            runtime = requireDependenciesLocked(modelOperation = true) ?: return false
            if (mutableSnapshots.value.modelPhase == TranscriptionModelPhase.INSTALLED) {
                publishLocked(
                    mutableSnapshots.value.copy(
                        modelMessage = "The local transcription model is already installed.",
                        modelError = null,
                    ),
                )
                return false
            }
            if (!claimOperationLocked(RuntimeOperation.INSTALL_MODEL, modelOperation = true)) {
                return false
            }
            installCancellation = cancellation
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = TranscriptionModelPhase.INSTALLING,
                    modelDownloadedBytes = 0L,
                    modelCurrentFile = null,
                    modelMessage = "Downloading the verified model into private local storage.",
                    modelError = null,
                ),
            )
        }
        return launchFiniteThread("DreamLog model install") {
            installModelOnThread(runtime, cancellation)
        }
    }

    fun cancelModelInstall(): Boolean = synchronized(lock) {
        if (activeOperation != RuntimeOperation.INSTALL_MODEL) return false
        val cancellation = installCancellation ?: return false
        if (!cancellation.compareAndSet(false, true)) return false
        publishLocked(
            mutableSnapshots.value.copy(
                modelPhase = TranscriptionModelPhase.CANCELLING,
                modelMessage = "Cancelling the model installation and cleaning partial files.",
            ),
        )
        true
    }

    /** Removes only the selected transcription model after an explicit owner action. */
    fun removeModel(): Boolean {
        val runtime = synchronized(lock) {
            val ready = requireDependenciesLocked(modelOperation = true) ?: return false
            if (!claimOperationLocked(RuntimeOperation.REMOVE_MODEL, modelOperation = true)) {
                return false
            }
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = TranscriptionModelPhase.REMOVING,
                    modelMessage = "Removing the local transcription model.",
                    modelError = null,
                    modelCurrentFile = null,
                ),
            )
            ready
        }
        return launchFiniteThread("DreamLog model removal") {
            removeModelOnThread(runtime)
        }
    }

    /** May be called automatically by the visible app after the requested night has ended. */
    fun processNight(nightId: String): Boolean =
        startTranscription(TranscriptionRequest.Night(nightId))

    /** Explicitly retries failed sessions once and continues never-claimed sessions in place. */
    fun resumeNight(nightId: String): Boolean =
        startTranscription(TranscriptionRequest.Resume(nightId))

    /** Retries exactly one failed retained session after an explicit owner action. */
    fun retrySession(
        nightId: String,
        sessionId: String,
    ): Boolean = startTranscription(TranscriptionRequest.Retry(nightId, sessionId))

    /** Replaces one completed result only after a new local inference succeeds. */
    fun retranscribeSession(
        nightId: String,
        sessionId: String,
    ): Boolean = startTranscription(TranscriptionRequest.Replace(nightId, sessionId))

    /** Atomically replaces every completed transcript in one retained night. */
    fun retranscribeNight(nightId: String): Boolean =
        startTranscription(TranscriptionRequest.ReplaceNight(nightId))

    private fun initializeOnThread(appContext: Context) {
        var recoveredAttempts = 0
        var claimedGate = false
        try {
            val database = DreamLogDatabase.get(appContext)
            val runtime = RuntimeDependencies(
                appContext = appContext,
                modelManager = LocalAsrModelManager(appContext.filesDir),
                nightDao = database.nightDao(),
                transcriptionDao = database.transcriptionDao(),
                audioRootDirectory = File(appContext.filesDir, AUDIO_DIRECTORY),
                pauseStore = TranscriptionPauseStore(appContext),
            )

            // Recovery is deliberately engine- and model-independent and always runs first.
            recoveredAttempts = TranscriptionAttemptRecovery(runtime.transcriptionDao).recover()
            val resumable = findResumableProgress(
                runtime = runtime,
                storedPause = runtime.pauseStore.read(),
            )
            if (resumable == null) runtime.pauseStore.clear()
            claimedGate = CaptureTranscriptionOperationGate.tryClaimLocalOperation {
                CaptureRuntimeStore.snapshots.value.active
            }
            val status = if (claimedGate) runtime.modelManager.status() else null
            synchronized(lock) {
                dependencies = runtime
                clearActiveOperationLocked()
                val recoveredRevision = if (recoveredAttempts > 0) 1L else 0L
                if (status == null) {
                    installedModel = null
                    publishLocked(
                        mutableSnapshots.value.copy(
                            initialized = true,
                            modelPhase = TranscriptionModelPhase.VERIFICATION_DEFERRED,
                            modelMessage =
                                "Model verification will resume after capture or other local work.",
                            modelError = null,
                            historyRevision =
                                mutableSnapshots.value.historyRevision + recoveredRevision,
                        ),
                    )
                } else {
                    publishModelStatusLocked(
                        status = status,
                        initialized = true,
                        historyRevisionIncrement = recoveredRevision,
                    )
                }
                if (resumable != null) {
                    publishLocked(
                        mutableSnapshots.value
                            .withProgress(resumable.progress, incrementHistory = false)
                            .copy(
                                transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                                activeSessionId = null,
                                pauseReason = resumable.reason,
                                pauseMessage = resumable.message,
                                transcriptionError = resumable.message,
                            ),
                    )
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                clearActiveOperationLocked()
                publishLocked(
                    mutableSnapshots.value.copy(
                        initialized = false,
                        modelPhase = TranscriptionModelPhase.ERROR,
                        modelMessage = "Local transcription could not be initialized.",
                        modelError = safeModelFailure(failure),
                        transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                        transcriptionError =
                            "Interrupted transcription recovery could not be completed.",
                        historyRevision = mutableSnapshots.value.historyRevision +
                            if (recoveredAttempts > 0) 1L else 0L,
                    ),
                )
            }
        } finally {
            if (claimedGate) CaptureTranscriptionOperationGate.releaseLocalOperation()
        }
    }

    private fun verifyModelOnThread(
        runtime: RuntimeDependencies,
        operation: RuntimeOperation,
    ) {
        try {
            val status = runtime.modelManager.status()
            synchronized(lock) {
                if (activeOperation != operation) return
                clearActiveOperationLocked()
                publishModelStatusLocked(status)
            }
        } catch (failure: Throwable) {
            finishModelFailure(operation, "Model verification failed.", failure)
        }
    }

    private fun installModelOnThread(
        runtime: RuntimeDependencies,
        cancellation: AtomicBoolean,
    ) {
        try {
            val model = runtime.modelManager.install(
                isCancelled = {
                    cancellation.get() || CaptureRuntimeStore.snapshots.value.active
                },
                onProgress = ::publishInstallProgress,
            )
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.INSTALL_MODEL) return
                installedModel = model
                installCancellation = null
                clearActiveOperationLocked()
                publishLocked(
                    mutableSnapshots.value.copy(
                        initialized = true,
                        modelPhase = TranscriptionModelPhase.INSTALLED,
                        modelDownloadedBytes = model.totalModelBytes,
                        modelCurrentFile = null,
                        modelMessage = "The verified local transcription model is installed.",
                        modelError = null,
                    ),
                )
            }
        } catch (failure: Throwable) {
            val cancelled =
                failure is LocalAsrInstallCancelledException || cancellation.get() ||
                    CaptureRuntimeStore.snapshots.value.active
            val status = runCatching { runtime.modelManager.status() }.getOrNull()
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.INSTALL_MODEL) return
                installCancellation = null
                clearActiveOperationLocked()
                if (status != null) {
                    publishModelStatusLocked(
                        status = status,
                        messageOverride = if (cancelled) {
                            "Model installation was cancelled; partial files were removed."
                        } else {
                            null
                        },
                        errorOverride = if (cancelled) null else safeModelFailure(failure),
                    )
                } else {
                    installedModel = null
                    publishLocked(
                        mutableSnapshots.value.copy(
                            modelPhase = TranscriptionModelPhase.ERROR,
                            modelCurrentFile = null,
                            modelMessage = if (cancelled) {
                                "Model installation was cancelled."
                            } else {
                                "The local model could not be installed."
                            },
                            modelError = if (cancelled) null else safeModelFailure(failure),
                        ),
                    )
                }
            }
        }
    }

    private fun removeModelOnThread(runtime: RuntimeDependencies) {
        try {
            runtime.modelManager.remove()
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.REMOVE_MODEL) return
                clearActiveOperationLocked()
                installedModel = null
                publishLocked(
                    mutableSnapshots.value.copy(
                        modelPhase = TranscriptionModelPhase.NOT_INSTALLED,
                        modelDownloadedBytes = 0L,
                        modelCurrentFile = null,
                        modelMessage = "The local transcription model is not installed.",
                        modelError = null,
                    ),
                )
            }
        } catch (failure: Throwable) {
            val status = runCatching { runtime.modelManager.status() }.getOrNull()
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.REMOVE_MODEL) return
                clearActiveOperationLocked()
                if (status != null) {
                    publishModelStatusLocked(
                        status = status,
                        messageOverride = "The local model could not be removed completely.",
                        errorOverride = safeModelFailure(failure),
                    )
                } else {
                    installedModel = null
                    publishLocked(
                        mutableSnapshots.value.copy(
                            modelPhase = TranscriptionModelPhase.ERROR,
                            modelCurrentFile = null,
                            modelMessage = "The local model could not be removed.",
                            modelError = safeModelFailure(failure),
                        ),
                    )
                }
            }
        }
    }

    private fun startTranscription(request: TranscriptionRequest): Boolean {
        require(request.nightId.isNotBlank()) { "A night ID is required." }
        when (request) {
            is TranscriptionRequest.Night -> Unit
            is TranscriptionRequest.Resume -> Unit
            is TranscriptionRequest.Retry ->
                require(request.sessionId.isNotBlank()) { "A session ID is required." }

            is TranscriptionRequest.Replace ->
                require(request.sessionId.isNotBlank()) { "A session ID is required." }

            is TranscriptionRequest.ReplaceNight -> Unit
        }

        val runtime: RuntimeDependencies
        val cachedModel: InstalledLocalAsrModel?
        val verifyDeferred: Boolean
        synchronized(lock) {
            runtime = requireDependenciesLocked(modelOperation = false) ?: return false
            if (
                request is TranscriptionRequest.Night &&
                mutableSnapshots.value.resumeAvailable &&
                mutableSnapshots.value.nightId == request.nightId
            ) {
                publishLocked(
                    mutableSnapshots.value.copy(
                        transcriptionError = mutableSnapshots.value.resumeActionLabel,
                    ),
                )
                return false
            }
            if (!claimOperationLocked(RuntimeOperation.TRANSCRIBE, modelOperation = false)) {
                return false
            }
            cachedModel = installedModel
            verifyDeferred =
                cachedModel == null &&
                    mutableSnapshots.value.modelPhase ==
                    TranscriptionModelPhase.VERIFICATION_DEFERRED
            if (cachedModel == null && !verifyDeferred) {
                clearActiveOperationLocked()
                publishLocked(
                    mutableSnapshots.value.copy(
                        transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                        nightId = request.nightId,
                        transcriptionError =
                            "Install the local transcription model before transcribing.",
                    ),
                )
                return false
            }
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = if (verifyDeferred) {
                        TranscriptionModelPhase.VERIFYING
                    } else {
                        mutableSnapshots.value.modelPhase
                    },
                    modelMessage = if (verifyDeferred) {
                        "Verifying the installed local model."
                    } else {
                        mutableSnapshots.value.modelMessage
                    },
                    transcriptionPhase = TranscriptionRuntimePhase.RUNNING,
                    nightId = request.nightId,
                    activeSessionId = null,
                    pauseReason = null,
                    pauseMessage = null,
                    transcriptionError = null,
                ),
            )
            transcriptionStopDeferral.set(null)
        }

        val rawAudioUseLease = RawAudioUseRegistry.processWide.tryAcquireUse(request.nightId)
        if (rawAudioUseLease == null) {
            synchronized(lock) {
                clearActiveOperationLocked()
                publishLocked(
                    mutableSnapshots.value.copy(
                        transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                        nightId = request.nightId,
                        transcriptionError =
                            "Raw audio is being updated. Try transcription again in a moment.",
                    ),
                )
            }
            return false
        }
        try {
            TranscriptionProcessingService.start(runtime.appContext)
        } catch (failure: Throwable) {
            rawAudioUseLease.close()
            synchronized(lock) {
                clearActiveOperationLocked()
                publishLocked(
                    mutableSnapshots.value.copy(
                        transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                        transcriptionError =
                            "The user-visible transcription service could not be started. " +
                                "Retained audio remains available to resume.",
                    ),
                )
            }
            return false
        }
        val launched = launchFiniteThread("DreamLog local transcription") {
            rawAudioUseLease.use {
                transcribeOnThread(runtime, request, cachedModel, verifyDeferred)
            }
        }
        if (!launched) {
            rawAudioUseLease.close()
            TranscriptionProcessingService.stop(runtime.appContext)
        }
        return launched
    }

    private fun transcribeOnThread(
        runtime: RuntimeDependencies,
        request: TranscriptionRequest,
        cachedModel: InstalledLocalAsrModel?,
        verifyDeferred: Boolean,
    ) {
        var model = cachedModel
        var deferredVerificationFinished = !verifyDeferred
        var replacementCommitted = false
        try {
            if (verifyDeferred) {
                val status = runtime.modelManager.status()
                deferredVerificationFinished = true
                when (status) {
                    is LocalAsrModelStatus.Installed -> {
                        model = status.model
                        synchronized(lock) {
                            installedModel = status.model
                            publishLocked(
                                mutableSnapshots.value.copy(
                                    modelPhase = TranscriptionModelPhase.INSTALLED,
                                    modelDownloadedBytes = status.model.totalModelBytes,
                                    modelMessage =
                                        "The verified local transcription model is installed.",
                                    modelError = null,
                                ),
                            )
                        }
                    }

                    else -> {
                        synchronized(lock) {
                            publishModelStatusLocked(status)
                        }
                        throw ModelUnavailableForTranscriptionException()
                    }
                }
            }
            val verifiedModel = requireNotNull(model) {
                "A verified installed model is required."
            }
            val thermalStatusSource = AndroidTranscriptionThermalStatusSource(runtime.appContext)
            val reseedWarmLatch = runtime.pauseStore.read()?.let { pause ->
                pause.nightId == request.nightId &&
                    pause.reason == TranscriptionPauseReason.THERMAL
            } == true
            val thermalPolicy = TranscriptionThermalPolicy(
                currentSignal = thermalStatusSource::currentSignal,
                initiallyWarm = reseedWarmLatch,
            )
            val continuationGate = TranscriptionContinuationGate {
                transcriptionStopDeferral.get() ?: thermalPolicy.evaluate()
            }
            val initialDecision = continuationGate.evaluate()
            if (initialDecision is TranscriptionContinuationDecision.Defer) {
                finishTranscriptionPause(
                    progress = readRuntimeProgress(runtime, request.nightId),
                    deferral = initialDecision,
                )
                return
            }
            // A stored thermal pause has now crossed both hysteresis exit conditions. Remove it
            // before claiming a session so process-loss recovery reports the actual running
            // attempt instead of a stale warm-device reason.
            if (reseedWarmLatch) runtime.pauseStore.clear()
            val engine = SherpaParakeetTranscriptionEngine(verifiedModel)
            val progress = NightTranscriptionCoordinator(
                nightDao = runtime.nightDao,
                transcriptionDao = runtime.transcriptionDao,
                audioRootDirectory = runtime.audioRootDirectory,
                engine = engine,
                continuationGate = continuationGate,
            ).use { coordinator ->
                when (request) {
                    is TranscriptionRequest.Night -> coordinator.processNight(
                        nightId = request.nightId,
                        onProgress = ::publishTranscriptionProgress,
                    )

                    is TranscriptionRequest.Resume -> coordinator.resumeNight(
                        nightId = request.nightId,
                        onProgress = ::publishTranscriptionProgress,
                    )

                    is TranscriptionRequest.Retry -> coordinator.retrySession(
                        nightId = request.nightId,
                        sessionId = request.sessionId,
                        onProgress = ::publishTranscriptionProgress,
                    )

                    is TranscriptionRequest.Replace -> coordinator.retranscribeSession(
                        nightId = request.nightId,
                        sessionId = request.sessionId,
                        onProgress = ::publishTranscriptionProgress,
                    ).also { replacementCommitted = true }

                    is TranscriptionRequest.ReplaceNight -> coordinator.retranscribeNight(
                        nightId = request.nightId,
                        onProgress = ::publishTranscriptionProgress,
                    ).also { replacementCommitted = true }
                }
            }
            val pauseReason = progress.pauseReason
            val pauseMessage = progress.pauseMessage
            if (pauseReason != null && pauseMessage != null) {
                finishTranscriptionPause(
                    progress = progress,
                    deferral = TranscriptionContinuationDecision.Defer(
                        reason = pauseReason,
                        message = pauseMessage,
                        signal = progress.platformThermalStatus?.let { status ->
                            TranscriptionThermalSignal(
                                platformStatus = status,
                                batteryTemperatureDeciCelsius =
                                    progress.batteryTemperatureCelsius
                                        ?.times(10.0)
                                        ?.toInt(),
                            )
                        },
                    ),
                )
            } else {
                finishTranscriptionSuccess(progress)
            }
        } catch (failure: Throwable) {
            // Exceptions are handled inside the coordinator. A Throwable such as OOM can escape
            // after a session was claimed, so recover that durable RUNNING attempt before the UI
            // is allowed to offer retry.
            val recoveredAttempts = runCatching {
                TranscriptionAttemptRecovery(runtime.transcriptionDao).recover()
            }.getOrDefault(0)
            val recoveredProgress = if (request.replacesCompletedTranscripts) {
                null
            } else {
                runCatching { readRuntimeProgress(runtime, request.nightId) }.getOrNull()
            }
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.TRANSCRIBE) return
                clearActiveOperationLocked()
                val modelUnavailable = failure is ModelUnavailableForTranscriptionException
                val canResume = !modelUnavailable && recoveredProgress?.hasRemainingWork() == true
                if (!deferredVerificationFinished) {
                    installedModel = null
                }
                if (canResume) {
                    runtime.pauseStore.write(
                        nightId = request.nightId,
                        reason = TranscriptionPauseReason.PROCESS_INTERRUPTED,
                        message =
                            "Transcription stopped. Retained audio remains available to resume.",
                    )
                }
                val current = recoveredProgress
                    ?.let { mutableSnapshots.value.withProgress(it, incrementHistory = false) }
                    ?: mutableSnapshots.value
                publishLocked(
                    current.copy(
                        modelPhase = if (!deferredVerificationFinished) {
                            TranscriptionModelPhase.ERROR
                        } else {
                            mutableSnapshots.value.modelPhase
                        },
                        modelMessage = if (!deferredVerificationFinished) {
                            "Model verification failed."
                        } else {
                            mutableSnapshots.value.modelMessage
                        },
                        modelError = if (!deferredVerificationFinished) {
                            safeModelFailure(failure)
                        } else {
                            mutableSnapshots.value.modelError
                        },
                        transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                        activeSessionId = null,
                        pauseReason = if (canResume) {
                            TranscriptionPauseReason.PROCESS_INTERRUPTED
                        } else {
                            null
                        },
                        pauseMessage = if (canResume) {
                            "Transcription stopped. Retained audio remains available to resume."
                        } else {
                            null
                        },
                        transcriptionError = when {
                            modelUnavailable ->
                                "Install the local transcription model before transcribing."

                            request.replacesCompletedTranscripts && replacementCommitted ->
                                "Re-transcription completed, but its final status refresh failed. " +
                                    "Review the saved transcript; retained audio was kept."

                            request.replacesCompletedTranscripts ->
                                "Re-transcription stopped before replacement. The existing " +
                                    "transcript and retained audio were kept."

                            else ->
                                "Local transcription stopped. Retained audio remains available " +
                                    "to resume."
                        },
                        historyRevision = mutableSnapshots.value.historyRevision +
                            if (recoveredAttempts > 0) 1L else 0L,
                    ),
                )
            }
        } finally {
            TranscriptionProcessingService.stop(runtime.appContext)
        }
    }

    private fun publishInstallProgress(progress: LocalAsrInstallProgress) {
        synchronized(lock) {
            if (activeOperation != RuntimeOperation.INSTALL_MODEL) return@synchronized
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = if (installCancellation?.get() == true) {
                        TranscriptionModelPhase.CANCELLING
                    } else {
                        TranscriptionModelPhase.INSTALLING
                    },
                    modelDownloadedBytes = progress.completedBytes,
                    modelCurrentFile = progress.currentFile,
                    modelMessage = "Downloading and verifying the local model.",
                ),
            )
        }
    }

    private fun publishTranscriptionProgress(progress: NightTranscriptionProgress) {
        val context: Context? = synchronized(lock) {
            if (activeOperation != RuntimeOperation.TRANSCRIBE) return@synchronized null
            publishLocked(mutableSnapshots.value.withProgress(progress, incrementHistory = true))
            dependencies?.appContext
        }
        if (context != null) {
            runCatching {
                TranscriptionProcessingService.updateProgress(
                    context = context,
                    completedCount = progress.completedSessionCount,
                    eligibleCount = progress.eligibleSessionCount,
                )
            }
        }
    }

    private fun finishTranscriptionSuccess(progress: NightTranscriptionProgress) {
        synchronized(lock) {
            if (activeOperation != RuntimeOperation.TRANSCRIBE) return@synchronized
            clearActiveOperationLocked()
            val hasFailure = progress.failedSessionCount > 0 || progress.unavailableSessionCount > 0
            val failureMessage = if (hasFailure) {
                "Some retained sessions need review or retry."
            } else {
                null
            }
            if (hasFailure) {
                dependencies?.pauseStore?.write(
                    nightId = progress.nightId,
                    reason = TranscriptionPauseReason.SESSION_FAILURE,
                    message = requireNotNull(failureMessage),
                )
            } else {
                dependencies?.pauseStore?.clear()
            }
            publishLocked(
                mutableSnapshots.value
                    .withProgress(progress, incrementHistory = false)
                    .copy(
                        transcriptionPhase = if (hasFailure) {
                            TranscriptionRuntimePhase.ERROR
                        } else {
                            TranscriptionRuntimePhase.IDLE
                        },
                        activeSessionId = null,
                        pauseReason = if (hasFailure) {
                            TranscriptionPauseReason.SESSION_FAILURE
                        } else {
                            null
                        },
                        pauseMessage = failureMessage,
                        platformThermalStatus = null,
                        batteryTemperatureCelsius = null,
                        transcriptionError = failureMessage,
                    ),
            )
        }
    }

    private fun finishTranscriptionPause(
        progress: NightTranscriptionProgress,
        deferral: TranscriptionContinuationDecision.Defer,
    ) {
        synchronized(lock) {
            if (activeOperation != RuntimeOperation.TRANSCRIBE) return@synchronized
            clearActiveOperationLocked()
            dependencies?.pauseStore?.write(
                nightId = progress.nightId,
                reason = deferral.reason,
                message = deferral.message,
            )
            publishLocked(
                mutableSnapshots.value
                    .withProgress(progress, incrementHistory = false)
                    .copy(
                        transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                        activeSessionId = null,
                        pauseReason = deferral.reason,
                        pauseMessage = deferral.message,
                        platformThermalStatus = deferral.signal?.platformStatus
                            ?: progress.platformThermalStatus,
                        batteryTemperatureCelsius =
                            deferral.signal?.batteryTemperatureDeciCelsius?.div(10.0)
                                ?: progress.batteryTemperatureCelsius,
                        transcriptionError = deferral.message,
                    ),
            )
        }
    }

    internal fun onForegroundServiceTimeout() {
        requestForegroundStop(
            TranscriptionContinuationDecision.Defer(
                reason = TranscriptionPauseReason.FOREGROUND_TIMEOUT,
                message = FOREGROUND_TIMEOUT_MESSAGE,
            ),
        )
    }

    internal fun onForegroundServiceDestroyed() {
        requestForegroundStop(
            TranscriptionContinuationDecision.Defer(
                reason = TranscriptionPauseReason.PROCESS_INTERRUPTED,
                message = FOREGROUND_SERVICE_LOST_MESSAGE,
            ),
        )
    }

    private fun requestForegroundStop(deferral: TranscriptionContinuationDecision.Defer) {
        transcriptionStopDeferral.compareAndSet(null, deferral)
        synchronized(lock) {
            if (activeOperation != RuntimeOperation.TRANSCRIBE) return@synchronized
            val effective = transcriptionStopDeferral.get() ?: deferral
            publishLocked(
                mutableSnapshots.value.copy(
                    pauseReason = effective.reason,
                    pauseMessage = effective.message,
                    transcriptionError = effective.message,
                ),
            )
        }
    }

    private fun claimOperationLocked(
        operation: RuntimeOperation,
        modelOperation: Boolean,
    ): Boolean {
        return when {
            activeOperation != null -> {
                val message = "Wait for the current local operation to finish."
                publishLocked(
                    if (modelOperation) {
                        mutableSnapshots.value.copy(modelError = message)
                    } else {
                        mutableSnapshots.value.copy(transcriptionError = message)
                    },
                )
                false
            }

            !CaptureTranscriptionOperationGate.tryClaimLocalOperation {
                CaptureRuntimeStore.snapshots.value.active
            } -> {
                val message = "End night capture before running local model operations."
                publishLocked(
                    if (modelOperation) {
                        mutableSnapshots.value.copy(modelError = message)
                    } else {
                        mutableSnapshots.value.copy(
                            transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                            transcriptionError = message,
                        )
                    },
                )
                false
            }

            else -> {
                activeOperation = operation
                true
            }
        }
    }

    private fun clearActiveOperationLocked() {
        val claimedLocalGate = activeOperation?.let { it != RuntimeOperation.INITIALIZE } == true
        activeOperation = null
        if (claimedLocalGate) {
            CaptureTranscriptionOperationGate.releaseLocalOperation()
        }
    }

    private fun requireDependenciesLocked(modelOperation: Boolean): RuntimeDependencies? {
        dependencies?.let { return it }
        val message = if (initializationStarted) {
            "Local transcription is still preparing."
        } else {
            "Initialize local transcription before using it."
        }
        publishLocked(
            if (modelOperation) {
                mutableSnapshots.value.copy(modelError = message)
            } else {
                mutableSnapshots.value.copy(
                    transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                    transcriptionError = message,
                )
            },
        )
        return null
    }

    private fun publishModelStatusLocked(
        status: LocalAsrModelStatus,
        initialized: Boolean = mutableSnapshots.value.initialized,
        historyRevisionIncrement: Long = 0L,
        messageOverride: String? = null,
        errorOverride: String? = null,
    ) {
        val current = mutableSnapshots.value
        when (status) {
            LocalAsrModelStatus.NotInstalled -> {
                installedModel = null
                publishLocked(
                    current.copy(
                        initialized = initialized,
                        modelPhase = TranscriptionModelPhase.NOT_INSTALLED,
                        modelDownloadedBytes = 0L,
                        modelCurrentFile = null,
                        modelMessage = messageOverride
                            ?: "The local transcription model is not installed.",
                        modelError = errorOverride,
                        historyRevision = current.historyRevision + historyRevisionIncrement,
                    ),
                )
            }

            is LocalAsrModelStatus.Installed -> {
                installedModel = status.model
                publishLocked(
                    current.copy(
                        initialized = initialized,
                        modelPhase = TranscriptionModelPhase.INSTALLED,
                        modelDownloadedBytes = status.model.totalModelBytes,
                        modelCurrentFile = null,
                        modelMessage = messageOverride
                            ?: "The verified local transcription model is installed.",
                        modelError = errorOverride,
                        historyRevision = current.historyRevision + historyRevisionIncrement,
                    ),
                )
            }

            is LocalAsrModelStatus.Invalid -> {
                installedModel = null
                publishLocked(
                    current.copy(
                        initialized = initialized,
                        modelPhase = TranscriptionModelPhase.INVALID,
                        modelDownloadedBytes = 0L,
                        modelCurrentFile = null,
                        modelMessage = messageOverride
                            ?: "The local model is incomplete or does not match its manifest.",
                        modelError = errorOverride ?: status.reason,
                        historyRevision = current.historyRevision + historyRevisionIncrement,
                    ),
                )
            }
        }
    }

    private fun finishModelFailure(
        operation: RuntimeOperation,
        message: String,
        failure: Throwable,
    ) {
        synchronized(lock) {
            if (activeOperation != operation) return@synchronized
            clearActiveOperationLocked()
            if (
                operation == RuntimeOperation.VERIFY_MODEL ||
                operation == RuntimeOperation.REMOVE_MODEL
            ) {
                installedModel = null
            }
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = TranscriptionModelPhase.ERROR,
                    modelCurrentFile = null,
                    modelMessage = message,
                    modelError = safeModelFailure(failure),
                ),
            )
        }
    }

    private fun launchFiniteThread(
        name: String,
        block: () -> Unit,
    ): Boolean = try {
        Thread(block, name).apply {
            isDaemon = false
            start()
        }
        true
    } catch (failure: Throwable) {
        synchronized(lock) {
            clearActiveOperationLocked()
            installCancellation = null
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = TranscriptionModelPhase.ERROR,
                    modelError = safeModelFailure(failure),
                    transcriptionPhase = TranscriptionRuntimePhase.ERROR,
                    transcriptionError = "The local operation could not be started.",
                ),
            )
        }
        false
    }

    private fun publishLocked(snapshot: TranscriptionRuntimeSnapshot): TranscriptionRuntimeSnapshot {
        mutableSnapshots.value = snapshot
        return snapshot
    }

    private fun safeModelFailure(failure: Throwable): String {
        val detail = failure.message
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.take(MAX_ERROR_DETAIL_LENGTH)
        return if (detail.isNullOrBlank()) {
            "${failure.javaClass.simpleName}: local model operation failed."
        } else {
            "${failure.javaClass.simpleName}: $detail"
        }
    }

    private fun readRuntimeProgress(
        runtime: RuntimeDependencies,
        nightId: String,
    ): NightTranscriptionProgress {
        val source = requireNotNull(runtime.nightDao.readNight(nightId)) {
            "The requested night does not exist."
        }
        return source.toRuntimeProgress()
    }

    private fun findResumableProgress(
        runtime: RuntimeDependencies,
        storedPause: StoredTranscriptionPause?,
    ): ResumableProgress? {
        storedPause?.let { pause ->
            val progress = runtime.nightDao.readNight(pause.nightId)?.toRuntimeProgress()
            if (progress != null && progress.hasRemainingWork()) {
                return ResumableProgress(progress, pause.reason, pause.message)
            }
        }

        val source = runtime.nightDao.readHistory()
            .asSequence()
            .filter { night ->
                night.transcripts.any { transcript ->
                    transcript.transcript.state == ProcessingState.FAILED
                }
            }
            .sortedByDescending { it.night.endedAtEpochMillis ?: Long.MIN_VALUE }
            .firstOrNull { it.toRuntimeProgress().hasRemainingWork() }
            ?: return null
        val interrupted = source.transcripts.any { transcript ->
            transcript.transcript.failureDetail == TranscriptionAttemptRecovery.FAILURE_DETAIL
        }
        val reason = if (interrupted) {
            TranscriptionPauseReason.PROCESS_INTERRUPTED
        } else {
            TranscriptionPauseReason.SESSION_FAILURE
        }
        val message = if (reason == TranscriptionPauseReason.PROCESS_INTERRUPTED) {
            "Transcription stopped before completion. Retained audio remains available to resume."
        } else {
            "A retained session needs another transcription attempt."
        }
        return ResumableProgress(source.toRuntimeProgress(), reason, message)
    }

    private fun NightWithDetails.toRuntimeProgress(): NightTranscriptionProgress {
        val eligible = sessions.filter { session ->
            session.audioState == AudioEvidenceState.RETAINED &&
                session.finalizedAtEpochMillis != null
        }
        val transcriptBySession = transcripts.associateBy { it.transcript.sessionId }
        val states = eligible.mapNotNull { transcriptBySession[it.sessionId]?.transcript?.state }
        val completed = states.count { it == ProcessingState.COMPLETE }
        val failed = states.count { it == ProcessingState.FAILED }
        val running = states.count { it == ProcessingState.RUNNING }
        return NightTranscriptionProgress(
            nightId = night.nightId,
            persistedState = night.transcriptionState,
            eligibleSessionCount = eligible.size,
            unavailableSessionCount = sessions.size - eligible.size,
            completedSessionCount = completed,
            failedSessionCount = failed,
            runningSessionCount = running,
            pendingSessionCount = (eligible.size - completed - failed - running).coerceAtLeast(0),
            activeSessionId = null,
            retryableSessionIds = eligible.mapNotNull { session ->
                session.sessionId.takeIf {
                    transcriptBySession[it]?.transcript?.state == ProcessingState.FAILED
                }
            },
            requiresAppToRemainOpen = false,
        )
    }

    private fun NightTranscriptionProgress.hasRemainingWork(): Boolean =
        eligibleSessionCount > completedSessionCount &&
            (pendingSessionCount > 0 || retryableSessionIds.isNotEmpty())

    private data class ResumableProgress(
        val progress: NightTranscriptionProgress,
        val reason: TranscriptionPauseReason,
        val message: String,
    )

    private data class RuntimeDependencies(
        val appContext: Context,
        val modelManager: LocalAsrModelManager,
        val nightDao: NightDao,
        val transcriptionDao: TranscriptionDao,
        val audioRootDirectory: File,
        val pauseStore: TranscriptionPauseStore,
    )

    private enum class RuntimeOperation {
        INITIALIZE,
        VERIFY_MODEL,
        INSTALL_MODEL,
        REMOVE_MODEL,
        TRANSCRIBE,
    }

    private sealed interface TranscriptionRequest {
        val nightId: String

        val replacesCompletedTranscripts: Boolean
            get() = this is Replace || this is ReplaceNight

        data class Night(override val nightId: String) : TranscriptionRequest

        data class Resume(override val nightId: String) : TranscriptionRequest

        data class Retry(
            override val nightId: String,
            val sessionId: String,
        ) : TranscriptionRequest

        data class Replace(
            override val nightId: String,
            val sessionId: String,
        ) : TranscriptionRequest

        data class ReplaceNight(override val nightId: String) : TranscriptionRequest
    }

    private class ModelUnavailableForTranscriptionException : IllegalStateException()

    private const val AUDIO_DIRECTORY = "capture/audio"
    private const val MAX_ERROR_DETAIL_LENGTH = 240
    private const val FOREGROUND_TIMEOUT_MESSAGE =
        "Android's foreground media-processing time limit was reached. " +
            "Retained audio remains available to resume."
    private const val FOREGROUND_SERVICE_LOST_MESSAGE =
        "Android stopped the user-visible transcription service. The current session will " +
            "finish if possible, then retained audio remains available to resume."
}

internal fun TranscriptionRuntimeSnapshot.withProgress(
    progress: NightTranscriptionProgress,
    incrementHistory: Boolean,
): TranscriptionRuntimeSnapshot = copy(
    transcriptionPhase = TranscriptionRuntimePhase.RUNNING,
    nightId = progress.nightId,
    activeSessionId = progress.activeSessionId,
    eligibleSessionCount = progress.eligibleSessionCount,
    unavailableSessionCount = progress.unavailableSessionCount,
    completedSessionCount = progress.completedSessionCount,
    failedSessionCount = progress.failedSessionCount,
    runningSessionCount = progress.runningSessionCount,
    pendingSessionCount = progress.pendingSessionCount,
    retryableSessionIds = progress.retryableSessionIds,
    pauseReason = progress.pauseReason,
    pauseMessage = progress.pauseMessage,
    platformThermalStatus = progress.platformThermalStatus,
    batteryTemperatureCelsius = progress.batteryTemperatureCelsius,
    transcriptionError = null,
    historyRevision = historyRevision + if (incrementHistory) 1L else 0L,
)

private const val SELECTED_MODEL_BYTES = 663_043_117L
private const val MODEL_SIZE_MIB = 632.327
private const val MODEL_SIZE_LABEL = "632.327 MiB"
private const val APP_OPEN_MESSAGE =
    "Local transcription continues as finite foreground media processing; retained audio remains retryable."
