package com.wivy.dreamlog.feasibility

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.wivy.dreamlog.transcription.Pcm16WavSource
import com.wivy.dreamlog.transcription.SherpaParakeetTranscriptionEngine
import com.wivy.dreamlog.transcription.SherpaRecognition
import com.wivy.dreamlog.transcription.SherpaTokenSegmenter
import com.wivy.dreamlog.transcription.model.InstalledLocalAsrModel
import com.wivy.dreamlog.transcription.model.LocalAsrModelManager
import com.wivy.dreamlog.transcription.model.LocalAsrModelManifest
import com.wivy.dreamlog.transcription.model.LocalAsrModelStatus
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import org.json.JSONObject

internal const val PARAKEET_LONG_FORM_SMOKE_DIRECTORY = "parakeet-long-form-smoke"
internal const val PARAKEET_LONG_FORM_SMOKE_INPUT_FILE = "input.wav"
internal const val PARAKEET_LONG_FORM_SMOKE_REPORT_FILE = "report.json"

internal data class ParakeetLongFormSmokeRun(
    val reportFile: File,
    val passed: Boolean,
)

/**
 * Exercises the production Parakeet model and recognizer configuration on one long waveform.
 *
 * The fixture intentionally persists no transcript, token text, audio hash, or audio bytes in its
 * report. The only input is a task-owned WAV staged inside this fixture package's private files.
 */
internal class ParakeetLongFormSmokeRunner(
    private val context: Context,
) {
    private val rootDirectory = File(context.filesDir, PARAKEET_LONG_FORM_SMOKE_DIRECTORY)
    private val inputFile = File(rootDirectory, PARAKEET_LONG_FORM_SMOKE_INPUT_FILE)
    private val reportFile = File(rootDirectory, PARAKEET_LONG_FORM_SMOKE_REPORT_FILE)
    private val powerManager = requireNotNull(context.getSystemService(PowerManager::class.java))

    fun run(): ParakeetLongFormSmokeRun {
        check(rootDirectory.isDirectory || rootDirectory.mkdirs()) {
            "Could not create the private smoke-test directory."
        }
        if (reportFile.exists()) check(reportFile.delete()) {
            "Could not remove the previous smoke-test report."
        }

        val createdAtEpochMillis = System.currentTimeMillis()
        val totalStarted = SystemClock.elapsedRealtimeNanos()
        var phase = SmokePhase.INPUT_VALIDATION
        var failureType: String? = null
        var modelVerificationMillis: Long? = null
        var modelLoadMillis: Long? = null
        var waveformReadMillis: Long? = null
        var decodeMillis: Long? = null
        var inputBytes: Long? = null
        var inputSampleRateHz: Int? = null
        var inputSampleCount: Long? = null
        var inputDurationMillis: Long? = null
        var acceptedSampleCount: Int? = null
        var acceptWaveformCallCount = 0
        var offlineDecodeCallCount = 0
        var transcriptNonEmpty: Boolean? = null
        var tokenCount: Int? = null
        var timestampCount: Int? = null
        var segmentCount: Int? = null
        var timestampsFinite: Boolean? = null
        var timestampsMonotonic: Boolean? = null
        var timestampsInRange: Boolean? = null
        var timestampCountMatchesTokenCount: Boolean? = null
        var productionSegmentationAccepted: Boolean? = null
        var pssBeforeLoadKib: Long? = null
        var pssAfterLoadKib: Long? = null
        var pssAfterDecodeKib: Long? = null
        var pssAfterReleaseKib: Long? = null
        val thermalBefore = powerManager.currentThermalStatus
        var recognizer: OfflineRecognizer? = null
        var sampler: SmokeResourceSampler? = null
        var resources: SmokeResourceSnapshot? = null

        try {
            val source = Pcm16WavSource.open(inputFile)
            check(source.sampleRateHz == REQUIRED_SAMPLE_RATE_HZ) {
                "Smoke-test audio must use the production 16 kHz sample rate."
            }
            check(source.durationMillis in MIN_INPUT_DURATION_MILLIS..MAX_INPUT_DURATION_MILLIS) {
                "Smoke-test audio must be a roughly 90-second long-form fixture."
            }
            check(source.sampleCount in 1L..Int.MAX_VALUE.toLong()) {
                "Smoke-test audio is too large for one offline utterance."
            }
            inputBytes = inputFile.length()
            inputSampleRateHz = source.sampleRateHz
            inputSampleCount = source.sampleCount
            inputDurationMillis = source.durationMillis

            phase = SmokePhase.WAVEFORM_READ
            val waveformStarted = SystemClock.elapsedRealtimeNanos()
            val samples = source.readCompleteFloatSamples(maxSampleCount = Int.MAX_VALUE)
            waveformReadMillis = elapsedMillis(waveformStarted)
            check(samples.size.toLong() == source.sampleCount) {
                "The complete fixture waveform was not loaded."
            }
            acceptedSampleCount = samples.size

            phase = SmokePhase.MODEL_VERIFICATION
            val verificationStarted = SystemClock.elapsedRealtimeNanos()
            val model = prepareAndVerifyProductionModel()
            modelVerificationMillis = elapsedMillis(verificationStarted)

            pssBeforeLoadKib = Debug.getPss().toLong()
            sampler = SmokeResourceSampler(powerManager).also(SmokeResourceSampler::start)
            phase = SmokePhase.MODEL_LOAD
            val loadStarted = SystemClock.elapsedRealtimeNanos()
            val loadedRecognizer = OfflineRecognizer(
                assetManager = null,
                config = SherpaParakeetTranscriptionEngine.recognizerConfig(model),
            )
            recognizer = loadedRecognizer
            modelLoadMillis = elapsedMillis(loadStarted)
            pssAfterLoadKib = Debug.getPss().toLong()

            phase = SmokePhase.COMPLETE_WAVEFORM_DECODE
            val decodeStarted = SystemClock.elapsedRealtimeNanos()
            val stream = loadedRecognizer.createStream()
            val recognition = try {
                // Exactly one full-waveform accept and one offline decode; there are no windows.
                acceptWaveformCallCount += 1
                stream.acceptWaveform(samples, source.sampleRateHz)
                offlineDecodeCallCount += 1
                loadedRecognizer.decode(stream)
                loadedRecognizer.getResult(stream).let { result ->
                    SherpaRecognition(
                        text = result.text,
                        tokens = result.tokens.toList(),
                        timestampsSeconds = result.timestamps.toList(),
                    )
                }
            } finally {
                stream.release()
            }
            decodeMillis = elapsedMillis(decodeStarted)
            pssAfterDecodeKib = Debug.getPss().toLong()

            phase = SmokePhase.OUTPUT_VALIDATION
            transcriptNonEmpty = recognition.text.isNotBlank()
            tokenCount = recognition.tokens.size
            timestampCount = recognition.timestampsSeconds.size
            timestampCountMatchesTokenCount = tokenCount == timestampCount
            timestampsFinite = recognition.timestampsSeconds.all(Float::isFinite)
            timestampsMonotonic = recognition.timestampsSeconds
                .zipWithNext()
                .all { (left, right) -> right >= left }
            timestampsInRange = recognition.timestampsSeconds.all { timestamp ->
                timestamp >= 0f && timestamp.toDouble() * 1_000.0 < source.durationMillis
            }
            check(transcriptNonEmpty == true) { "The long-form decode returned no text." }
            check((tokenCount ?: 0) > 0) { "The long-form decode returned no tokens." }
            check(timestampCountMatchesTokenCount == true) {
                "The long-form decode returned inconsistent timestamp counts."
            }
            check(timestampsFinite == true) {
                "The long-form decode returned non-finite timestamps."
            }
            check(timestampsMonotonic == true) {
                "The long-form decode returned non-monotonic timestamps."
            }
            check(timestampsInRange == true) {
                "The long-form decode returned out-of-range timestamps."
            }
            val segmented = SherpaTokenSegmenter.segment(
                recognition = recognition,
                sourceDurationMillis = source.durationMillis,
            )
            productionSegmentationAccepted = true
            segmentCount = segmented.segments.size
        } catch (failure: Throwable) {
            failureType = failure::class.java.simpleName.ifBlank { "Failure" }
        } finally {
            runCatching { recognizer?.release() }
                .onFailure { releaseFailure ->
                    if (failureType == null) {
                        phase = SmokePhase.MODEL_RELEASE
                        failureType = releaseFailure::class.java.simpleName.ifBlank { "Failure" }
                    }
                }
            resources = sampler?.stop()
            pssAfterReleaseKib = Debug.getPss().toLong()
        }

        val passed = failureType == null &&
            acceptWaveformCallCount == 1 &&
            offlineDecodeCallCount == 1 &&
            transcriptNonEmpty == true &&
            timestampCountMatchesTokenCount == true &&
            timestampsFinite == true &&
            timestampsMonotonic == true &&
            timestampsInRange == true &&
            productionSegmentationAccepted == true
        val totalMillis = elapsedMillis(totalStarted)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("status", if (passed) "passed" else "failed")
            .put("createdAtEpochMillis", createdAtEpochMillis)
            .put(
                "privacy",
                JSONObject()
                    .put("taskOwnedInputRequired", true)
                    .put("transcriptIncluded", false)
                    .put("tokenTextIncluded", false)
                    .put("audioIncluded", false)
                    .put("audioHashIncluded", false),
            )
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("device", Build.DEVICE)
                    .put("socModel", Build.SOC_MODEL)
                    .put("sdkInt", Build.VERSION.SDK_INT),
            )
            .put(
                "model",
                JSONObject()
                    .put("id", LocalAsrModelManifest.ID)
                    .put("revision", LocalAsrModelManifest.REVISION)
                    .put("identitySha256", LocalAsrModelManifest.MODEL_SHA256)
                    .put("totalBytes", LocalAsrModelManifest.definition.totalBytes),
            )
            .put(
                "runtime",
                JSONObject()
                    .put("id", "sherpa-onnx")
                    .put("version", SHERPA_RUNTIME_VERSION)
                    .put("modelType", "nemo_transducer")
                    .put("provider", "cpu")
                    .put("threadCount", PRODUCTION_THREAD_COUNT),
            )
            .put(
                "input",
                JSONObject()
                    .putNullable("bytes", inputBytes)
                    .putNullable("sampleRateHz", inputSampleRateHz)
                    .putNullable("sampleCount", inputSampleCount)
                    .putNullable("durationMillis", inputDurationMillis),
            )
            .put(
                "execution",
                JSONObject()
                    .put("completeWaveformAcceptCount", acceptWaveformCallCount)
                    .put("offlineDecodeCount", offlineDecodeCallCount)
                    .putNullable("acceptedSampleCount", acceptedSampleCount)
                    .putNullable("modelVerificationMillis", modelVerificationMillis)
                    .putNullable("waveformReadMillis", waveformReadMillis)
                    .putNullable("modelLoadMillis", modelLoadMillis)
                    .putNullable("decodeMillis", decodeMillis)
                    .put("totalMillis", totalMillis),
            )
            .put(
                "output",
                JSONObject()
                    .putNullable("transcriptNonEmpty", transcriptNonEmpty)
                    .putNullable("tokenCount", tokenCount)
                    .putNullable("timestampCount", timestampCount)
                    .putNullable("segmentCount", segmentCount)
                    .putNullable("timestampCountMatchesTokenCount", timestampCountMatchesTokenCount)
                    .putNullable("timestampsFinite", timestampsFinite)
                    .putNullable("timestampsMonotonic", timestampsMonotonic)
                    .putNullable("timestampsInRange", timestampsInRange)
                    .putNullable("productionSegmentationAccepted", productionSegmentationAccepted),
            )
            .put(
                "resources",
                JSONObject()
                    .putNullable("pssBeforeLoadKib", pssBeforeLoadKib)
                    .putNullable("pssAfterLoadKib", pssAfterLoadKib)
                    .putNullable("pssAfterDecodeKib", pssAfterDecodeKib)
                    .putNullable("peakPssKib", resources?.peakPssKib)
                    .putNullable("pssAfterReleaseKib", pssAfterReleaseKib)
                    .put("thermalStatusBefore", thermalBefore)
                    .putNullable("maximumThermalStatus", resources?.maximumThermalStatus)
                    .put("thermalStatusAfter", powerManager.currentThermalStatus),
            )
            .put("failurePhase", if (passed) JSONObject.NULL else phase.reportValue)
            .put("failureType", failureType ?: JSONObject.NULL)

        writeAtomically(reportFile, report.toString(2))
        return ParakeetLongFormSmokeRun(reportFile = reportFile, passed = passed)
    }

    private fun prepareAndVerifyProductionModel(): InstalledLocalAsrModel {
        val modelDirectory = File(
            context.filesDir,
            "transcription-models/${LocalAsrModelManifest.DIRECTORY_NAME}",
        )
        check(modelDirectory.isDirectory) { "The staged production model directory is missing." }
        val artifactNames = LocalAsrModelManifest.definition.files
            .mapTo(mutableSetOf()) { it.localName }
        val actualNames = modelDirectory.listFiles()?.mapTo(mutableSetOf(), File::getName)
            ?: error("The staged production model directory is unreadable.")
        check(actualNames == artifactNames ||
            actualNames == artifactNames + LocalAsrModelManifest.INSTALLED_MANIFEST_FILE
        ) {
            "The staged production model directory has unexpected files."
        }

        // Host staging supplies only the exact pinned artifacts. This local marker lets the
        // production manager perform its normal size, SHA-256, revision, and file-set checks.
        writeAtomically(
            File(modelDirectory, LocalAsrModelManifest.INSTALLED_MANIFEST_FILE),
            LocalAsrModelManifest.definition.installedManifestText,
        )
        return when (val status = LocalAsrModelManager(context.filesDir).status()) {
            is LocalAsrModelStatus.Installed -> status.model
            LocalAsrModelStatus.NotInstalled -> error("The staged production model is missing.")
            is LocalAsrModelStatus.Invalid -> error("The staged production model is invalid.")
        }
    }

    private fun writeAtomically(destination: File, text: String) {
        check(destination.parentFile?.isDirectory == true) {
            "The private report directory is unavailable."
        }
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(text, StandardCharsets.UTF_8)
        runCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L

    private companion object {
        const val REQUIRED_SAMPLE_RATE_HZ = 16_000
        const val MIN_INPUT_DURATION_MILLIS = 60_000L
        const val MAX_INPUT_DURATION_MILLIS = 120_000L
        const val SHERPA_RUNTIME_VERSION = "1.13.4"
        const val PRODUCTION_THREAD_COUNT = 2
    }
}

private enum class SmokePhase(val reportValue: String) {
    INPUT_VALIDATION("input_validation"),
    WAVEFORM_READ("waveform_read"),
    MODEL_VERIFICATION("model_verification"),
    MODEL_LOAD("model_load"),
    COMPLETE_WAVEFORM_DECODE("complete_waveform_decode"),
    OUTPUT_VALIDATION("output_validation"),
    MODEL_RELEASE("model_release"),
}

private data class SmokeResourceSnapshot(
    val peakPssKib: Long,
    val maximumThermalStatus: Int,
)

private class SmokeResourceSampler(
    private val powerManager: PowerManager,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var peakPssKib = Debug.getPss().toLong()

    @Volatile
    private var maximumThermalStatus = powerManager.currentThermalStatus

    private var worker: Thread? = null

    fun start() {
        check(running.compareAndSet(false, true))
        worker = thread(name = "DreamLog-Parakeet-resource-sampler") {
            while (running.get()) {
                peakPssKib = max(peakPssKib, Debug.getPss().toLong())
                maximumThermalStatus = max(
                    maximumThermalStatus,
                    powerManager.currentThermalStatus,
                )
                try {
                    Thread.sleep(RESOURCE_SAMPLE_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop(): SmokeResourceSnapshot {
        running.set(false)
        worker?.interrupt()
        runCatching { worker?.join(1_000L) }
        peakPssKib = max(peakPssKib, Debug.getPss().toLong())
        maximumThermalStatus = max(maximumThermalStatus, powerManager.currentThermalStatus)
        return SmokeResourceSnapshot(
            peakPssKib = peakPssKib,
            maximumThermalStatus = maximumThermalStatus,
        )
    }

    private companion object {
        const val RESOURCE_SAMPLE_INTERVAL_MILLIS = 50L
    }
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)
