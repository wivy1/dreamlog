package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSessionReplayScoringTest {
    @Test
    fun `validation derives all control and scored counts without a small-list cap`() {
        val control = invocation(
            id = "control",
            phrase = WakeReplayPhrase.DREAM_LOG,
            role = WakeReplayInvocationRole.CONTROL,
            scoreStart = 0L,
        )
        val scored = buildList {
            repeat(21) { index ->
                add(
                    invocation(
                        id = "dream-${index.toString().padStart(2, '0')}",
                        phrase = WakeReplayPhrase.DREAM_LOG,
                        scoreStart = 100L + index * 100L,
                    ),
                )
            }
            repeat(17) { index ->
                add(
                    invocation(
                        id = "hey-${index.toString().padStart(2, '0')}",
                        phrase = WakeReplayPhrase.HEY_DREAM_LOG,
                        scoreStart = 2_200L + index * 100L,
                    ),
                )
            }
        }
        val validated = WakeSessionReplayScoring.validateSession(
            session(control, *scored.toTypedArray()),
        )

        assertEquals(100L, validated.scoredStartSample)
        assertEquals(39, validated.spec.invocations.size)
        assertEquals(1, validated.controlInvocations.size)
        assertEquals(38, validated.scoredInvocations.size)
        assertEquals(22, validated.allCounts.getValue(WakeReplayPhrase.DREAM_LOG))
        assertEquals(17, validated.allCounts.getValue(WakeReplayPhrase.HEY_DREAM_LOG))
        assertEquals(21, validated.scoredCounts.getValue(WakeReplayPhrase.DREAM_LOG))
        assertEquals(17, validated.scoredCounts.getValue(WakeReplayPhrase.HEY_DREAM_LOG))
    }

    @Test
    fun `continuous scoring separates exact wrong duplicate miss and negative detections`() {
        val validated = WakeSessionReplayScoring.validateSession(
            session(
                invocation(
                    id = "control",
                    phrase = WakeReplayPhrase.DREAM_LOG,
                    role = WakeReplayInvocationRole.CONTROL,
                    scoreStart = 0L,
                ),
                invocation("first", WakeReplayPhrase.DREAM_LOG, scoreStart = 120L),
                invocation("second", WakeReplayPhrase.HEY_DREAM_LOG, scoreStart = 240L),
                invocation("third", WakeReplayPhrase.DREAM_LOG, scoreStart = 360L),
                sampleCount = 500L,
            ),
        )
        val result = WakeSessionReplayScoring.scoreContinuous(
            session = validated,
            detections = listOf(
                WakeReplayDetection(100L, WakeReplayPhrase.DREAM_LOG),
                WakeReplayDetection(150L, WakeReplayPhrase.DREAM_LOG),
                WakeReplayDetection(160L, WakeReplayPhrase.HEY_DREAM_LOG),
                WakeReplayDetection(225L, WakeReplayPhrase.DREAM_LOG),
                WakeReplayDetection(250L, WakeReplayPhrase.DREAM_LOG),
            ),
        )

        assertEquals(3, result.invocationCount)
        assertEquals(2, result.anyLabelHits)
        assertEquals(1, result.exactLabelHits)
        assertEquals(1, result.wrongLabelHits)
        assertEquals(1, result.misses)
        assertEquals(1, result.duplicateDetectionCount)
        assertEquals(listOf(100L, 225L), result.falsePositiveDetections.map { it.sampleExclusive })
        assertEquals(160L, result.negativeSampleCount)
        assertTrue(result.invocationScores[0].exactLabelHit)
        assertTrue(result.invocationScores[1].wrongLabelHit)
        assertFalse(result.invocationScores[2].matchedDetection != null)
    }

    @Test
    fun `validation rejects invalid overlapping and pre-floor score windows`() {
        val base = session(
            invocation(
                id = "control",
                phrase = WakeReplayPhrase.DREAM_LOG,
                role = WakeReplayInvocationRole.CONTROL,
                scoreStart = 0L,
            ),
            invocation("first", WakeReplayPhrase.DREAM_LOG, scoreStart = 120L),
            sampleCount = 500L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            WakeSessionReplayScoring.validateSession(
                base.copy(
                    invocations = base.invocations +
                        invocation("overlap", WakeReplayPhrase.HEY_DREAM_LOG, scoreStart = 190L),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WakeSessionReplayScoring.validateSession(
                base.copy(
                    invocations = listOf(
                        base.invocations.first(),
                        invocation("pre-floor", WakeReplayPhrase.DREAM_LOG, scoreStart = 90L),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WakeSessionReplayScoring.validateSession(
                base.copy(
                    invocations = listOf(
                        base.invocations.first(),
                        invocation("outside", WakeReplayPhrase.DREAM_LOG, scoreStart = 460L),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WakeSessionReplayScoring.validateSession(
                base.copy(
                    invocations = listOf(
                        base.invocations.first(),
                        WakeReplayInvocation(
                            id = "reversed",
                            phrase = WakeReplayPhrase.DREAM_LOG,
                            role = WakeReplayInvocationRole.SCORED,
                            spokenStartSample = 170L,
                            spokenEndSampleExclusive = 160L,
                            scoreStartSample = 140L,
                            scoreEndSampleExclusive = 200L,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `continuous detections must remain absolute and monotonic across resets`() {
        val validated = WakeSessionReplayScoring.validateSession(
            session(
                invocation(
                    id = "control",
                    phrase = WakeReplayPhrase.DREAM_LOG,
                    role = WakeReplayInvocationRole.CONTROL,
                    scoreStart = 0L,
                ),
                invocation("first", WakeReplayPhrase.DREAM_LOG, scoreStart = 120L),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            WakeSessionReplayScoring.scoreContinuous(
                validated,
                listOf(
                    WakeReplayDetection(180L, WakeReplayPhrase.DREAM_LOG),
                    WakeReplayDetection(150L, WakeReplayPhrase.DREAM_LOG),
                ),
            )
        }
    }

    @Test
    fun `gain measurement and replay scaling account for saturation`() {
        val samples = shortArrayOf(Short.MIN_VALUE, -24_000, 0, 24_000, Short.MAX_VALUE)

        val production = WakeSessionReplayScoring.measureGain(samples, 0, samples.size, 1f)
        val candidate = WakeSessionReplayScoring.measureGain(samples, 0, samples.size, 2f)
        val scaled = WakeSessionReplayScoring.scaledPcm16Frame(samples, 0, samples.size, 2f)

        assertEquals(0L, production.clippedSampleCount)
        assertEquals(4L, candidate.clippedSampleCount)
        assertEquals(0.8, candidate.clippedFraction, 0.0)
        assertEquals(-1f, scaled.first(), 0f)
        assertEquals(1f, scaled.last(), 0f)
    }

    private fun session(
        vararg invocations: WakeReplayInvocation,
        sampleCount: Long = 4_000L,
    ): WakeReplaySessionSpec = WakeReplaySessionSpec(
        alias = "session-08",
        sampleCount = sampleCount,
        triggerPhrase = WakeReplayPhrase.DREAM_LOG,
        preRollSampleCount = 80L,
        cueStartSample = 80L,
        cueEndSampleExclusive = 100L,
        invocations = invocations.toList(),
    )

    private fun invocation(
        id: String,
        phrase: WakeReplayPhrase,
        role: WakeReplayInvocationRole = WakeReplayInvocationRole.SCORED,
        scoreStart: Long,
    ): WakeReplayInvocation = WakeReplayInvocation(
        id = id,
        phrase = phrase,
        role = role,
        spokenStartSample = scoreStart + 10L,
        spokenEndSampleExclusive = scoreStart + 50L,
        scoreStartSample = scoreStart,
        scoreEndSampleExclusive = scoreStart + 80L,
    )
}
