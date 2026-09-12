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
        triggerReportMillis: Long? = null,
    ): TranscriptionResult {
        require(sourceDurationMillis >= 0L) { "Source duration is negative." }
        require(contentStartMillis in 0L..sourceDurationMillis) {
            "Content start is outside the recognized audio."
        }
        require(triggerReportMillis == null || triggerReportMillis in 0L..contentStartMillis) {
            "Trigger report is outside the retained wake context."
        }

        val rawText = recognition.text.trim()
        if (rawText.isEmpty()) {
            return TranscriptionResult(rawText = rawText, segments = emptyList())
        }
        check(sourceDurationMillis > 0L) {
            "Non-empty transcription text has no source duration."
        }

        if (recognition.tokens.isEmpty()) {
            throw TranscriptionOutputException(TranscriptionOutputFailure.MISSING_TOKENS)
        }
        if (recognition.tokens.size != recognition.timestampsSeconds.size) {
            throw TranscriptionOutputException(TranscriptionOutputFailure.TIMESTAMP_COUNT_MISMATCH)
        }
        if (
            !timestampsAreUsable(
                timestamps = recognition.timestampsSeconds,
                sourceDurationMillis = sourceDurationMillis,
            )
        ) {
            throw TranscriptionOutputException(TranscriptionOutputFailure.INVALID_TIMESTAMPS)
        }

        val tokenStarts = recognition.timestampsSeconds.map { timestamp ->
            (timestamp.toDouble() * MILLIS_PER_SECOND)
                .roundToLong()
                .coerceIn(0L, sourceDurationMillis - 1L)
        }
        val words = groupTokens(reconcileNativePunctuationSpacing(recognition.tokens), tokenStarts)
        if (words.isEmpty()) {
            throw TranscriptionOutputException(TranscriptionOutputFailure.MISSING_TIMED_WORDS)
        }
        if (canonical(words.joinToString(separator = " ") { it.text }) != canonical(rawText)) {
            throw TranscriptionOutputException(TranscriptionOutputFailure.TOKEN_TEXT_MISMATCH)
        }

        val retainedWords = selectNarrationWords(
            words = words,
            originalWords = if (contentStartMillis > 0L) groupTokens(recognition.tokens, tokenStarts) else words,
            contentStartMillis = contentStartMillis,
            triggeringWakePhrase = triggeringWakePhrase,
            triggerReportMillis = triggerReportMillis,
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
     * exact trigger is found at or shortly before that report boundary, every later word is
     * narration even if its timestamp is slightly before the boundary. A detector report selects
     * the last exact nearby attempt when the owner repeated the phrase. Missing or garbled trigger
     * evidence falls back to the strict word-start boundary; no fuzzy deletion is used.
     */
    private fun selectNarrationWords(
        words: List<TimedText>,
        originalWords: List<TimedText>,
        contentStartMillis: Long,
        triggeringWakePhrase: TriggeringWakePhrase?,
        triggerReportMillis: Long?,
    ): List<TimedText> {
        if (contentStartMillis == 0L) return words
        val exactMatchBoundary = triggerReportMillis ?: contentStartMillis
        val exactMatches = triggeringWakePhrase?.let { phrase ->
            exactControlPhraseMatches(
                words = words,
                matchBoundaryMillis = exactMatchBoundary,
                expectedCanonicalText = phrase.canonicalText,
            )
        }.orEmpty()
        val selectedMatch = if (triggerReportMillis == null) {
            exactMatches.singleOrNull()
        } else {
            val earliestGroundedStart =
                (triggerReportMillis - TRIGGER_REPORT_LOOKBACK_MILLIS).coerceAtLeast(0L)
            exactMatches.lastOrNull { match ->
                words[match.first].startMillis >= earliestGroundedStart
            }
        }
        // A formatting repair must never merge narration into an early word that the fallback
        // would discard. Keep this ambiguous attempt retryable instead of silently losing text.
        if (selectedMatch == null &&
            originalWords.takeWhile { it.startMillis < contentStartMillis } !=
            words.takeWhile { it.startMillis < contentStartMillis }
        ) {
            throw TranscriptionOutputException(TranscriptionOutputFailure.WAKE_BOUNDARY_MISMATCH)
        }
        return if (selectedMatch != null) {
            words.drop(selectedMatch.last + 1)
        } else {
            words.dropWhile { it.startMillis < contentStartMillis }
        }
    }

    private fun exactControlPhraseMatches(
        words: List<TimedText>,
        matchBoundaryMillis: Long,
        expectedCanonicalText: String,
    ): List<IntRange> {
        val matches = mutableListOf<IntRange>()
        for (startIndex in words.indices) {
            if (words[startIndex].startMillis > matchBoundaryMillis) break
            var combined = ""
            for (endIndex in startIndex..words.lastIndex) {
                val word = words[endIndex]
                if (word.startMillis > matchBoundaryMillis) break
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

    private const val TRIGGER_REPORT_LOOKBACK_MILLIS = 2_500L

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

    /**
     * sherpa-onnx 1.13.4 Convert normalizes final text without changing its timestamped tokens.
     * Match the ASCII-punctuation branch of RemoveSpaceBetweenCjk for the pinned English model:
     * remove only the immediately preceding ASCII space, using original neighbors. Keeping token
     * slots (including empty ones) preserves all timestamps; the exact text check still applies.
     */
    private fun reconcileNativePunctuationSpacing(tokens: List<String>): List<String> {
        val expanded = tokens.map { it.replace(SENTENCE_PIECE_WORD_BOUNDARY, ' ') }
        val original = expanded.joinToString(separator = "")
        var offset = 0
        return expanded.map { token ->
            val result = buildString(token.length) {
                token.forEachIndexed { index, character ->
                    val position = offset + index
                    val next = original.getOrNull(position + 1)
                    val removedByNative = character == ' ' && position > 0 && next != null &&
                        (next in '!'..'/' || next in ':'..'@' || next in '['..'`' || next in '{'..'~')
                    if (!removedByNative) append(character)
                }
            }
            offset += token.length
            result
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
