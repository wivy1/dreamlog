package com.wivy.dreamlog.enrichment

import java.util.concurrent.atomic.AtomicBoolean

data class EnrichmentNightSource(
    val nightId: String,
    val captureEnded: Boolean,
    val transcriptionComplete: Boolean,
    val rawTranscriptReviewable: Boolean,
    val segments: List<NightTranscriptSegment>,
)

data class EnrichmentEngineMetadata(
    val localeTag: String,
    val engineId: String,
    val engineVersion: String,
    val runtimeId: String,
    val runtimeVersion: String,
    val modelId: String,
    val modelVersion: String,
    val modelSha256: String,
    val backendId: String,
    val modelBytes: Long,
    val contextWindowTokens: Int,
    val maxTotalTokens: Int,
) {
    init {
        require(
            listOf(
                localeTag,
                engineId,
                engineVersion,
                runtimeId,
                runtimeVersion,
                modelId,
                modelVersion,
                backendId,
            ).all(String::isNotBlank),
        ) { "Enrichment engine metadata cannot be blank." }
        require(Regex("[0-9a-f]{64}").matches(modelSha256)) {
            "Enrichment model metadata needs a SHA-256 identity."
        }
        require(modelBytes > 0L) { "The enrichment model size must be positive." }
        require(contextWindowTokens > 0) { "The enrichment context window must be positive." }
        require(maxTotalTokens > 0) { "The enrichment total-token limit must be positive." }
    }
}

data class EnrichmentEngineResult(
    val rawJsonObject: String,
)

class EnrichmentInputTooLargeException : IllegalArgumentException(
    "The whole-night transcript exceeds the selected local model's measured context budget.",
)

/** A concrete adapter may wrap LiteRT-LM, but no Android/runtime type crosses this boundary. */
interface EnrichmentEngine : AutoCloseable {
    fun generate(request: EnrichmentModelRequest): EnrichmentEngineResult
}

/** Metadata access must not load native model state; [open] owns the finite resident engine. */
interface EnrichmentEngineFactory {
    val metadata: EnrichmentEngineMetadata

    fun open(): EnrichmentEngine
}

data class EnrichmentRunDescriptor(
    val inputFingerprintSha256: String,
    val promptVersion: String = ENRICHMENT_PROMPT_VERSION,
    val schemaVersion: Int = ENRICHMENT_SCHEMA_VERSION,
    val engine: EnrichmentEngineMetadata,
    val inferenceSkippedForEmptyInput: Boolean,
)

data class EnrichmentAttemptClaim(
    val nightId: String,
    val runId: String,
    val attempt: Int,
    val startedAtEpochMillis: Long,
) {
    init {
        require(nightId.isNotBlank()) { "An enrichment claim needs a night ID." }
        require(runId.isNotBlank()) { "An enrichment claim needs a run ID." }
        require(attempt > 0) { "An enrichment claim needs a positive attempt." }
        require(startedAtEpochMillis >= 0L) { "An enrichment start time cannot be negative." }
    }
}

data class PersistedEnrichmentFailure(
    val code: String,
    val detail: String,
    val retryable: Boolean,
)

/**
 * Persistence implementation contract:
 * - raw transcripts are read-only to every method;
 * - completion validates the input fingerprint again and writes all dreams/spans atomically;
 * - a rejected or failed completion leaves no partial replacement.
 */
interface NightEnrichmentStore {
    fun loadNightSource(nightId: String): EnrichmentNightSource?

    fun claimAttempt(
        nightId: String,
        descriptor: EnrichmentRunDescriptor,
        startedAtEpochMillis: Long,
    ): EnrichmentAttemptClaim?

    fun completeAttempt(
        claim: EnrichmentAttemptClaim,
        descriptor: EnrichmentRunDescriptor,
        result: ValidatedEnrichment,
        completedAtEpochMillis: Long,
    ): Boolean

    fun failAttempt(
        claim: EnrichmentAttemptClaim,
        failure: PersistedEnrichmentFailure,
        completedAtEpochMillis: Long,
    ): Boolean
}

fun interface EnrichmentOperationLease : AutoCloseable {
    override fun close()
}

/** Production adapts the existing capture/local-operation mutex to this interface. */
fun interface EnrichmentOperationGate {
    fun tryAcquire(): EnrichmentOperationLease?
}

enum class EnrichmentInterruptionCause {
    APP_HIDDEN,
    SCREEN_OFF_OR_LOCKED,
    USER_CANCELLED,
}

enum class EnrichmentFailureCode(
    val persistedValue: String,
    val safeDetail: String,
    val retryable: Boolean,
) {
    BUSY("busy", "Another operation is running.", true),
    NIGHT_NOT_FOUND("night_not_found", "Night unavailable.", false),
    NIGHT_ACTIVE("night_active", "End listening before enriching.", true),
    TRANSCRIPTION_INCOMPLETE(
        "transcription_incomplete",
        "Complete every raw transcript before enriching.",
        true,
    ),
    RAW_SOURCE_UNAVAILABLE(
        "raw_source_unavailable",
        "Raw transcript incomplete or unavailable.",
        false,
    ),
    INPUT_TOO_LARGE(
        "capture_input_too_large",
        "Capture exceeds the model's context limit. Update DreamLog to retry.",
        false,
    ),
    SOURCE_UNIT_TOO_LARGE(
        "source_unit_too_large",
        "Transcript source unit too large to enrich.",
        false,
    ),
    INVALID_SOURCE("invalid_source", "Raw transcript source invalid.", false),
    CLAIM_REJECTED("claim_rejected", "Enrichment could not start.", true),
    MODEL_LOAD_FAILED("model_load_failed", "Enrichment model could not load.", true),
    INFERENCE_FAILED("inference_failed", "Dream generation stopped.", true),
    APP_HIDDEN(
        "app_hidden",
        "Enrichment stopped: DreamLog was hidden.",
        true,
    ),
    SCREEN_OFF_OR_LOCKED(
        "screen_off_or_locked",
        "Enrichment stopped: screen off or phone locked.",
        true,
    ),
    USER_CANCELLED(
        "user_cancelled",
        "Enrichment cancelled.",
        true,
    ),
    UNKNOWN_PROCESS_LOSS(
        "unknown_process_loss",
        "DreamLog stopped before enrichment finished.",
        true,
    ),
    UNEXPECTED_FAILURE(
        "unexpected_failure",
        "Enrichment stopped unexpectedly.",
        true,
    ),
    OUTPUT_INVALID(
        "output_invalid",
        "Generated result failed source or format validation.",
        true,
    ),
    PERSISTENCE_FAILED(
        "persistence_failed",
        "Generated result could not be saved.",
        true,
    ),
}

internal fun EnrichmentInterruptionCause.failureCode(): EnrichmentFailureCode = when (this) {
    EnrichmentInterruptionCause.APP_HIDDEN -> EnrichmentFailureCode.APP_HIDDEN
    EnrichmentInterruptionCause.SCREEN_OFF_OR_LOCKED ->
        EnrichmentFailureCode.SCREEN_OFF_OR_LOCKED
    EnrichmentInterruptionCause.USER_CANCELLED -> EnrichmentFailureCode.USER_CANCELLED
}

internal fun EnrichmentFailureCode.persistedFailureDetail(): String =
    "$safeDetail [code=$persistedValue; retryable=$retryable]"

sealed interface EnrichmentRunOutcome {
    val nightId: String
    val rawFallbackAvailable: Boolean

    data class Completed(
        override val nightId: String,
        val runId: String,
        val attempt: Int,
        val inputFingerprintSha256: String,
        val dreamCount: Int,
        val inferenceSkippedForEmptyInput: Boolean,
        override val rawFallbackAvailable: Boolean,
    ) : EnrichmentRunOutcome

    data class Failure(
        override val nightId: String,
        val code: EnrichmentFailureCode,
        val retryable: Boolean,
        override val rawFallbackAvailable: Boolean,
    ) : EnrichmentRunOutcome
}

data class EnrichmentBatchProgress(
    val currentNightNumber: Int,
    val totalNightCount: Int,
    val completedNightCount: Int,
    val failedNightCount: Int,
    val operation: EnrichmentOperationSnapshot,
) {
    init {
        require(totalNightCount > 0) { "An enrichment batch needs at least one night." }
        require(currentNightNumber in 1..totalNightCount) {
            "The current enrichment night is outside the batch."
        }
        require(completedNightCount >= 0 && failedNightCount >= 0) {
            "Enrichment batch counts cannot be negative."
        }
        require(completedNightCount + failedNightCount <= totalNightCount) {
            "Enrichment batch counts exceed the requested nights."
        }
    }
}

data class EnrichmentBatchOutcome(
    val requestedNightIds: List<String>,
    val outcomes: List<EnrichmentRunOutcome>,
    val stoppedEarly: Boolean,
) {
    init {
        require(requestedNightIds.isNotEmpty()) { "An enrichment batch needs requested nights." }
        require(requestedNightIds.distinct().size == requestedNightIds.size) {
            "An enrichment batch cannot contain duplicate night IDs."
        }
        require(outcomes.size <= requestedNightIds.size) {
            "An enrichment batch produced too many outcomes."
        }
    }

    val completedNightCount: Int
        get() = outcomes.count { it is EnrichmentRunOutcome.Completed }

    val failedNightCount: Int
        get() = outcomes.count { it is EnrichmentRunOutcome.Failure }

    val unstartedNightCount: Int
        get() = requestedNightIds.size - outcomes.size
}

enum class EnrichmentOperationPhase {
    IDLE,
    PREPARING,
    LOADING_MODEL,
    GENERATING,
    VALIDATING,
    SAVING,
    COMPLETE,
    FAILED,
}

data class EnrichmentOperationSnapshot(
    val phase: EnrichmentOperationPhase = EnrichmentOperationPhase.IDLE,
    val nightId: String? = null,
    val attempt: Int? = null,
    val rawFallbackAvailable: Boolean = false,
    val failureCode: EnrichmentFailureCode? = null,
    val requiresAppToRemainOpen: Boolean = true,
)

/** Pure finite state for presentation; it never creates a worker, service, or resident engine. */
class AppOpenEnrichmentStateMachine {
    private var snapshot = EnrichmentOperationSnapshot()

    @Synchronized
    fun current(): EnrichmentOperationSnapshot = snapshot

    @Synchronized
    fun begin(nightId: String): EnrichmentOperationSnapshot {
        require(nightId.isNotBlank()) { "An enrichment operation needs a night ID." }
        check(snapshot.phase in TERMINAL_PHASES) { "An enrichment operation is already active." }
        snapshot = EnrichmentOperationSnapshot(
            phase = EnrichmentOperationPhase.PREPARING,
            nightId = nightId,
        )
        return snapshot
    }

    @Synchronized
    fun advance(
        phase: EnrichmentOperationPhase,
        attempt: Int?,
        rawFallbackAvailable: Boolean,
    ): EnrichmentOperationSnapshot {
        val allowed = ALLOWED_TRANSITIONS[snapshot.phase].orEmpty()
        check(phase in allowed) { "Illegal enrichment operation transition." }
        require(phase !in TERMINAL_PHASES) { "Use complete or fail for a terminal transition." }
        snapshot = snapshot.copy(
            phase = phase,
            attempt = attempt ?: snapshot.attempt,
            rawFallbackAvailable = rawFallbackAvailable,
            failureCode = null,
        )
        return snapshot
    }

    @Synchronized
    fun complete(
        attempt: Int,
        rawFallbackAvailable: Boolean,
    ): EnrichmentOperationSnapshot {
        check(EnrichmentOperationPhase.COMPLETE in ALLOWED_TRANSITIONS[snapshot.phase].orEmpty()) {
            "Illegal enrichment completion transition."
        }
        snapshot = snapshot.copy(
            phase = EnrichmentOperationPhase.COMPLETE,
            attempt = attempt,
            rawFallbackAvailable = rawFallbackAvailable,
            failureCode = null,
        )
        return snapshot
    }

    @Synchronized
    fun fail(
        code: EnrichmentFailureCode,
        attempt: Int?,
        rawFallbackAvailable: Boolean,
    ): EnrichmentOperationSnapshot {
        check(snapshot.phase !in TERMINAL_PHASES) { "No active enrichment operation can fail." }
        snapshot = snapshot.copy(
            phase = EnrichmentOperationPhase.FAILED,
            attempt = attempt ?: snapshot.attempt,
            rawFallbackAvailable = rawFallbackAvailable,
            failureCode = code,
        )
        return snapshot
    }

    private companion object {
        val TERMINAL_PHASES = setOf(
            EnrichmentOperationPhase.IDLE,
            EnrichmentOperationPhase.COMPLETE,
            EnrichmentOperationPhase.FAILED,
        )
        val ALLOWED_TRANSITIONS = mapOf(
            EnrichmentOperationPhase.PREPARING to setOf(
                EnrichmentOperationPhase.LOADING_MODEL,
                EnrichmentOperationPhase.GENERATING,
                EnrichmentOperationPhase.SAVING,
            ),
            EnrichmentOperationPhase.LOADING_MODEL to setOf(EnrichmentOperationPhase.GENERATING),
            EnrichmentOperationPhase.GENERATING to setOf(EnrichmentOperationPhase.VALIDATING),
            EnrichmentOperationPhase.VALIDATING to setOf(EnrichmentOperationPhase.SAVING),
            EnrichmentOperationPhase.SAVING to setOf(EnrichmentOperationPhase.COMPLETE),
        )
    }
}

/**
 * Synchronous application-owned orchestration. The caller owns the finite background thread and
 * must keep DreamLog open; the selected model exists only inside [EnrichmentEngineFactory.open].
 */
class NightEnrichmentCoordinator(
    private val store: NightEnrichmentStore,
    private val engineFactory: EnrichmentEngineFactory,
    private val operationGate: EnrichmentOperationGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val interruptionCause: () -> EnrichmentInterruptionCause? = { null },
    private val requestPartitioner: (OrderedNightTranscript) -> List<OrderedNightTranscript> =
        OrderedNightTranscript::enrichmentRequestPartitions,
) {
    val requiresAppToRemainOpen: Boolean = true
    val operationState = AppOpenEnrichmentStateMachine()

    private val runActive = AtomicBoolean(false)

    fun processNight(
        nightId: String,
        onProgress: (EnrichmentOperationSnapshot) -> Unit = {},
    ): EnrichmentRunOutcome {
        val batch = processBatch(listOf(nightId)) { progress ->
            onProgress(progress.operation)
        }
        return batch.outcomes.singleOrNull() ?: interruptionOutcome(
            nightId = nightId,
            rawFallbackAvailable = false,
        )
    }

    fun processBatch(
        nightIds: List<String>,
        onProgress: (EnrichmentBatchProgress) -> Unit = {},
    ): EnrichmentBatchOutcome {
        val requestedNightIds = nightIds
            .onEach { require(it.isNotBlank()) { "A batch night ID is required." } }
            .map(String::trim)
            .distinct()
        require(requestedNightIds.isNotEmpty()) {
            "At least one night is required for local enrichment."
        }
        if (!runActive.compareAndSet(false, true)) {
            return EnrichmentBatchOutcome(
                requestedNightIds = requestedNightIds,
                outcomes = listOf(busyOutcome(requestedNightIds.first())),
                stoppedEarly = true,
            )
        }

        var lease: EnrichmentOperationLease? = null
        val engineHolder = BatchEngineHolder()
        try {
            interruptionCause()?.let { cause ->
                markUnclaimedInterruption(requestedNightIds.first(), cause)
                return EnrichmentBatchOutcome(
                    requestedNightIds = requestedNightIds,
                    outcomes = emptyList(),
                    stoppedEarly = true,
                )
            }
            lease = operationGate.tryAcquire()
            interruptionCause()?.let { cause ->
                markUnclaimedInterruption(requestedNightIds.first(), cause)
                return EnrichmentBatchOutcome(
                    requestedNightIds = requestedNightIds,
                    outcomes = emptyList(),
                    stoppedEarly = true,
                )
            }
            if (lease == null) {
                val firstNightId = requestedNightIds.first()
                val callback: (EnrichmentOperationSnapshot) -> Unit = { operation ->
                    publishBatch(
                        onProgress,
                        EnrichmentBatchProgress(
                            currentNightNumber = 1,
                            totalNightCount = requestedNightIds.size,
                            completedNightCount = 0,
                            failedNightCount = 0,
                            operation = operation,
                        ),
                    )
                }
                publish(callback, operationState.begin(firstNightId))
                val failure = fail(
                    nightId = firstNightId,
                    code = EnrichmentFailureCode.BUSY,
                    rawFallbackAvailable = false,
                    claim = null,
                    onProgress = callback,
                )
                return EnrichmentBatchOutcome(
                    requestedNightIds = requestedNightIds,
                    outcomes = listOf(failure),
                    stoppedEarly = true,
                )
            }

            val outcomes = mutableListOf<EnrichmentRunOutcome>()
            var completedNightCount = 0
            var failedNightCount = 0
            var stoppedEarly = false
            for ((index, nightId) in requestedNightIds.withIndex()) {
                val beforeNightCause = interruptionCause()
                if (beforeNightCause != null) {
                    markUnclaimedInterruption(nightId, beforeNightCause)
                    stoppedEarly = true
                    break
                }
                var terminalOperation: EnrichmentOperationSnapshot? = null
                val outcome = processNightUnderLease(
                    nightId = nightId,
                    engineHolder = engineHolder,
                ) { operation ->
                    if (operation.phase in BATCH_TERMINAL_PHASES) {
                        terminalOperation = operation
                    } else {
                        publishBatch(
                            onProgress,
                            EnrichmentBatchProgress(
                                currentNightNumber = index + 1,
                                totalNightCount = requestedNightIds.size,
                                completedNightCount = completedNightCount,
                                failedNightCount = failedNightCount,
                                operation = operation,
                            ),
                        )
                    }
                }
                if (outcome == null) {
                    stoppedEarly = true
                    break
                }
                outcomes += outcome
                when (outcome) {
                    is EnrichmentRunOutcome.Completed -> completedNightCount += 1
                    is EnrichmentRunOutcome.Failure -> failedNightCount += 1
                }
                publishBatch(
                    onProgress,
                    EnrichmentBatchProgress(
                        currentNightNumber = index + 1,
                        totalNightCount = requestedNightIds.size,
                        completedNightCount = completedNightCount,
                        failedNightCount = failedNightCount,
                        operation = terminalOperation ?: operationState.current(),
                    ),
                )
                if (
                    outcome is EnrichmentRunOutcome.Failure &&
                    outcome.code in BATCH_STOP_FAILURES &&
                    index < requestedNightIds.lastIndex
                ) {
                    stoppedEarly = true
                    break
                }
            }
            return EnrichmentBatchOutcome(
                requestedNightIds = requestedNightIds,
                outcomes = outcomes,
                stoppedEarly = stoppedEarly,
            )
        } finally {
            runCatching { engineHolder.engine?.close() }
            engineHolder.engine = null
            runCatching { lease?.close() }
            runActive.set(false)
        }
    }

    private class BatchEngineHolder(
        var engine: EnrichmentEngine? = null,
    )

    private fun busyOutcome(nightId: String) = EnrichmentRunOutcome.Failure(
        nightId = nightId,
        code = EnrichmentFailureCode.BUSY,
        retryable = true,
        rawFallbackAvailable = false,
    )

    private fun interruptionOutcome(
        nightId: String,
        rawFallbackAvailable: Boolean,
    ): EnrichmentRunOutcome.Failure {
        val code = interruptionCause()?.failureCode()
            ?: EnrichmentFailureCode.UNKNOWN_PROCESS_LOSS
        return EnrichmentRunOutcome.Failure(
            nightId = nightId,
            code = code,
            retryable = true,
            rawFallbackAvailable = rawFallbackAvailable,
        )
    }

    private fun processNightUnderLease(
        nightId: String,
        engineHolder: BatchEngineHolder,
        onProgress: (EnrichmentOperationSnapshot) -> Unit,
    ): EnrichmentRunOutcome? {
        var claim: EnrichmentAttemptClaim? = null
        var rawFallbackAvailable = false
        interruptionCause()?.let { cause ->
            publish(onProgress, markUnclaimedInterruption(nightId, cause))
            return null
        }
        publish(onProgress, operationState.begin(nightId))
        val source = try {
                store.loadNightSource(nightId)
            } catch (_: Throwable) {
                return fail(
                    nightId,
                    EnrichmentFailureCode.PERSISTENCE_FAILED,
                    false,
                    null,
                    onProgress,
                )
            } ?: return fail(
                nightId,
                EnrichmentFailureCode.NIGHT_NOT_FOUND,
                false,
                null,
                onProgress,
            )
            rawFallbackAvailable = source.rawTranscriptReviewable
            if (source.nightId != nightId) {
                return fail(
                    nightId,
                    EnrichmentFailureCode.INVALID_SOURCE,
                    rawFallbackAvailable,
                    null,
                    onProgress,
                )
            }
            if (!source.captureEnded) {
                return fail(
                    nightId,
                    EnrichmentFailureCode.NIGHT_ACTIVE,
                    rawFallbackAvailable,
                    null,
                    onProgress,
                )
            }
            if (!source.transcriptionComplete) {
                return fail(
                    nightId,
                    EnrichmentFailureCode.TRANSCRIPTION_INCOMPLETE,
                    rawFallbackAvailable,
                    null,
                    onProgress,
                )
            }
            if (!source.rawTranscriptReviewable) {
                return fail(
                    nightId,
                    EnrichmentFailureCode.RAW_SOURCE_UNAVAILABLE,
                    false,
                    null,
                    onProgress,
                )
            }
            val input = try {
                OrderedNightTranscript.create(nightId, source.segments)
            } catch (_: IllegalArgumentException) {
                return fail(
                    nightId,
                    EnrichmentFailureCode.INVALID_SOURCE,
                    true,
                    null,
                    onProgress,
                )
            }
            val descriptor = EnrichmentRunDescriptor(
                inputFingerprintSha256 = input.fingerprintSha256,
                engine = engineFactory.metadata,
                inferenceSkippedForEmptyInput = input.isEmpty,
            )
            interruptionCause()?.let { cause ->
                publish(
                    onProgress,
                    markUnclaimedInterruption(
                        nightId = nightId,
                        cause = cause,
                        rawFallbackAvailable = true,
                    ),
                )
                return null
            }
            val startedAt = clock().coerceAtLeast(0L)
            claim = try {
                store.claimAttempt(nightId, descriptor, startedAt)
            } catch (_: Throwable) {
                return fail(
                    nightId,
                    EnrichmentFailureCode.PERSISTENCE_FAILED,
                    true,
                    null,
                    onProgress,
                )
            } ?: return fail(
                nightId,
                EnrichmentFailureCode.CLAIM_REJECTED,
                true,
                null,
                onProgress,
            )
            interruptionFailure(
                nightId = nightId,
                rawFallbackAvailable = true,
                claim = claim,
                onProgress = onProgress,
            )?.let { return it }

            val validated = if (input.isEmpty) {
                ValidatedEnrichment(
                    schemaVersion = ENRICHMENT_SCHEMA_VERSION,
                    attempt = claim.attempt,
                    inputFingerprintSha256 = input.fingerprintSha256,
                    dreams = emptyList(),
                )
            } else {
                val captureInputs = try {
                    requestPartitioner(input)
                } catch (_: EnrichmentInputTooLargeException) {
                    interruptionFailure(
                        nightId = nightId,
                        rawFallbackAvailable = true,
                        claim = claim,
                        onProgress = onProgress,
                    )?.let { return it }
                    return fail(
                        nightId,
                        EnrichmentFailureCode.SOURCE_UNIT_TOO_LARGE,
                        true,
                        claim,
                        onProgress,
                    )
                } catch (_: Throwable) {
                    interruptionFailure(
                        nightId = nightId,
                        rawFallbackAvailable = true,
                        claim = claim,
                        onProgress = onProgress,
                    )?.let { return it }
                    return fail(
                        nightId,
                        EnrichmentFailureCode.UNEXPECTED_FAILURE,
                        true,
                        claim,
                        onProgress,
                    )
                }
                val requests = try {
                    captureInputs.map { capture ->
                        EnrichmentPromptBuilder.build(capture, claim.attempt)
                    }
                } catch (_: EnrichmentInputTooLargeException) {
                    interruptionFailure(
                        nightId = nightId,
                        rawFallbackAvailable = true,
                        claim = claim,
                        onProgress = onProgress,
                    )?.let { return it }
                    return fail(
                        nightId,
                        EnrichmentFailureCode.SOURCE_UNIT_TOO_LARGE,
                        true,
                        claim,
                        onProgress,
                    )
                } catch (_: Throwable) {
                    interruptionFailure(
                        nightId = nightId,
                        rawFallbackAvailable = true,
                        claim = claim,
                        onProgress = onProgress,
                    )?.let { return it }
                    return fail(
                        nightId,
                        EnrichmentFailureCode.UNEXPECTED_FAILURE,
                        true,
                        claim,
                        onProgress,
                    )
                }
                val engine = engineHolder.engine ?: run {
                    interruptionFailure(
                        nightId = nightId,
                        rawFallbackAvailable = true,
                        claim = claim,
                        onProgress = onProgress,
                    )?.let { return it }
                    publish(
                        onProgress,
                        operationState.advance(
                            EnrichmentOperationPhase.LOADING_MODEL,
                            claim.attempt,
                            true,
                        ),
                    )
                    val opened = try {
                        engineFactory.open()
                    } catch (_: Throwable) {
                        interruptionFailure(
                            nightId = nightId,
                            rawFallbackAvailable = true,
                            claim = claim,
                            onProgress = onProgress,
                        )?.let { return it }
                        return fail(
                            nightId,
                            EnrichmentFailureCode.MODEL_LOAD_FAILED,
                            true,
                            claim,
                            onProgress,
                        )
                    }
                    engineHolder.engine = opened
                    opened
                }
                interruptionFailure(
                    nightId = nightId,
                    rawFallbackAvailable = true,
                    claim = claim,
                    onProgress = onProgress,
                )?.let { return it }
                val responses = try {
                    publish(
                        onProgress,
                        operationState.advance(
                            EnrichmentOperationPhase.GENERATING,
                            claim.attempt,
                            true,
                        ),
                    )
                    val generated = mutableListOf<EnrichmentEngineResult>()
                    for (request in requests) {
                        val beforeGeneration = interruptionFailure(
                            nightId = nightId,
                            rawFallbackAvailable = true,
                            claim = claim,
                            onProgress = onProgress,
                        )
                        if (beforeGeneration != null) return beforeGeneration
                        generated += engine.generate(request)
                        val afterGeneration = interruptionFailure(
                            nightId = nightId,
                            rawFallbackAvailable = true,
                            claim = claim,
                            onProgress = onProgress,
                        )
                        if (afterGeneration != null) return afterGeneration
                    }
                    generated
                } catch (_: EnrichmentInputTooLargeException) {
                    interruptionFailure(
                        nightId = nightId,
                        rawFallbackAvailable = true,
                        claim = claim,
                        onProgress = onProgress,
                    )?.let { return it }
                    return fail(
                        nightId,
                        EnrichmentFailureCode.INPUT_TOO_LARGE,
                        true,
                        claim,
                        onProgress,
                    )
                } catch (_: Throwable) {
                    interruptionFailure(
                        nightId = nightId,
                        rawFallbackAvailable = true,
                        claim = claim,
                        onProgress = onProgress,
                    )?.let { return it }
                    runCatching { engineHolder.engine?.close() }
                    engineHolder.engine = null
                    return fail(
                        nightId,
                        EnrichmentFailureCode.INFERENCE_FAILED,
                        true,
                        claim,
                        onProgress,
                    )
                }
                interruptionFailure(
                    nightId = nightId,
                    rawFallbackAvailable = true,
                    claim = claim,
                    onProgress = onProgress,
                )?.let { return it }
                publish(
                    onProgress,
                    operationState.advance(
                        EnrichmentOperationPhase.VALIDATING,
                        claim.attempt,
                        true,
                    ),
                )
                try {
                    val captureResults = captureInputs.zip(responses).map { (capture, response) ->
                        EnrichmentOutputParser.parse(
                            outputJson = response.rawJsonObject,
                            input = capture,
                            expectedAttempt = claim.attempt,
                        )
                    }
                    mergeCaptureEnrichments(
                        input = input,
                        captureInputs = captureInputs,
                        captureResults = captureResults,
                        expectedAttempt = claim.attempt,
                    )
                } catch (failure: EnrichmentOutputException) {
                    interruptionFailure(
                        nightId = nightId,
                        rawFallbackAvailable = true,
                        claim = claim,
                        onProgress = onProgress,
                    )?.let { return it }
                    return fail(
                        nightId,
                        EnrichmentFailureCode.OUTPUT_INVALID,
                        true,
                        claim,
                        onProgress,
                        failure.reason.safeDetail,
                    )
                }
            }

            interruptionFailure(
                nightId = nightId,
                rawFallbackAvailable = true,
                claim = claim,
                onProgress = onProgress,
            )?.let { return it }
            publish(
                onProgress,
                operationState.advance(
                    EnrichmentOperationPhase.SAVING,
                    claim.attempt,
                    true,
                ),
            )
            val completedAt = maxOf(claim.startedAtEpochMillis, clock().coerceAtLeast(0L))
            val saved = try {
                store.completeAttempt(claim, descriptor, validated, completedAt)
            } catch (_: Throwable) {
                false
            }
            if (!saved) {
                return fail(
                    nightId,
                    EnrichmentFailureCode.PERSISTENCE_FAILED,
                    true,
                    claim,
                    onProgress,
                )
            }
            publish(onProgress, operationState.complete(claim.attempt, true))
            return EnrichmentRunOutcome.Completed(
                nightId = nightId,
                runId = claim.runId,
                attempt = claim.attempt,
                inputFingerprintSha256 = input.fingerprintSha256,
                dreamCount = validated.dreams.size,
                inferenceSkippedForEmptyInput = input.isEmpty,
                rawFallbackAvailable = true,
            )
    }

    private fun markUnclaimedInterruption(
        nightId: String,
        cause: EnrichmentInterruptionCause,
        rawFallbackAvailable: Boolean = false,
    ): EnrichmentOperationSnapshot {
        if (
            operationState.current().phase == EnrichmentOperationPhase.IDLE ||
            operationState.current().phase in BATCH_TERMINAL_PHASES
        ) {
            operationState.begin(nightId)
        }
        return operationState.fail(
            code = cause.failureCode(),
            attempt = null,
            rawFallbackAvailable = rawFallbackAvailable,
        )
    }

    private fun interruptionFailure(
        nightId: String,
        rawFallbackAvailable: Boolean,
        claim: EnrichmentAttemptClaim,
        onProgress: (EnrichmentOperationSnapshot) -> Unit,
    ): EnrichmentRunOutcome.Failure? = interruptionCause()?.let { cause ->
        fail(
            nightId = nightId,
            code = cause.failureCode(),
            rawFallbackAvailable = rawFallbackAvailable,
            claim = claim,
            onProgress = onProgress,
        )
    }

    private fun fail(
        nightId: String,
        code: EnrichmentFailureCode,
        rawFallbackAvailable: Boolean,
        claim: EnrichmentAttemptClaim?,
        onProgress: (EnrichmentOperationSnapshot) -> Unit,
        failureDetail: String = code.safeDetail,
    ): EnrichmentRunOutcome.Failure {
        var effectiveCode = code
        if (claim != null) {
            val persisted = runCatching {
                store.failAttempt(
                    claim = claim,
                    failure = PersistedEnrichmentFailure(
                        code = code.persistedValue,
                        detail = failureDetail,
                        retryable = code.retryable,
                    ),
                    completedAtEpochMillis = maxOf(
                        claim.startedAtEpochMillis,
                        clock().coerceAtLeast(0L),
                    ),
                )
            }.getOrDefault(false)
            if (!persisted) effectiveCode = EnrichmentFailureCode.PERSISTENCE_FAILED
        }
        publish(
            onProgress,
            operationState.fail(effectiveCode, claim?.attempt, rawFallbackAvailable),
        )
        return EnrichmentRunOutcome.Failure(
            nightId = nightId,
            code = effectiveCode,
            retryable = effectiveCode.retryable,
            rawFallbackAvailable = rawFallbackAvailable,
        )
    }

    private fun publish(
        callback: (EnrichmentOperationSnapshot) -> Unit,
        snapshot: EnrichmentOperationSnapshot,
    ) {
        runCatching { callback(snapshot) }
    }

    private fun publishBatch(
        callback: (EnrichmentBatchProgress) -> Unit,
        progress: EnrichmentBatchProgress,
    ) {
        runCatching { callback(progress) }
    }

    private companion object {
        val BATCH_TERMINAL_PHASES = setOf(
            EnrichmentOperationPhase.COMPLETE,
            EnrichmentOperationPhase.FAILED,
        )
        val BATCH_STOP_FAILURES = setOf(
            EnrichmentFailureCode.BUSY,
            EnrichmentFailureCode.MODEL_LOAD_FAILED,
            EnrichmentFailureCode.INFERENCE_FAILED,
            EnrichmentFailureCode.PERSISTENCE_FAILED,
            EnrichmentFailureCode.APP_HIDDEN,
            EnrichmentFailureCode.SCREEN_OFF_OR_LOCKED,
            EnrichmentFailureCode.USER_CANCELLED,
            EnrichmentFailureCode.UNKNOWN_PROCESS_LOSS,
        )
    }
}
