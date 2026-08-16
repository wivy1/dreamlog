package com.wivy.dreamlog.feasibility

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** ADB-driven entry point for the isolated, transcript-free Parakeet device smoke test. */
class ParakeetLongFormSmokeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DreamLog-Parakeet-long-form-smoke")
    }
    private val running = AtomicBoolean(false)
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "Ready for the private Parakeet long-form smoke test."
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
        setContentView(statusView)
        maybeAutostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAutostart(intent)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun maybeAutostart(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) startSmokeTest()
    }

    private fun startSmokeTest() {
        if (!running.compareAndSet(false, true)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusView.text = "Running one complete-waveform Parakeet decode..."
        worker.execute {
            val outcome = runCatching {
                ParakeetLongFormSmokeRunner(applicationContext).run()
            }
            runOnUiThread {
                running.set(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                statusView.text = outcome.fold(
                    onSuccess = { result ->
                        if (result.passed) {
                            "Smoke test passed. Privacy-safe report: ${result.reportFile.absolutePath}"
                        } else {
                            "Smoke test failed. Privacy-safe report: ${result.reportFile.absolutePath}"
                        }
                    },
                    onFailure = {
                        "Smoke test failed before a report could be written."
                    },
                )
            }
        }
    }

    private companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}
