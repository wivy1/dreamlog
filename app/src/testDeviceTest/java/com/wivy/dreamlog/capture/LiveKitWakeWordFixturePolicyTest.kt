package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitWakeWordFixturePolicyTest {
    @Test
    fun transientHitIsRejectedAndPersistentHitAcceptsAfter80Millis() {
        val stream = stream(
            RecordingScorer(
                ArrayDeque(
                    listOf(
                        scores(dreamLog = 0.8f),
                        scores(dreamLog = 0.1f),
                        scores(dreamLog = 0.8f),
                        scores(dreamLog = 0.4f),
                    ),
                ),
            ),
        )

        val transient = stream.accept(activePcm(32_000 + 1_280))
        assertTrue(transient.none { it.detectedPhrase != null })
        assertEquals("transient_candidate", transient.last().candidateTelemetry?.reason)
        val accepted = stream.accept(activePcm(2 * 1_280)).last()

        assertEquals(WakeReplayPhrase.DREAM_LOG, accepted.detectedPhrase)
        assertEquals("persistent_candidate", accepted.candidateTelemetry?.reason)
        assertEquals(32_000L + 2 * 1_280, accepted.triggerSampleExclusive)
    }

    @Test
    fun heyPhraseKeepsMinimumDreamLogHeadAgreement() {
        val stream = stream(
            RecordingScorer(
                ArrayDeque(
                    listOf(
                        scores(dreamLog = 0.05f, heyDreamLog = 0.85f),
                        scores(dreamLog = 0.05f, heyDreamLog = 0.8f),
                    ),
                ),
            ),
        )

        assertEquals(
            WakeReplayPhrase.HEY_DREAM_LOG,
            stream.accept(activePcm(32_000 + 1_280)).last().detectedPhrase,
        )
    }

    @Test
    fun heyLikeEpisodeBelowDreamLogAgreementFloorIsRejected() {
        val evaluations = stream(
            RecordingScorer(
                ArrayDeque(List(6) { scores(dreamLog = 0.049f, heyDreamLog = 0.8f) }),
            ),
        ).accept(activePcm(32_000 + 5 * 1_280))

        assertTrue(evaluations.none { it.detectedPhrase != null })
        assertEquals("phrase_agreement_rejected", evaluations.last().candidateTelemetry?.reason)
    }

    @Test
    fun liveHeyGoogleLikePersistentEpisodeIsRejectedByHeyConfidenceFloor() {
        val falseScores = scores(dreamLog = 0.12438783f, heyDreamLog = 0.21953752f)
        val stream = LiveKitWakeWordFixtureStream(
            scorer = RecordingScorer(
                ArrayDeque(listOf(falseScores, falseScores, scores())),
            ),
            thresholds = LiveKitFixtureThresholds(dreamLog = 0.24f, heyDreamLog = 0.15f),
        )

        val evaluations = stream.accept(activePcm(32_000 + 2 * 1_280))

        assertTrue(evaluations.none { it.detectedPhrase != null })
        assertEquals(
            "hey_dream_log_confidence_rejected",
            evaluations.last().candidateTelemetry?.reason,
        )
    }

    @Test
    fun weakestRetainedHeyDreamLogReplayScoreStillAcceptsPersistently() {
        val trueScores = scores(dreamLog = 0.12438783f, heyDreamLog = 0.26417512f)
        val stream = LiveKitWakeWordFixtureStream(
            scorer = RecordingScorer(ArrayDeque(listOf(trueScores, trueScores))),
            thresholds = LiveKitFixtureThresholds(dreamLog = 0.24f, heyDreamLog = 0.15f),
        )

        val accepted = stream.accept(activePcm(32_000 + 1_280)).last()

        assertEquals(WakeReplayPhrase.HEY_DREAM_LOG, accepted.detectedPhrase)
        assertEquals("persistent_candidate", accepted.candidateTelemetry?.reason)
    }

    @Test
    fun veryStrongDreamLogHitAcceptsAtFirstFullWindow() {
        val evaluation = LiveKitWakeWordFixtureStream(
            scorer = RecordingScorer(ArrayDeque(listOf(scores(dreamLog = 0.8f)))),
            thresholds = LiveKitFixtureThresholds(dreamLog = 0.3f, heyDreamLog = 0.5f),
        ).accept(activePcm(32_000)).single()

        assertEquals(WakeReplayPhrase.DREAM_LOG, evaluation.detectedPhrase)
        assertEquals("strong_candidate", evaluation.candidateTelemetry?.reason)
        assertEquals(32_000L, evaluation.triggerSampleExclusive)
    }

    @Test
    fun peakNormalizedHeadSelectsDreamLogWhenLastRawHitIsHeyDreamLog() {
        val accepted = stream(
            RecordingScorer(
                ArrayDeque(
                    listOf(
                        scores(dreamLog = 0.9f, heyDreamLog = 0.1f),
                        scores(dreamLog = 0.4f, heyDreamLog = 0.8f),
                    ),
                ),
            ),
        ).accept(activePcm(32_000 + 1_280)).last()

        assertEquals(WakeReplayPhrase.DREAM_LOG, accepted.detectedPhrase)
        assertEquals(32_000L, accepted.triggerSampleExclusive)
        assertEquals(33_280L, accepted.sampleExclusive)
    }

    @Test
    fun simultaneousHeadsUseLongerPhrasePrecedence() {
        val stream = stream(
            RecordingScorer(
                ArrayDeque(
                    listOf(
                        scores(dreamLog = 0.9f, heyDreamLog = 0.8f),
                        scores(dreamLog = 0.8f, heyDreamLog = 0.9f),
                    ),
                ),
            ),
        )

        assertEquals(
            WakeReplayPhrase.HEY_DREAM_LOG,
            stream.accept(activePcm(32_000 + 1_280)).last().detectedPhrase,
        )
    }

    @Test
    fun quietHitsFailGuardAndNearThresholdTelemetryIsBounded() {
        val quiet = stream(
            RecordingScorer(ArrayDeque(List(6) { scores(dreamLog = 0.8f) })),
        ).accept(ShortArray(32_000 + 5 * 1_280))
        assertTrue(quiet.none { it.detectedPhrase != null })
        assertEquals("acoustic_guard_rejected", quiet.last().candidateTelemetry?.reason)

        val near = stream(
            RecordingScorer(ArrayDeque(List(6) { scores(dreamLog = 0.4f) })),
        ).accept(activePcm(32_000 + 5 * 1_280))
        assertEquals(1, near.count { it.candidateTelemetry != null })
        assertEquals(6, near.last().candidateTelemetry?.observedHopCount)
        assertEquals("episode_limit_reached", near.last().candidateTelemetry?.reason)
    }

    @Test
    fun resetDropsPendingCandidateAndRequiresFreshWindow() {
        val stream = stream(
            RecordingScorer(
                ArrayDeque(
                    listOf(
                        scores(dreamLog = 0.8f),
                        scores(dreamLog = 0.8f),
                        scores(dreamLog = 0.8f),
                    ),
                ),
            ),
        )
        assertNull(stream.accept(activePcm(32_000)).single().detectedPhrase)
        stream.reset()

        assertTrue(stream.accept(activePcm(31_999)).isEmpty())
        assertNull(stream.accept(activePcm(1)).single().detectedPhrase)
        assertEquals(
            WakeReplayPhrase.DREAM_LOG,
            stream.accept(activePcm(1_280)).single().detectedPhrase,
        )
    }

    @Test
    fun isolatedActiveHopFailsAcousticGuard() {
        val window = ShortArray(LiveKitWakeWordFixturePolicy.WINDOW_SAMPLES)
        window.fill(1_000, window.size - 1_280, window.size)
        assertFalse(LiveKitWakeWordFixturePolicy.passesCandidateAcousticGuard(window))
        window.fill(1_000, window.size - 2 * 1_280, window.size - 1_280)
        assertTrue(LiveKitWakeWordFixturePolicy.passesCandidateAcousticGuard(window))
    }

    @Test
    fun scoringCadenceAndChronologicalRingStayUnchanged() {
        val scorer = RecordingScorer()
        val stream = stream(scorer)
        val initial = ShortArray(32_000) { it.toShort() }
        val hop = ShortArray(1_280) { (10_000 + it).toShort() }

        assertTrue(stream.accept(initial.copyOf(31_999)).isEmpty())
        stream.accept(shortArrayOf(initial.last()))
        stream.accept(hop)

        assertEquals(listOf(32_000, 32_000), scorer.windows.map { it.size })
        assertEquals(hop.first(), scorer.windows.last()[30_720])
        assertEquals(hop.last(), scorer.windows.last().last())
    }

    private fun stream(scorer: RecordingScorer) = LiveKitWakeWordFixtureStream(
        scorer = scorer,
        thresholds = LiveKitFixtureThresholds(dreamLog = 0.5f, heyDreamLog = 0.5f),
    )

    private fun scores(
        dreamLog: Float = 0f,
        heyDreamLog: Float = 0f,
    ) = LiveKitFixtureScores(dreamLog, heyDreamLog)

    private fun activePcm(size: Int) = ShortArray(size) { 1_000 }

    private class RecordingScorer(
        private val scores: ArrayDeque<LiveKitFixtureScores> = ArrayDeque(),
    ) : LiveKitFixtureWindowScorer {
        val windows = mutableListOf<ShortArray>()

        override fun score(window: ShortArray): LiveKitFixtureScoreMeasurement {
            windows += window.copyOf()
            return LiveKitFixtureScoreMeasurement(
                scores.removeFirstOrNull() ?: LiveKitFixtureScores(0f, 0f),
                latencyNanos = 1L,
            )
        }
    }
}
