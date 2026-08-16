package com.wivy.dreamlog.capture

class NarrativeBoundaryDetector(
    val continuousNonSpeechSeconds: Int = DEFAULT_CONTINUOUS_NON_SPEECH_SECONDS,
) {
    init {
        require(continuousNonSpeechSeconds > 0) {
            "The continuous non-speech duration must be positive."
        }
    }

    val requiredNonSpeechSamples: Long =
        SAMPLE_RATE_HZ.toLong() * continuousNonSpeechSeconds

    var consecutiveNonSpeechSamples: Long = 0
        private set

    fun onNonSpeech(sampleCount: Int): Boolean {
        require(sampleCount >= 0) { "Sample count must not be negative." }

        consecutiveNonSpeechSamples += sampleCount
        return consecutiveNonSpeechSamples >= requiredNonSpeechSamples
    }

    fun onSpeech() {
        reset()
    }

    fun onCue() {
        reset()
    }

    fun onSilencing() {
        reset()
    }

    fun onGap() {
        reset()
    }

    fun reset() {
        consecutiveNonSpeechSamples = 0
    }

    companion object {
        const val SAMPLE_RATE_HZ: Int = 16_000
        const val DEFAULT_CONTINUOUS_NON_SPEECH_SECONDS: Int = 10
        const val DEFAULT_REQUIRED_NON_SPEECH_SAMPLES: Long = 160_000L
    }
}
