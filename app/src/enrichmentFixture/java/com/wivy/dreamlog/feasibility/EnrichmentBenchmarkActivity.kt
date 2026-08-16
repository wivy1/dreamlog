package com.wivy.dreamlog.feasibility

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Visible, finite benchmark surface for the isolated enrichmentFixture application. */
class EnrichmentBenchmarkActivity : ComponentActivity() {
    private val cancelRequested = AtomicBoolean(false)
    private val actionRunning = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dreamlog-enrichment-benchmark").apply { isDaemon = false }
    }
    private var currentTask: Future<*>? = null
    private var state by mutableStateOf(EnrichmentBenchmarkUiState())
    private lateinit var runner: EnrichmentBenchmarkRunner
    private var automaticRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        automaticRun = intent.getBooleanExtra(EXTRA_AUTO_RUN_SMOKE, false) ||
            intent.getBooleanExtra(EXTRA_AUTO_RUN_GPU, false) ||
            intent.getBooleanExtra(EXTRA_AUTO_RUN_FULL, false)
        if (automaticRun) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        enableEdgeToEdge()
        val modelSelection = EnrichmentBenchmarkModelSelection.fromIntentValue(
            intent.getStringExtra(EXTRA_MODEL_CANDIDATE),
        )
        runner = EnrichmentBenchmarkRunner(
            context = applicationContext,
            modelSelection = modelSelection,
            isCancelled = { cancelRequested.get() },
            onProgress = { progress ->
                updateState { current ->
                    current.copy(
                        message = progress.message,
                        progressFraction = progress.fraction,
                    )
                }
            },
        )
        setContent {
            MaterialTheme(colorScheme = BENCHMARK_COLORS) {
                EnrichmentBenchmarkScreen(
                    state = state,
                    onInstall = ::installModel,
                    onCancel = ::cancelCurrentAction,
                    onRemove = ::removeModel,
                    onCpu = { startBenchmark(EnrichmentBenchmarkPlan.CPU, installIfMissing = false) },
                    onGpu = { startBenchmark(EnrichmentBenchmarkPlan.GPU, installIfMissing = false) },
                    onFull = {
                        startBenchmark(
                            EnrichmentBenchmarkPlan.GPU_RELIABILITY,
                            installIfMissing = false,
                        )
                    },
                )
            }
        }

        // Do not repeat an automatic model run after configuration-driven Activity recreation.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_RUN_SMOKE, false)) {
            startBenchmark(EnrichmentBenchmarkPlan.GPU_SMOKE, installIfMissing = true)
        } else if (
            savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_AUTO_RUN_GPU, false)
        ) {
            startBenchmark(EnrichmentBenchmarkPlan.GPU, installIfMissing = true)
        } else if (
            savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_AUTO_RUN_FULL, false)
        ) {
            startBenchmark(EnrichmentBenchmarkPlan.GPU_RELIABILITY, installIfMissing = true)
        } else {
            refreshModelState()
        }
    }

    override fun onDestroy() {
        val finishAutomaticRunAfterRecreation =
            automaticRun && isChangingConfigurations && actionRunning.get()
        destroyed.set(true)
        if (finishAutomaticRunAfterRecreation) {
            // A screen/configuration transition must not invalidate a finite automatic fixture
            // run. The detached instance stops publishing UI state and releases its executor
            // from the task's finally block once the private report is complete.
            super.onDestroy()
            return
        }
        cancelRequested.set(true)
        currentTask?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onStop() {
        if (!automaticRun && !isChangingConfigurations && actionRunning.get()) {
            cancelCurrentAction()
        }
        super.onStop()
    }

    private fun refreshModelState() {
        startAction("Checking local model") {
            val modelState = runner.modelState()
            ActionCompletion(
                message = modelState.description,
                modelState = modelState,
            )
        }
    }

    private fun installModel() {
        startAction("Installing selected local model") {
            val modelState = runner.installModel()
            ActionCompletion(
                message = modelState.description,
                modelState = modelState,
            )
        }
    }

    private fun removeModel() {
        startAction("Removing selected local model") {
            val modelState = runner.removeModel()
            ActionCompletion(
                message = modelState.description,
                modelState = modelState,
                clearReport = true,
            )
        }
    }

    private fun startBenchmark(
        plan: EnrichmentBenchmarkPlan,
        installIfMissing: Boolean,
    ) {
        startAction("Running ${plan.displayName}") {
            val result = runner.run(plan, installIfMissing)
            ActionCompletion(
                message = when {
                    result.cancelled ->
                        "Cancelled after ${result.completedCalls} of ${result.expectedCalls} calls."

                    result.passed ->
                        "Benchmark passed: ${result.completedCalls} of ${result.expectedCalls} calls."

                    else ->
                        "Benchmark failed: ${result.completedCalls} of ${result.expectedCalls} calls completed."
                },
                modelState = result.modelState,
                result = result,
            )
        }
    }

    private fun cancelCurrentAction() {
        if (!actionRunning.get()) return
        cancelRequested.set(true)
        updateState { current ->
            current.copy(
                message = "Cancellation requested. The active native call will close before exit.",
                progressFraction = null,
            )
        }
    }

    private fun startAction(
        label: String,
        work: () -> ActionCompletion,
    ) {
        if (!actionRunning.compareAndSet(false, true)) return
        if (!PROCESS_ACTION_RUNNING.compareAndSet(false, true)) {
            actionRunning.set(false)
            state = state.copy(
                message = "A previous native benchmark action is still closing.",
                error = "Wait for that action to finish before starting another measurement.",
            )
            return
        }
        cancelRequested.set(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        state = state.copy(
            running = true,
            activeAction = label,
            message = "$label…",
            progressFraction = null,
            error = null,
        )
        currentTask = executor.submit {
            try {
                val completion = work()
                updateState { current ->
                    val modelState = completion.modelState
                    current.copy(
                        message = completion.message,
                        modelInstalled = modelState?.installed ?: current.modelInstalled,
                        modelRemovable = modelState?.removable ?: current.modelRemovable,
                        modelDescription = modelState?.description ?: current.modelDescription,
                        reportPath = when {
                            completion.clearReport -> null
                            completion.result != null -> completion.result.reportFile.absolutePath
                            else -> current.reportPath
                        },
                        summaryPath = when {
                            completion.clearReport -> null
                            completion.result != null -> completion.result.summaryFile.absolutePath
                            else -> current.summaryPath
                        },
                        resultSummary = when {
                            completion.clearReport -> null
                            completion.result != null -> completion.result.summary
                            else -> current.resultSummary
                        },
                    )
                }
            } catch (failure: Exception) {
                val cancelled = cancelRequested.get() ||
                    failure is EnrichmentBenchmarkCancelledException ||
                    Thread.currentThread().isInterrupted
                updateState { current ->
                    current.copy(
                        message = if (cancelled) {
                            "Action cancelled. No further model work will start."
                        } else {
                            "Action failed."
                        },
                        error = if (cancelled) null else safeFailureDescription(failure),
                    )
                }
            } finally {
                actionRunning.set(false)
                PROCESS_ACTION_RUNNING.set(false)
                currentTask = null
                if (destroyed.get()) {
                    executor.shutdown()
                }
                updateState { current ->
                    current.copy(
                        running = false,
                        activeAction = null,
                        progressFraction = null,
                    )
                }
                if (!destroyed.get()) {
                    runOnUiThread {
                        if (!destroyed.get()) {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
            }
        }
    }

    private fun updateState(transform: (EnrichmentBenchmarkUiState) -> EnrichmentBenchmarkUiState) {
        if (destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get()) state = transform(state)
        }
    }

    private fun safeFailureDescription(failure: Exception): String = when (failure) {
        is EnrichmentBenchmarkModelUnavailableException -> failure.message.orEmpty()
        else -> "${failure.javaClass.simpleName}: the local benchmark action did not complete."
    }

    private data class ActionCompletion(
        val message: String,
        val modelState: EnrichmentBenchmarkModelState? = null,
        val result: EnrichmentBenchmarkResult? = null,
        val clearReport: Boolean = false,
    )

    companion object {
        const val EXTRA_AUTO_RUN_SMOKE = EnrichmentBenchmarkRunner.AUTO_RUN_SMOKE_EXTRA
        const val EXTRA_AUTO_RUN_GPU = EnrichmentBenchmarkRunner.AUTO_RUN_GPU_EXTRA
        const val EXTRA_AUTO_RUN_FULL = EnrichmentBenchmarkRunner.AUTO_RUN_FULL_EXTRA
        const val EXTRA_MODEL_CANDIDATE = EnrichmentBenchmarkRunner.MODEL_CANDIDATE_EXTRA
        private val PROCESS_ACTION_RUNNING = AtomicBoolean(false)
    }
}

private data class EnrichmentBenchmarkUiState(
    val running: Boolean = false,
    val activeAction: String? = null,
    val message: String = "Checking the isolated benchmark model…",
    val progressFraction: Float? = null,
    val modelInstalled: Boolean = false,
    val modelRemovable: Boolean = false,
    val modelDescription: String = "Not checked yet.",
    val reportPath: String? = null,
    val summaryPath: String? = null,
    val resultSummary: String? = null,
    val error: String? = null,
)

@Composable
private fun EnrichmentBenchmarkScreen(
    state: EnrichmentBenchmarkUiState,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onCpu: () -> Unit,
    onGpu: () -> Unit,
    onFull: () -> Unit,
) {
    val oneSeedCallCount = EnrichmentBenchmarkFixtures.behaviorCases.size + 1
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Whole-night enrichment benchmark",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Isolated synthetic fixture app • no DreamLog database or owner recordings",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BenchmarkSection("Selected model") {
                Text(state.modelDescription, style = MaterialTheme.typography.bodyLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onInstall,
                        enabled = !state.running && !state.modelInstalled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Install")
                    }
                    OutlinedButton(
                        onClick = onRemove,
                        enabled = !state.running && state.modelRemovable,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Remove")
                    }
                }
            }

            BenchmarkSection("Run") {
                Text(
                    text = "CPU and GPU buttons run all $oneSeedCallCount current fixtures with " +
                        "seed 0. Reliability runs the selected GPU backend with seeds 0, 1, and 2.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onCpu,
                        enabled = !state.running && state.modelInstalled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("CPU")
                    }
                    Button(
                        onClick = onGpu,
                        enabled = !state.running && state.modelInstalled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("GPU")
                    }
                }
                Button(
                    onClick = onFull,
                    enabled = !state.running && state.modelInstalled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Selected GPU reliability • ${oneSeedCallCount * 3} calls")
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = state.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel current action")
                }
            }

            BenchmarkSection(state.activeAction ?: "Status") {
                if (state.running) {
                    val fraction = state.progressFraction
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (state.reportPath != null || state.summaryPath != null) {
                BenchmarkSection("ADB pull artifacts") {
                    state.reportPath?.let { path -> PathText("JSON", path) }
                    state.summaryPath?.let { path -> PathText("Summary", path) }
                }
            }

            state.resultSummary?.let { summary ->
                BenchmarkSection("Last run summary") {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BenchmarkSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
private fun PathText(label: String, path: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val BENCHMARK_COLORS = darkColorScheme(
    primary = Color(0xFF7ADCB4),
    onPrimary = Color(0xFF003827),
    secondary = Color(0xFFA6CCBB),
    background = Color(0xFF081310),
    surface = Color(0xFF101E1A),
    onSurface = Color(0xFFE1F1E9),
    onSurfaceVariant = Color(0xFFB7C9C0),
    error = Color(0xFFFFB4AB),
)
