package com.wivy.dreamlog.capture

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.min

/**
 * Runs the staged LiveKit frontend and two wake-word heads only in the isolated device-test app.
 *
 * The four models and all WAVs must be explicitly staged under this package's private filesDir.
 * Result JSON contains hashes, sample boundaries, scores, labels, and resource metrics, never audio
 * samples or recognized speech.
 */
@RunWith(AndroidJUnit4::class)
class LiveKitWakeWordReplayInstrumentedTest {
    @Test
    fun validateHostOracleAndReplayAnnotatedSessions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName == DEVICE_TEST_PACKAGE) {
            "The LiveKit fixture may run only in the isolated device-test package."
        }
        val runId = requireNotNull(
            InstrumentationRegistry.getArguments().getString(ARGUMENT_RUN_ID),
        ) { "The LiveKit replay run ID argument is required." }
        require(RUN_ID_PATTERN.matches(runId)) { "The LiveKit replay run ID is malformed." }

        val runDirectory = File(File(context.filesDir, REPLAY_ROOT_DIRECTORY), runId)
        assertContained(context.filesDir, runDirectory)
        require(runDirectory.isDirectory) { "The private LiveKit replay directory is missing." }
        val manifestFile = File(runDirectory, MANIFEST_FILE_NAME)
        assertContained(runDirectory, manifestFile)
        require(manifestFile.isFile && manifestFile.length() in 1L..MAX_MANIFEST_BYTES) {
            "The private LiveKit replay manifest is missing or has an invalid size."
        }
        val manifestBytes = manifestFile.readBytes()
        val manifestHash = sha256(manifestBytes)
        val input = try {
            readManifest(
                manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8)),
                expectedRunId = runId,
                runDirectory = runDirectory,
            )
        } catch (failure: Throwable) {
            cleanupRejectedRunDirectory(runDirectory, failure)
            throw failure
        } finally {
            manifestBytes.fill(0)
        }

        val stagedFiles = input.allStagedFiles
        val resultFile = File(runDirectory, RESULT_FILE_NAME)
        val resultStagingFile = File(runDirectory, ".$RESULT_FILE_NAME.part")
        try {
            stagedFiles.forEach { file -> assertContained(runDirectory, file) }
            require(stagedFiles.map(File::getName).distinct().size == stagedFiles.size) {
                "LiveKit fixture filenames must be unique across all staged inputs."
            }
            assertContained(runDirectory, resultFile)
            assertContained(runDirectory, resultStagingFile)
            deleteIfPresent(resultFile)
            deleteIfPresent(resultStagingFile)
            requireExactRunDirectoryContents(
                runDirectory,
                stagedFiles.map(File::getName).toSet() + MANIFEST_FILE_NAME,
            )
        } catch (failure: Throwable) {
            cleanupRejectedRunDirectory(runDirectory, failure)
            throw failure
        }

        val resourceTracker = ResourceTracker(context)
        val allLatencies = mutableListOf<Long>()
        val startedElapsedMillis = SystemClock.elapsedRealtime()
        var runtime: LiveKitWakeWordFixtureRuntime? = null
        var replayFailure: Throwable? = null
        lateinit var output: JSONObject
        try {
            resourceTracker.sample("before_model_load")
            val loadStartedNanos = SystemClock.elapsedRealtimeNanos()
            runtime = LiveKitWakeWordFixtureRuntime(
                privateRoot = runDirectory,
                models = input.models,
            )
            val modelLoadNanos = SystemClock.elapsedRealtimeNanos() - loadStartedNanos
            resourceTracker.sample("after_model_load")

            val coexistence = runSherpaCoexistenceSmoke(context)
            resourceTracker.sample("after_sherpa_coexistence")

            val oracleResults = input.oracleCases.map { oracle ->
                runOracle(runtime, oracle).also { result ->
                    allLatencies += result.measurement.latencyNanos
                    resourceTracker.sample("oracle_${oracle.alias}")
                }
            }
            require(oracleResults.all(OracleResult::withinTolerance)) {
                "At least one Android score differs from its host ORT oracle."
            }

            val sessionResults = input.sessions.map { session ->
                replaySession(runtime, input.thresholds, session).also { result ->
                    allLatencies += result.latenciesNanos
                    resourceTracker.sample("session_${session.validated.spec.alias}")
                }
            }
            val negativeResults = input.negativeFiles.map { negative ->
                replayNegative(runtime, input.thresholds, negative).also { result ->
                    allLatencies += result.latenciesNanos
                    resourceTracker.sample("negative_${negative.alias}")
                }
            }
            resourceTracker.sample("before_runtime_close")

            output = JSONObject()
                .put("schema_version", RESULT_SCHEMA_VERSION)
                .put("run_id", runId)
                .put("manifest_sha256", manifestHash)
                .put("source_replay_manifest_sha256", input.sourceManifestSha256)
                .put("package_name", context.packageName)
                .put(
                    "privacy",
                    JSONObject()
                        .put("content_free", true)
                        .put("audio_written", false)
                        .put("transcript_written", false)
                        .put("tokens_written", false)
                        .put("features_written", false),
                )
                .put("sample_rate_hz", LiveKitWakeWordFixturePolicy.SAMPLE_RATE_HZ)
                .put("window_samples", LiveKitWakeWordFixturePolicy.WINDOW_SAMPLES)
                .put("hop_samples", LiveKitWakeWordFixturePolicy.HOP_SAMPLES)
                .put("ort_version", runtime.ortVersion)
                .put("thresholds", thresholdsJson(input.thresholds))
                .put(
                    "models",
                    JSONArray().also { array ->
                        runtime.modelVerification.forEach { verification ->
                            array.put(modelVerificationJson(verification))
                        }
                    },
                )
                .put("native_coexistence", coexistence)
                .put(
                    "host_oracle",
                    JSONObject()
                        .put("all_within_tolerance", oracleResults.all(OracleResult::withinTolerance))
                        .put(
                            "cases",
                            JSONArray().also { array ->
                                oracleResults.forEach { result -> array.put(oracleResultJson(result)) }
                            },
                        ),
                )
                .put("authoritative_replay", replayAggregateJson(sessionResults))
                .put("negative_replay", negativeAggregateJson(negativeResults))
                .put(
                    "union_negative_exposure",
                    unionNegativeExposureJson(sessionResults, negativeResults),
                )
                .put(
                    "performance",
                    JSONObject()
                        .put("model_load_nanos", modelLoadNanos)
                        .put("inference", latencyStatisticsJson(allLatencies))
                        .put("resources", resourceTracker.toJson()),
                )
                .put("duration_millis", SystemClock.elapsedRealtime() - startedElapsedMillis)
        } catch (failure: Throwable) {
            replayFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            runCatching { runtime?.close() }.onFailure(cleanupFailures::add)
            stagedFiles.forEach { file ->
                runCatching { deleteIfPresent(file) }.onFailure(cleanupFailures::add)
            }
            runCatching { deleteIfPresent(manifestFile) }.onFailure(cleanupFailures::add)
            cleanupFailures.firstOrNull()?.let { cleanupFailure ->
                cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                replayFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }

        writeAtomically(resultFile, output.toString(2))
        requireExactRunDirectoryContents(runDirectory, setOf(RESULT_FILE_NAME))
    }

    private fun runOracle(
        runtime: LiveKitWakeWordFixtureRuntime,
        oracle: OracleInput,
    ): OracleResult {
        val samples = readPcm16Wav(oracle.file, oracle.audioIdentity)
        return try {
            require(samples.size == LiveKitWakeWordFixturePolicy.WINDOW_SAMPLES) {
                "A host-oracle fixture must contain exactly one 32,000-sample window."
            }
            val measurement = runtime.score(samples)
            val dreamLogDifference = kotlin.math.abs(
                measurement.scores.dreamLog - oracle.expectedScores.dreamLog,
            )
            val heyDreamLogDifference = kotlin.math.abs(
                measurement.scores.heyDreamLog - oracle.expectedScores.heyDreamLog,
            )
            OracleResult(
                input = oracle,
                measurement = measurement,
                dreamLogAbsoluteDifference = dreamLogDifference,
                heyDreamLogAbsoluteDifference = heyDreamLogDifference,
                withinTolerance = dreamLogDifference <= oracle.tolerance &&
                    heyDreamLogDifference <= oracle.tolerance,
            )
        } finally {
            samples.fill(0)
        }
    }

    private fun replaySession(
        runtime: LiveKitWakeWordFixtureRuntime,
        thresholds: LiveKitFixtureThresholds,
        input: SessionInput,
    ): SessionResult {
        val samples = readPcm16Wav(input.file, input.audioIdentity)
        return try {
            require(samples.size.toLong() == input.validated.spec.sampleCount)
            val trace = replayContinuous(runtime, thresholds, samples)
            val controlDetections = trace.detections.filter { detection ->
                detection.detection.sampleExclusive < input.validated.scoredStartSample
            }
            val scoredDetections = trace.detections.filter { detection ->
                detection.detection.sampleExclusive >= input.validated.scoredStartSample
            }
            SessionResult(
                input = input,
                evaluationCount = trace.evaluationCount,
                latenciesNanos = trace.latenciesNanos,
                preFloorDetections = controlDetections,
                scoredDetections = scoredDetections,
                candidateEpisodes = trace.candidateEpisodes,
                score = WakeSessionReplayScoring.scoreContinuous(
                    input.validated,
                    scoredDetections.map(ScoredReplayDetection::detection),
                ),
            )
        } finally {
            samples.fill(0)
        }
    }

    private fun replayNegative(
        runtime: LiveKitWakeWordFixtureRuntime,
        thresholds: LiveKitFixtureThresholds,
        input: NegativeInput,
    ): NegativeResult {
        val samples = readPcm16Wav(input.file, input.audioIdentity)
        return try {
            val trace = replayContinuous(runtime, thresholds, samples)
            NegativeResult(
                input = input,
                evaluationCount = trace.evaluationCount,
                latenciesNanos = trace.latenciesNanos,
                detections = trace.detections,
                candidateEpisodes = trace.candidateEpisodes,
            )
        } finally {
            samples.fill(0)
        }
    }

    private fun replayContinuous(
        runtime: LiveKitWakeWordFixtureRuntime,
        thresholds: LiveKitFixtureThresholds,
        samples: ShortArray,
    ): ReplayTrace {
        val stream = LiveKitWakeWordFixtureStream(runtime, thresholds)
        val detections = mutableListOf<ScoredReplayDetection>()
        val latencies = mutableListOf<Long>()
        val candidateEpisodes = mutableListOf<LiveKitFixtureCandidateTelemetry>()
        var evaluationCount = 0
        var offset = 0
        while (offset < samples.size) {
            val count = min(REPLAY_FRAME_SAMPLES, samples.size - offset)
            stream.accept(samples, offset, count).forEach { evaluation ->
                evaluationCount += 1
                latencies += evaluation.latencyNanos
                evaluation.candidateTelemetry?.let(candidateEpisodes::add)
                evaluation.detectedPhrase?.let { phrase ->
                    detections += ScoredReplayDetection(
                        detection = WakeReplayDetection(
                            checkNotNull(evaluation.triggerSampleExclusive),
                            phrase,
                        ),
                        scores = evaluation.scores,
                    )
                }
            }
            offset += count
        }
        return ReplayTrace(evaluationCount, latencies, detections, candidateEpisodes)
    }

    private fun runSherpaCoexistenceSmoke(context: Context): JSONObject {
        val apkFiles = buildList {
            add(File(context.applicationInfo.sourceDir))
            context.applicationInfo.splitSourceDirs?.forEach { path -> add(File(path)) }
        }
        val libraries = EXPECTED_NATIVE_LIBRARIES.map { expected ->
            val entryName = "lib/arm64-v8a/${expected.name}"
            val matches = apkFiles.mapNotNull { apk ->
                ZipFile(apk).use { archive ->
                    archive.getEntry(entryName)?.let { entry ->
                        PackagedNativeLibrary(
                            bytes = entry.size,
                            sha256 = archive.getInputStream(entry).use(::sha256),
                        )
                    }
                }
            }
            require(matches.size == 1) {
                "The device-test APK set must contain ${expected.name} exactly once."
            }
            val observed = matches.single()
            require(observed.bytes == expected.bytes) {
                "The ${expected.name} packaged byte count differs from the pinned fixture."
            }
            require(observed.sha256 == expected.sha256) {
                "The ${expected.name} native library SHA-256 differs from the pinned fixture."
            }
            expected
        }

        val vad = Vad(
            context.assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = CaptureAssets.VAD_MODEL,
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = 0f,
                    minSpeechDuration = 0f,
                    windowSize = VAD_WINDOW_SAMPLES,
                    maxSpeechDuration = 0f,
                ),
                sampleRate = LiveKitWakeWordFixturePolicy.SAMPLE_RATE_HZ,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
        val silenceScore = try {
            vad.compute(FloatArray(VAD_WINDOW_SAMPLES))
        } finally {
            vad.release()
        }
        require(silenceScore.isFinite()) { "Sherpa VAD returned a non-finite score." }
        val offlineRecognizerClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        require(offlineRecognizerClass.name == "com.k2fsa.sherpa.onnx.OfflineRecognizer")

        return JSONObject()
            .put("sherpa_onnx_version", SHERPA_ONNX_VERSION)
            .put("sherpa_vad_inference_completed", true)
            .put("sherpa_vad_silence_score", silenceScore.toDouble())
            .put("offline_recognizer_java_class_linked", true)
            .put("parakeet_shared_sherpa_jni_and_ort_core_verified", true)
            .put("parakeet_model_decode_run", false)
            .put(
                "native_libraries",
                JSONArray().also { array ->
                    libraries.forEach { library ->
                        array.put(
                            JSONObject()
                                .put("name", library.name)
                                .put("bytes", library.bytes)
                                .put("sha256", library.sha256),
                        )
                    }
                },
            )
    }

    private fun readManifest(
        manifest: JSONObject,
        expectedRunId: String,
        runDirectory: File,
    ): ReplayInput {
        requireOnlyKeys(manifest, TOP_LEVEL_MANIFEST_KEYS)
        require(manifest.getInt("schema_version") == MANIFEST_SCHEMA_VERSION) {
            "Unknown LiveKit replay manifest schema."
        }
        require(manifest.getString("run_id") == expectedRunId) {
            "The LiveKit replay manifest belongs to another run."
        }
        val sourceManifestFileName = manifest.getString("source_replay_manifest_file_name")
        require(
            SAFE_JSON_NAME.matches(sourceManifestFileName) &&
                sourceManifestFileName != MANIFEST_FILE_NAME,
        ) { "The authorized source replay manifest filename is malformed." }
        val sourceManifestFile = File(runDirectory, sourceManifestFileName)
        assertContained(runDirectory, sourceManifestFile)
        require(
            sourceManifestFile.isFile &&
                sourceManifestFile.length() in 1L..MAX_MANIFEST_BYTES,
        ) { "The authorized source replay manifest is missing or has an invalid size." }
        val sourceManifestBytes = sourceManifestFile.readBytes()
        val sourceManifestHash = sha256(sourceManifestBytes)
        require(sourceManifestHash == SOURCE_REPLAY_MANIFEST_SHA256) {
            "The authorized source replay manifest SHA-256 differs."
        }
        val sourceManifest = try {
            JSONObject(sourceManifestBytes.toString(Charsets.UTF_8))
        } finally {
            sourceManifestBytes.fill(0)
        }
        requireOnlyKeys(sourceManifest, SOURCE_REPLAY_MANIFEST_KEYS)
        require(sourceManifest.getInt("schema_version") == SOURCE_REPLAY_MANIFEST_SCHEMA_VERSION) {
            "The authorized source replay manifest schema differs."
        }
        require(sourceManifest.getString("run_id") == SOURCE_REPLAY_RUN_ID) {
            "The authorized source replay manifest run ID differs."
        }

        val modelsJson = manifest.getJSONArray("models")
        require(modelsJson.length() == EXPECTED_MODEL_ROLES.size) {
            "The LiveKit replay manifest must stage exactly four ONNX models."
        }
        val modelSpecs = List(modelsJson.length()) { index ->
            readModelSpec(modelsJson.getJSONObject(index), runDirectory)
        }
        require(modelSpecs.map(LiveKitFixtureModelSpec::role).toSet() == EXPECTED_MODEL_ROLES) {
            "The LiveKit replay manifest has unexpected model roles."
        }
        require(modelSpecs.map { spec -> spec.file.name }.distinct().size == modelSpecs.size)
        val modelsByRole = modelSpecs.associateBy(LiveKitFixtureModelSpec::role)
        val mel = modelsByRole.getValue("melspectrogram")
        val embedding = modelsByRole.getValue("embedding")
        require(mel.expectedBytes == MEL_MODEL_BYTES && mel.expectedSha256 == MEL_MODEL_SHA256) {
            "The staged mel frontend differs from the pinned LiveKit model."
        }
        require(
            embedding.expectedBytes == EMBEDDING_MODEL_BYTES &&
                embedding.expectedSha256 == EMBEDDING_MODEL_SHA256,
        ) { "The staged embedding frontend differs from the pinned LiveKit model." }
        val dreamLogHead = modelsByRole.getValue("dreamlog_head")
        val heyDreamLogHead = modelsByRole.getValue("hey_dreamlog_head")
        require(dreamLogHead.expectedSha256 != heyDreamLogHead.expectedSha256) {
            "The two classifier heads must not be byte-identical."
        }

        val thresholdsJson = manifest.getJSONObject("thresholds")
        requireOnlyKeys(thresholdsJson, THRESHOLD_KEYS)
        val thresholds = LiveKitFixtureThresholds(
            dreamLog = thresholdsJson.getDouble("dreamlog").toFloat(),
            heyDreamLog = thresholdsJson.getDouble("hey_dreamlog").toFloat(),
        )

        val oracleJson = manifest.getJSONArray("oracle_cases")
        require(oracleJson.length() in 1..MAX_ORACLE_CASES) {
            "The LiveKit replay manifest has an invalid host-oracle case count."
        }
        val oracleCases = List(oracleJson.length()) { index ->
            readOracleInput(oracleJson.getJSONObject(index), runDirectory)
        }
        require(oracleCases.map(OracleInput::alias).distinct().size == oracleCases.size)

        val sessionsJson = sourceManifest.getJSONArray("sessions")
        require(sessionsJson.length() == EXPECTED_SESSIONS.size) {
            "The LiveKit replay manifest must contain the two authorized sessions."
        }
        val sessions = List(sessionsJson.length()) { index ->
            readSessionInput(sessionsJson.getJSONObject(index), runDirectory)
        }
        require(sessions.map { input -> input.validated.spec.alias }.distinct().size == sessions.size)
        sessions.forEach { session ->
            val expected = EXPECTED_SESSIONS.getValue(session.validated.spec.alias)
            require(
                session.file.name == expected.fileName &&
                    session.audioIdentity.sha256 == expected.sha256 &&
                    session.audioIdentity.wavBytes == expected.wavBytes &&
                    session.audioIdentity.sampleCount == expected.sampleCount &&
                    session.validated.spec.triggerPhrase == expected.triggerPhrase &&
                    session.validated.controlInvocations.size == 1 &&
                    session.validated.scoredInvocations.size == expected.scoredInvocationCount,
            ) { "An authorized wake session identity or annotation count differs." }
        }

        val negativesJson = sourceManifest.getJSONArray("negative_files")
        require(negativesJson.length() == EXPECTED_NEGATIVES.size) {
            "The LiveKit replay manifest must contain the two authorized neutral controls."
        }
        val negativeFiles = List(negativesJson.length()) { index ->
            readNegativeInput(negativesJson.getJSONObject(index), runDirectory)
        }
        require(negativeFiles.map(NegativeInput::alias).distinct().size == negativeFiles.size)
        negativeFiles.forEach { input ->
            val expectedHash = EXPECTED_NEGATIVES[input.alias]
                ?: throw IllegalArgumentException("A neutral-control alias is not authorized.")
            require(input.file.nameWithoutExtension == input.alias && input.audioIdentity.sha256 == expectedHash) {
                "A neutral-control identity differs from the authorized replay."
            }
        }

        return ReplayInput(
            sourceManifestFile = sourceManifestFile,
            sourceManifestSha256 = sourceManifestHash,
            models = LiveKitFixtureModelSet(mel, embedding, dreamLogHead, heyDreamLogHead),
            thresholds = thresholds,
            oracleCases = oracleCases.sortedBy(OracleInput::alias),
            sessions = sessions.sortedBy { input -> input.validated.spec.alias },
            negativeFiles = negativeFiles.sortedBy(NegativeInput::alias),
        )
    }

    private fun readModelSpec(
        value: JSONObject,
        runDirectory: File,
    ): LiveKitFixtureModelSpec {
        requireOnlyKeys(value, MODEL_KEYS)
        val role = value.getString("role")
        require(MODEL_ROLE_PATTERN.matches(role)) { "A staged model role is malformed." }
        val fileName = value.getString("file_name")
        require(SAFE_ONNX_NAME.matches(fileName)) { "A staged ONNX filename is malformed." }
        val bytes = value.getLong("bytes")
        require(bytes in 1L..MAX_MODEL_BYTES) { "A staged ONNX model has an invalid byte count." }
        val hash = value.getString("sha256")
        require(SHA256_PATTERN.matches(hash)) { "A staged ONNX SHA-256 is malformed." }
        return LiveKitFixtureModelSpec(role, File(runDirectory, fileName), bytes, hash)
    }

    private fun readOracleInput(
        value: JSONObject,
        runDirectory: File,
    ): OracleInput {
        requireOnlyKeys(value, ORACLE_KEYS)
        val alias = safeAlias(value.getString("alias"))
        val file = safeWavFile(value.getString("file_name"), runDirectory)
        require(file.nameWithoutExtension == alias) {
            "A host-oracle alias does not match its staged filename."
        }
        val identity = readAudioIdentity(value)
        require(identity.sampleCount == LiveKitWakeWordFixturePolicy.WINDOW_SAMPLES.toLong()) {
            "A host-oracle WAV must contain exactly 32,000 samples."
        }
        val expected = value.getJSONObject("expected_scores")
        requireOnlyKeys(expected, THRESHOLD_KEYS)
        val scores = LiveKitFixtureScores(
            dreamLog = expected.getDouble("dreamlog").toFloat(),
            heyDreamLog = expected.getDouble("hey_dreamlog").toFloat(),
        )
        val tolerance = value.getDouble("absolute_tolerance").toFloat()
        require(tolerance.isFinite() && tolerance in MIN_ORACLE_TOLERANCE..MAX_ORACLE_TOLERANCE) {
            "A host-oracle tolerance is outside the approved range."
        }
        return OracleInput(alias, file, identity, scores, tolerance)
    }

    private fun readSessionInput(
        value: JSONObject,
        runDirectory: File,
    ): SessionInput {
        requireOnlyKeys(value, SESSION_KEYS)
        val alias = safeAlias(value.getString("alias"))
        val file = safeWavFile(value.getString("file_name"), runDirectory)
        require(file.nameWithoutExtension == alias) {
            "A replay-session alias does not match its staged filename."
        }
        val identity = readAudioIdentity(value)
        val invocationsJson = value.getJSONArray("invocations")
        require(invocationsJson.length() in 1..MAX_INVOCATIONS_PER_SESSION) {
            "A replay session has an invalid invocation count."
        }
        val spec = WakeReplaySessionSpec(
            alias = alias,
            sampleCount = identity.sampleCount,
            triggerPhrase = WakeReplayPhrase.fromManifest(value.getString("trigger_phrase")),
            preRollSampleCount = value.getLong("pre_roll_sample_count"),
            cueStartSample = nullableLong(value, "cue_start_sample"),
            cueEndSampleExclusive = nullableLong(value, "cue_end_sample_exclusive"),
            invocations = List(invocationsJson.length()) { index ->
                val invocation = invocationsJson.getJSONObject(index)
                requireOnlyKeys(invocation, INVOCATION_KEYS)
                WakeReplayInvocation(
                    id = invocation.getString("id"),
                    phrase = WakeReplayPhrase.fromManifest(invocation.getString("phrase")),
                    role = WakeReplayInvocationRole.fromManifest(invocation.getString("role")),
                    spokenStartSample = invocation.getLong("spoken_start_sample"),
                    spokenEndSampleExclusive = invocation.getLong("spoken_end_sample_exclusive"),
                    scoreStartSample = invocation.getLong("score_start_sample"),
                    scoreEndSampleExclusive = invocation.getLong("score_end_sample_exclusive"),
                )
            },
        )
        return SessionInput(file, identity, WakeSessionReplayScoring.validateSession(spec))
    }

    private fun readNegativeInput(
        value: JSONObject,
        runDirectory: File,
    ): NegativeInput {
        requireOnlyKeys(value, NEGATIVE_KEYS)
        return NegativeInput(
            alias = safeAlias(value.getString("alias")),
            file = safeWavFile(value.getString("file_name"), runDirectory),
            audioIdentity = readAudioIdentity(value),
        )
    }

    private fun readAudioIdentity(value: JSONObject): AudioIdentity {
        val hash = value.getString("sha256")
        require(SHA256_PATTERN.matches(hash)) { "A WAV SHA-256 is malformed." }
        val wavBytes = value.getLong("wav_bytes")
        val sampleCount = value.getLong("sample_count")
        require(sampleCount in 1L..Int.MAX_VALUE.toLong()) {
            "A WAV has an unsupported sample count."
        }
        require(wavBytes == WAV_HEADER_BYTES + sampleCount * PCM16_BYTES) {
            "A WAV has inconsistent length metadata."
        }
        require(value.getInt("sample_rate_hz") == LiveKitWakeWordFixturePolicy.SAMPLE_RATE_HZ)
        require(value.getInt("channels") == CHANNELS)
        require(value.getInt("bits_per_sample") == BITS_PER_SAMPLE)
        return AudioIdentity(hash, wavBytes, sampleCount)
    }

    private fun readPcm16Wav(
        file: File,
        expected: AudioIdentity,
    ): ShortArray {
        require(file.isFile && file.length() == expected.wavBytes) {
            "A private replay WAV is missing or has an unexpected length."
        }
        val bytes = file.readBytes()
        return try {
            require(sha256(bytes) == expected.sha256) { "A private replay WAV SHA-256 differs." }
            require(bytes.size >= WAV_HEADER_BYTES)
            require(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF")
            require(bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE")
            require(bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII) == "fmt ")
            require(bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII) == "data")
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(header.getInt(4).toLong() == bytes.size.toLong() - 8L)
            require(header.getInt(16) == 16)
            require(header.getShort(20).toInt() == 1)
            require(header.getShort(22).toInt() == CHANNELS)
            require(header.getInt(24) == LiveKitWakeWordFixturePolicy.SAMPLE_RATE_HZ)
            require(header.getInt(28) == LiveKitWakeWordFixturePolicy.SAMPLE_RATE_HZ * PCM16_BYTES)
            require(header.getShort(32).toInt() == PCM16_BYTES)
            require(header.getShort(34).toInt() == BITS_PER_SAMPLE)
            val dataBytes = header.getInt(40)
            require(dataBytes >= 0 && dataBytes % PCM16_BYTES == 0)
            require(bytes.size == WAV_HEADER_BYTES + dataBytes)
            require(dataBytes / PCM16_BYTES == expected.sampleCount.toInt())
            ShortArray(dataBytes / PCM16_BYTES) { index ->
                header.getShort(WAV_HEADER_BYTES + index * PCM16_BYTES)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun modelVerificationJson(value: LiveKitFixtureModelVerification): JSONObject =
        JSONObject()
            .put("role", value.role)
            .put("file_name", value.fileName)
            .put("bytes", value.bytes)
            .put("sha256", value.sha256)
            .put("input", tensorMetadataJson(value.input))
            .put("output", tensorMetadataJson(value.output))

    private fun tensorMetadataJson(value: LiveKitFixtureTensorMetadata): JSONObject = JSONObject()
        .put("name", value.name)
        .put("shape", JSONArray(value.shape))

    private fun thresholdsJson(value: LiveKitFixtureThresholds): JSONObject = JSONObject()
        .put("dreamlog", value.dreamLog.toDouble())
        .put("hey_dreamlog", value.heyDreamLog.toDouble())

    private fun oracleResultJson(result: OracleResult): JSONObject = JSONObject()
        .put("alias", result.input.alias)
        .put("sha256", result.input.audioIdentity.sha256)
        .put("sample_count", result.input.audioIdentity.sampleCount)
        .put("expected_scores", scoresJson(result.input.expectedScores))
        .put("observed_scores", scoresJson(result.measurement.scores))
        .put(
            "absolute_difference",
            scoresJson(
                LiveKitFixtureScores(
                    result.dreamLogAbsoluteDifference,
                    result.heyDreamLogAbsoluteDifference,
                ),
            ),
        )
        .put("absolute_tolerance", result.input.tolerance.toDouble())
        .put("within_tolerance", result.withinTolerance)
        .put("latency_nanos", result.measurement.latencyNanos)

    private fun scoresJson(value: LiveKitFixtureScores): JSONObject = JSONObject()
        .put("dreamlog", value.dreamLog.toDouble())
        .put("hey_dreamlog", value.heyDreamLog.toDouble())

    private fun replayAggregateJson(results: List<SessionResult>): JSONObject {
        val scores = results.map(SessionResult::score)
        val invocationCount = scores.sumOf(WakeReplayContinuousScore::invocationCount)
        val anyHits = scores.sumOf(WakeReplayContinuousScore::anyLabelHits)
        val exactHits = scores.sumOf(WakeReplayContinuousScore::exactLabelHits)
        val wrongHits = scores.sumOf(WakeReplayContinuousScore::wrongLabelHits)
        val misses = scores.sumOf(WakeReplayContinuousScore::misses)
        val duplicates = scores.sumOf(WakeReplayContinuousScore::duplicateDetectionCount)
        val falsePositives = scores.sumOf { score -> score.falsePositiveDetections.size }
        val negativeSamples = scores.sumOf(WakeReplayContinuousScore::negativeSampleCount)
        return JSONObject()
            .put("source_count", results.size)
            .put("evaluation_count", results.sumOf(SessionResult::evaluationCount))
            .put("invocation_count", invocationCount)
            .put("any_label_hits", anyHits)
            .put("any_label_recall", fraction(anyHits, invocationCount))
            .put("exact_label_hits", exactHits)
            .put("exact_label_recall", fraction(exactHits, invocationCount))
            .put("wrong_label_hits", wrongHits)
            .put("misses", misses)
            .put("duplicate_detections", duplicates)
            .put("false_positives", falsePositives)
            .put("negative_sample_count", negativeSamples)
            .put("false_positives_per_negative_minute", perMinute(falsePositives, negativeSamples))
            .put(
                "sessions",
                JSONArray().also { array -> results.forEach { result -> array.put(sessionResultJson(result)) } },
            )
    }

    private fun sessionResultJson(result: SessionResult): JSONObject = JSONObject()
        .put("alias", result.input.validated.spec.alias)
        .put("sha256", result.input.audioIdentity.sha256)
        .put("sample_count", result.input.audioIdentity.sampleCount)
        .put("scored_start_sample", result.input.validated.scoredStartSample)
        .put("evaluation_count", result.evaluationCount)
        .put("pre_floor_detections", scoredDetectionsJson(result.preFloorDetections))
        .put("scored_detections", scoredDetectionsJson(result.scoredDetections))
        .put("candidate_episodes", candidateEpisodesJson(result.candidateEpisodes))
        .put(
            "metrics",
            JSONObject()
                .put("invocation_count", result.score.invocationCount)
                .put("any_label_hits", result.score.anyLabelHits)
                .put("exact_label_hits", result.score.exactLabelHits)
                .put("wrong_label_hits", result.score.wrongLabelHits)
                .put("misses", result.score.misses)
                .put("duplicate_detections", result.score.duplicateDetectionCount)
                .put("false_positives", result.score.falsePositiveDetections.size),
        )
        .put(
            "invocations",
            JSONArray().also { array ->
                result.score.invocationScores.forEach { score ->
                    array.put(
                        JSONObject()
                            .put("id", score.invocation.id)
                            .put("phrase", score.invocation.phrase.manifestValue)
                            .put("exact_label_hit", score.exactLabelHit)
                            .put("wrong_label_hit", score.wrongLabelHit)
                            .put(
                                "matched_detection",
                                score.matchedDetection?.let(::detectionJson) ?: JSONObject.NULL,
                            )
                            .put("duplicates", detectionsJson(score.duplicateDetections)),
                    )
                }
            },
        )

    private fun negativeAggregateJson(results: List<NegativeResult>): JSONObject {
        val detections = results.sumOf { result -> result.detections.size }
        val sampleCount = results.sumOf { result -> result.input.audioIdentity.sampleCount }
        return JSONObject()
            .put("source_count", results.size)
            .put("evaluation_count", results.sumOf(NegativeResult::evaluationCount))
            .put("detection_count", detections)
            .put("sample_count", sampleCount)
            .put("false_positives_per_minute", perMinute(detections, sampleCount))
            .put(
                "sources",
                JSONArray().also { array ->
                    results.forEach { result ->
                        array.put(
                            JSONObject()
                                .put("alias", result.input.alias)
                                .put("sha256", result.input.audioIdentity.sha256)
                                .put("sample_count", result.input.audioIdentity.sampleCount)
                                .put("evaluation_count", result.evaluationCount)
                                .put("detections", scoredDetectionsJson(result.detections))
                                .put(
                                    "candidate_episodes",
                                    candidateEpisodesJson(result.candidateEpisodes),
                                ),
                        )
                    }
                },
            )
    }

    private fun unionNegativeExposureJson(
        sessionResults: List<SessionResult>,
        negativeResults: List<NegativeResult>,
    ): JSONObject {
        val sessionNegativeSamples = sessionResults.sumOf { result ->
            result.score.negativeSampleCount
        }
        val neutralSamples = negativeResults.sumOf { result ->
            result.input.audioIdentity.sampleCount
        }
        val sessionFalsePositives = sessionResults.sumOf { result ->
            result.score.falsePositiveDetections.size
        }
        val neutralFalsePositives = negativeResults.sumOf { result ->
            result.detections.size
        }
        val unionNegativeSamples = sessionNegativeSamples + neutralSamples
        val unionFalsePositives = sessionFalsePositives + neutralFalsePositives
        return JSONObject()
            .put("session_negative_sample_count", sessionNegativeSamples)
            .put("neutral_sample_count", neutralSamples)
            .put("union_negative_sample_count", unionNegativeSamples)
            .put("session_false_positives", sessionFalsePositives)
            .put("neutral_false_positives", neutralFalsePositives)
            .put("union_false_positives", unionFalsePositives)
            .put(
                "union_false_positives_per_hour",
                perHour(unionFalsePositives, unionNegativeSamples),
            )
    }

    private fun latencyStatisticsJson(latencies: List<Long>): JSONObject {
        require(latencies.isNotEmpty()) { "The LiveKit fixture completed no inferences." }
        val ordered = latencies.sorted()
        val deadlineMisses = ordered.count { latency -> latency > HOP_DEADLINE_NANOS }
        return JSONObject()
            .put("count", ordered.size)
            .put("min_nanos", ordered.first())
            .put("median_nanos", percentile(ordered, 0.50))
            .put("p95_nanos", percentile(ordered, 0.95))
            .put("p99_nanos", percentile(ordered, 0.99))
            .put("max_nanos", ordered.last())
            .put("mean_nanos", ordered.average())
            .put("hop_deadline_nanos", HOP_DEADLINE_NANOS)
            .put("deadline_miss_count", deadlineMisses)
            .put("deadline_miss_fraction", deadlineMisses.toDouble() / ordered.size)
    }

    private fun percentile(
        ordered: List<Long>,
        fraction: Double,
    ): Long {
        val index = (ceil(fraction * ordered.size).toInt() - 1).coerceIn(0, ordered.lastIndex)
        return ordered[index]
    }

    private fun detectionsJson(values: List<WakeReplayDetection>): JSONArray =
        JSONArray().also { array -> values.forEach { detection -> array.put(detectionJson(detection)) } }

    private fun scoredDetectionsJson(values: List<ScoredReplayDetection>): JSONArray =
        JSONArray().also { array ->
            values.forEach { detection ->
                array.put(
                    detectionJson(detection.detection)
                        .put("scores", scoresJson(detection.scores)),
                )
            }
        }

    private fun candidateEpisodesJson(
        values: List<LiveKitFixtureCandidateTelemetry>,
    ): JSONArray = JSONArray().also { array ->
        values.forEach { value ->
            array.put(
                JSONObject()
                    .put("max_dream_log_score", value.maxDreamLogScore)
                    .put("max_hey_dream_log_score", value.maxHeyDreamLogScore)
                    .put("max_dream_log_threshold_ratio", value.maxDreamLogThresholdRatio)
                    .put("max_hey_dream_log_threshold_ratio", value.maxHeyDreamLogThresholdRatio)
                    .put("dream_log_threshold_margin", value.dreamLogThresholdMargin)
                    .put("hey_dream_log_threshold_margin", value.heyDreamLogThresholdMargin)
                    .put("observed_hop_count", value.observedHopCount)
                    .put(
                        "max_adjacent_qualifying_hop_count",
                        value.maxAdjacentQualifyingHopCount,
                    )
                    .put("guard_outcome", value.guardOutcome)
                    .put("accepted", value.accepted)
                    .put("reason", value.reason)
                    .put(
                        "accepted_phrase",
                        value.acceptedPhrase?.manifestValue ?: JSONObject.NULL,
                    ),
            )
        }
    }

    private fun detectionJson(value: WakeReplayDetection): JSONObject = JSONObject()
        .put("sample_exclusive", value.sampleExclusive)
        .put("phrase", value.phrase.manifestValue)

    private fun fraction(numerator: Int, denominator: Int): Any =
        if (denominator == 0) JSONObject.NULL else numerator.toDouble() / denominator

    private fun perMinute(detections: Int, sampleCount: Long): Any =
        if (sampleCount == 0L) JSONObject.NULL
        else detections.toDouble() * LiveKitWakeWordFixturePolicy.SAMPLE_RATE_HZ * 60.0 / sampleCount

    private fun perHour(detections: Int, sampleCount: Long): Any =
        if (sampleCount == 0L) JSONObject.NULL
        else detections.toDouble() * LiveKitWakeWordFixturePolicy.SAMPLE_RATE_HZ * 3_600.0 / sampleCount

    private fun safeAlias(value: String): String {
        require(SAFE_ID.matches(value)) { "A LiveKit replay alias is malformed." }
        return value
    }

    private fun safeWavFile(
        fileName: String,
        runDirectory: File,
    ): File {
        require(SAFE_WAV_NAME.matches(fileName)) { "A staged WAV filename is malformed." }
        return File(runDirectory, fileName)
    }

    private fun nullableLong(value: JSONObject, key: String): Long? =
        if (value.isNull(key)) null else value.getLong(key)

    private fun requireOnlyKeys(value: JSONObject, allowedKeys: Set<String>) {
        val observed = buildSet {
            val keys = value.keys()
            while (keys.hasNext()) add(keys.next())
        }
        require(observed.all(allowedKeys::contains)) {
            "A LiveKit replay manifest object contains an unexpected field."
        }
    }

    private fun requireExactRunDirectoryContents(
        runDirectory: File,
        allowedNames: Set<String>,
    ) {
        val entries = requireNotNull(runDirectory.listFiles()) {
            "The private LiveKit replay directory could not be enumerated."
        }
        require(entries.all(File::isFile)) {
            "The private LiveKit replay directory contains a subdirectory."
        }
        require(entries.map(File::getName).toSet() == allowedNames) {
            "The private LiveKit replay directory contains missing or unexpected files."
        }
    }

    private fun assertContained(parent: File, child: File) {
        val prefix = parent.canonicalPath + File.separator
        require(child.canonicalPath.startsWith(prefix)) {
            "A LiveKit replay path escaped its private parent directory."
        }
    }

    private fun deleteIfPresent(file: File) {
        if (file.exists()) require(file.isFile && file.delete()) {
            "A private LiveKit fixture input could not be deleted exactly."
        }
    }

    private fun cleanupRejectedRunDirectory(
        runDirectory: File,
        failure: Throwable,
    ) {
        val entries = runCatching {
            requireNotNull(runDirectory.listFiles()) {
                "The rejected LiveKit replay directory could not be enumerated."
            }
        }.getOrElse { cleanupFailure ->
            failure.addSuppressed(cleanupFailure)
            return
        }
        entries.forEach { entry ->
            runCatching {
                assertContained(runDirectory, entry)
                require(entry.isFile) {
                    "The rejected LiveKit replay directory contains a subdirectory."
                }
                deleteIfPresent(entry)
            }.onFailure(failure::addSuppressed)
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val parent = requireNotNull(file.parentFile)
        val staging = File(parent, ".${file.name}.part")
        assertContained(parent, staging)
        deleteIfPresent(staging)
        FileOutputStream(staging, false).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        deleteIfPresent(file)
        require(staging.renameTo(file)) { "The content-free LiveKit result could not be finalized." }
    }

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class ReplayInput(
        val sourceManifestFile: File,
        val sourceManifestSha256: String,
        val models: LiveKitFixtureModelSet,
        val thresholds: LiveKitFixtureThresholds,
        val oracleCases: List<OracleInput>,
        val sessions: List<SessionInput>,
        val negativeFiles: List<NegativeInput>,
    ) {
        val allStagedFiles: List<File>
            get() = listOf(
                sourceManifestFile,
                models.melSpectrogram.file,
                models.embedding.file,
                models.dreamLogHead.file,
                models.heyDreamLogHead.file,
            ) + oracleCases.map(OracleInput::file) +
                sessions.map(SessionInput::file) +
                negativeFiles.map(NegativeInput::file)
    }

    private data class AudioIdentity(
        val sha256: String,
        val wavBytes: Long,
        val sampleCount: Long,
    )

    private data class OracleInput(
        val alias: String,
        val file: File,
        val audioIdentity: AudioIdentity,
        val expectedScores: LiveKitFixtureScores,
        val tolerance: Float,
    )

    private data class SessionInput(
        val file: File,
        val audioIdentity: AudioIdentity,
        val validated: ValidatedWakeReplaySession,
    )

    private data class NegativeInput(
        val alias: String,
        val file: File,
        val audioIdentity: AudioIdentity,
    )

    private data class OracleResult(
        val input: OracleInput,
        val measurement: LiveKitFixtureScoreMeasurement,
        val dreamLogAbsoluteDifference: Float,
        val heyDreamLogAbsoluteDifference: Float,
        val withinTolerance: Boolean,
    )

    private data class ReplayTrace(
        val evaluationCount: Int,
        val latenciesNanos: List<Long>,
        val detections: List<ScoredReplayDetection>,
        val candidateEpisodes: List<LiveKitFixtureCandidateTelemetry>,
    )

    private data class ScoredReplayDetection(
        val detection: WakeReplayDetection,
        val scores: LiveKitFixtureScores,
    )

    private data class SessionResult(
        val input: SessionInput,
        val evaluationCount: Int,
        val latenciesNanos: List<Long>,
        val preFloorDetections: List<ScoredReplayDetection>,
        val scoredDetections: List<ScoredReplayDetection>,
        val candidateEpisodes: List<LiveKitFixtureCandidateTelemetry>,
        val score: WakeReplayContinuousScore,
    )

    private data class NegativeResult(
        val input: NegativeInput,
        val evaluationCount: Int,
        val latenciesNanos: List<Long>,
        val detections: List<ScoredReplayDetection>,
        val candidateEpisodes: List<LiveKitFixtureCandidateTelemetry>,
    )

    private data class NativeLibraryIdentity(
        val name: String,
        val bytes: Long,
        val sha256: String,
    )

    private data class ExpectedSessionIdentity(
        val fileName: String,
        val sha256: String,
        val wavBytes: Long,
        val sampleCount: Long,
        val triggerPhrase: WakeReplayPhrase,
        val scoredInvocationCount: Int,
    )

    private data class PackagedNativeLibrary(
        val bytes: Long,
        val sha256: String,
    )

    private class ResourceTracker(context: Context) {
        private val powerManager = context.getSystemService(PowerManager::class.java)
        private val samples = mutableListOf<ResourceSample>()

        fun sample(label: String) {
            require(SAFE_METRIC_LABEL.matches(label)) { "A resource metric label is malformed." }
            samples += ResourceSample(
                label = label,
                elapsedMillis = SystemClock.elapsedRealtime(),
                pssKb = Debug.getPss(),
                thermalStatus = powerManager.currentThermalStatus,
            )
        }

        fun toJson(): JSONObject {
            require(samples.isNotEmpty())
            return JSONObject()
                .put("pss_peak_kb", samples.maxOf(ResourceSample::pssKb))
                .put("thermal_max_status", samples.maxOf(ResourceSample::thermalStatus))
                .put(
                    "samples",
                    JSONArray().also { array ->
                        samples.forEach { sample ->
                            array.put(
                                JSONObject()
                                    .put("label", sample.label)
                                    .put("elapsed_millis", sample.elapsedMillis)
                                    .put("pss_kb", sample.pssKb)
                                    .put("thermal_status", sample.thermalStatus),
                            )
                        }
                    },
                )
        }
    }

    private data class ResourceSample(
        val label: String,
        val elapsedMillis: Long,
        val pssKb: Long,
        val thermalStatus: Int,
    )

    private companion object {
        const val DEVICE_TEST_PACKAGE = "com.wivy.dreamlog.devicetest"
        const val ARGUMENT_RUN_ID = "runId"
        const val REPLAY_ROOT_DIRECTORY = "livekit-wakeword-replay"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val RESULT_FILE_NAME = "livekit-replay-results.json"
        const val MANIFEST_SCHEMA_VERSION = 1
        const val RESULT_SCHEMA_VERSION = 1
        const val REPLAY_FRAME_SAMPLES = 512
        const val VAD_WINDOW_SAMPLES = 512
        const val VAD_THRESHOLD = 0.25f
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val PCM16_BYTES = 2
        const val WAV_HEADER_BYTES = 44
        const val SHERPA_ONNX_VERSION = "1.13.4"
        const val MAX_MANIFEST_BYTES = 1_048_576L
        const val MAX_MODEL_BYTES = 64L * 1024L * 1024L
        const val MAX_ORACLE_CASES = 100
        const val MAX_INVOCATIONS_PER_SESSION = 10_000
        const val MIN_ORACLE_TOLERANCE = 0.0000001f
        const val MAX_ORACLE_TOLERANCE = 0.001f
        const val HOP_DEADLINE_NANOS = 80_000_000L
        const val MEL_MODEL_BYTES = 1_087_958L
        const val EMBEDDING_MODEL_BYTES = 1_326_578L
        const val MEL_MODEL_SHA256 =
            "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f"
        const val EMBEDDING_MODEL_SHA256 =
            "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f"

        val RUN_ID_PATTERN = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        val SAFE_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        val SAFE_WAV_NAME = Regex("[a-z0-9][a-z0-9_-]{0,79}\\.wav")
        val SAFE_ONNX_NAME = Regex("[a-z0-9][a-z0-9_-]{0,79}\\.onnx")
        val SAFE_JSON_NAME = Regex("[a-z0-9][a-z0-9_-]{0,79}\\.json")
        val SAFE_METRIC_LABEL = Regex("[a-z0-9][a-z0-9_-]{0,95}")
        val MODEL_ROLE_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val EXPECTED_MODEL_ROLES = setOf(
            "melspectrogram",
            "embedding",
            "dreamlog_head",
            "hey_dreamlog_head",
        )
        val TOP_LEVEL_MANIFEST_KEYS = setOf(
            "schema_version",
            "run_id",
            "source_replay_manifest_file_name",
            "models",
            "thresholds",
            "oracle_cases",
        )
        val SOURCE_REPLAY_MANIFEST_KEYS = setOf(
            "schema_version",
            "run_id",
            "sessions",
            "negative_files",
        )
        val MODEL_KEYS = setOf("role", "file_name", "bytes", "sha256")
        val THRESHOLD_KEYS = setOf("dreamlog", "hey_dreamlog")
        val ORACLE_KEYS = setOf(
            "alias",
            "file_name",
            "sha256",
            "wav_bytes",
            "sample_count",
            "sample_rate_hz",
            "channels",
            "bits_per_sample",
            "expected_scores",
            "absolute_tolerance",
        )
        val SESSION_KEYS = setOf(
            "alias",
            "file_name",
            "sha256",
            "wav_bytes",
            "sample_count",
            "sample_rate_hz",
            "channels",
            "bits_per_sample",
            "trigger_phrase",
            "pre_roll_sample_count",
            "cue_start_sample",
            "cue_end_sample_exclusive",
            "invocations",
        )
        val INVOCATION_KEYS = setOf(
            "id",
            "phrase",
            "role",
            "spoken_start_sample",
            "spoken_end_sample_exclusive",
            "score_start_sample",
            "score_end_sample_exclusive",
        )
        val NEGATIVE_KEYS = setOf(
            "alias",
            "file_name",
            "sha256",
            "wav_bytes",
            "sample_count",
            "sample_rate_hz",
            "channels",
            "bits_per_sample",
        )
        val EXPECTED_NATIVE_LIBRARIES = listOf(
            NativeLibraryIdentity(
                "libonnxruntime.so",
                21_688_920L,
                "994848008526a934dfb579ac773b00e5867929234852b061005d45aacaee9533",
            ),
            NativeLibraryIdentity(
                "libonnxruntime4j_jni.so",
                111_648L,
                "8cd202ef5ad8fb13754abc8767b892a6ae8ded41ee0a30bd1917007720aa56bb",
            ),
            NativeLibraryIdentity(
                "libsherpa-onnx-jni.so",
                4_710_728L,
                "a79ff75fbe1c3813cc239037b458a7828298a90a5b77f5314056508eefdf72bc",
            ),
        )
        const val SOURCE_REPLAY_MANIFEST_SHA256 =
            "9935be7e05b47744b18e9583a46c3a9b588d0532551b4cb65ccb9c92b49b18ed"
        const val SOURCE_REPLAY_MANIFEST_SCHEMA_VERSION = 1
        const val SOURCE_REPLAY_RUN_ID = "a2f8c1d4-7b9e-4c31-8d62-5e7f9012ab34"
        val EXPECTED_SESSIONS = mapOf(
            "session-08" to ExpectedSessionIdentity(
                fileName = "session-08.wav",
                sha256 = "4d0b53546912a0f153c3edd7053065e51d5025b9bb5e3b925d85d44ae19e9aeb",
                wavBytes = 1_910_316L,
                sampleCount = 955_136L,
                triggerPhrase = WakeReplayPhrase.DREAM_LOG,
                scoredInvocationCount = 22,
            ),
            "session-09" to ExpectedSessionIdentity(
                fileName = "session-09.wav",
                sha256 = "7f0efde7307a197de6c0b24c43003fa10df64f1359be8bbd9bce2b87575b0894",
                wavBytes = 1_633_836L,
                sampleCount = 816_896L,
                triggerPhrase = WakeReplayPhrase.HEY_DREAM_LOG,
                scoredInvocationCount = 17,
            ),
        )
        val EXPECTED_NEGATIVES = mapOf(
            "neutral-0" to "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f",
            "neutral-1" to "5143a6ba93c4b274e2c4ac22deb75c2c48936c853f0519add1de828b6c79cc5a",
        )
    }
}
