package com.wivy.dreamlog.feasibility

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.wivy.dreamlog.transcription.Pcm16WavSource
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal const val ASR_BENCHMARK_REPORT_FILE = "transcription-benchmark-report.json"

internal data class AsrBenchmarkRun(
    val reportFile: File,
    val summary: String,
)

internal class AsrBenchmarkRunner(
    private val context: Context,
    private val progress: (String) -> Unit,
) {
    private val fixtureStore = FixtureStore(context)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun run(): AsrBenchmarkRun {
        val fixtures = fixtureStore.snapshots()
        check(fixtures.size == TranscriptionFixtures.size && fixtures.all(FixtureSnapshot::approved)) {
            "All four owner-reviewed fixtures must remain approved."
        }
        val startedAtEpochMillis = System.currentTimeMillis()
        val candidateReports = BenchmarkCandidates.all.mapIndexed { index, candidate ->
            progress("Candidate ${index + 1} of ${BenchmarkCandidates.all.size}: ${candidate.name}")
            runCandidate(candidate, fixtures)
        }
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("createdAtEpochMillis", startedAtEpochMillis)
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("device", Build.DEVICE)
                    .put("socModel", Build.SOC_MODEL)
                    .put("sdkInt", Build.VERSION.SDK_INT)
                    .put("securityPatch", Build.VERSION.SECURITY_PATCH),
            )
            .put(
                "runtime",
                JSONObject()
                    .put("id", "sherpa-onnx")
                    .put("version", "1.13.4")
                    .put(
                        "aarSha256",
                        "2e0e0c98d1d887ec7fcc55ff4e175151029595e263f97f59c0fd4fd493c67a43",
                    ),
            )
            .put(
                "offlineEvidence",
                "The fixture variant has no INTERNET permission; models and WAVs are app-private.",
            )
            .put(
                "fixtures",
                JSONArray().apply {
                    fixtures.forEach { fixture ->
                        put(
                            JSONObject()
                                .put("id", fixture.definition.id)
                                .put("category", fixture.definition.category)
                                .put("referenceText", fixture.definition.referenceText)
                                .put("approved", fixture.approved)
                                .put("durationMillis", fixture.durationMillis),
                        )
                    }
                },
            )
            .put(
                "candidates",
                JSONArray().apply { candidateReports.forEach { put(it.toJson()) } },
            )
        val reportFile = File(context.filesDir, ASR_BENCHMARK_REPORT_FILE)
        writeAtomically(reportFile, report.toString(2))
        return AsrBenchmarkRun(
            reportFile = reportFile,
            summary = candidateReports.joinToString(separator = "\n\n") { it.summary() },
        )
    }

    private fun runCandidate(
        candidate: BenchmarkCandidate,
        fixtures: List<FixtureSnapshot>,
    ): CandidateReport {
        val modelDirectory = File(context.filesDir, "models/${candidate.modelDirectoryName}")
        val verificationStarted = SystemClock.elapsedRealtimeNanos()
        val verifiedFiles = candidate.files.map { specification ->
            verifyModelFile(modelDirectory, specification)
        }
        val verificationMillis = elapsedMillis(verificationStarted)
        val pssBeforeKib = Debug.getPss()
        val thermalBefore = powerManager.currentThermalStatus
        val sampler = ResourceSampler(powerManager).also(ResourceSampler::start)
        val totalStarted = SystemClock.elapsedRealtimeNanos()
        var recognizer: OfflineRecognizer? = null
        var modelLoadMillis: Long? = null
        var pssAfterLoadKib: Long? = null
        var failure: String? = null
        val fixtureReports = mutableListOf<FixtureReport>()
        try {
            progress("${candidate.name}: loading verified model")
            val loadStarted = SystemClock.elapsedRealtimeNanos()
            recognizer = OfflineRecognizer(
                assetManager = null,
                config = candidate.config(modelDirectory),
            )
            modelLoadMillis = elapsedMillis(loadStarted)
            pssAfterLoadKib = Debug.getPss()
            fixtures.forEachIndexed { index, fixture ->
                progress(
                    "${candidate.name}: ${index + 1} of ${fixtures.size} - " +
                        fixture.definition.category,
                )
                fixtureReports += decodeFixture(candidate, recognizer, fixture)
            }
        } catch (caught: Throwable) {
            failure = failureText(caught)
        } finally {
            runCatching { recognizer?.release() }
                .onFailure { releaseFailure ->
                    if (failure == null) failure = failureText(releaseFailure)
                }
        }
        val totalMillis = elapsedMillis(totalStarted)
        val resourceSnapshot = sampler.stop()
        val pssAfterReleaseKib = Debug.getPss()
        val thermalAfter = powerManager.currentThermalStatus
        return CandidateReport(
            candidate = candidate,
            verifiedFiles = verifiedFiles,
            verificationMillis = verificationMillis,
            modelLoadMillis = modelLoadMillis,
            totalMillis = totalMillis,
            pssBeforeKib = pssBeforeKib,
            pssAfterLoadKib = pssAfterLoadKib,
            peakPssKib = resourceSnapshot.peakPssKib,
            pssAfterReleaseKib = pssAfterReleaseKib,
            thermalBefore = thermalBefore,
            maxThermal = resourceSnapshot.maxThermalStatus,
            thermalAfter = thermalAfter,
            fixtures = fixtureReports,
            failure = failure,
        )
    }

    private fun decodeFixture(
        candidate: BenchmarkCandidate,
        recognizer: OfflineRecognizer,
        fixture: FixtureSnapshot,
    ): FixtureReport {
        val started = SystemClock.elapsedRealtimeNanos()
        val source = Pcm16WavSource.open(fixtureStore.audioFile(fixture.definition))
        check(source.sampleCount <= Int.MAX_VALUE.toLong()) {
            "The approved fixture is too large for one offline utterance."
        }
        if (candidate.family == CandidateFamily.WHISPER) {
            check(source.durationMillis <= WHISPER_MAX_WINDOW_MILLIS) {
                "Whisper fixtures must fit the measured 20-second source window."
            }
        }
        val samples = FloatArray(source.sampleCount.toInt())
        var copiedSamples = 0
        source.forEachFloatChunk(recognitionRange = null) { chunk ->
            check(chunk.startSample == copiedSamples.toLong()) {
                "Fixture PCM chunks are not contiguous."
            }
            chunk.samples.copyInto(samples, destinationOffset = copiedSamples)
            copiedSamples += chunk.samples.size
        }
        check(copiedSamples == samples.size) { "Fixture PCM was not read completely." }

        val stream = recognizer.createStream()
        val result = try {
            stream.acceptWaveform(samples, source.sampleRateHz)
            recognizer.decode(stream)
            recognizer.getResult(stream)
        } finally {
            stream.release()
        }
        val elapsedMillis = elapsedMillis(started)
        val transcript = result.text.trim()
        val referenceWords = normalizeWords(fixture.definition.referenceText)
        val transcriptWords = normalizeWords(transcript)
        val wordErrors = editDistance(referenceWords, transcriptWords)
        val tokens = result.tokens.toList()
        val timestamps = result.timestamps.map(Float::toDouble)
        val durationSeconds = source.durationMillis / 1_000.0
        val timestampsFinite = timestamps.all(Double::isFinite)
        val timestampsMonotonic = timestamps.zipWithNext().all { (first, second) ->
            second >= first
        }
        val timestampsInRange = timestamps.all { timestamp ->
            timestamp >= 0.0 && timestamp <= durationSeconds + TIMESTAMP_TOLERANCE_SECONDS
        }
        return FixtureReport(
            fixtureId = fixture.definition.id,
            category = fixture.definition.category,
            referenceText = fixture.definition.referenceText,
            transcript = transcript,
            audioDurationMillis = source.durationMillis,
            elapsedMillis = elapsedMillis,
            wordErrors = wordErrors,
            referenceWordCount = referenceWords.size,
            tokens = tokens,
            timestamps = timestamps,
            timestampsFinite = timestampsFinite,
            timestampsMonotonic = timestampsMonotonic,
            timestampsInRange = timestampsInRange,
        )
    }

    private fun verifyModelFile(
        modelDirectory: File,
        specification: ModelFileSpecification,
    ): VerifiedModelFile {
        val file = File(modelDirectory, specification.fileName)
        check(file.isFile) { "Model file is missing: ${specification.fileName}" }
        check(file.length() == specification.bytes) {
            "Model file has the wrong size: ${specification.fileName}"
        }
        val sha256 = sha256(file)
        check(sha256 == specification.sha256) {
            "Model file failed SHA-256 verification: ${specification.fileName}"
        }
        return VerifiedModelFile(specification.fileName, specification.bytes, sha256)
    }

    private fun writeAtomically(destination: File, text: String) {
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L

    private fun failureText(failure: Throwable): String {
        val name = failure::class.java.simpleName.ifBlank { "Failure" }
        val message = failure.message?.lineSequence()?.firstOrNull()?.take(240)
        return if (message.isNullOrBlank()) name else "$name: $message"
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 1024 * 1024
        const val WHISPER_MAX_WINDOW_MILLIS = 20_000L
        const val TIMESTAMP_TOLERANCE_SECONDS = 0.25
    }
}

private enum class CandidateFamily {
    ZIPFORMER,
    WHISPER,
}

private data class ModelFileSpecification(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
)

private data class BenchmarkCandidate(
    val id: String,
    val name: String,
    val family: CandidateFamily,
    val modelDirectoryName: String,
    val modelRevision: String,
    val modelLicense: String,
    val modelBytes: Long,
    val timestampExpectation: String,
    val files: List<ModelFileSpecification>,
    val config: (File) -> OfflineRecognizerConfig,
)

private object BenchmarkCandidates {
    val all = listOf(
        BenchmarkCandidate(
            id = "zipformer-gigaspeech-mixed-int8",
            name = "GigaSpeech Zipformer mixed-int8",
            family = CandidateFamily.ZIPFORMER,
            modelDirectoryName = "zipformer-gigaspeech",
            modelRevision = "b609c835cd60c8ff0dd9b771f2b4edcfe2da943a",
            modelLicense = "Apache-2.0",
            modelBytes = 75_208_255L,
            timestampExpectation = "Token start timestamps expected from Zipformer.",
            files = listOf(
                ModelFileSpecification(
                    "encoder.int8.onnx",
                    72_850_738L,
                    "d3eff4b1bd747bd781a47966795988539227d05638524b0313f26d3a166962d7",
                ),
                ModelFileSpecification(
                    "decoder.onnx",
                    2_093_080L,
                    "9610f32e7adb66dd57fc31af532652cdaa590bc3bbf7072a480b01c30592bdda",
                ),
                ModelFileSpecification(
                    "joiner.int8.onnx",
                    259_417L,
                    "80160e45cca71dd52f6b0a6d3d12be18126f5308b2d4ba03f001300fea377c64",
                ),
                ModelFileSpecification(
                    "tokens.txt",
                    5_020L,
                    "0ef7d736bf4de3ef947292e4b119ef13f6808cd5f3aec225a843a7135ac1c2ce",
                ),
            ),
            config = { directory ->
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = 16_000,
                        featureDim = 80,
                        dither = 0f,
                    ),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(directory, "encoder.int8.onnx").absolutePath,
                            decoder = File(directory, "decoder.onnx").absolutePath,
                            joiner = File(directory, "joiner.int8.onnx").absolutePath,
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                        tokens = File(directory, "tokens.txt").absolutePath,
                    ),
                    decodingMethod = "greedy_search",
                    maxActivePaths = 4,
                )
            },
        ),
        BenchmarkCandidate(
            id = "whisper-base-en-int8",
            name = "Whisper base.en int8",
            family = CandidateFamily.WHISPER,
            modelDirectoryName = "whisper-base-en",
            modelRevision = "59eea950fc76df2453efb57e6c0fd334548e8ffe",
            modelLicense = "Apache-2.0 export; upstream Whisper MIT",
            modelBytes = 160_626_066L,
            timestampExpectation =
                "No token timestamps promised; source/VAD window bounds are the fallback.",
            files = listOf(
                ModelFileSpecification(
                    "encoder.int8.onnx",
                    29_120_534L,
                    "ef6b936f4c9b1d90a3b68634b60c4ed8576b26172b33c2535ec0e933c9edb823",
                ),
                ModelFileSpecification(
                    "decoder.int8.onnx",
                    130_669_978L,
                    "f7162ad6db2dbef16cfaeaa7f945b9d7dd9c1b8d472f6aca82f2273d185e4d41",
                ),
                ModelFileSpecification(
                    "tokens.txt",
                    835_554L,
                    "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930",
                ),
            ),
            config = { directory ->
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = 16_000,
                        featureDim = 80,
                        dither = 0f,
                    ),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = File(directory, "encoder.int8.onnx").absolutePath,
                            decoder = File(directory, "decoder.int8.onnx").absolutePath,
                            language = "en",
                            task = "transcribe",
                            tailPaddings = 1_000,
                            enableTokenTimestamps = false,
                            enableSegmentTimestamps = false,
                        ),
                        tokens = File(directory, "tokens.txt").absolutePath,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                        modelType = "whisper",
                    ),
                    decodingMethod = "greedy_search",
                )
            },
        ),
    )
}

private data class VerifiedModelFile(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fileName", fileName)
        .put("bytes", bytes)
        .put("sha256", sha256)
}

private data class FixtureReport(
    val fixtureId: String,
    val category: String,
    val referenceText: String,
    val transcript: String,
    val audioDurationMillis: Long,
    val elapsedMillis: Long,
    val wordErrors: Int,
    val referenceWordCount: Int,
    val tokens: List<String>,
    val timestamps: List<Double>,
    val timestampsFinite: Boolean,
    val timestampsMonotonic: Boolean,
    val timestampsInRange: Boolean,
) {
    val wordErrorRate: Double
        get() = if (referenceWordCount == 0) 0.0 else wordErrors.toDouble() / referenceWordCount

    fun toJson(): JSONObject = JSONObject()
        .put("fixtureId", fixtureId)
        .put("category", category)
        .put("referenceText", referenceText)
        .put("transcript", transcript)
        .put("audioDurationMillis", audioDurationMillis)
        .put("elapsedMillis", elapsedMillis)
        .put("realTimeFactor", elapsedMillis.toDouble() / max(audioDurationMillis, 1L))
        .put("wordErrors", wordErrors)
        .put("referenceWordCount", referenceWordCount)
        .put("wordErrorRate", wordErrorRate)
        .put("tokens", JSONArray(tokens))
        .put("timestampsSeconds", JSONArray(timestamps))
        .put("timestampsFinite", timestampsFinite)
        .put("timestampsMonotonic", timestampsMonotonic)
        .put("timestampsInRange", timestampsInRange)
        .put("timestampCountMatchesTokenCount", timestamps.size == tokens.size)
}

private data class CandidateReport(
    val candidate: BenchmarkCandidate,
    val verifiedFiles: List<VerifiedModelFile>,
    val verificationMillis: Long,
    val modelLoadMillis: Long?,
    val totalMillis: Long,
    val pssBeforeKib: Long,
    val pssAfterLoadKib: Long?,
    val peakPssKib: Long,
    val pssAfterReleaseKib: Long,
    val thermalBefore: Int,
    val maxThermal: Int,
    val thermalAfter: Int,
    val fixtures: List<FixtureReport>,
    val failure: String?,
) {
    private val totalWordErrors: Int
        get() = fixtures.sumOf(FixtureReport::wordErrors)
    private val totalReferenceWords: Int
        get() = fixtures.sumOf(FixtureReport::referenceWordCount)
    private val aggregateWordErrorRate: Double
        get() = if (totalReferenceWords == 0) {
            0.0
        } else {
            totalWordErrors.toDouble() / totalReferenceWords
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", candidate.id)
        .put("name", candidate.name)
        .put("modelRevision", candidate.modelRevision)
        .put("modelLicense", candidate.modelLicense)
        .put("modelBytes", candidate.modelBytes)
        .put("numThreads", 2)
        .put("timestampExpectation", candidate.timestampExpectation)
        .put("modelFiles", JSONArray().apply { verifiedFiles.forEach { put(it.toJson()) } })
        .put("verificationMillis", verificationMillis)
        .put("modelLoadMillis", modelLoadMillis ?: JSONObject.NULL)
        .put("totalMillis", totalMillis)
        .put("pssBeforeKib", pssBeforeKib)
        .put("pssAfterLoadKib", pssAfterLoadKib ?: JSONObject.NULL)
        .put("peakPssKib", peakPssKib)
        .put("pssAfterReleaseKib", pssAfterReleaseKib)
        .put("thermalBefore", thermalBefore)
        .put("maxThermal", maxThermal)
        .put("thermalAfter", thermalAfter)
        .put("totalWordErrors", totalWordErrors)
        .put("totalReferenceWords", totalReferenceWords)
        .put("aggregateWordErrorRate", aggregateWordErrorRate)
        .put("failure", failure ?: JSONObject.NULL)
        .put("fixtures", JSONArray().apply { fixtures.forEach { put(it.toJson()) } })

    fun summary(): String = buildString {
        append(candidate.name)
        if (failure != null) {
            append("\nFAILED: ").append(failure)
            return@buildString
        }
        append("\nWER: ")
            .append(totalWordErrors)
            .append('/')
            .append(totalReferenceWords)
            .append(" (")
            .append(String.format(Locale.US, "%.1f%%", aggregateWordErrorRate * 100.0))
            .append(')')
        append("\nLoad: ").append(modelLoadMillis).append(" ms")
        append("; peak PSS: ").append(peakPssKib / 1024).append(" MiB")
        append("; thermal max: ").append(maxThermal)
        fixtures.forEach { fixture ->
            append("\n- ").append(fixture.category)
                .append(": ").append(fixture.wordErrors)
                .append('/').append(fixture.referenceWordCount)
                .append(" errors, ").append(fixture.elapsedMillis).append(" ms")
                .append(", ").append(fixture.timestamps.size).append(" timestamps")
                .append("\n  ").append(fixture.transcript.ifBlank { "[empty transcript]" })
        }
    }
}

private data class ResourceSnapshot(
    val peakPssKib: Long,
    val maxThermalStatus: Int,
)

private class ResourceSampler(
    private val powerManager: PowerManager,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var peakPssKib = Debug.getPss()

    @Volatile
    private var maxThermalStatus = powerManager.currentThermalStatus

    private var worker: Thread? = null

    fun start() {
        check(running.compareAndSet(false, true))
        worker = thread(name = "DreamLog-ASR-resource-sampler") {
            while (running.get()) {
                peakPssKib = max(peakPssKib, Debug.getPss())
                maxThermalStatus = max(maxThermalStatus, powerManager.currentThermalStatus)
                try {
                    Thread.sleep(RESOURCE_SAMPLE_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop(): ResourceSnapshot {
        running.set(false)
        worker?.interrupt()
        runCatching { worker?.join(1_000L) }
        peakPssKib = max(peakPssKib, Debug.getPss())
        maxThermalStatus = max(maxThermalStatus, powerManager.currentThermalStatus)
        return ResourceSnapshot(peakPssKib, maxThermalStatus)
    }

    private companion object {
        const val RESOURCE_SAMPLE_INTERVAL_MILLIS = 50L
    }
}

private fun normalizeWords(text: String): List<String> =
    text.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9']+"), " ")
        .trim()
        .takeIf(String::isNotEmpty)
        ?.split(Regex("\\s+"))
        .orEmpty()

private fun editDistance(reference: List<String>, hypothesis: List<String>): Int {
    var previous = IntArray(hypothesis.size + 1) { it }
    reference.forEachIndexed { referenceIndex, referenceWord ->
        val current = IntArray(hypothesis.size + 1)
        current[0] = referenceIndex + 1
        hypothesis.forEachIndexed { hypothesisIndex, hypothesisWord ->
            val substitution = previous[hypothesisIndex] +
                if (referenceWord == hypothesisWord) 0 else 1
            val insertion = current[hypothesisIndex] + 1
            val deletion = previous[hypothesisIndex + 1] + 1
            current[hypothesisIndex + 1] = minOf(substitution, insertion, deletion)
        }
        previous = current
    }
    return previous[hypothesis.size]
}
