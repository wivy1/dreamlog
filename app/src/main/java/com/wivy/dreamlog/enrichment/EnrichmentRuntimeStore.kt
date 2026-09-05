package com.wivy.dreamlog.enrichment

import android.content.Context
import com.wivy.dreamlog.capture.CaptureRuntimeStore
import com.wivy.dreamlog.enrichment.litert.LiteRtEnrichmentBackend
import com.wivy.dreamlog.enrichment.litert.LiteRtEnrichmentEngineFactory
import com.wivy.dreamlog.enrichment.litert.ENRICHMENT_ENGINE_VERSION
import com.wivy.dreamlog.enrichment.litert.MAX_TOTAL_TOKENS
import com.wivy.dreamlog.enrichment.model.EnrichmentInstallCancelledException
import com.wivy.dreamlog.enrichment.model.EnrichmentInstallProgress
import com.wivy.dreamlog.enrichment.model.EnrichmentModelManifest
import com.wivy.dreamlog.enrichment.model.EnrichmentModelManager
import com.wivy.dreamlog.enrichment.model.EnrichmentModelStatus
import com.wivy.dreamlog.enrichment.model.InstalledEnrichmentModel
import com.wivy.dreamlog.enrichment.persistence.RoomNightEnrichmentStore
import com.wivy.dreamlog.history.DreamLogDatabase
import com.wivy.dreamlog.history.EnrichmentDao
import com.wivy.dreamlog.history.ProcessingState
import com.wivy.dreamlog.transcription.CaptureTranscriptionOperationGate
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EnrichmentModelPhase {
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

enum class EnrichmentRuntimePhase {
    IDLE,
    RUNNING,
    ERROR,
}

data class EnrichmentRuntimeSnapshot(
    val initialized: Boolean = false,
    val modelPhase: EnrichmentModelPhase = EnrichmentModelPhase.UNINITIALIZED,
    val modelDownloadedBytes: Long = 0L,
    val modelTotalBytes: Long = EnrichmentModelManifest.MODEL_BYTES,
    val modelCurrentFile: String? = null,
    val modelMessage: String = "Checking enrichment model.",
    val modelError: String? = null,
    val runtimePhase: EnrichmentRuntimePhase = EnrichmentRuntimePhase.IDLE,
    val operation: EnrichmentOperationSnapshot = EnrichmentOperationSnapshot(),
    val nightId: String? = null,
    val dreamCount: Int? = null,
    val batchCurrentNightNumber: Int = 0,
    val batchTotalNightCount: Int = 0,
    val batchCompletedNightCount: Int = 0,
    val batchFailedNightCount: Int = 0,
    val batchUnstartedNightCount: Int = 0,
    val runtimeMessage: String? = null,
    val runtimeError: String? = null,
    val interruptionCause: EnrichmentInterruptionCause? = null,
    val historyRevision: Long = 0L,
) {
    val modelSizeMiB: Double
        get() = modelTotalBytes.toDouble() / BYTES_PER_MEBIBYTE

    val busy: Boolean
        get() = modelPhase in BUSY_MODEL_PHASES || runtimePhase == EnrichmentRuntimePhase.RUNNING

    val canInstallModel: Boolean
        get() = initialized && !busy && modelPhase != EnrichmentModelPhase.INSTALLED

    val canRemoveModel: Boolean
        get() = initialized && !busy && modelPhase in setOf(
            EnrichmentModelPhase.INSTALLED,
            EnrichmentModelPhase.INVALID,
        )

    val batchProcessedNightCount: Int
        get() = batchCompletedNightCount + batchFailedNightCount

    companion object {
        private val BUSY_MODEL_PHASES = setOf(
            EnrichmentModelPhase.VERIFYING,
            EnrichmentModelPhase.INSTALLING,
            EnrichmentModelPhase.CANCELLING,
            EnrichmentModelPhase.REMOVING,
        )
    }
}

/**
 * Application-process owner for M05 model management and finite app-open enrichment.
 *
 * Durable truth remains in Room. This object never retains an Activity, starts no service or
 * worker, and creates the resident LiteRT-LM engine only inside one finite [processNights] thread.
 */
object EnrichmentRuntimeStore {
    private val lock = Any()
    private val mutableSnapshots = MutableStateFlow(EnrichmentRuntimeSnapshot())

    val snapshots: StateFlow<EnrichmentRuntimeSnapshot> = mutableSnapshots.asStateFlow()

    private var initializationStarted = false
    private var applicationContext: Context? = null
    private var dependencies: RuntimeDependencies? = null
    private var installedModel: InstalledEnrichmentModel? = null
    private var activeOperation: RuntimeOperation? = null
    private var modelOperationOwnsGate = false
    private var installCancellation: AtomicBoolean? = null
    private var activeInterruption: AtomicReference<EnrichmentInterruptionCause?>? = null

    fun initialize(context: Context): Boolean {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (initializationStarted) return false
            initializationStarted = true
            applicationContext = appContext
            activeOperation = RuntimeOperation.INITIALIZE
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = EnrichmentModelPhase.UNINITIALIZED,
                    modelMessage = "Recovering enrichment.",
                    modelError = null,
                    runtimeError = null,
                ),
            )
        }
        return launchFiniteThread("DreamLog enrichment startup") {
            initializeOnThread(appContext)
        }
    }

    fun refreshModelStatus(): Boolean {
        val retryContext = synchronized(lock) {
            if (dependencies == null && activeOperation == null) applicationContext else null
        }
        if (retryContext != null) return initialize(retryContext)

        val runtime = synchronized(lock) {
            val ready = dependencies ?: return false
            if (!claimModelOperationLocked(RuntimeOperation.VERIFY_MODEL)) return false
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = EnrichmentModelPhase.VERIFYING,
                    modelMessage = "Verifying model.",
                    modelError = null,
                    modelCurrentFile = null,
                    modelDownloadedBytes = 0L,
                ),
            )
            ready
        }
        return launchFiniteThread("DreamLog enrichment model verification") {
            verifyModelOnThread(runtime, RuntimeOperation.VERIFY_MODEL)
        }
    }

    fun installModel(): Boolean {
        val runtime: RuntimeDependencies
        val cancellation = AtomicBoolean(false)
        synchronized(lock) {
            runtime = dependencies ?: return false
            if (mutableSnapshots.value.modelPhase == EnrichmentModelPhase.INSTALLED) return false
            if (!claimModelOperationLocked(RuntimeOperation.INSTALL_MODEL)) return false
            installCancellation = cancellation
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = EnrichmentModelPhase.INSTALLING,
                    modelDownloadedBytes = 0L,
                    modelCurrentFile = null,
                    modelMessage =
                        "Downloading model.",
                    modelError = null,
                ),
            )
        }
        return launchFiniteThread("DreamLog enrichment model install") {
            installModelOnThread(runtime, cancellation)
        }
    }

    fun cancelModelInstall(): Boolean = synchronized(lock) {
        if (activeOperation != RuntimeOperation.INSTALL_MODEL) return false
        val cancellation = installCancellation ?: return false
        if (!cancellation.compareAndSet(false, true)) return false
        publishLocked(
            mutableSnapshots.value.copy(
                modelPhase = EnrichmentModelPhase.CANCELLING,
                modelMessage = "Cancelling download.",
            ),
        )
        true
    }

    fun removeModel(): Boolean {
        val runtime = synchronized(lock) {
            val ready = dependencies ?: return false
            if (!claimModelOperationLocked(RuntimeOperation.REMOVE_MODEL)) return false
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = EnrichmentModelPhase.REMOVING,
                    modelMessage = "Removing model.",
                    modelError = null,
                ),
            )
            ready
        }
        return launchFiniteThread("DreamLog enrichment model removal") {
            removeModelOnThread(runtime)
        }
    }

    fun processNight(nightId: String): Boolean = processNights(listOf(nightId))

    fun requestForegroundInterruption(cause: EnrichmentInterruptionCause): Boolean =
        synchronized(lock) {
            if (
                activeOperation != RuntimeOperation.ENRICH ||
                mutableSnapshots.value.runtimePhase != EnrichmentRuntimePhase.RUNNING
            ) {
                return@synchronized false
            }
            val signal = activeInterruption ?: return@synchronized false
            if (!signal.compareAndSet(null, cause)) return@synchronized false
            dependencies?.interruptionJournal?.record(cause)
            publishLocked(
                mutableSnapshots.value.copy(
                    interruptionCause = cause,
                    runtimeMessage = interruptionRequestedMessage(cause),
                ),
            )
            true
        }

    /**
     * Freezes one owner-selected batch for this app process. Leaving or losing the process makes
     * any started attempt retryable and leaves unstarted nights waiting.
     */
    fun processNights(nightIds: List<String>): Boolean {
        val requestedNightIds = nightIds
            .onEach { require(it.isNotBlank()) { "A batch night ID is required." } }
            .map(String::trim)
            .distinct()
        require(requestedNightIds.isNotEmpty()) {
            "At least one night is required for local enrichment."
        }
        val firstNightId = requestedNightIds.first()
        val request = synchronized(lock) {
            val runtime = dependencies ?: return false
            if (activeOperation != null) return false
            if (runtime.interruptionJournal.isActive()) {
                publishLocked(
                    mutableSnapshots.value.copy(
                        runtimePhase = EnrichmentRuntimePhase.ERROR,
                        runtimeMessage = null,
                        runtimeError =
                            "Restart DreamLog before enriching again.",
                    ),
                )
                return false
            }
            if (!runtime.interruptionJournal.begin(requestedNightIds)) {
                publishLocked(
                    mutableSnapshots.value.copy(
                        runtimePhase = EnrichmentRuntimePhase.ERROR,
                        runtimeMessage = null,
                        runtimeError =
                            "Could not prepare enrichment recovery. Try again.",
                    ),
                )
                return false
            }
            val interruption = AtomicReference<EnrichmentInterruptionCause?>(null)
            activeInterruption = interruption
            activeOperation = RuntimeOperation.ENRICH
            publishLocked(
                mutableSnapshots.value.copy(
                    runtimePhase = EnrichmentRuntimePhase.RUNNING,
                    operation = EnrichmentOperationSnapshot(
                        phase = EnrichmentOperationPhase.PREPARING,
                        nightId = firstNightId,
                    ),
                    nightId = firstNightId,
                    dreamCount = null,
                    batchCurrentNightNumber = 1,
                    batchTotalNightCount = requestedNightIds.size,
                    batchCompletedNightCount = 0,
                    batchFailedNightCount = 0,
                    batchUnstartedNightCount = requestedNightIds.size,
                    runtimeMessage = APP_OPEN_MESSAGE,
                    runtimeError = null,
                    interruptionCause = null,
                ),
            )
            EnrichmentRequest(runtime, installedModel, interruption)
        }
        val launched = launchFiniteThread("DreamLog pending-night enrichment batch") {
            processBatchOnThread(request, requestedNightIds)
        }
        if (!launched) {
            request.runtime.interruptionJournal.clear()
            synchronized(lock) { activeInterruption = null }
        }
        return launched
    }

    private fun initializeOnThread(appContext: Context) {
        var recovered = 0
        try {
            val database = DreamLogDatabase.get(appContext)
            val nightDao = database.nightDao()
            val runtime = RuntimeDependencies(
                modelManager = EnrichmentModelManager(appContext.filesDir),
                enrichmentDao = database.enrichmentDao(),
                store = RoomNightEnrichmentStore(nightDao, database.enrichmentDao()),
                cacheDirectory = File(appContext.cacheDir, "enrichment-litert-lm"),
                interruptionJournal = EnrichmentInterruptionJournal(appContext),
            )
            val interruptedNightIds = runtime.interruptionJournal.requestedNightIds()
            val recoveredCause = runtime.interruptionJournal.recoveredCause()
            recovered = runtime.enrichmentDao.markStaleRunningRunsFailed(
                startedBeforeEpochMillis = Long.MAX_VALUE,
                recoveredAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                failureDetail = recoveredEnrichmentFailureDetail(recoveredCause),
            )
            val interruptedNightStates = interruptedNightIds.map { nightId ->
                nightDao.readNight(nightId)?.night?.enrichmentState
            }
            val reportRecoveredInterruption = shouldReportRecoveredEnrichmentInterruption(
                recoveredRunCount = recovered,
                requestedNightStates = interruptedNightStates,
            )
            check(runtime.interruptionJournal.clear()) {
                "The private enrichment recovery marker could not be cleared."
            }
            synchronized(lock) {
                dependencies = runtime
                activeOperation = null
                installedModel = null
                publishLocked(
                    mutableSnapshots.value.copy(
                        initialized = true,
                        modelPhase = EnrichmentModelPhase.VERIFICATION_DEFERRED,
                        modelMessage = "Model check deferred until needed.",
                        historyRevision =
                            mutableSnapshots.value.historyRevision + if (recovered > 0) 1 else 0,
                    ),
                )
                if (reportRecoveredInterruption) {
                    publishLocked(
                        mutableSnapshots.value.copy(
                            runtimePhase = EnrichmentRuntimePhase.ERROR,
                            runtimeMessage = null,
                            runtimeError = recoveredEnrichmentMessage(recoveredCause),
                            interruptionCause = recoveredCause,
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            synchronized(lock) {
                initializationStarted = false
                activeOperation = null
                installedModel = null
                publishLocked(
                    mutableSnapshots.value.copy(
                        initialized = false,
                        modelPhase = EnrichmentModelPhase.ERROR,
                        modelMessage = "Enrichment setup failed.",
                        modelError = "Model setup or recovery failed.",
                        runtimePhase = EnrichmentRuntimePhase.ERROR,
                        runtimeError = "Enrichment recovery failed. Restart DreamLog.",
                        historyRevision =
                            mutableSnapshots.value.historyRevision + if (recovered > 0) 1 else 0,
                    ),
                )
            }
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
                releaseModelOperationLocked()
                publishModelStatusLocked(status)
            }
        } catch (_: Throwable) {
            finishModelFailure(operation, "Model verification failed.")
        }
    }

    private fun installModelOnThread(
        runtime: RuntimeDependencies,
        cancellation: AtomicBoolean,
    ) {
        try {
            val model = runtime.modelManager.install(
                isCancelled = cancellation::get,
                onProgress = ::publishInstallProgress,
            )
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.INSTALL_MODEL) return
                installCancellation = null
                installedModel = model
                releaseModelOperationLocked()
                publishLocked(
                    mutableSnapshots.value.copy(
                        initialized = true,
                        modelPhase = EnrichmentModelPhase.INSTALLED,
                        modelDownloadedBytes = model.artifactBytes,
                        modelCurrentFile = null,
                        modelMessage = "Model installed and verified.",
                        modelError = null,
                    ),
                )
            }
        } catch (failure: Throwable) {
            val cancelled = failure is EnrichmentInstallCancelledException || cancellation.get()
            val status = runCatching(runtime.modelManager::status).getOrNull()
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.INSTALL_MODEL) return
                installCancellation = null
                releaseModelOperationLocked()
                if (status != null) {
                    publishModelStatusLocked(
                        status,
                        messageOverride = if (cancelled) {
                            "Download cancelled."
                        } else {
                            null
                        },
                        errorOverride = if (cancelled) null else "Model installation failed.",
                    )
                } else {
                    installedModel = null
                    publishLocked(
                        mutableSnapshots.value.copy(
                            modelPhase = EnrichmentModelPhase.ERROR,
                            modelMessage = if (cancelled) {
                                "Download cancelled."
                            } else {
                                "Model installation failed."
                            },
                            modelError = if (cancelled) null else "Model installation failed.",
                        ),
                    )
                }
            }
        }
    }

    private fun removeModelOnThread(runtime: RuntimeDependencies) {
        try {
            runtime.modelManager.remove()
            if (
                runtime.cacheDirectory.exists() &&
                !runtime.cacheDirectory.deleteRecursively()
            ) {
                error("The private enrichment cache could not be removed.")
            }
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.REMOVE_MODEL) return
                installedModel = null
                releaseModelOperationLocked()
                publishLocked(
                    mutableSnapshots.value.copy(
                        modelPhase = EnrichmentModelPhase.NOT_INSTALLED,
                        modelDownloadedBytes = 0L,
                        modelMessage = "Model not installed.",
                        modelError = null,
                    ),
                )
            }
        } catch (_: Throwable) {
            finishModelFailure(
                RuntimeOperation.REMOVE_MODEL,
                "Model removal failed.",
            )
        }
    }

    private fun processBatchOnThread(
        request: EnrichmentRequest,
        nightIds: List<String>,
    ) {
        try {
            processBatchSafely(request, nightIds)
        } catch (_: Throwable) {
            val recovery = runCatching {
                reconcileRunningEnrichmentAttempts(
                    request = request,
                    failureDetail = unexpectedBatchFailureDetail(request),
                )
            }
            val recoveredRunCount = recovery.getOrDefault(0)
            synchronized(lock) {
                if (activeInterruption !== request.interruption) return@synchronized
                val recoveryCompleted = recovery.isSuccess &&
                    request.runtime.interruptionJournal.clear()
                val previous = mutableSnapshots.value
                val processedNightCount = previous.batchProcessedNightCount
                publishLocked(
                    previous.copy(
                        runtimePhase = EnrichmentRuntimePhase.ERROR,
                        runtimeMessage = null,
                        runtimeError = if (recoveryCompleted) {
                            "Enrichment stopped after $processedNightCount/${nightIds.size} nights. " +
                                "Retry unfinished nights."
                        } else {
                            "Enrichment recovery failed. Restart DreamLog before retrying."
                        },
                        batchUnstartedNightCount =
                            (nightIds.size - processedNightCount).coerceAtLeast(0),
                        historyRevision = previous.historyRevision +
                            if (processedNightCount > 0 || recoveredRunCount > 0) 1L else 0L,
                        interruptionCause = request.interruption.get(),
                    ),
                )
                activeOperation = null
                activeInterruption = null
            }
        } finally {
            synchronized(lock) {
                if (activeInterruption === request.interruption) {
                    activeInterruption = null
                }
            }
        }
    }

    private fun processBatchSafely(
        request: EnrichmentRequest,
        nightIds: List<String>,
    ) {
        val factory = request.model?.let { model ->
            LiteRtEnrichmentEngineFactory(
                installedModel = model,
                cacheDirectory = request.runtime.cacheDirectory,
                backend = SELECTED_BACKEND,
            )
        } ?: metadataOnlyFactory()
        val coordinator = NightEnrichmentCoordinator(
            store = request.runtime.store,
            engineFactory = factory,
            operationGate = EnrichmentOperationGate {
                if (
                    CaptureTranscriptionOperationGate.tryClaimLocalOperation {
                        CaptureRuntimeStore.snapshots.value.active
                    }
                ) {
                    EnrichmentOperationLease {
                        CaptureTranscriptionOperationGate.releaseLocalOperation()
                    }
                } else {
                    null
                }
            },
            interruptionCause = request.interruption::get,
        )
        val outcome = coordinator.processBatch(nightIds) { progress ->
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.ENRICH) return@synchronized
                publishLocked(
                    mutableSnapshots.value.copy(
                        runtimePhase = EnrichmentRuntimePhase.RUNNING,
                        operation = progress.operation,
                        nightId = progress.operation.nightId,
                        batchCurrentNightNumber = progress.currentNightNumber,
                        batchTotalNightCount = progress.totalNightCount,
                        batchCompletedNightCount = progress.completedNightCount,
                        batchFailedNightCount = progress.failedNightCount,
                        batchUnstartedNightCount =
                            (progress.totalNightCount - progress.currentNightNumber)
                                .coerceAtLeast(0),
                        runtimeMessage = batchOperationMessage(progress),
                        runtimeError = null,
                    ),
                )
            }
        }
        val recoveredRunCount = reconcileRunningEnrichmentAttempts(
            request = request,
            failureDetail = outcomeReconciliationFailureDetail(request, outcome),
        )
        synchronized(lock) {
            check(activeOperation == RuntimeOperation.ENRICH) {
                "The active enrichment batch changed before terminal publication."
            }
            check(activeInterruption === request.interruption) {
                "The active enrichment signal changed before terminal publication."
            }
            val journalCleared = request.runtime.interruptionJournal.clear()
            val totalDreamCount = outcome.outcomes
                .filterIsInstance<EnrichmentRunOutcome.Completed>()
                .sumOf(EnrichmentRunOutcome.Completed::dreamCount)
            val workSuccessful = outcome.failedNightCount == 0 &&
                outcome.unstartedNightCount == 0 &&
                recoveredRunCount == 0
            val successful = workSuccessful && journalCleared
            val completedInterruptionCause = if (successful) null else request.interruption.get()
            publishLocked(
                mutableSnapshots.value.copy(
                    runtimePhase = if (successful) {
                        EnrichmentRuntimePhase.IDLE
                    } else {
                        EnrichmentRuntimePhase.ERROR
                    },
                    operation = coordinator.operationState.current(),
                    nightId = outcome.outcomes.lastOrNull()?.nightId ?: nightIds.first(),
                    dreamCount = totalDreamCount,
                    batchCurrentNightNumber = outcome.outcomes.size.coerceAtLeast(1),
                    batchTotalNightCount = outcome.requestedNightIds.size,
                    batchCompletedNightCount = outcome.completedNightCount,
                    batchFailedNightCount = outcome.failedNightCount,
                    batchUnstartedNightCount = outcome.unstartedNightCount,
                    runtimeMessage = if (successful) {
                        batchCompletionMessage(outcome, totalDreamCount)
                    } else {
                        null
                    },
                    runtimeError = if (successful) {
                        null
                    } else if (!journalCleared) {
                        "Enrichment finished, but recovery cleanup failed. " +
                            "Restart DreamLog before retrying."
                    } else {
                        batchFailureMessage(outcome, completedInterruptionCause)
                    },
                    interruptionCause = completedInterruptionCause,
                    historyRevision = mutableSnapshots.value.historyRevision + 1L,
                ),
            )
            activeOperation = null
            activeInterruption = null
        }
    }

    private fun reconcileRunningEnrichmentAttempts(
        request: EnrichmentRequest,
        failureDetail: String,
    ): Int = request.runtime.enrichmentDao.markStaleRunningRunsFailed(
        startedBeforeEpochMillis = Long.MAX_VALUE,
        recoveredAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
        failureDetail = failureDetail,
    )

    private fun outcomeReconciliationFailureDetail(
        request: EnrichmentRequest,
        outcome: EnrichmentBatchOutcome,
    ): String = request.interruption.get()
        ?.failureCode()
        ?.persistedFailureDetail()
        ?: outcome.outcomes
            .filterIsInstance<EnrichmentRunOutcome.Failure>()
            .lastOrNull()
            ?.code
            ?.persistedFailureDetail()
        ?: EnrichmentFailureCode.UNEXPECTED_FAILURE.persistedFailureDetail()

    private fun unexpectedBatchFailureDetail(request: EnrichmentRequest): String =
        request.interruption.get()
            ?.failureCode()
            ?.persistedFailureDetail()
            ?: EnrichmentFailureCode.UNEXPECTED_FAILURE.persistedFailureDetail()

    private fun metadataOnlyFactory(): EnrichmentEngineFactory = object : EnrichmentEngineFactory {
        override val metadata = EnrichmentEngineMetadata(
            localeTag = "en-US",
            engineId = "dreamlog-litert-enrichment",
            engineVersion = ENRICHMENT_ENGINE_VERSION,
            runtimeId = "litert-lm-kotlin",
            runtimeVersion = "0.14.0",
            modelId = EnrichmentModelManifest.ID,
            modelVersion = EnrichmentModelManifest.REVISION,
            modelSha256 = EnrichmentModelManifest.MODEL_SHA256,
            backendId = SELECTED_BACKEND.persistedId,
            modelBytes = EnrichmentModelManifest.MODEL_BYTES,
            contextWindowTokens = 2_048,
            maxTotalTokens = MAX_TOTAL_TOKENS,
        )

        override fun open(): EnrichmentEngine = error("The local enrichment model is not installed.")
    }

    private fun claimModelOperationLocked(operation: RuntimeOperation): Boolean {
        if (activeOperation != null || CaptureRuntimeStore.snapshots.value.active) return false
        if (
            !CaptureTranscriptionOperationGate.tryClaimLocalOperation {
                CaptureRuntimeStore.snapshots.value.active
            }
        ) {
            return false
        }
        modelOperationOwnsGate = true
        activeOperation = operation
        return true
    }

    private fun releaseModelOperationLocked() {
        activeOperation = null
        if (modelOperationOwnsGate) {
            modelOperationOwnsGate = false
            CaptureTranscriptionOperationGate.releaseLocalOperation()
        }
    }

    private fun finishModelFailure(
        operation: RuntimeOperation,
        message: String,
    ) = synchronized(lock) {
        if (activeOperation != operation) return@synchronized
        installedModel = null
        releaseModelOperationLocked()
        publishLocked(
            mutableSnapshots.value.copy(
                modelPhase = EnrichmentModelPhase.ERROR,
                modelMessage = message,
                modelError = message,
            ),
        )
    }

    private fun publishInstallProgress(progress: EnrichmentInstallProgress) = synchronized(lock) {
        if (activeOperation != RuntimeOperation.INSTALL_MODEL) return@synchronized
        publishLocked(
            mutableSnapshots.value.copy(
                modelDownloadedBytes = progress.completedBytes,
                modelCurrentFile = progress.artifactName,
            ),
        )
    }

    private fun publishModelStatusLocked(
        status: EnrichmentModelStatus,
        initialized: Boolean = mutableSnapshots.value.initialized,
        historyRevisionIncrement: Int = 0,
        messageOverride: String? = null,
        errorOverride: String? = null,
    ) {
        when (status) {
            EnrichmentModelStatus.NotInstalled -> {
                installedModel = null
                publishLocked(
                    mutableSnapshots.value.copy(
                        initialized = initialized,
                        modelPhase = EnrichmentModelPhase.NOT_INSTALLED,
                        modelDownloadedBytes = 0L,
                        modelCurrentFile = null,
                        modelMessage = messageOverride ?: "Model not installed.",
                        modelError = errorOverride,
                        historyRevision =
                            mutableSnapshots.value.historyRevision + historyRevisionIncrement,
                    ),
                )
            }

            is EnrichmentModelStatus.Installed -> {
                installedModel = status.model
                publishLocked(
                    mutableSnapshots.value.copy(
                        initialized = initialized,
                        modelPhase = EnrichmentModelPhase.INSTALLED,
                        modelDownloadedBytes = status.model.artifactBytes,
                        modelCurrentFile = null,
                        modelMessage = messageOverride ?: "Model installed and verified.",
                        modelError = errorOverride,
                        historyRevision =
                            mutableSnapshots.value.historyRevision + historyRevisionIncrement,
                    ),
                )
            }

            is EnrichmentModelStatus.Invalid -> {
                installedModel = null
                publishLocked(
                    mutableSnapshots.value.copy(
                        initialized = initialized,
                        modelPhase = EnrichmentModelPhase.INVALID,
                        modelDownloadedBytes = 0L,
                        modelCurrentFile = null,
                        modelMessage = messageOverride ?: "Model invalid. Reinstall it.",
                        modelError = errorOverride ?: status.reason,
                        historyRevision =
                            mutableSnapshots.value.historyRevision + historyRevisionIncrement,
                    ),
                )
            }
        }
    }

    private fun publishLocked(snapshot: EnrichmentRuntimeSnapshot) {
        mutableSnapshots.value = snapshot
    }

    private fun launchFiniteThread(
        name: String,
        block: () -> Unit,
    ): Boolean = try {
        Thread(block, name).start()
        true
    } catch (_: Throwable) {
        synchronized(lock) {
            val initializing = activeOperation == RuntimeOperation.INITIALIZE
            if (initializing) initializationStarted = false
            if (modelOperationOwnsGate) releaseModelOperationLocked() else activeOperation = null
            publishLocked(mutableSnapshots.value.withThreadStartFailure(initializing))
        }
        false
    }

    private fun operationMessage(phase: EnrichmentOperationPhase): String = when (phase) {
        EnrichmentOperationPhase.IDLE -> APP_OPEN_MESSAGE
        EnrichmentOperationPhase.PREPARING -> "Preparing transcript. $APP_OPEN_MESSAGE"
        EnrichmentOperationPhase.LOADING_MODEL -> "Loading model. $APP_OPEN_MESSAGE"
        EnrichmentOperationPhase.GENERATING -> "Organizing dreams. $APP_OPEN_MESSAGE"
        EnrichmentOperationPhase.VALIDATING -> "Checking dreams against the transcript."
        EnrichmentOperationPhase.SAVING -> "Saving dreams."
        EnrichmentOperationPhase.COMPLETE -> "Enrichment complete."
        EnrichmentOperationPhase.FAILED -> "Enrichment needs attention."
    }

    private fun interruptionRequestedMessage(cause: EnrichmentInterruptionCause): String =
        when (cause) {
            EnrichmentInterruptionCause.APP_HIDDEN ->
                "Stopping enrichment: DreamLog was hidden."

            EnrichmentInterruptionCause.SCREEN_OFF_OR_LOCKED ->
                "Stopping enrichment: screen off or phone locked."

            EnrichmentInterruptionCause.USER_CANCELLED ->
                "Cancelling enrichment."
        }

    private fun batchOperationMessage(progress: EnrichmentBatchProgress): String =
        "Night ${progress.currentNightNumber}/${progress.totalNightCount}. " +
            operationMessage(progress.operation.phase)

    private fun batchCompletionMessage(
        outcome: EnrichmentBatchOutcome,
        totalDreamCount: Int,
    ): String {
        val nightLabel = if (outcome.completedNightCount == 1) "night" else "nights"
        val dreamLabel = if (totalDreamCount == 1) "dream" else "dreams"
        return "Enrichment complete: ${outcome.completedNightCount} $nightLabel, " +
            "$totalDreamCount $dreamLabel."
    }

    private fun batchFailureMessage(
        outcome: EnrichmentBatchOutcome,
        interruptionCause: EnrichmentInterruptionCause?,
    ): String {
        val lead = if (outcome.unstartedNightCount > 0) "Batch stopped" else "Batch finished"
        val counts = buildString {
            append("${outcome.completedNightCount} completed, ${outcome.failedNightCount} failed")
            if (outcome.unstartedNightCount > 0) {
                append(", ${outcome.unstartedNightCount} not started")
            }
            append('.')
        }
        val detail = interruptionCause?.failureCode()?.safeDetail
            ?: outcome.outcomes
                .filterIsInstance<EnrichmentRunOutcome.Failure>()
                .lastOrNull()
                ?.code
                ?.safeDetail
            ?: "Enrichment needs attention."
        return "$lead: $counts $detail"
    }

    private data class RuntimeDependencies(
        val modelManager: EnrichmentModelManager,
        val enrichmentDao: EnrichmentDao,
        val store: RoomNightEnrichmentStore,
        val cacheDirectory: File,
        val interruptionJournal: EnrichmentInterruptionJournal,
    )

    private data class EnrichmentRequest(
        val runtime: RuntimeDependencies,
        val model: InstalledEnrichmentModel?,
        val interruption: AtomicReference<EnrichmentInterruptionCause?>,
    )

    private enum class RuntimeOperation {
        INITIALIZE,
        VERIFY_MODEL,
        INSTALL_MODEL,
        REMOVE_MODEL,
        ENRICH,
    }
}

internal fun EnrichmentRuntimeSnapshot.withThreadStartFailure(
    initializing: Boolean,
): EnrichmentRuntimeSnapshot = copy(
    modelPhase = if (initializing) EnrichmentModelPhase.ERROR else modelPhase,
    runtimePhase = EnrichmentRuntimePhase.ERROR,
    runtimeError = "Enrichment could not start. Try again.",
)

internal fun recoveredEnrichmentFailureDetail(
    cause: EnrichmentInterruptionCause?,
): String = (cause?.failureCode() ?: EnrichmentFailureCode.UNKNOWN_PROCESS_LOSS)
    .persistedFailureDetail()

internal fun shouldReportRecoveredEnrichmentInterruption(
    recoveredRunCount: Int,
    requestedNightStates: List<String?>,
): Boolean = recoveredRunCount > 0 ||
    (
        requestedNightStates.isNotEmpty() &&
            requestedNightStates.any { state -> state != ProcessingState.COMPLETE }
        )

private fun recoveredEnrichmentMessage(
    cause: EnrichmentInterruptionCause?,
): String = when (cause) {
    EnrichmentInterruptionCause.APP_HIDDEN ->
        "Enrichment stopped: DreamLog was hidden. Retry unfinished nights."

    EnrichmentInterruptionCause.SCREEN_OFF_OR_LOCKED ->
        "Enrichment stopped: screen off or phone locked. Retry unfinished nights."

    EnrichmentInterruptionCause.USER_CANCELLED ->
        "Enrichment cancelled. Retry unfinished nights when ready."

    null ->
        "Enrichment interrupted. Retry unfinished nights."
}

private class EnrichmentInterruptionJournal(context: Context) {
    private val preferences = context.getSharedPreferences(
        ENRICHMENT_INTERRUPTION_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun begin(requestedNightIds: List<String>): Boolean {
        if (isActive()) return false
        return preferences.edit()
            .clear()
            .putBoolean(ENRICHMENT_INTERRUPTION_ACTIVE_KEY, true)
            .putStringSet(ENRICHMENT_INTERRUPTION_NIGHT_IDS_KEY, requestedNightIds.toSet())
            .commit()
    }

    fun record(cause: EnrichmentInterruptionCause): Boolean = preferences.edit()
        .putBoolean(ENRICHMENT_INTERRUPTION_ACTIVE_KEY, true)
        .putString(ENRICHMENT_INTERRUPTION_CAUSE_KEY, cause.name)
        .commit()

    fun isActive(): Boolean =
        preferences.getBoolean(ENRICHMENT_INTERRUPTION_ACTIVE_KEY, false)

    fun requestedNightIds(): List<String> {
        if (!isActive()) return emptyList()
        return preferences.getStringSet(ENRICHMENT_INTERRUPTION_NIGHT_IDS_KEY, emptySet())
            .orEmpty()
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
    }

    fun recoveredCause(): EnrichmentInterruptionCause? {
        if (!isActive()) return null
        val persisted = preferences.getString(ENRICHMENT_INTERRUPTION_CAUSE_KEY, null)
        return persisted?.let { value ->
            runCatching { EnrichmentInterruptionCause.valueOf(value) }.getOrNull()
        }
    }

    fun clear(): Boolean = preferences.edit().clear().commit()
}

private val SELECTED_BACKEND = LiteRtEnrichmentBackend.GPU
private const val BYTES_PER_MEBIBYTE = 1024.0 * 1024.0
private const val APP_OPEN_MESSAGE =
    "Keep DreamLog visible and your phone unlocked."
private const val ENRICHMENT_INTERRUPTION_PREFERENCES = "enrichment_interruption"
private const val ENRICHMENT_INTERRUPTION_ACTIVE_KEY = "active"
private const val ENRICHMENT_INTERRUPTION_CAUSE_KEY = "cause"
private const val ENRICHMENT_INTERRUPTION_NIGHT_IDS_KEY = "requested_night_ids"
internal const val INTERRUPTED_ENRICHMENT_FAILURE_DETAIL =
    "Local enrichment was interrupted before completion. The raw transcript remains reviewable. " +
        "[code=interrupted; retryable=true]"
