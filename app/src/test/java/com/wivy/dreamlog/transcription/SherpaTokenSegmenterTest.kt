package com.wivy.dreamlog.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaTokenSegmenterTest {
    @Test
    fun ambiguousPunctuationMergeCannotSilentlyDropImmediateNarration() {
        listOf(false, true).forEach { hasLaterWord ->
            val tokens = listOf(" DREAM", " LOG", " ,", "first") +
                if (hasLaterWord) listOf(" later") else emptyList()
            val starts = listOf(0.2f, 0.4f, 0.5f, 0.9f) +
                if (hasLaterWord) listOf(1.5f) else emptyList()
            assertThrows(TranscriptionOutputException::class.java) {
                SherpaTokenSegmenter.segment(
                    SherpaRecognition("DREAM LOG,first" + if (hasLaterWord) " later" else "", tokens, starts),
                    sourceDurationMillis = 2_000L,
                    contentStartMillis = 1_000L,
                    triggeringWakePhrase = TriggeringWakePhrase.DREAM_LOG,
                    triggerReportMillis = 800L,
                )
            }
        }
    }

    @Test
    fun nativePunctuationSpacingKeepsTimedWordsInsteadOfFailingTheRecording() {
        val result = SherpaTokenSegmenter.segment(
            SherpaRecognition("hello, world", listOf(" hello", " ", ",", " world"),
                listOf(0f, 0.2f, 0.3f, 0.5f)),
            1_000L,
        )
        assertEquals("hello, world", result.rawText)
        assertEquals(listOf(TranscriptionSegment(0L, 500L, "hello,"),
            TranscriptionSegment(500L, 1_000L, "world")), result.segments)
    }

    @Test
    fun nativeSpacingAcrossAndInsideTokensPreservesEveryCharacter() {
        val examples = listOf(
            listOf("\u2581hello", "\u2581,", "\u2581world") to "hello, world",
            listOf(" hello ", ",", " world") to "hello, world",
            listOf(" hello", " .", " !", " world") to "hello.! world",
            listOf(" hello ,", " world") to "hello, world",
        )
        examples.forEach { (tokens, text) ->
            val result = SherpaTokenSegmenter.segment(
                SherpaRecognition(text, tokens, tokens.indices.map { it * 0.1f }), 1_000L,
            )
            assertEquals(text, result.rawText)
            assertEquals(0L, result.segments.first().sourceStartMillis)
            assertEquals(1_000L, result.segments.last().sourceEndMillis)
        }
    }

    @Test
    fun nativeSpacingDoesNotRemoveBothOfTwoSpacesOrJoinActualWords() {
        val result = SherpaTokenSegmenter.segment(
            SherpaRecognition("one , two", listOf(" one", " ", " ", ",", " two"),
                listOf(0f, 0.2f, 0.3f, 0.4f, 0.5f)), 1_000L,
        )
        assertEquals("one , two", result.rawText)
        listOf(
            SherpaRecognition("one,two", listOf(" one", " ,", " two"), listOf(0f, 0.2f, 0.5f)),
            SherpaRecognition("onetwo", listOf(" one", " two"), listOf(0f, 0.5f)),
            SherpaRecognition("One", listOf(" one"), listOf(0f)),
        ).forEach { invalid ->
            assertThrows(TranscriptionOutputException::class.java) {
                SherpaTokenSegmenter.segment(invalid, 1_000L)
            }
        }
    }

    @Test
    fun malformedModelOutputHasSpecificContentFreeReasons() {
        val cases = listOf(
            SherpaRecognition("PRIVATE", emptyList(), emptyList()) to
                "The speech model returned no word timings (missing_tokens).",
            SherpaRecognition("PRIVATE", listOf(" PRIVATE"), emptyList()) to
                "The speech model returned mismatched words and timings (timestamp_count_mismatch).",
            SherpaRecognition("PRIVATE", listOf(" PRIVATE"), listOf(Float.NaN)) to
                "The speech model returned invalid word times (invalid_timestamps).",
            SherpaRecognition("PRIVATE", listOf(" "), listOf(0f)) to
                "The speech model returned text without timed words (missing_timed_words).",
            SherpaRecognition("PRIVATE", listOf(" DIFFERENT"), listOf(0f)) to
                "The speech model's words did not match its transcript (token_text_mismatch).",
        )
        cases.forEach { (recognition, expectedMessage) ->
            val failure = assertThrows(IllegalStateException::class.java) {
                SherpaTokenSegmenter.segment(recognition, sourceDurationMillis = 1_000L)
            }
            assertEquals("TranscriptionOutputException", failure.javaClass.simpleName)
            assertEquals(expectedMessage, failure.message)
        }
    }

    @Test
    fun groupsPiecesAndStandaloneBoundariesIntoSourceTimedWords() {
        val result = SherpaTokenSegmenter.segment(
            recognition = SherpaRecognition(
                text = "YET THESE THOUGHTS",
                tokens = listOf(" ", "Y", "ET", " THESE", " THOUGH", "T", "S"),
                timestampsSeconds = listOf(0.10f, 0.12f, 0.20f, 0.40f, 0.80f, 0.88f, 0.96f),
            ),
            sourceDurationMillis = 1_200L,
        )

        assertEquals("YET THESE THOUGHTS", result.rawText)
        assertEquals(
            listOf(
                TranscriptionSegment(100L, 400L, "YET"),
                TranscriptionSegment(400L, 800L, "THESE"),
                TranscriptionSegment(800L, 1_200L, "THOUGHTS"),
            ),
            result.segments,
        )
    }

    @Test
    fun understandsSentencePieceBoundariesAndMergesRoundedDuplicateStarts() {
        val result = SherpaTokenSegmenter.segment(
            recognition = SherpaRecognition(
                text = "A DREAM LOG",
                tokens = listOf("▁A", "▁DR", "EAM", "▁LOG"),
                timestampsSeconds = listOf(0f, 0.0001f, 0.08f, 0.40f),
            ),
            sourceDurationMillis = 700L,
        )

        assertEquals(
            listOf(
                TranscriptionSegment(0L, 400L, "A DREAM"),
                TranscriptionSegment(400L, 700L, "LOG"),
            ),
            result.segments,
        )
    }

    @Test
    fun missingOrMalformedTimestampsRejectTheRecognitionResult() {
        assertThrows(IllegalStateException::class.java) {
            SherpaTokenSegmenter.segment(
                recognition = SherpaRecognition(
                    text = "usable raw text",
                    tokens = listOf(" usable", " raw", " text"),
                    timestampsSeconds = emptyList(),
                ),
                sourceDurationMillis = 2_000L,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            SherpaTokenSegmenter.segment(
                recognition = SherpaRecognition(
                    text = "usable raw text",
                    tokens = listOf(" usable", " raw", " text"),
                    timestampsSeconds = listOf(0.4f, 0.2f, Float.NaN),
                ),
                sourceDurationMillis = 2_000L,
            )
        }
    }

    @Test
    fun timestampsPastSourceEndAreRejectedInsteadOfInventingPlaybackPositions() {
        assertThrows(IllegalStateException::class.java) {
            SherpaTokenSegmenter.segment(
                recognition = SherpaRecognition(
                    text = "ONE TWO",
                    tokens = listOf(" ONE", " TWO"),
                    timestampsSeconds = listOf(9f, 10f),
                ),
                sourceDurationMillis = 1_000L,
            )
        }
    }

    @Test
    fun tokenTextThatDoesNotMatchRawTextIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            SherpaTokenSegmenter.segment(
                recognition = SherpaRecognition(
                    text = "ONE TWO",
                    tokens = listOf(" ONE", " THREE"),
                    timestampsSeconds = listOf(0f, 0.5f),
                ),
                sourceDurationMillis = 1_000L,
            )
        }
    }

    @Test
    fun preservesParakeetCasePunctuationAndDuplicatePieceStarts() {
        val result = SherpaTokenSegmenter.segment(
            recognition = SherpaRecognition(
                text = "I'M HERE.",
                tokens = listOf("\u2581I", "'M", "\u2581HERE", "."),
                timestampsSeconds = listOf(0f, 0f, 0.4f, 0.4f),
            ),
            sourceDurationMillis = 900L,
        )

        assertEquals("I'M HERE.", result.rawText)
        assertEquals(
            listOf(
                TranscriptionSegment(0L, 400L, "I'M"),
                TranscriptionSegment(400L, 900L, "HERE."),
            ),
            result.segments,
        )
    }

    @Test
    fun emptyTextNeverCreatesAnInventedSegment() {
        val result = SherpaTokenSegmenter.segment(
            recognition = SherpaRecognition(
                text = "  ",
                tokens = listOf(" HALLUCINATION"),
                timestampsSeconds = listOf(0f),
            ),
            sourceDurationMillis = 1_000L,
        )

        assertEquals("", result.rawText)
        assertTrue(result.segments.isEmpty())
    }
}
