package com.wivy.dreamlog.transcription

import kotlin.math.roundToLong

internal data class SherpaRecognition(
    val text: String,
    val tokens: List<String>,
    val timestampsSeconds: List<Float>,
)

/** Converts sherpa token starts into deterministic, non-overlapping source-time segments. */
internal object SherpaTokenSegmenter {
    fun segment(
        recognition: SherpaRecognition,
        sourceDurationMillis: Long,
        contentStartMillis: Long = 0L,
        triggeringWakePhrase: TriggeringWakePhrase? = null,
    ): TranscriptionResult {
        require(sourceDurationMillis >= 0L) { "Source duration is negative." }
        require(contentStartMillis in 0L..sourceDurationMillis) {
            "Content start is outside the recognized audio."
        }

        val rawText = recognition.text.trim()
        if (rawText.isEmpty()) {
            return TranscriptionResult(rawText = rawText, segments = emptyList())
        }
        check(sourceDurationMillis > 0L) {
            "Non-empty transcription text has no source duration."
        }

        check(recognition.tokens.isNotEmpty()) {
            "The local recognizer did not return timestamped tokens."
        }
        check(recognition.tokens.size == recognition.timestampsSeconds.size) {
            "The local recognizer returned inconsistent token timestamps."
        }
        check(
            timestampsAreUsable(
                timestamps = recognition.timestampsSeconds,
                sourceDurationMillis = sourceDurationMillis,
            ),
        ) {
            "The local recognizer returned invalid source timestamps."
        }

        val tokenStarts = recognition.timestampsSeconds.map { timestamp ->
            (timestamp.toDouble() * MILLIS_PER_SECOND)
                .roundToLong()
                .coerceIn(0L, sourceDurationMillis - 1L)
        }
        val words = groupTokens(recognition.tokens, tokenStarts)
        check(words.isNotEmpty()) {
            "The local recognizer returned text without timestamped words."
        }
        check(canonical(words.joinToString(separator = " ") { it.text }) == canonical(rawText)) {
            "The local recognizer's timestamped tokens do not match its text."
        }

        val retainedWords = selectNarrationWords(
            words = words,
            contentStartMillis = contentStartMillis,
            triggeringWakePhrase = triggeringWakePhrase,
        )
        if (retainedWords.isEmpty()) {
            return TranscriptionResult(rawText = "", segments = emptyList())
        }

        val mergedWords = mutableListOf<TimedText>()
        retainedWords.forEach { word ->
            val previous = mergedWords.lastOrNull()
            if (previous != null && word.startMillis <= previous.startMillis) {
                mergedWords[mergedWords.lastIndex] = previous.copy(
                    text = "${previous.text} ${word.text}",
                )
            } else {
                mergedWords += word
            }
        }

        val segments = mergedWords.mapIndexed { index, word ->
            val endMillis = mergedWords.getOrNull(index + 1)?.startMillis
                ?: sourceDurationMillis
            TranscriptionSegment(
                sourceStartMillis = word.startMillis,
                sourceEndMillis = endMillis,
                text = word.text,
            )
        }
        return TranscriptionResult(
            rawText = retainedWords.joinToString(separator = " ") { it.text },
            segments = segments,
        )
    }

    /**
     * Removes only an exact, event-grounded trigger in the decode-only prefix.
     *
     * Keyword detection can be reported after immediate narration has already begun. When the
     * exact trigger is found once at or before that report boundary, every later word is narration
     * even if its timestamp is slightly before the boundary. Missing, garbled, or ambiguous
     * trigger evidence falls back to the strict word-start boundary; no fuzzy deletion is used.
     */
    private fun selectNarrationWords(
        words: List<TimedText>,
        contentStartMillis: Long,
        triggeringWakePhrase: TriggeringWakePhrase?,
    ): List<TimedText> {
        if (contentStartMillis == 0L) return words
        val exactMatches = triggeringWakePhrase?.let { phrase ->
            exactControlPhraseMatches(
                words = words,
                contentStartMillis = contentStartMillis,
                expectedCanonicalText = phrase.canonicalText,
            )
        }.orEmpty()
        return if (exactMatches.size == 1) {
            words.drop(exactMatches.single().last + 1)
        } else {
            words.dropWhile { it.startMillis < contentStartMillis }
        }
    }

    private fun exactControlPhraseMatches(
        words: List<TimedText>,
        contentStartMillis: Long,
        expectedCanonicalText: String,
    ): List<IntRange> {
        val matches = mutableListOf<IntRange>()
        for (startIndex in words.indices) {
            if (words[startIndex].startMillis > contentStartMillis) break
            var combined = ""
            for (endIndex in startIndex..words.lastIndex) {
                val word = words[endIndex]
                if (word.startMillis > contentStartMillis) break
                combined += word.text.canonicalControlText()
                if (!expectedCanonicalText.startsWith(combined)) break
                if (combined == expectedCanonicalText) {
                    matches += startIndex..endIndex
                    break
                }
            }
        }
        return matches
    }

    private fun timestampsAreUsable(
        timestamps: List<Float>,
        sourceDurationMillis: Long,
    ): Boolean {
        var previous = 0f
        return timestamps.allIndexed { index, timestamp ->
            val usable = timestamp.isFinite() && timestamp >= 0f &&
                timestamp.toDouble() * MILLIS_PER_SECOND < sourceDurationMillis &&
                (index == 0 || timestamp >= previous)
            previous = timestamp
            usable
        }
    }

    private fun groupTokens(
        tokens: List<String>,
        startsMillis: List<Long>,
    ): List<TimedText> {
        val words = mutableListOf<TimedText>()
        var text = StringBuilder()
        var startMillis: Long? = null
        var pendingBoundaryStartMillis: Long? = null

        fun finishWord() {
            val completed = text.toString().trim()
            val completedStart = startMillis
            if (completed.isNotEmpty() && completedStart != null) {
                words += TimedText(startMillis = completedStart, text = completed)
            }
            text = StringBuilder()
            startMillis = null
        }

        tokens.zip(startsMillis).forEach { (token, tokenStartMillis) ->
            val expanded = token.replace(SENTENCE_PIECE_WORD_BOUNDARY, ' ')
            val startsAtBoundary = expanded.firstOrNull()?.isWhitespace() == true
            val endsAtBoundary = expanded.lastOrNull()?.isWhitespace() == true
            val content = expanded.trim().replace(INNER_WHITESPACE, " ")

            if (startsAtBoundary) {
                finishWord()
                pendingBoundaryStartMillis = tokenStartMillis
            }
            if (content.isNotEmpty()) {
                if (startMillis == null) {
                    startMillis = pendingBoundaryStartMillis ?: tokenStartMillis
                }
                text.append(content)
                pendingBoundaryStartMillis = null
            }
            if (endsAtBoundary && content.isNotEmpty()) {
                finishWord()
                pendingBoundaryStartMillis = tokenStartMillis
            }
        }
        finishWord()
        return words
    }

    private fun canonical(text: String): String = text.trim().replace(INNER_WHITESPACE, " ")

    private fun String.canonicalControlText(): String =
        buildString(length) {
            this@canonicalControlText.forEach { character ->
                if (character.isLetterOrDigit()) append(character.uppercaseChar())
            }
        }

    private data class TimedText(
        val startMillis: Long,
        val text: String,
    )

    private const val MILLIS_PER_SECOND = 1_000.0
    private const val SENTENCE_PIECE_WORD_BOUNDARY = '\u2581'
    private val INNER_WHITESPACE = Regex("\\s+")
}

private inline fun <T> Iterable<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, value ->
        if (!predicate(index, value)) return false
    }
    return true
}
