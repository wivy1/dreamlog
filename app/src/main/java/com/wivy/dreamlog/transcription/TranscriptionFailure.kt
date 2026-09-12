package com.wivy.dreamlog.transcription

/** Fixed reasons preserve useful model-output diagnostics without retaining recognition content. */
internal enum class TranscriptionOutputFailure(val code: String, val explanation: String) {
    MISSING_TOKENS("missing_tokens", "The speech model returned no word timings"),
    TIMESTAMP_COUNT_MISMATCH(
        "timestamp_count_mismatch",
        "The speech model returned mismatched words and timings",
    ),
    INVALID_TIMESTAMPS("invalid_timestamps", "The speech model returned invalid word times"),
    MISSING_TIMED_WORDS("missing_timed_words", "The speech model returned text without timed words"),
    TOKEN_TEXT_MISMATCH(
        "token_text_mismatch",
        "The speech model's words did not match its transcript",
    ),
    WAKE_BOUNDARY_MISMATCH(
        "wake_boundary_mismatch",
        "The speech model's word boundaries could not separate narration from wake audio",
    ),
    ;

    val safeDetail: String get() = "$explanation ($code)."
}

internal class TranscriptionOutputException(val reason: TranscriptionOutputFailure) :
    IllegalStateException(reason.safeDetail)

/** Readable legacy/current failure details; never echo an unrecognized persisted payload. */
internal fun transcriptionFailureDisplayText(failureDetail: String?): String? {
    if (failureDetail.isNullOrBlank()) return null
    val retry = "Audio is saved; resume transcription to retry."
    TranscriptionOutputFailure.entries.firstOrNull { failureDetail.hasFailureCode(it.code) }
        ?.let { return "${it.explanation}. $retry" }
    val cause = TRANSCRIPTION_FAILURE_EXPLANATIONS.entries
        .firstOrNull { failureDetail.hasFailureCode(it.key) }
    if (cause != null) {
        if (cause.key == "source_access_denied" || cause.key == "source_read_failed") {
            return "${cause.value} Use Check recordings in Technical details, then retry."
        }
        val originalDetail = if (cause.key == "local_inference_failed") {
            " The original failure did not record a more specific cause."
        } else {
            ""
        }
        return "${cause.value}$originalDetail $retry"
    }
    if (failureDetail == TranscriptionAttemptRecovery.FAILURE_DETAIL) {
        return "Transcription stopped before this recording finished. " +
            "The cause was not recorded. $retry"
    }
    return "Transcription could not finish. $retry"
}

internal fun transcriptionFailureExplanation(code: String): String =
    TRANSCRIPTION_FAILURE_EXPLANATIONS.getValue(code)

private fun String.hasFailureCode(code: String): Boolean =
    contains("($code)") || contains("[code=$code;") || contains("[code=$code]")

private val TRANSCRIPTION_FAILURE_EXPLANATIONS = mapOf(
    "source_access_denied" to "DreamLog could not access the saved recording.",
    "source_read_failed" to "DreamLog could not read the saved recording.",
    "input_invalid" to "The recording or its source boundaries could not be processed.",
    "local_inference_failed" to "The speech model could not finish this recording.",
    "unexpected_runtime_failure" to "An unexpected processing error stopped this recording.",
)
