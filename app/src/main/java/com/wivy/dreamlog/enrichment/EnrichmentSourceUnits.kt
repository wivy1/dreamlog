package com.wivy.dreamlog.enrichment

internal enum class EnrichmentCue {
    NONE,
    NEW_DREAM,
    DREAM_REFERENCE,
    ADDITION,
    CORRECTION,
    UNCERTAIN_FRAGMENT,
    UNCERTAIN,
}

internal fun classifyEnrichmentCue(text: String): EnrichmentCue {
    val normalized = supportedWords(text).joinToString(separator = " ")
    return when {
        sourceExpressesCorrection(text) -> EnrichmentCue.CORRECTION
        sourceExpressesAddition(text) -> EnrichmentCue.ADDITION
        sourceExpressesDreamReturn(normalized) -> EnrichmentCue.DREAM_REFERENCE
        sourceExpressesNewDream(normalized) -> EnrichmentCue.NEW_DREAM
        ORDINAL_DREAM_REFERENCE.containsMatchIn(normalized) -> EnrichmentCue.DREAM_REFERENCE
        sourceExpressesIncompleteRecall(text) -> EnrichmentCue.UNCERTAIN_FRAGMENT
        sourceExpressesUncertainty(text) -> EnrichmentCue.UNCERTAIN
        else -> EnrichmentCue.NONE
    }
}

internal data class EnrichmentSourceUnit(
    val ordinal: Int,
    val segments: List<NightTranscriptSegment>,
) {
    init {
        require(ordinal >= 0) { "An enrichment source-unit ordinal cannot be negative." }
        require(segments.isNotEmpty()) { "An enrichment source unit cannot be empty." }
        val first = segments.first()
        require(
            segments.all { segment ->
                segment.nightId == first.nightId &&
                    segment.sessionId == first.sessionId &&
                    segment.sessionOrder == first.sessionOrder &&
                    segment.transcriptAttempt == first.transcriptAttempt
            },
        ) { "An enrichment source unit cannot cross source sessions or revisions." }
        segments.zipWithNext().forEach { (previous, next) ->
            require(next.segmentIndex == previous.segmentIndex + 1) {
                "An enrichment source unit must contain contiguous source segments."
            }
            require(next.sourceStartMillis >= previous.sourceEndMillis) {
                "An enrichment source unit must keep source segments chronological."
            }
        }
    }

    val text: String = segments.joinToString(separator = " ") { it.text.trim() }
    val cue: EnrichmentCue = classifyEnrichmentCue(text)
    val sessionOrder: Int = segments.first().sessionOrder
    val narrationStartedAtEpochMillis: Long? =
        segments.first().narrationStartedAtEpochMillis
    val narrationStartedUtcOffsetSeconds: Int? =
        segments.first().narrationStartedUtcOffsetSeconds
}

/**
 * Coalesces M04's fine-grained transcript segments into bounded prompt units.
 * Raw segments remain the immutable source of truth and are never split or rewritten.
 */
internal fun OrderedNightTranscript.toEnrichmentSourceUnits(): List<EnrichmentSourceUnit> {
    if (segments.isEmpty()) return emptyList()

    val cuePhraseEndsByStart = findCuePhraseEnds(segments)
    val units = mutableListOf<EnrichmentSourceUnit>()
    var current = mutableListOf<NightTranscriptSegment>()
    var cuePhraseProtectedThrough = -1

    fun flush() {
        if (current.isEmpty()) return
        units += EnrichmentSourceUnit(
            ordinal = units.size,
            segments = current.toList(),
        )
        current = mutableListOf()
        cuePhraseProtectedThrough = -1
    }

    segments.forEachIndexed { index, segment ->
        val startsCuePhrase = cuePhraseEndsByStart[index]
        val followsCurrent = current.lastOrNull()?.let { previous ->
            previous.isImmediatelyFollowedBy(segment)
        } ?: true

        if (current.isNotEmpty() && (!followsCurrent || startsCuePhrase != null)) {
            flush()
        }

        if (
            current.isNotEmpty() &&
            index > cuePhraseProtectedThrough &&
            (current + segment).exceedsEnrichmentUnitBounds()
        ) {
            flush()
        }

        if (current.isEmpty()) {
            cuePhraseProtectedThrough = startsCuePhrase ?: -1
        }
        current += segment

        // An oversized atomic source segment is valid by itself, but must not cause adjacent
        // source material to exceed the prompt-unit bounds too.
        if (index >= cuePhraseProtectedThrough && current.exceedsEnrichmentUnitBounds()) {
            flush()
        }
    }
    flush()
    return units
}

private const val MAX_ENRICHMENT_UNIT_SUPPORTED_WORDS = 16
private const val MAX_ENRICHMENT_UNIT_TEXT_CHARS = 200

private val ORDINAL_DREAM_WORDS = listOf(
    "first",
    "second",
    "third",
    "fourth",
    "fifth",
    "sixth",
    "seventh",
    "eighth",
    "ninth",
    "tenth",
)

private val CUE_PHRASES: List<List<String>> = buildList {
    // Long forms precede their natural suffixes so one spoken cue yields one unit boundary.
    add(listOf("that", "dream", "ended", "and", "my", "next", "dream"))
    add(listOf("i", "remember", "more", "about", "the", "same"))
    add(listOf("suddenly", "a", "different", "dream"))
    add(listOf("remember", "more", "about", "the", "same"))
    add(listOf("my", "next", "dream"))
    add(listOf("a", "different", "dream"))
    add(listOf("i", "remember", "more"))
    add(listOf("no", "i", "mean"))
    add(listOf("another", "detail"))
    add(listOf("different", "dream"))
    add(listOf("next", "dream"))
    add(listOf("i", "mean"))
    add(listOf("no", "the"))
    add(listOf("correction"))
    add(listOf("actually"))
    ORDINAL_DREAM_WORDS.forEach { ordinal ->
        add(listOf("returning", "to", "the", ordinal, "dream"))
        add(listOf("returning", "to", ordinal, "dream"))
        add(listOf("back", "in", "the", ordinal, "dream"))
        add(listOf("back", "to", "the", ordinal, "dream"))
        add(listOf("back", "in", ordinal, "dream"))
        add(listOf("back", "to", ordinal, "dream"))
        add(listOf("the", ordinal, "dream"))
        add(listOf(ordinal, "dream"))
    }
}.distinct().sortedByDescending(List<String>::size)

private val ORDINAL_DREAM_REFERENCE = Regex(
    "\\b(?:the )?(?:${ORDINAL_DREAM_WORDS.joinToString(separator = "|")}|" +
        "[0-9]+(?:st|nd|rd|th)) dream\\b",
)

private val NEW_DREAM_ORDINAL_REFERENCE = Regex(
    "\\b(?:the )?(?:${ORDINAL_DREAM_WORDS.drop(1).joinToString(separator = "|")}|" +
        "[2-9][0-9]*(?:st|nd|rd|th)) dream\\b",
)

private fun sourceExpressesDreamReturn(normalized: String): Boolean =
    ("back in " in normalized || "back to " in normalized || "returning to " in normalized) &&
        ORDINAL_DREAM_REFERENCE.containsMatchIn(normalized)

private fun sourceExpressesNewDream(normalized: String): Boolean =
    "next dream" in normalized ||
        "different dream" in normalized ||
        NEW_DREAM_ORDINAL_REFERENCE.containsMatchIn(normalized)

private fun findCuePhraseEnds(
    segments: List<NightTranscriptSegment>,
): Map<Int, Int> {
    val result = mutableMapOf<Int, Int>()
    var priorMatchEndSegment = -1
    segments.indices.forEach { start ->
        if (start <= priorMatchEndSegment) return@forEach
        val matchEnd = matchCuePhraseEnd(segments, start) ?: return@forEach
        result[start] = matchEnd
        priorMatchEndSegment = matchEnd
    }
    return result
}

private fun matchCuePhraseEnd(
    segments: List<NightTranscriptSegment>,
    start: Int,
): Int? {
    val words = mutableListOf<String>()
    val segmentIndexByWord = mutableListOf<Int>()
    val maximumPhraseWords = CUE_PHRASES.first().size
    var index = start
    while (index < segments.size && words.size < maximumPhraseWords) {
        if (index > start && !segments[index - 1].isImmediatelyFollowedBy(segments[index])) break
        supportedWords(segments[index].text).forEach { word ->
            if (words.size < maximumPhraseWords) {
                words += word
                segmentIndexByWord += index
            }
        }
        index += 1
    }

    val phrase = CUE_PHRASES.firstOrNull { candidate ->
        candidate.size <= words.size && candidate.indices.all { candidate[it] == words[it] }
    } ?: return null
    return segmentIndexByWord[phrase.lastIndex]
}

private fun NightTranscriptSegment.isImmediatelyFollowedBy(
    next: NightTranscriptSegment,
): Boolean =
    nightId == next.nightId &&
        sessionId == next.sessionId &&
        sessionOrder == next.sessionOrder &&
        transcriptAttempt == next.transcriptAttempt &&
        next.segmentIndex == segmentIndex + 1 &&
        next.sourceStartMillis >= sourceEndMillis

private fun List<NightTranscriptSegment>.exceedsEnrichmentUnitBounds(): Boolean {
    val joinedLength = sumOf { it.text.trim().length } + (size - 1).coerceAtLeast(0)
    val wordCount = sumOf { supportedWords(it.text).size }
    return wordCount > MAX_ENRICHMENT_UNIT_SUPPORTED_WORDS ||
        joinedLength > MAX_ENRICHMENT_UNIT_TEXT_CHARS
}
