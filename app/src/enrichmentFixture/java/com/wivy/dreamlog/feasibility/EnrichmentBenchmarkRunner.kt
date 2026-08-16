package com.wivy.dreamlog.feasibility

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.wivy.dreamlog.enrichment.ENRICHMENT_PROMPT_VERSION
import com.wivy.dreamlog.enrichment.ENRICHMENT_SCHEMA_VERSION
import com.wivy.dreamlog.enrichment.EnrichmentEngineMetadata
import com.wivy.dreamlog.enrichment.EnrichmentOutputException
import com.wivy.dreamlog.enrichment.EnrichmentOutputParser
import com.wivy.dreamlog.enrichment.EnrichmentPromptBuilder
import com.wivy.dreamlog.enrichment.ValidatedEnrichment
import com.wivy.dreamlog.enrichment.litert.LiteRtEnrichmentBackend
import com.wivy.dreamlog.enrichment.litert.LiteRtEnrichmentEngine
import com.wivy.dreamlog.enrichment.litert.LiteRtGenerationMetrics
import com.wivy.dreamlog.enrichment.model.EnrichmentInstallProgress
import com.wivy.dreamlog.enrichment.model.HttpsEnrichmentArtifactDownloader
import com.wivy.dreamlog.enrichment.model.EnrichmentModelManager
import com.wivy.dreamlog.enrichment.model.EnrichmentModelStatus
import com.wivy.dreamlog.enrichment.model.InstalledEnrichmentModel
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

internal enum class EnrichmentBenchmarkPlan(val displayName: String) {
    GPU_SMOKE("GPU smoke, seed 0"),
    CPU("CPU, seed 0"),
    GPU("GPU, seed 0"),
    GPU_RELIABILITY("GPU, seeds 0, 1, 2"),
}

internal data class EnrichmentBenchmarkProgress(
    val message: String,
    val fraction: Float? = null,
)

internal data class EnrichmentBenchmarkModelState(
    val installed: Boolean,
    val removable: Boolean,
    val description: String,
)

internal data class EnrichmentBenchmarkResult(
    val passed: Boolean,
    val cancelled: Boolean,
    val completedCalls: Int,
    val expectedCalls: Int,
    val reportFile: File,
    val summaryFile: File,
    val summary: String,
    val modelState: EnrichmentBenchmarkModelState,
)

internal class EnrichmentBenchmarkCancelledException : Exception("Benchmark cancelled.")

internal class EnrichmentBenchmarkModelUnavailableException(message: String) :
    IllegalStateException(message)

/**
 * Finite synthetic benchmark used only by the isolated enrichmentFixture application ID.
 *
 * This class never opens DreamLog's database or capture files. Each call creates and closes one
 * local model engine, and every artifact it writes contains synthetic fixture data only.
 */
internal class EnrichmentBenchmarkRunner(
    context: Context,
    private val modelSelection: EnrichmentBenchmarkModelSelection,
    private val isCancelled: () -> Boolean,
    private val onProgress: (EnrichmentBenchmarkProgress) -> Unit,
) {
    private val appContext = context.applicationContext
    private val modelDefinition = modelSelection.definition
    private val modelManager = EnrichmentModelManager(
        appFilesDirectory = appContext.filesDir,
        definition = modelDefinition,
        downloader = HttpsEnrichmentArtifactDownloader(),
    )
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val engineCacheDirectory = File(appContext.cacheDir, "enrichment-benchmark-litert")

    fun modelState(): EnrichmentBenchmarkModelState = when (val status = modelManager.status()) {
        EnrichmentModelStatus.NotInstalled -> EnrichmentBenchmarkModelState(
            installed = false,
            removable = false,
            description = "${modelDefinition.id} is not installed " +
                "(${formatBytes(modelDefinition.artifact.bytes)} download).",
        )

        is EnrichmentModelStatus.Invalid -> EnrichmentBenchmarkModelState(
            installed = false,
            removable = true,
            description = "Installed ${modelDefinition.id} is invalid: ${status.reason}",
        )

        is EnrichmentModelStatus.Installed -> status.model.toModelState()
    }

    fun installModel(): EnrichmentBenchmarkModelState {
        ensureActive()
        val installed = modelManager.install(
            isCancelled = ::cancelRequested,
            onProgress = ::publishInstallProgress,
        )
        ensureActive()
        return installed.toModelState()
    }

    fun removeModel(): EnrichmentBenchmarkModelState {
        ensureActive()
        val removed = modelManager.remove()
        ensureActive()
        return EnrichmentBenchmarkModelState(
            installed = false,
            removable = false,
            description = if (removed) {
                "${modelDefinition.id} was removed."
            } else {
                "${modelDefinition.id} was already absent."
            },
        )
    }

    fun run(
        plan: EnrichmentBenchmarkPlan,
        installIfMissing: Boolean,
    ): EnrichmentBenchmarkResult {
        ensureActive()
        val acquisition = acquireModel(installIfMissing)
        val outputDirectory = File(
            appContext.filesDir,
            "enrichment-benchmark-reports",
        ).also { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "The private benchmark report directory could not be created."
            }
        }
        val startedAtEpochMillis = System.currentTimeMillis()
        val reportFile = File(
            outputDirectory,
            "enrichment-benchmark-${plan.name.lowercase()}-$startedAtEpochMillis.json",
        )
        val summaryFile = File(
            outputDirectory,
            "enrichment-benchmark-${plan.name.lowercase()}-$startedAtEpochMillis.txt",
        )
        val startedThermalStatus = thermalStatus()
        val malformedOutputCheck = checkMalformedOutputRejection()
        val work = plan.workItems()
        val fixtures = if (plan == EnrichmentBenchmarkPlan.GPU_SMOKE) {
            EnrichmentBenchmarkFixtures.behaviorCases.take(2)
        } else {
            EnrichmentBenchmarkFixtures.behaviorCases + EnrichmentBenchmarkFixtures.contextCase
        }
        val expectedCallCount = work.size * fixtures.size
        val calls = mutableListOf<BenchmarkCallReport>()
        var cancelled = false

        benchmarkLoop@ for ((backend, seed) in work) {
            for (fixture in fixtures) {
                if (cancelRequested()) {
                    cancelled = true
                    break@benchmarkLoop
                }
                val callNumber = calls.size + 1
                onProgress(
                    EnrichmentBenchmarkProgress(
                        message = "${backend.persistedId.uppercase()} seed $seed — ${fixture.id}",
                        fraction = (callNumber - 1).toFloat() / expectedCallCount.toFloat(),
                    ),
                )
                calls += runCall(
                    installedModel = acquisition.model,
                    backend = backend,
                    seed = seed,
                    fixture = fixture,
                )
                onProgress(
                    EnrichmentBenchmarkProgress(
                        message = "Completed $callNumber of $expectedCallCount model calls",
                        fraction = callNumber.toFloat() / expectedCallCount.toFloat(),
                    ),
                )
            }
        }
        if (cancelRequested()) cancelled = true

        val endedAtEpochMillis = System.currentTimeMillis()
        val endedThermalStatus = thermalStatus()
        val maxThermalStatus = calls.mapNotNull { it.resources.thermalMax }
            .fold(startedThermalStatus) { current, candidate -> maxNullable(current, candidate) }
            .let { maxNullable(it, endedThermalStatus) }
        val passed = !cancelled &&
            malformedOutputCheck.passed &&
            calls.size == expectedCallCount &&
            calls.all(BenchmarkCallReport::passed)
        val modelState = acquisition.model.toModelState()
        val report = createReport(
            plan = plan,
            installedForRun = acquisition.installedForRun,
            model = acquisition.model,
            calls = calls,
            malformedOutputCheck = malformedOutputCheck,
            startedAtEpochMillis = startedAtEpochMillis,
            endedAtEpochMillis = endedAtEpochMillis,
            startedThermalStatus = startedThermalStatus,
            maximumThermalStatus = maxThermalStatus,
            endedThermalStatus = endedThermalStatus,
            expectedCallCount = expectedCallCount,
            cancelled = cancelled,
            passed = passed,
            reportFile = reportFile,
            summaryFile = summaryFile,
        )
        val summary = createSummary(
            plan = plan,
            model = acquisition.model,
            calls = calls,
            malformedOutputCheck = malformedOutputCheck,
            expectedCallCount = expectedCallCount,
            cancelled = cancelled,
            passed = passed,
            startedThermalStatus = startedThermalStatus,
            maximumThermalStatus = maxThermalStatus,
            endedThermalStatus = endedThermalStatus,
            reportFile = reportFile,
            summaryFile = summaryFile,
        )
        writeAtomically(reportFile, report.toString(2))
        writeAtomically(summaryFile, summary)

        return EnrichmentBenchmarkResult(
            passed = passed,
            cancelled = cancelled,
            completedCalls = calls.size,
            expectedCalls = expectedCallCount,
            reportFile = reportFile,
            summaryFile = summaryFile,
            summary = summary,
            modelState = modelState,
        )
    }

    private fun acquireModel(installIfMissing: Boolean): ModelAcquisition =
        when (val status = modelManager.status()) {
            is EnrichmentModelStatus.Installed -> ModelAcquisition(
                model = status.model,
                installedForRun = false,
            )

            EnrichmentModelStatus.NotInstalled,
            is EnrichmentModelStatus.Invalid,
            -> {
                if (!installIfMissing) {
                    throw EnrichmentBenchmarkModelUnavailableException(
                        "Install the selected local model before starting this benchmark.",
                    )
                }
                onProgress(
                    EnrichmentBenchmarkProgress(
                        "Auto-run explicitly authorized installation of the selected model.",
                    ),
                )
                ModelAcquisition(
                    model = modelManager.install(
                        isCancelled = ::cancelRequested,
                        onProgress = ::publishInstallProgress,
                    ),
                    installedForRun = true,
                )
            }
        }

    private fun runCall(
        installedModel: InstalledEnrichmentModel,
        backend: LiteRtEnrichmentBackend,
        seed: Int,
        fixture: EnrichmentBenchmarkFixture,
    ): BenchmarkCallReport {
        val request = EnrichmentPromptBuilder.build(fixture.input, BENCHMARK_ATTEMPT)
        val factory = EnrichmentBenchmarkEngineFactory(
            installedModel = installedModel,
            selectedDefinition = modelDefinition,
            cacheDirectory = engineCacheDirectory,
            backend = backend,
            seed = seed,
        )
        val sampler = ProcessResourceSampler(powerManager)
        var engine: LiteRtEnrichmentEngine? = null
        var runnerInitializationMillis: Long? = null
        var runnerGenerationMillis: Long? = null
        var runtimeMetrics: LiteRtGenerationMetrics? = null
        var schemaAccepted = false
        var reading: ValidatedEnrichment? = null
        var diagnosticRawResponse: String? = null
        var diagnosticRuntimeMessage: String? = null
        var resourceSamples: ProcessResourceSamples? = null
        var fatalFailure: Throwable? = null
        val failures = mutableListOf<String>()

        try {
            sampler.start()
            try {
                val initializationStart = SystemClock.elapsedRealtimeNanos()
                engine = factory.open()
                runnerInitializationMillis = elapsedMillis(initializationStart)

                val generationStart = SystemClock.elapsedRealtimeNanos()
                val result = engine.generate(request)
                runnerGenerationMillis = elapsedMillis(generationStart)
                runtimeMetrics = engine.lastMetrics
                diagnosticRawResponse = result.rawJsonObject
                try {
                    reading = EnrichmentOutputParser.parse(
                        outputJson = result.rawJsonObject,
                        input = fixture.input,
                        expectedAttempt = BENCHMARK_ATTEMPT,
                    )
                    schemaAccepted = true
                    failures += fixture.evaluate(checkNotNull(reading))
                        .map { expectation -> "fixture-expectation: $expectation" }
                } catch (failure: EnrichmentOutputException) {
                    failures +=
                        "schema-rejection: ${failure.message ?: "invalid enrichment output"}"
                }
            } catch (failure: Exception) {
                if (cancelRequested()) {
                    failures += "cancelled: model call ended after cancellation was requested"
                } else {
                    // This application contains synthetic fixtures only and is isolated from owner
                    // data, so retain a bounded native message to diagnose candidate compatibility.
                    diagnosticRuntimeMessage = failure.message?.take(MAX_DIAGNOSTIC_CHARACTERS)
                    failures += "runtime-failure: ${failure.javaClass.simpleName}"
                }
            }
        } catch (failure: Throwable) {
            fatalFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                engine?.close()
            } catch (failure: Throwable) {
                when {
                    fatalFailure != null -> fatalFailure.addSuppressed(failure)
                    failure is Exception ->
                        failures += "engine-close-failure: ${failure.javaClass.simpleName}"
                    else -> cleanupFailure = failure
                }
            }
            try {
                resourceSamples = sampler.finish()
            } catch (failure: Throwable) {
                when {
                    fatalFailure != null -> fatalFailure.addSuppressed(failure)
                    cleanupFailure != null -> cleanupFailure.addSuppressed(failure)
                    else -> cleanupFailure = failure
                }
            }
            cleanupFailure?.let { throw it }
        }
        val resources = checkNotNull(resourceSamples) {
            "Resource sampling finished without returning its final snapshot."
        }

        return BenchmarkCallReport(
            fixtureId = fixture.id,
            fixtureDescription = fixture.description,
            fixtureKind = if (fixture === EnrichmentBenchmarkFixtures.contextCase) {
                "context-stress"
            } else {
                "behavior"
            },
            backend = backend.persistedId,
            seed = seed,
            inputFingerprintSha256 = fixture.input.fingerprintSha256,
            inputSegmentCount = fixture.input.segments.size,
            requestUserCharacters = request.userContent.length,
            engineMetadata = factory.metadata,
            runnerInitializationMillis = runnerInitializationMillis,
            runnerGenerationMillis = runnerGenerationMillis,
            runtimeMetrics = runtimeMetrics,
            schemaAccepted = schemaAccepted,
            reading = reading,
            diagnosticRawResponse = diagnosticRawResponse,
            diagnosticRuntimeMessage = diagnosticRuntimeMessage,
            failures = failures,
            resources = resources,
        )
    }

    private fun checkMalformedOutputRejection(): MalformedOutputCheck {
        val input = EnrichmentBenchmarkFixtures.behaviorCases.first().input
        return try {
            EnrichmentOutputParser.parse(
                outputJson = "{\"parts\":[",
                input = input,
                expectedAttempt = BENCHMARK_ATTEMPT,
            )
            MalformedOutputCheck(
                passed = false,
                detail = "The strict parser unexpectedly accepted truncated JSON.",
            )
        } catch (_: EnrichmentOutputException) {
            MalformedOutputCheck(
                passed = true,
                detail = "The strict parser deterministically rejected truncated JSON.",
            )
        } catch (failure: Exception) {
            MalformedOutputCheck(
                passed = false,
                detail = "Unexpected rejection type: ${failure.javaClass.simpleName}.",
            )
        }
    }

    private fun createReport(
        plan: EnrichmentBenchmarkPlan,
        installedForRun: Boolean,
        model: InstalledEnrichmentModel,
        calls: List<BenchmarkCallReport>,
        malformedOutputCheck: MalformedOutputCheck,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
        startedThermalStatus: Int?,
        maximumThermalStatus: Int?,
        endedThermalStatus: Int?,
        expectedCallCount: Int,
        cancelled: Boolean,
        passed: Boolean,
        reportFile: File,
        summaryFile: File,
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("report_schema_version", REPORT_SCHEMA_VERSION)
        put("benchmark", "dreamlog-m06-semantic-grouping")
        put("model_candidate", modelSelection.intentValue)
        put("selection", plan.name.lowercase())
        put("selection_description", plan.displayName)
        put("started_at_epoch_millis", startedAtEpochMillis)
        put("ended_at_epoch_millis", endedAtEpochMillis)
        put("duration_millis", endedAtEpochMillis - startedAtEpochMillis)
        put("cancelled", cancelled)
        put("passed", passed)
        put("expected_call_count", expectedCallCount)
        put("completed_call_count", calls.size)
        put("installed_for_this_run", installedForRun)
        put("privacy", org.json.JSONObject().apply {
            put("source", "synthetic fixtures only")
            put("owner_data_read", false)
            put("owner_prompts_or_outputs_stored", false)
            put("synthetic_raw_responses_included", true)
            put("synthetic_validated_readings_included", true)
        })
        put("schema", org.json.JSONObject().apply {
            put("enrichment_schema_version", ENRICHMENT_SCHEMA_VERSION)
            put("prompt_version", ENRICHMENT_PROMPT_VERSION)
            put("response_format", "plain-json-object")
            put("response_contract", EnrichmentPromptBuilder.responseContractDescription)
        })
        put("device", deviceJson())
        put("model", modelJson(model))
        put("storage", storageJson(model, reportFile, summaryFile))
        put("thermal", thermalJson(startedThermalStatus, maximumThermalStatus, endedThermalStatus))
        put("malformed_output_rejection", malformedOutputCheck.toJson())
        put("calls", org.json.JSONArray().apply {
            calls.forEach { call -> put(call.toJson()) }
        })
        put("failures", org.json.JSONArray().apply {
            if (!malformedOutputCheck.passed) put(malformedOutputCheck.detail)
            if (calls.size != expectedCallCount) {
                put("completed ${calls.size} of $expectedCallCount expected calls")
            }
            calls.forEach { call ->
                call.failures.forEach { failure ->
                    put("${call.backend}/seed-${call.seed}/${call.fixtureId}: $failure")
                }
            }
        })
    }

    private fun createSummary(
        plan: EnrichmentBenchmarkPlan,
        model: InstalledEnrichmentModel,
        calls: List<BenchmarkCallReport>,
        malformedOutputCheck: MalformedOutputCheck,
        expectedCallCount: Int,
        cancelled: Boolean,
        passed: Boolean,
        startedThermalStatus: Int?,
        maximumThermalStatus: Int?,
        endedThermalStatus: Int?,
        reportFile: File,
        summaryFile: File,
    ): String = buildString {
        appendLine("DreamLog M06 semantic grouping benchmark")
        appendLine("Result: ${when {
            cancelled -> "CANCELLED"
            passed -> "PASS"
            else -> "FAIL"
        }}")
        appendLine("Selection: ${plan.displayName}")
        appendLine("Model candidate: ${modelSelection.intentValue}")
        appendLine("Calls: ${calls.size}/$expectedCallCount completed; ${calls.count { it.passed }} passed")
        appendLine(
            "Malformed-output rejection: ${if (malformedOutputCheck.passed) "PASS" else "FAIL"}",
        )
        appendLine("Model: ${model.id}@${model.revision}")
        appendLine("Model SHA-256: ${model.artifactSha256}")
        appendLine("Model bytes: ${model.artifactBytes}")
        appendLine(
            "Thermal: ${thermalName(startedThermalStatus)} -> " +
                "max ${thermalName(maximumThermalStatus)} -> ${thermalName(endedThermalStatus)}",
        )
        val peakPss = calls.mapNotNull { it.resources.pssPeakKiB }.maxOrNull()
        appendLine("Peak sampled process PSS: ${peakPss?.let { "$it KiB" } ?: "unavailable"}")
        val failures = calls.flatMap { call ->
            call.failures.map { failure ->
                "${call.backend}/seed-${call.seed}/${call.fixtureId}: $failure"
            }
        }
        if (!malformedOutputCheck.passed || failures.isNotEmpty()) {
            appendLine("Failures:")
            if (!malformedOutputCheck.passed) appendLine("- ${malformedOutputCheck.detail}")
            failures.forEach { failure -> appendLine("- $failure") }
        }
        appendLine("JSON report: ${reportFile.absolutePath}")
        appendLine("Text summary: ${summaryFile.absolutePath}")
        appendLine("Synthetic raw responses and validated readings appear only in the JSON report.")
    }

    private fun deviceJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("hardware", Build.HARDWARE)
        put("android_release", Build.VERSION.RELEASE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("build_fingerprint", Build.FINGERPRINT)
        put("supported_abis", org.json.JSONArray(Build.SUPPORTED_ABIS.toList()))
    }

    private fun modelJson(model: InstalledEnrichmentModel): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("id", model.id)
            put("revision", model.revision)
            put("sha256", model.artifactSha256)
            put("bytes", model.artifactBytes)
            put("license_spdx", model.licenseSpdxIdentifier)
            put("license_url", model.licenseSource.toASCIIString())
            put("file_name", model.modelFile.name)
            put("verified_file_length", model.modelFile.length())
        }

    private fun storageJson(
        model: InstalledEnrichmentModel,
        reportFile: File,
        summaryFile: File,
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("model_private_path", model.modelFile.absolutePath)
        put("model_storage_total_bytes", model.modelFile.totalSpace)
        put("model_storage_usable_bytes", model.modelFile.usableSpace)
        put("runtime_cache_private_path", engineCacheDirectory.absolutePath)
        put("report_private_path", reportFile.absolutePath)
        put("summary_private_path", summaryFile.absolutePath)
    }

    private fun thermalJson(
        before: Int?,
        maximum: Int?,
        after: Int?,
    ): org.json.JSONObject = org.json.JSONObject().apply {
        putNullable("before", before)
        putNullable("maximum", maximum)
        putNullable("after", after)
        put("before_name", thermalName(before))
        put("maximum_name", thermalName(maximum))
        put("after_name", thermalName(after))
    }

    private fun publishInstallProgress(progress: EnrichmentInstallProgress) {
        val fraction = if (progress.totalBytes > 0L) {
            progress.completedBytes.toDouble().div(progress.totalBytes.toDouble()).toFloat()
                .coerceIn(0f, 1f)
        } else {
            null
        }
        onProgress(
            EnrichmentBenchmarkProgress(
                message = "Installing ${progress.artifactName}: " +
                    "${formatBytes(progress.completedBytes)} / ${formatBytes(progress.totalBytes)}",
                fraction = fraction,
            ),
        )
    }

    private fun thermalStatus(): Int? = runCatching { powerManager.currentThermalStatus }.getOrNull()

    private fun ensureActive() {
        if (cancelRequested()) throw EnrichmentBenchmarkCancelledException()
    }

    private fun cancelRequested(): Boolean = isCancelled() || Thread.currentThread().isInterrupted

    private fun writeAtomically(file: File, text: String) {
        val atomicFile = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(text.toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            stream = null
        } finally {
            stream?.let(atomicFile::failWrite)
        }
    }

    private data class ModelAcquisition(
        val model: InstalledEnrichmentModel,
        val installedForRun: Boolean,
    )

    companion object {
        const val AUTO_RUN_SMOKE_EXTRA = "auto_run_smoke"
        const val AUTO_RUN_GPU_EXTRA = "auto_run_gpu"
        const val AUTO_RUN_FULL_EXTRA = "auto_run_full"
        const val MODEL_CANDIDATE_EXTRA = "model_candidate"
        private const val REPORT_SCHEMA_VERSION = 4
        private const val BENCHMARK_ATTEMPT = 1
        private const val MAX_DIAGNOSTIC_CHARACTERS = 2_000
    }
}

private data class BenchmarkCallReport(
    val fixtureId: String,
    val fixtureDescription: String,
    val fixtureKind: String,
    val backend: String,
    val seed: Int,
    val inputFingerprintSha256: String,
    val inputSegmentCount: Int,
    val requestUserCharacters: Int,
    val engineMetadata: EnrichmentEngineMetadata,
    val runnerInitializationMillis: Long?,
    val runnerGenerationMillis: Long?,
    val runtimeMetrics: LiteRtGenerationMetrics?,
    val schemaAccepted: Boolean,
    val reading: ValidatedEnrichment?,
    val diagnosticRawResponse: String?,
    val diagnosticRuntimeMessage: String?,
    val failures: List<String>,
    val resources: ProcessResourceSamples,
) {
    val passed: Boolean
        get() = failures.isEmpty() && schemaAccepted && reading != null

    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("fixture_id", fixtureId)
        put("fixture_description", fixtureDescription)
        put("fixture_kind", fixtureKind)
        put("backend", backend)
        put("seed", seed)
        put("input_fingerprint_sha256", inputFingerprintSha256)
        put("input_segment_count", inputSegmentCount)
        put("request_user_characters", requestUserCharacters)
        put("passed", passed)
        put("schema_accepted", schemaAccepted)
        put("engine", engineMetadata.toJson())
        put("metrics", metricsJson())
        put("resources", resources.toJson())
        put("failures", org.json.JSONArray(failures))
        putNullable("diagnostic_raw_response", diagnosticRawResponse)
        putNullable("diagnostic_runtime_message", diagnosticRuntimeMessage)
        put("validated_synthetic_reading", reading?.toJson() ?: org.json.JSONObject.NULL)
    }

    private fun metricsJson(): org.json.JSONObject = org.json.JSONObject().apply {
        putNullable("runner_engine_initialization_millis", runnerInitializationMillis)
        putNullable("runner_generation_millis", runnerGenerationMillis)
        putNullable("adapter_engine_initialization_millis", runtimeMetrics?.engineInitializationMillis)
        putNullable("adapter_generation_millis", runtimeMetrics?.generationMillis)
        putNullable("rendered_prompt_characters", runtimeMetrics?.renderedPromptCharacters)
        putNullable("conversation_token_count", runtimeMetrics?.conversationTokenCount)
        put("litert_benchmark", runtimeMetrics?.benchmark?.toJson() ?: org.json.JSONObject.NULL)
    }
}

private data class MalformedOutputCheck(
    val passed: Boolean,
    val detail: String,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("passed", passed)
        put("case", "truncated-json")
        put("detail", detail)
    }
}

private data class ProcessResourceSamples(
    val sampleCount: Int,
    val pssBeforeKiB: Int?,
    val pssPeakKiB: Int?,
    val pssAfterKiB: Int?,
    val thermalBefore: Int?,
    val thermalMax: Int?,
    val thermalAfter: Int?,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("pss_sample_count", sampleCount)
        putNullable("pss_before_kib", pssBeforeKiB)
        putNullable("pss_peak_kib", pssPeakKiB)
        putNullable("pss_after_kib", pssAfterKiB)
        putNullable("thermal_before", thermalBefore)
        putNullable("thermal_maximum", thermalMax)
        putNullable("thermal_after", thermalAfter)
        put("thermal_before_name", thermalName(thermalBefore))
        put("thermal_maximum_name", thermalName(thermalMax))
        put("thermal_after_name", thermalName(thermalAfter))
    }
}

private class ProcessResourceSampler(
    private val powerManager: PowerManager,
) {
    private val running = AtomicBoolean(false)
    private val sampleCount = AtomicInteger(0)
    private val peakPssKiB = AtomicInteger(-1)
    private val maximumThermal = AtomicInteger(-1)
    private var pssBeforeKiB: Int? = null
    private var thermalBefore: Int? = null
    private val samplerThread = Thread(::sampleLoop, "dreamlog-enrichment-pss-sampler").apply {
        isDaemon = true
    }

    fun start() {
        check(running.compareAndSet(false, true)) { "Resource sampling already started." }
        val first = readSample()
        pssBeforeKiB = first.first
        thermalBefore = first.second
        record(first)
        samplerThread.start()
    }

    fun finish(): ProcessResourceSamples {
        running.set(false)
        samplerThread.interrupt()
        val callerWasInterrupted = Thread.interrupted()
        try {
            samplerThread.join(SAMPLER_JOIN_MILLIS)
        } finally {
            if (callerWasInterrupted) Thread.currentThread().interrupt()
        }
        val final = readSample()
        record(final)
        return ProcessResourceSamples(
            sampleCount = sampleCount.get(),
            pssBeforeKiB = pssBeforeKiB,
            pssPeakKiB = peakPssKiB.get().takeIf { it >= 0 },
            pssAfterKiB = final.first,
            thermalBefore = thermalBefore,
            thermalMax = maximumThermal.get().takeIf { it >= 0 },
            thermalAfter = final.second,
        )
    }

    private fun sampleLoop() {
        while (running.get()) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                break
            }
            if (running.get()) record(readSample())
        }
    }

    private fun readSample(): Pair<Int?, Int?> {
        val pss = runCatching {
            Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
        }.getOrNull()
        val thermal = runCatching { powerManager.currentThermalStatus }.getOrNull()
        return pss to thermal
    }

    private fun record(sample: Pair<Int?, Int?>) {
        sampleCount.incrementAndGet()
        sample.first?.let { updateMaximum(peakPssKiB, it) }
        sample.second?.let { updateMaximum(maximumThermal, it) }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 250L
        const val SAMPLER_JOIN_MILLIS = 2_000L
    }
}

private fun EnrichmentBenchmarkPlan.workItems(): List<Pair<LiteRtEnrichmentBackend, Int>> =
    when (this) {
        EnrichmentBenchmarkPlan.GPU_SMOKE -> listOf(LiteRtEnrichmentBackend.GPU to 0)
        EnrichmentBenchmarkPlan.CPU -> listOf(LiteRtEnrichmentBackend.CPU to 0)
        EnrichmentBenchmarkPlan.GPU -> listOf(LiteRtEnrichmentBackend.GPU to 0)
        EnrichmentBenchmarkPlan.GPU_RELIABILITY ->
            (0..2).map { seed -> LiteRtEnrichmentBackend.GPU to seed }
    }

private fun InstalledEnrichmentModel.toModelState(): EnrichmentBenchmarkModelState =
    EnrichmentBenchmarkModelState(
        installed = true,
        removable = true,
        description = "$id @ ${revision.take(12)} (${formatBytes(artifactBytes)}, verified)",
    )

private fun EnrichmentEngineMetadata.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    put("locale_tag", localeTag)
    put("engine_id", engineId)
    put("engine_version", engineVersion)
    put("runtime_id", runtimeId)
    put("runtime_version", runtimeVersion)
    put("model_id", modelId)
    put("model_version", modelVersion)
    put("model_sha256", modelSha256)
    put("backend_id", backendId)
    put("model_bytes", modelBytes)
    put("context_window_tokens", contextWindowTokens)
    put("max_total_tokens", maxTotalTokens)
}

private fun BenchmarkInfo.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    putFinite("engine_init_seconds", initTimeInSecond)
    putFinite("time_to_first_token_seconds", timeToFirstTokenInSecond)
    put("prefill_token_count", lastPrefillTokenCount)
    put("decode_token_count", lastDecodeTokenCount)
    putFinite("prefill_tokens_per_second", lastPrefillTokensPerSecond)
    putFinite("decode_tokens_per_second", lastDecodeTokensPerSecond)
}

private fun ValidatedEnrichment.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    put("schema_version", schemaVersion)
    put("attempt", attempt)
    put("input_fingerprint", inputFingerprintSha256)
    put("dreams", org.json.JSONArray().apply {
        dreams.forEach { dream ->
            put(org.json.JSONObject().apply {
                put("order", dream.order)
                put("kind", dream.kind.wireValue)
                put("title", dream.generatedTitle ?: org.json.JSONObject.NULL)
                put("text", dream.generatedText)
                put("uncertain", dream.uncertain)
                put("source_spans", org.json.JSONArray().apply {
                    dream.sourceSpans.forEach { span ->
                        put(org.json.JSONObject().apply {
                            put("role", span.role.wireValue)
                            put("session_id", span.sessionId)
                            put("start_segment_index", span.startSegmentIndex)
                            put("end_segment_index_inclusive", span.endSegmentIndexInclusive)
                            put("source_start_millis", span.sourceStartMillis)
                            put("source_end_millis", span.sourceEndMillis)
                            put(
                                "segment_ids",
                                org.json.JSONArray(span.segmentIds.map { it.encoded }),
                            )
                        })
                    }
                })
            })
        }
    })
}

private fun org.json.JSONObject.putNullable(name: String, value: Any?) {
    put(name, value ?: org.json.JSONObject.NULL)
}

private fun org.json.JSONObject.putFinite(name: String, value: Double) {
    put(name, if (value.isFinite()) value else org.json.JSONObject.NULL)
}

private fun updateMaximum(target: AtomicInteger, candidate: Int) {
    while (true) {
        val current = target.get()
        if (candidate <= current || target.compareAndSet(current, candidate)) return
    }
}

private fun maxNullable(first: Int?, second: Int?): Int? = when {
    first == null -> second
    second == null -> first
    else -> max(first, second)
}

private fun elapsedMillis(startNanos: Long): Long =
    (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes.toDouble() / (1024L * 1024L * 1024L))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes.toDouble() / (1024L * 1024L))
    bytes >= 1024L -> "%.1f KiB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

private fun thermalName(status: Int?): String = when (status) {
    null -> "unavailable"
    PowerManager.THERMAL_STATUS_NONE -> "none"
    PowerManager.THERMAL_STATUS_LIGHT -> "light"
    PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
    else -> "unknown-$status"
}
