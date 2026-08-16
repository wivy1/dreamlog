package com.wivy.dreamlog.playback

import com.wivy.dreamlog.history.AudioEvidenceState
import com.wivy.dreamlog.history.CaptureSessionEntity
import com.wivy.dreamlog.history.DreamSourceRole
import com.wivy.dreamlog.history.DreamSourceSpanEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamSourcePlayerTest {
    @Test
    fun planPreservesLogicalSpanOrderAcrossSessions() {
        val sessions = listOf(
            session("session-later", captureOrder = 1, audioId = 'b'),
            session("session-earlier", captureOrder = 0, audioId = 'a'),
        )
        val spans = listOf(
            span(
                order = 1,
                sessionId = "session-earlier",
                start = 4_000L,
                end = 5_500L,
            ),
            span(
                order = 0,
                sessionId = "session-later",
                start = 1_000L,
                end = 2_500L,
            ),
        )

        val plan = buildDreamSourcePlaybackPlan(NIGHT_ID, spans, sessions)

        assertTrue(plan is DreamSourcePlaybackPlan.Ready)
        val clips = (plan as DreamSourcePlaybackPlan.Ready).clips
        assertEquals(
            listOf(
                "a_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.wav",
                "a_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.wav",
            ),
            clips.map(DreamSourcePlaybackClip::audioFileName),
        )
        assertEquals(listOf(0L, 3_700L), clips.map { it.sourceStartMillis })
        assertEquals(listOf(2_500L, 5_500L), clips.map { it.sourceEndMillis })
    }

    @Test
    fun firstTranscriptSpanStartsShortlyBeforeTheRecordedCue() {
        val plan = buildDreamSourcePlaybackPlan(
            nightId = NIGHT_ID,
            sourceSpans = listOf(span(0, "session-1", 2_100L, 3_000L)),
            sessions = listOf(
                session(
                    sessionId = "session-1",
                    captureOrder = 0,
                    audioId = 'a',
                    preRollSampleCount = 32_000L,
                    cueStartSample = 36_000L,
                ),
            ),
        )

        assertTrue(plan is DreamSourcePlaybackPlan.Ready)
        assertEquals(
            250L,
            (plan as DreamSourcePlaybackPlan.Ready).clips.single().sourceStartMillis,
        )
    }

    @Test
    fun legacyAndShortSourceStartsRemainConfinedToTheSessionFile() {
        val legacyPlan = buildDreamSourcePlaybackPlan(
            nightId = NIGHT_ID,
            sourceSpans = listOf(span(0, "legacy", 1_100L, 2_000L)),
            sessions = listOf(
                session(
                    sessionId = "legacy",
                    captureOrder = 0,
                    audioId = 'a',
                    preRollSampleCount = 0L,
                    cueStartSample = 16_000L,
                ),
            ),
        )
        val zeroPlan = buildDreamSourcePlaybackPlan(
            nightId = NIGHT_ID,
            sourceSpans = listOf(span(0, "zero", 100L, 500L)),
            sessions = listOf(
                session(
                    sessionId = "zero",
                    captureOrder = 0,
                    audioId = 'b',
                    preRollSampleCount = null,
                    cueStartSample = null,
                ),
            ),
        )

        assertEquals(
            0L,
            (legacyPlan as DreamSourcePlaybackPlan.Ready).clips.single().sourceStartMillis,
        )
        assertEquals(
            0L,
            (zeroPlan as DreamSourcePlaybackPlan.Ready).clips.single().sourceStartMillis,
        )
    }

    @Test
    fun planFailsBeforePlaybackWhenAnyRequiredSourceIsUnavailable() {
        val sessions = listOf(
            session("session-1", captureOrder = 0, audioId = 'a'),
            session(
                "session-2",
                captureOrder = 1,
                audioId = 'b',
                audioState = AudioEvidenceState.EXPIRED,
            ),
        )
        val plan = buildDreamSourcePlaybackPlan(
            nightId = NIGHT_ID,
            sourceSpans = listOf(
                span(0, "session-1", 0L, 1_000L),
                span(1, "session-2", 0L, 1_000L),
            ),
            sessions = sessions,
        )

        assertTrue(plan is DreamSourcePlaybackPlan.Unavailable)
        assertTrue((plan as DreamSourcePlaybackPlan.Unavailable).message.startsWith("Audio expired"))
    }

    @Test
    fun plannerRejectsMissingMappingAndCursorCompletesExactlyOnce() {
        val plan = buildDreamSourcePlaybackPlan(
            nightId = NIGHT_ID,
            sourceSpans = listOf(span(0, "missing-session", 0L, 1_000L)),
            sessions = emptyList(),
        )

        assertTrue(plan is DreamSourcePlaybackPlan.Unavailable)
        assertEquals(1, nextDreamSourceSpanIndex(0, 3))
        assertEquals(2, nextDreamSourceSpanIndex(1, 3))
        assertNull(nextDreamSourceSpanIndex(2, 3))
    }

    @Test
    fun materiallyTruncatedSourceRangeCannotCompleteSilently() {
        assertNull(
            resolvedDreamSourceEndMillis(
                sourceStartMillis = 500L,
                sourceEndMillis = 2_000L,
                actualDurationMillis = 1_000L,
            ),
        )
        assertEquals(
            1_000L,
            resolvedDreamSourceEndMillis(
                sourceStartMillis = 500L,
                sourceEndMillis = 1_020L,
                actualDurationMillis = 1_000L,
            ),
        )
    }

    private fun session(
        sessionId: String,
        captureOrder: Int,
        audioId: Char,
        audioState: String = AudioEvidenceState.RETAINED,
        preRollSampleCount: Long? = 0L,
        cueStartSample: Long? = 0L,
    ) = CaptureSessionEntity(
        sessionId = sessionId,
        nightId = NIGHT_ID,
        captureOrder = captureOrder,
        startedAtEpochMillis = 0L,
        startedUtcOffsetSeconds = 0,
        finalizedAtEpochMillis = 10_000L,
        finalizedUtcOffsetSeconds = 0,
        incompleteReason = null,
        audioFileName = "a_${audioId.toString().repeat(32)}.wav",
        audioState = audioState,
        sampleRateHz = 16_000,
        channelCount = 1,
        bitsPerSample = 16,
        sampleCount = 160_000L,
        preRollSampleCount = preRollSampleCount,
        cueStartSample = cueStartSample,
        cueEndSampleExclusive = 0L,
    )

    private fun span(
        order: Int,
        sessionId: String,
        start: Long,
        end: Long,
    ) = DreamSourceSpanEntity(
        dreamId = "dream-1",
        spanOrder = order,
        sessionId = sessionId,
        sourceTranscriptAttemptCount = 1,
        firstSegmentIndex = order,
        lastSegmentIndex = order,
        sourceStartMillis = start,
        sourceEndMillis = end,
        sourceText = "source-$order",
        role = DreamSourceRole.NARRATIVE,
    )

    private companion object {
        const val NIGHT_ID = "night-1"
    }
}
