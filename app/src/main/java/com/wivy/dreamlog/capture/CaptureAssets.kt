package com.wivy.dreamlog.capture

import android.content.Context

data class CaptureAssetValidation(
    val valid: Boolean,
    val detail: String,
)

object CaptureAssets {
    const val VAD_MODEL = "vad/silero_vad.int8.onnx"

    private val requiredBinaryAssets = mapOf(
        "livekit-wakeword/melspectrogram.onnx" to 1_087_958,
        "livekit-wakeword/embedding_model.onnx" to 1_326_578,
        "livekit-wakeword/dreamlog.onnx" to 174_843,
        "livekit-wakeword/hey_dreamlog.onnx" to 174_843,
        VAD_MODEL to 200_000,
    )

    fun validate(context: Context): CaptureAssetValidation =
        runCatching {
            requiredBinaryAssets.forEach { (path, minimumBytes) ->
                context.assets.open(path).use { input ->
                    val observedBytes = input.available()
                    require(observedBytes >= minimumBytes) {
                        "$path is incomplete ($observedBytes bytes)."
                    }
                    require(input.read() >= 0) { "$path is empty." }
                }
            }

            CaptureAssetValidation(
                valid = true,
                detail = "The selected local wake and boundary models are installed.",
            )
        }.getOrElse { error ->
            CaptureAssetValidation(
                valid = false,
                detail = error.message ?: "The selected local model assets are unavailable.",
            )
        }
}
