package com.wivy.dreamlog.enrichment.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.wivy.dreamlog.enrichment.EnrichmentEngine
import com.wivy.dreamlog.enrichment.EnrichmentEngineFactory
import com.wivy.dreamlog.enrichment.EnrichmentEngineMetadata
import com.wivy.dreamlog.enrichment.EnrichmentEngineResult
import com.wivy.dreamlog.enrichment.EnrichmentInputTooLargeException
import com.wivy.dreamlog.enrichment.MAX_ENRICHMENT_USER_CONTENT_CHARACTERS
import com.wivy.dreamlog.enrichment.EnrichmentModelRequest
import com.wivy.dreamlog.enrichment.model.EnrichmentModelManifest
import com.wivy.dreamlog.enrichment.model.InstalledEnrichmentModel
import java.io.File
import kotlin.system.measureNanoTime

internal enum class LiteRtEnrichmentBackend(val persistedId: String) {
    CPU("cpu"),
    GPU("gpu"),
}

internal data class LiteRtGenerationMetrics(
    val engineInitializationMillis: Long,
    val generationMillis: Long,
    val renderedPromptCharacters: Int,
    val conversationTokenCount: Int,
    val benchmark: BenchmarkInfo?,
)

/**
 * Opens the selected LiteRT-LM model only for one finite enrichment run.
 *
 * Construction and [metadata] do not touch native model state. [open] initializes the engine on
 * the caller's background thread, and the returned adapter closes both conversation and engine
 * before control returns to the coordinator's validation/persistence path.
 */
@OptIn(ExperimentalApi::class)
internal class LiteRtEnrichmentEngineFactory(
    private val installedModel: InstalledEnrichmentModel,
    private val cacheDirectory: File,
    private val backend: LiteRtEnrichmentBackend,
    private val seed: Int = PRODUCTION_SEED,
    private val collectBenchmarkMetrics: Boolean = false,
) : EnrichmentEngineFactory {
    init {
        require(installedModel.modelFile.isFile) { "The verified enrichment model is unavailable." }
        require(installedModel.artifactBytes == EnrichmentModelManifest.MODEL_BYTES) {
            "The enrichment model size does not match the selected artifact."
        }
        require(installedModel.artifactSha256 == EnrichmentModelManifest.MODEL_SHA256) {
            "The enrichment model identity does not match the selected artifact."
        }
        require(seed >= 0) { "The enrichment sampler seed cannot be negative." }
    }

    override val metadata = EnrichmentEngineMetadata(
        localeTag = "en-US",
        engineId = ENGINE_ID,
        engineVersion = ENRICHMENT_ENGINE_VERSION,
        runtimeId = RUNTIME_ID,
        runtimeVersion = RUNTIME_VERSION,
        modelId = installedModel.id,
        modelVersion = installedModel.revision,
        modelSha256 = installedModel.artifactSha256,
        backendId = backend.persistedId,
        modelBytes = installedModel.artifactBytes,
        contextWindowTokens = MODEL_CONTEXT_TOKENS,
        maxTotalTokens = MAX_TOTAL_TOKENS,
    )

    override fun open(): EnrichmentEngine {
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) {
            "The private enrichment cache directory is unavailable."
        }
        val backendCache = File(cacheDirectory, backend.persistedId)
        check(backendCache.isDirectory || backendCache.mkdirs()) {
            "The private enrichment backend cache is unavailable."
        }

        // LiteRT-LM exposes these as process-global experimental switches. DreamLog serializes all
        // local model work through one operation lease, so no two conversations can race them.
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        ExperimentalFlags.enableBenchmark = collectBenchmarkMetrics
        // Native runtime messages are process-global and are not guaranteed to exclude prompt
        // material on failure paths. Suppress them completely while dream content is resident.
        Engine.setNativeMinLogSeverity(LogSeverity.INFINITY)

        val engine = Engine(
            EngineConfig(
                modelPath = installedModel.modelFile.absolutePath,
                backend = when (backend) {
                    LiteRtEnrichmentBackend.CPU -> Backend.CPU()
                    LiteRtEnrichmentBackend.GPU -> Backend.GPU()
                },
                maxNumTokens = MODEL_CONTEXT_TOKENS,
                cacheDir = backendCache.absolutePath,
            ),
        )
        var initializationMillis = 0L
        try {
            initializationMillis = nanosToMillis(measureNanoTime(engine::initialize))
            return LiteRtEnrichmentEngine(
                engine = engine,
                seed = seed,
                initializationMillis = initializationMillis,
                collectBenchmarkMetrics = collectBenchmarkMetrics,
            )
        } catch (failure: Throwable) {
            runCatching(engine::close).onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private companion object {
        // Seed 1 passed the complete repaired-contract fixture set and gives a retry a genuinely
        // different path from the repeated schema-invalid seed-0 response observed on Aug 12.
        const val PRODUCTION_SEED = 1
    }
}

@OptIn(ExperimentalApi::class)
internal class LiteRtEnrichmentEngine(
    private val engine: Engine,
    private val seed: Int,
    private val initializationMillis: Long,
    private val collectBenchmarkMetrics: Boolean,
) : EnrichmentEngine {
    private var closed = false

    var lastMetrics: LiteRtGenerationMetrics? = null
        private set

    override fun generate(request: EnrichmentModelRequest): EnrichmentEngineResult {
        check(!closed) { "The enrichment engine is already closed." }
        if (request.userContent.length > MAX_ENRICHMENT_USER_CONTENT_CHARACTERS) {
            throw EnrichmentInputTooLargeException()
        }

        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(request.systemInstruction),
                tools = emptyList(),
                samplerConfig = SamplerConfig(
                    topK = SAMPLER_TOP_K,
                    topP = SAMPLER_TOP_P,
                    temperature = SAMPLER_TEMPERATURE,
                    seed = seed,
                ),
                automaticToolCalling = false,
                extraContext = mapOf("enable_thinking" to false),
            ),
        )
        return conversation.use { activeConversation ->
            val renderedCharacters = activeConversation
                .renderMessageIntoString(com.google.ai.edge.litertlm.Message.user(request.userContent))
                .length
            lateinit var response: com.google.ai.edge.litertlm.Message
            val generationMillis = nanosToMillis(
                measureNanoTime {
                    response = activeConversation.sendMessage(
                        request.userContent,
                        extraContext = mapOf("enable_thinking" to false),
                    )
                },
            )
            val responseText = response.contents.toString()
            lastMetrics = LiteRtGenerationMetrics(
                engineInitializationMillis = initializationMillis,
                generationMillis = generationMillis,
                renderedPromptCharacters = renderedCharacters,
                conversationTokenCount = activeConversation.getTokenCount(),
                benchmark = if (collectBenchmarkMetrics) {
                    runCatching(activeConversation::getBenchmarkInfo).getOrNull()
                } else {
                    null
                },
            )
            EnrichmentEngineResult(
                rawJsonObject = responseText,
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.close()
    }
}

private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

private const val ENGINE_ID = "dreamlog-litert-enrichment"
internal const val ENRICHMENT_ENGINE_VERSION = "11"
private const val RUNTIME_ID = "litert-lm-kotlin"
private const val RUNTIME_VERSION = "0.14.0"
internal const val MODEL_CONTEXT_TOKENS = 2_048
// LiteRT-LM 0.14 exposes only the engine's total-token ceiling; Conversation has no separate
// generation-token cap. Record that enforced ceiling rather than claiming an unconfigured limit.
internal const val MAX_TOTAL_TOKENS = MODEL_CONTEXT_TOKENS
private const val SAMPLER_TOP_K = 20
private const val SAMPLER_TOP_P = 0.8
private const val SAMPLER_TEMPERATURE = 0.1
