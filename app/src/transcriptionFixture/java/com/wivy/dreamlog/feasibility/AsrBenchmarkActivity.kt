package com.wivy.dreamlog.feasibility

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wivy.dreamlog.ui.theme.DreamLogTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AsrBenchmarkActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DreamLog-ASR-benchmark")
    }
    private val running = AtomicBoolean(false)
    private var screenState by mutableStateOf(AsrBenchmarkScreenState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            DreamLogTheme {
                AsrBenchmarkScreen(
                    state = screenState,
                    onRun = ::startBenchmark,
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            startBenchmark()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            startBenchmark()
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startBenchmark() {
        if (!running.compareAndSet(false, true)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenState = AsrBenchmarkScreenState(
            running = true,
            status = "Validating approvals and model hashes...",
        )
        worker.execute {
            runCatching {
                AsrBenchmarkRunner(applicationContext) { status ->
                    runOnUiThread {
                        screenState = screenState.copy(status = status)
                    }
                }.run()
            }.fold(
                onSuccess = { benchmark ->
                    runOnUiThread {
                        running.set(false)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        screenState = AsrBenchmarkScreenState(
                            running = false,
                            status = "Benchmark complete. The private evidence report is saved.",
                            summary = benchmark.summary,
                            reportPath = benchmark.reportFile.absolutePath,
                        )
                    }
                },
                onFailure = { failure ->
                    runOnUiThread {
                        running.set(false)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        screenState = AsrBenchmarkScreenState(
                            running = false,
                            status = "Benchmark failed.",
                            error = failureText(failure),
                        )
                    }
                },
            )
        }
    }

    private fun failureText(failure: Throwable): String {
        val name = failure::class.java.simpleName.ifBlank { "Failure" }
        val message = failure.message?.lineSequence()?.firstOrNull()?.take(320)
        return if (message.isNullOrBlank()) name else "$name: $message"
    }

    private companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}

private data class AsrBenchmarkScreenState(
    val running: Boolean = false,
    val status: String =
        "Ready to compare the two verified offline candidates on the four approved fixtures.",
    val summary: String? = null,
    val reportPath: String? = null,
    val error: String? = null,
)

@Composable
private fun AsrBenchmarkScreen(
    state: AsrBenchmarkScreenState,
    onRun: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "Local ASR benchmark",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                Text(
                    text =
                        "The approved neutral WAVs and both models remain app-private. " +
                            "This fixture package has no Internet permission and never logs audio " +
                            "or transcripts.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = if (state.running) "RUNNING - keep this screen open" else "STATUS",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(
                            onClick = onRun,
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.summary == null) "Run benchmark" else "Run again")
                        }
                    }
                }
            }
            state.error?.let { error ->
                item {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            state.reportPath?.let { path ->
                item {
                    Text(
                        text = "Private report: $path",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.summary?.let { summary ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = summary,
                            modifier = Modifier.padding(16.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
