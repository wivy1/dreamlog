package com.wivy.dreamlog.transcription

import java.io.File

/** A file-oriented, fully local transcription engine. */
interface TranscriptionEngine : AutoCloseable {
    val metadata: TranscriptionEngineMetadata

    /**
     * Transcribes one retained session without modifying its source audio.
     *
     * Implementations must keep every segment boundary relative to [audioFile], including when
     * [input] excludes known leading and trailing non-narrative audio or supplies earlier acoustic
     * context around the persisted narration boundary.
     */
    fun transcribe(
        audioFile: File,
        input: TranscriptionInput? = null,
    ): TranscriptionResult
}

/** Exact wake phrase persisted on the session-linked capture event. */
enum class TriggeringWakePhrase(
    val persistedId: String,
    internal val canonicalText: String,
) {
    DREAM_LOG("dream_log", "DREAMLOG"),
    HEY_DREAM_LOG("hey_dream_log", "HEYDREAMLOG"),
}

/**
 * Separates the waveform sent to the recognizer from the persisted narration boundary.
 *
 * Earlier acoustic context can keep a recognizer from starting on the first narration phoneme.
 * Words beginning before [contentStartSample] are excluded unless an exact triggering phrase is
 * supplied and removed; every word after that marker is then retained. This lets modern sessions
 * decode from their retained wake context without persisting the wake phrase as dream text.
 */
data class TranscriptionInput(
    val acousticRange: Pcm16WavSource.RecognitionRange,
    val contentStartSample: Long = acousticRange.startSample,
    val triggeringWakePhrase: TriggeringWakePhrase? = null,
    val openingRecoveryFloorSample: Long? = null,
) {
    init {
        require(contentStartSample >= acousticRange.startSample) {
            "Transcription content starts before its acoustic context."
        }
        acousticRange.endSampleExclusive?.let { endSampleExclusive ->
            require(contentStartSample <= endSampleExclusive) {
                "Transcription content starts after its acoustic range."
            }
        }
        openingRecoveryFloorSample?.let { recoveryFloor ->
            require(recoveryFloor >= acousticRange.startSample) {
                "Opening recovery starts before the acoustic context."
            }
            acousticRange.endSampleExclusive?.let { endSampleExclusive ->
                require(recoveryFloor <= endSampleExclusive) {
                    "Opening recovery starts after the acoustic range."
                }
            }
        }
    }
}

data class TranscriptionEngineMetadata(
    val localeTag: String,
    val engineId: String,
    val engineVersion: String,
    val runtimeId: String,
    val runtimeVersion: String,
    val modelId: String,
    val modelVersion: String,
    val modelSha256: String,
) {
    init {
        require(localeTag.isNotBlank()) { "Transcription locale is missing." }
        require(engineId.isNotBlank()) { "Transcription engine ID is missing." }
        require(engineVersion.isNotBlank()) { "Transcription engine version is missing." }
        require(runtimeId.isNotBlank()) { "Transcription runtime ID is missing." }
        require(runtimeVersion.isNotBlank()) { "Transcription runtime version is missing." }
        require(modelId.isNotBlank()) { "Transcription model ID is missing." }
        require(modelVersion.isNotBlank()) { "Transcription model version is missing." }
        require(modelSha256.matches(SHA_256_PATTERN)) {
            "Transcription model SHA-256 is invalid."
        }
    }

    private companion object {
        val SHA_256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}

data class TranscriptionResult(
    val rawText: String,
    val segments: List<TranscriptionSegment>,
) {
    init {
        require(segments.zipWithNext().all { (left, right) ->
            left.sourceEndMillis <= right.sourceStartMillis
        }) { "Transcript segments must be ordered and non-overlapping." }
    }
}

data class TranscriptionSegment(
    val sourceStartMillis: Long,
    val sourceEndMillis: Long,
    val text: String,
) {
    init {
        require(sourceStartMillis >= 0L) { "Transcript segment start is negative." }
        require(sourceEndMillis > sourceStartMillis) {
            "Transcript segment must have a positive source duration."
        }
        require(text.isNotBlank()) { "Transcript segment text is empty." }
    }
}
