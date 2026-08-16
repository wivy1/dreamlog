package com.wivy.dreamlog.feasibility

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.wivy.dreamlog.enrichment.EnrichmentEngineMetadata
import com.wivy.dreamlog.enrichment.litert.ENRICHMENT_ENGINE_VERSION
import com.wivy.dreamlog.enrichment.litert.LiteRtEnrichmentBackend
import com.wivy.dreamlog.enrichment.litert.LiteRtEnrichmentEngine
import com.wivy.dreamlog.enrichment.litert.MAX_TOTAL_TOKENS
import com.wivy.dreamlog.enrichment.litert.MODEL_CONTEXT_TOKENS
import com.wivy.dreamlog.enrichment.model.EnrichmentModelDefinition
import com.wivy.dreamlog.enrichment.model.InstalledEnrichmentModel
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Fixture-only engine factory that permits a fixed benchmark candidate without widening the
 * production engine's pinned-model checks.
 */
@OptIn(ExperimentalApi::class)
internal class EnrichmentBenchmarkEngineFactory(
    private val installedModel: InstalledEnrichmentModel,
    private val selectedDefinition: EnrichmentModelDefinition,
    private val cacheDirectory: File,
    private val backend: LiteRtEnrichmentBackend,
    private val seed: Int,
) {
    init {
        require(installedModel.modelFile.isFile) { "The verified enrichment model is unavailable." }
        require(installedModel.id == selectedDefinition.id) {
            "The enrichment model ID does not match the selected benchmark candidate."
        }
        require(installedModel.revision == selectedDefinition.revision) {
            "The enrichment model revision does not match the selected benchmark candidate."
        }
        require(installedModel.artifactBytes == selectedDefinition.artifact.bytes) {
            "The enrichment model size does not match the selected benchmark candidate."
        }
        require(installedModel.artifactSha256 == selectedDefinition.artifact.sha256) {
            "The enrichment model identity does not match the selected benchmark candidate."
        }
        require(seed >= 0) { "The enrichment sampler seed cannot be negative." }
    }

    val metadata = EnrichmentEngineMetadata(
        localeTag = "en-US",
        engineId = "dreamlog-litert-enrichment",
        engineVersion = ENRICHMENT_ENGINE_VERSION,
        runtimeId = "litert-lm-kotlin",
        runtimeVersion = "0.14.0",
        modelId = installedModel.id,
        modelVersion = installedModel.revision,
        modelSha256 = installedModel.artifactSha256,
        backendId = backend.persistedId,
        modelBytes = installedModel.artifactBytes,
        contextWindowTokens = MODEL_CONTEXT_TOKENS,
        maxTotalTokens = MAX_TOTAL_TOKENS,
    )

    fun open(): LiteRtEnrichmentEngine {
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) {
            "The private benchmark cache directory is unavailable."
        }
        val modelCache = File(cacheDirectory, selectedDefinition.directoryName)
        check(modelCache.isDirectory || modelCache.mkdirs()) {
            "The private benchmark model cache is unavailable."
        }
        val backendCache = File(modelCache, backend.persistedId)
        check(backendCache.isDirectory || backendCache.mkdirs()) {
            "The private benchmark backend cache is unavailable."
        }

        // The fixture process serializes all actions, matching the production global-flag contract.
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        ExperimentalFlags.enableBenchmark = true
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
        try {
            var initializationMillis = 0L
            initializationMillis = measureNanoTime(engine::initialize) / NANOS_PER_MILLISECOND
            return LiteRtEnrichmentEngine(
                engine = engine,
                seed = seed,
                initializationMillis = initializationMillis,
                collectBenchmarkMetrics = true,
            )
        } catch (failure: Throwable) {
            runCatching(engine::close).onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
