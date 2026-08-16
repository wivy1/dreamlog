package com.wivy.dreamlog.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaTokenSegmenterTest {
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
