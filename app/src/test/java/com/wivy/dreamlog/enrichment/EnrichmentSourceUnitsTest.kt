package com.wivy.dreamlog.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentSourceUnitsTest {
    @Test
    fun fiftyWordLevelSegmentsAreChunkedIntoBoundedUnitsWithExactCoverage() {
        val input = transcript(
            (0 until 50).map { index -> segment(index = index, text = "word$index") },
        )

        val units = input.toEnrichmentSourceUnits()

        assertEquals(listOf(16, 16, 16, 2), units.map { it.segments.size })
        assertEquals(listOf(0, 1, 2, 3), units.map { it.ordinal })
        assertTrue(units.all { supportedWords(it.text).size <= 16 })
        assertExactCoverage(input, units)
    }

    @Test
    fun sessionAndSourceIndexBoundariesAlwaysStartNewUnits() {
        val input = transcript(
            listOf(
                segment(sessionId = "session-a", sessionOrder = 0, index = 0, text = "one"),
                segment(sessionId = "session-a", sessionOrder = 0, index = 1, text = "two"),
                segment(sessionId = "session-a", sessionOrder = 0, index = 3, text = "three"),
                segment(sessionId = "session-b", sessionOrder = 1, index = 0, text = "four"),
                segment(sessionId = "session-b", sessionOrder = 1, index = 1, text = "five"),
            ),
        )

        val units = input.toEnrichmentSourceUnits()

        assertEquals(listOf(listOf(0, 1), listOf(3), listOf(0, 1)), units.map { unit ->
            unit.segments.map { it.segmentIndex }
        })
        assertEquals(listOf("session-a", "session-a", "session-b"), units.map {
            it.segments.singleOrFirst().sessionId
        })
        assertExactCoverage(input, units)
    }

    @Test
    fun longestMultiwordCuePhrasesCrossWordSegmentsWithoutDuplicateStarts() {
        val words = listOf(
            "before",
            "that", "dream", "ended", "and", "my", "next", "dream", "at", "sea",
            "i", "remember", "more", "about", "the", "same", "dream", "had", "rain",
            "no", "i", "mean", "snow",
        )
        val input = transcript(words.mapIndexed { index, word -> segment(index = index, text = word) })

        val units = input.toEnrichmentSourceUnits()

        assertEquals(listOf(1, 9, 9, 4), units.map { it.segments.size })
        assertEquals(
            listOf(
                EnrichmentCue.NONE,
                EnrichmentCue.NEW_DREAM,
                EnrichmentCue.ADDITION,
                EnrichmentCue.CORRECTION,
            ),
            units.map { it.cue },
        )
        assertEquals("that dream ended and my next dream at sea", units[1].text)
        assertEquals("i remember more about the same dream had rain", units[2].text)
        assertEquals("no i mean snow", units[3].text)
        assertExactCoverage(input, units)
    }

    @Test
    fun ordinalAndReturnReferencesStartCandidateUnitsWithoutChangingRawCoverage() {
        val words = listOf(
            "before",
            "the", "second", "dream", "was", "on", "a", "train",
            "back", "in", "the", "first", "dream", "the", "door", "was", "blue",
            "returning", "to", "the", "second", "dream", "the", "lights", "changed",
        )
        val input = transcript(words.mapIndexed { index, word -> segment(index = index, text = word) })

        val units = input.toEnrichmentSourceUnits()

        assertEquals(listOf(1, 7, 9, 8), units.map { it.segments.size })
        assertEquals(
            listOf(
                EnrichmentCue.NONE,
                EnrichmentCue.NEW_DREAM,
                EnrichmentCue.DREAM_REFERENCE,
                EnrichmentCue.DREAM_REFERENCE,
            ),
            units.map(EnrichmentSourceUnit::cue),
        )
        assertEquals("the second dream was on a train", units[1].text)
        assertEquals("back in the first dream the door was blue", units[2].text)
        assertEquals("returning to the second dream the lights changed", units[3].text)
        assertExactCoverage(input, units)
    }

    @Test
    fun oversizedAtomicSegmentsRemainWholeAndDoNotExpandAdjacentUnits() {
        val tooManyWords = (0 until 17).joinToString(separator = " ") { "large$it" }
        val tooManyCharacters = "x".repeat(201)
        val input = transcript(
            listOf(
                segment(index = 0, text = "before"),
                segment(index = 1, text = tooManyWords),
                segment(index = 2, text = tooManyCharacters),
                segment(index = 3, text = "after"),
            ),
        )

        val units = input.toEnrichmentSourceUnits()

        assertEquals(listOf(1, 1, 1, 1), units.map { it.segments.size })
        assertEquals(17, supportedWords(units[1].text).size)
        assertEquals(201, units[2].text.length)
        assertExactCoverage(input, units)
    }

    @Test
    fun cueClassificationKeepsSafetyPrecedence() {
        assertEquals(
            EnrichmentCue.CORRECTION,
            classifyEnrichmentCue("Actually I remember more about the same dream, maybe"),
        )
        assertEquals(
            EnrichmentCue.ADDITION,
            classifyEnrichmentCue("I remember more about the same dream, maybe it was next"),
        )
        assertEquals(
            EnrichmentCue.NEW_DREAM,
            classifyEnrichmentCue("My next dream was unclear and I cannot remember the rest"),
        )
        assertEquals(
            EnrichmentCue.NEW_DREAM,
            classifyEnrichmentCue("The second dream was in a library"),
        )
        assertEquals(
            EnrichmentCue.DREAM_REFERENCE,
            classifyEnrichmentCue("Back in the first dream the door was blue"),
        )
        assertEquals(
            EnrichmentCue.UNCERTAIN_FRAGMENT,
            classifyEnrichmentCue("I cannot remember the rest"),
        )
        assertEquals(EnrichmentCue.UNCERTAIN, classifyEnrichmentCue("Maybe a lake"))
        assertEquals(EnrichmentCue.NONE, classifyEnrichmentCue("A clear lake"))
    }

    @Test
    fun sourceUnitRejectsEmptyCrossSessionAndNoncontiguousSources() {
        assertTrue(runCatching { EnrichmentSourceUnit(0, emptyList()) }.isFailure)
        assertTrue(
            runCatching {
                EnrichmentSourceUnit(
                    0,
                    listOf(
                        segment(sessionId = "session-a", index = 0, text = "one"),
                        segment(sessionId = "session-b", index = 1, text = "two"),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                EnrichmentSourceUnit(
                    0,
                    listOf(segment(index = 0, text = "one"), segment(index = 2, text = "two")),
                )
            }.isFailure,
        )
    }

    private fun assertExactCoverage(
        input: OrderedNightTranscript,
        units: List<EnrichmentSourceUnit>,
    ) {
        val flattened = units.flatMap { it.segments }
        assertEquals(input.segments, flattened)
        assertEquals(input.segments.size, flattened.map { it.id }.distinct().size)
    }

    private fun transcript(segments: List<NightTranscriptSegment>): OrderedNightTranscript =
        OrderedNightTranscript.create(NIGHT_ID, segments)

    private fun segment(
        sessionId: String = "session-a",
        sessionOrder: Int = 0,
        index: Int,
        text: String,
    ) = NightTranscriptSegment(
        nightId = NIGHT_ID,
        sessionId = sessionId,
        sessionOrder = sessionOrder,
        transcriptAttempt = 1,
        segmentIndex = index,
        sourceStartMillis = index * 100L,
        sourceEndMillis = (index + 1L) * 100L,
        text = text,
        narrationStartedAtEpochMillis = 1_000_000L + sessionOrder * 60_000L,
        narrationStartedUtcOffsetSeconds = -18_000,
    )

    private fun List<NightTranscriptSegment>.singleOrFirst(): NightTranscriptSegment =
        if (size == 1) single() else first()

    private companion object {
        const val NIGHT_ID = "night-1"
    }
}
