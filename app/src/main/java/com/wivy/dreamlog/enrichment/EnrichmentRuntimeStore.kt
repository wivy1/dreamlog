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
import com.wivy.dreamlog.transcription.CaptureTranscriptionOperationGate
import com.wivy.dreamlog.transcription.TranscriptionRuntimeStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
    val modelMessage: String = "Checking the local enrichment model.",
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

    fun initialize(context: Context): Boolean {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (initializationStarted) return false
            initializationStarted = true
            applicationContext = appContext
            activeOperation = RuntimeOperation.INITIALIZE
            publishLocked(
                mutableSnapshots.value.copy(
                    modelPhase = EnrichmentModelPhase.VERIFYING,
                    modelMessage = "Preparing local enrichment recovery.",
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
                    modelMessage = "Verifying the installed local enrichment model.",
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
                        "Downloading the pinned enrichment model into private local storage.",
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
                modelMessage = "Cancelling the enrichment model download and cleaning partial data.",
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
                    modelMessage = "Removing the local enrichment model.",
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
                ),
            )
            EnrichmentRequest(runtime, installedModel)
        }
        return launchFiniteThread("DreamLog pending-night enrichment batch") {
            processBatchOnThread(request, requestedNightIds)
        }
    }

    private fun initializeOnThread(appContext: Context) {
        var recovered = 0
        var claimedGate = false
        try {
            val database = DreamLogDatabase.get(appContext)
            val runtime = RuntimeDependencies(
                modelManager = EnrichmentModelManager(appContext.filesDir),
                enrichmentDao = database.enrichmentDao(),
                store = RoomNightEnrichmentStore(database.nightDao(), database.enrichmentDao()),
                cacheDirectory = File(appContext.cacheDir, "enrichment-litert-lm"),
            )
            recovered = runtime.enrichmentDao.markStaleRunningRunsFailed(
                startedBeforeEpochMillis = Long.MAX_VALUE,
                recoveredAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                failureDetail = INTERRUPTED_ENRICHMENT_FAILURE_DETAIL,
            )
            val canVerify = !CaptureRuntimeStore.snapshots.value.active &&
                TranscriptionRuntimeStore.snapshots.value.initialized &&
                !TranscriptionRuntimeStore.snapshots.value.busy
            claimedGate = canVerify && CaptureTranscriptionOperationGate.tryClaimLocalOperation {
                CaptureRuntimeStore.snapshots.value.active
            }
            val status = if (claimedGate) runtime.modelManager.status() else null
            synchronized(lock) {
                dependencies = runtime
                activeOperation = null
                if (status == null) {
                    installedModel = null
                    publishLocked(
                        mutableSnapshots.value.copy(
                            initialized = true,
                            modelPhase = EnrichmentModelPhase.VERIFICATION_DEFERRED,
                            modelMessage =
                                "Enrichment model verification will resume after other local work.",
                            historyRevision =
                                mutableSnapshots.value.historyRevision + if (recovered > 0) 1 else 0,
                        ),
                    )
                } else {
                    publishModelStatusLocked(
                        status,
                        initialized = true,
                        historyRevisionIncrement = if (recovered > 0) 1 else 0,
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
                        modelMessage = "Local enrichment could not be initialized.",
                        modelError = "Private model or interrupted-run recovery failed.",
                        runtimePhase = EnrichmentRuntimePhase.ERROR,
                        runtimeError = "Local enrichment recovery needs attention.",
                        historyRevision =
                            mutableSnapshots.value.historyRevision + if (recovered > 0) 1 else 0,
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
                releaseModelOperationLocked()
                publishModelStatusLocked(status)
            }
        } catch (_: Throwable) {
            finishModelFailure(operation, "The enrichment model could not be verified.")
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
                        modelMessage = "The verified local enrichment model is installed.",
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
                            "Model installation was cancelled; partial data was removed."
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
                                "Model installation was cancelled."
                            } else {
                                "The local enrichment model could not be installed."
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
                        modelMessage = "The local enrichment model is not installed.",
                        modelError = null,
                    ),
                )
            }
        } catch (_: Throwable) {
            finishModelFailure(
                RuntimeOperation.REMOVE_MODEL,
                "The local enrichment model could not be removed.",
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
            synchronized(lock) {
                if (activeOperation != RuntimeOperation.ENRICH) return@synchronized
                activeOperation = null
                val previous = mutableSnapshots.value
                val processedNightCount = previous.batchProcessedNightCount
                publishLocked(
                    previous.copy(
                        runtimePhase = EnrichmentRuntimePhase.ERROR,
                        runtimeMessage = null,
                        runtimeError = "The enrichment batch stopped unexpectedly after " +
                            "$processedNightCount of ${nightIds.size} nights. Existing raw " +
                            "transcripts were not changed.",
                        batchUnstartedNightCount =
                            (nightIds.size - processedNightCount).coerceAtLeast(0),
                        historyRevision = previous.historyRevision +
                            if (processedNightCount > 0) 1L else 0L,
                    ),
                )
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
        synchronized(lock) {
            if (activeOperation != RuntimeOperation.ENRICH) return
            activeOperation = null
            val totalDreamCount = outcome.outcomes
                .filterIsInstance<EnrichmentRunOutcome.Completed>()
                .sumOf(EnrichmentRunOutcome.Completed::dreamCount)
            val successful = outcome.failedNightCount == 0 && outcome.unstartedNightCount == 0
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
                    runtimeError = if (successful) null else batchFailureMessage(outcome),
                    historyRevision = mutableSnapshots.value.historyRevision + 1L,
                ),
            )
        }
    }

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
                        modelMessage = messageOverride ?: "The local enrichment model is not installed.",
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
                        modelMessage = messageOverride ?: "The verified local enrichment model is installed.",
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
                        modelMessage = messageOverride ?: "The installed enrichment model is invalid.",
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
            if (activeOperation == RuntimeOperation.INITIALIZE) initializationStarted = false
            if (modelOperationOwnsGate) releaseModelOperationLocked() else activeOperation = null
            publishLocked(
                mutableSnapshots.value.copy(
                    runtimePhase = EnrichmentRuntimePhase.ERROR,
                    runtimeError = "The finite local enrichment thread could not start.",
                ),
            )
        }
        false
    }

    private fun operationMessage(phase: EnrichmentOperationPhase): String = when (phase) {
        EnrichmentOperationPhase.IDLE -> APP_OPEN_MESSAGE
        EnrichmentOperationPhase.PREPARING -> "Preparing the ordered raw transcript. $APP_OPEN_MESSAGE"
        EnrichmentOperationPhase.LOADING_MODEL -> "Loading the local model. $APP_OPEN_MESSAGE"
        EnrichmentOperationPhase.GENERATING -> "Creating the faithful reading version. $APP_OPEN_MESSAGE"
        EnrichmentOperationPhase.VALIDATING -> "Checking every source link and generated word."
        EnrichmentOperationPhase.SAVING -> "Saving the validated dream records atomically."
        EnrichmentOperationPhase.COMPLETE -> "Local enrichment completed."
        EnrichmentOperationPhase.FAILED -> "Local enrichment needs attention."
    }

    private fun batchOperationMessage(progress: EnrichmentBatchProgress): String =
        "Night ${progress.currentNightNumber} of ${progress.totalNightCount}. " +
            operationMessage(progress.operation.phase)

    private fun batchCompletionMessage(
        outcome: EnrichmentBatchOutcome,
        totalDreamCount: Int,
    ): String {
        val nightLabel = if (outcome.completedNightCount == 1) "night" else "nights"
        val dreamLabel = if (totalDreamCount == 1) "dream record" else "dream records"
        return "Enrichment complete: ${outcome.completedNightCount} $nightLabel processed and " +
            "$totalDreamCount $dreamLabel saved."
    }

    private fun batchFailureMessage(outcome: EnrichmentBatchOutcome): String {
        val lead = if (outcome.unstartedNightCount > 0) "Batch stopped" else "Batch finished"
        val counts = buildString {
            append("${outcome.completedNightCount} completed, ${outcome.failedNightCount} failed")
            if (outcome.unstartedNightCount > 0) {
                append(", ${outcome.unstartedNightCount} not started")
            }
            append('.')
        }
        val detail = outcome.outcomes
            .filterIsInstance<EnrichmentRunOutcome.Failure>()
            .lastOrNull()
            ?.code
            ?.safeDetail
            ?: "Local enrichment needs attention."
        return "$lead: $counts $detail Existing raw transcripts were not changed."
    }

    private data class RuntimeDependencies(
        val modelManager: EnrichmentModelManager,
        val enrichmentDao: EnrichmentDao,
        val store: RoomNightEnrichmentStore,
        val cacheDirectory: File,
    )

    private data class EnrichmentRequest(
        val runtime: RuntimeDependencies,
        val model: InstalledEnrichmentModel?,
    )

    private enum class RuntimeOperation {
        INITIALIZE,
        VERIFY_MODEL,
        INSTALL_MODEL,
        REMOVE_MODEL,
        ENRICH,
    }
}

private val SELECTED_BACKEND = LiteRtEnrichmentBackend.GPU
private const val BYTES_PER_MEBIBYTE = 1024.0 * 1024.0
private const val APP_OPEN_MESSAGE =
    "Keep DreamLog open while local enrichment runs; Android process loss makes it retryable."
internal const val INTERRUPTED_ENRICHMENT_FAILURE_DETAIL =
    "Local enrichment was interrupted before completion. The raw transcript remains reviewable. " +
        "[code=interrupted; retryable=true]"
