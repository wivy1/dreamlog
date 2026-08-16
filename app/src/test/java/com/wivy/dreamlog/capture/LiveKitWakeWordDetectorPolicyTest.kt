package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitWakeWordDetectorPolicyTest {
    @Test
    fun scoresFirstFullWindowAndThenEvery1280Samples() {
        val scorer = RecordingScorer()
        val stream = stream(scorer)

        assertEquals(0, stream.accept(ShortArray(31_999)).size)
        assertEquals(listOf(32_000L), stream.accept(ShortArray(1)).map { it.sampleExclusive })
        assertEquals(0, stream.accept(ShortArray(1_279)).size)
        assertEquals(listOf(33_280L), stream.accept(ShortArray(1)).map { it.sampleExclusive })
        assertEquals(2L, stream.evaluationCount)
        assertEquals(2, scorer.windows.size)
    }

    @Test
    fun transientThresholdCrossingIsRejectedAfterOneConfirmationHop() {
        val stream = stream(
            RecordingScorer(
                ArrayDeque(listOf(scores(dreamLog = 0.8f), scores(dreamLog = 0.1f))),
            ),
        )

        val evaluations = stream.accept(activePcm(32_000 + 1_280))

        assertTrue(evaluations.none { it.detectedPhrase != null })
        val telemetry = evaluations.single { it.candidateTelemetry != null }.candidateTelemetry!!
        assertFalse(telemetry.accepted)
        assertEquals(LiveKitWakeCandidateDecisionReason.TRANSIENT_CANDIDATE, telemetry.reason)
        assertEquals(1, telemetry.maxAdjacentQualifyingHopCount)
        assertEquals(LiveKitWakeCandidateGuardOutcome.PASSED, telemetry.guardOutcome)
    }

    @Test
    fun fullHitPlusAdjacentSupportAcceptsAfter80MillisAndThenRequiresFreshWindow() {
        val stream = stream(
            RecordingScorer(
                ArrayDeque(
                    listOf(scores(dreamLog = 0.8f), scores(dreamLog = 0.4f), scores()),
                ),
            ),
        )

        val accepted = stream.accept(activePcm(32_000 + 1_280))
        assertEquals(ApprovedWakePhrase.DREAM_LOG, accepted.last().detectedPhrase)
        assertEquals(2, accepted.last().candidateTelemetry?.observedHopCount)
        assertEquals(0, stream.accept(activePcm(31_999)).size)
        val afterFreshWindow = stream.accept(activePcm(1)).single()
        assertEquals(65_280L, afterFreshWindow.sampleExclusive)
        assertNull(afterFreshWindow.detectedPhrase)
    }

    @Test
    fun persistentHeyDreamLogAcceptsWithMinimumDreamLogHeadAgreement() {
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

        val accepted = stream.accept(activePcm(32_000 + 1_280)).last()

        assertEquals(ApprovedWakePhrase.HEY_DREAM_LOG, accepted.detectedPhrase)
        assertEquals(0.1f, accepted.candidateTelemetry?.maxDreamLogThresholdRatio)
    }

    @Test
    fun heyLikeEpisodeBelowDreamLogAgreementFloorIsRejected() {
        val stream = stream(
            RecordingScorer(
                ArrayDeque(
                    List(6) { scores(dreamLog = 0.049f, heyDreamLog = 0.8f) },
                ),
            ),
        )

        val evaluations = stream.accept(activePcm(32_000 + 5 * 1_280))

        assertTrue(evaluations.none { it.detectedPhrase != null })
        assertEquals(
            LiveKitWakeCandidateDecisionReason.PHRASE_AGREEMENT_REJECTED,
            evaluations.last().candidateTelemetry?.reason,
        )
    }

    @Test
    fun liveHeyGoogleLikePersistentEpisodeIsRejectedByHeyConfidenceFloor() {
        val falseScores = scores(dreamLog = 0.12438783f, heyDreamLog = 0.21953752f)
        val stream = LiveKitWakeWordStream(
            scorer = RecordingScorer(
                ArrayDeque(listOf(falseScores, falseScores, scores())),
            ),
            thresholds = LiveKitWakeWordThresholds(dreamLog = 0.24f, heyDreamLog = 0.15f),
        )

        val evaluations = stream.accept(activePcm(32_000 + 2 * 1_280))

        assertTrue(evaluations.none { it.detectedPhrase != null })
        assertEquals(
            LiveKitWakeCandidateDecisionReason.HEY_DREAM_LOG_CONFIDENCE_REJECTED,
            evaluations.last().candidateTelemetry?.reason,
        )
    }

    @Test
    fun weakestRetainedHeyDreamLogReplayScoreStillAcceptsPersistently() {
        val trueScores = scores(dreamLog = 0.12438783f, heyDreamLog = 0.26417512f)
        val stream = LiveKitWakeWordStream(
            scorer = RecordingScorer(ArrayDeque(listOf(trueScores, trueScores))),
            thresholds = LiveKitWakeWordThresholds(dreamLog = 0.24f, heyDreamLog = 0.15f),
        )

        val accepted = stream.accept(activePcm(32_000 + 1_280)).last()

        assertEquals(ApprovedWakePhrase.HEY_DREAM_LOG, accepted.detectedPhrase)
        assertEquals(
            LiveKitWakeCandidateDecisionReason.PERSISTENT_CANDIDATE,
            accepted.candidateTelemetry?.reason,
        )
    }

    @Test
    fun veryStrongDreamLogHitAcceptsWithoutAddedLatency() {
        val evaluation = LiveKitWakeWordStream(
            scorer = RecordingScorer(ArrayDeque(listOf(scores(dreamLog = 0.8f)))),
            thresholds = LiveKitWakeWordThresholds(dreamLog = 0.3f, heyDreamLog = 0.5f),
        ).accept(activePcm(32_000)).single()

        assertEquals(ApprovedWakePhrase.DREAM_LOG, evaluation.detectedPhrase)
        assertEquals(
            LiveKitWakeCandidateDecisionReason.STRONG_CANDIDATE,
            evaluation.candidateTelemetry?.reason,
        )
        assertEquals(32_000L, evaluation.sampleExclusive)
    }

    @Test
    fun peakNormalizedHeadSelectsDreamLogWhenLastRawHitIsHeyDreamLog() {
        val stream = stream(
            RecordingScorer(
                ArrayDeque(
                    listOf(
                        scores(dreamLog = 0.9f, heyDreamLog = 0.1f),
                        scores(dreamLog = 0.4f, heyDreamLog = 0.8f),
                    ),
                ),
            ),
        )

        assertEquals(
            ApprovedWakePhrase.DREAM_LOG,
            stream.accept(activePcm(32_000 + 1_280)).last().detectedPhrase,
        )
    }

    @Test
    fun simultaneousHeadHitsUseLongerPhrasePrecedence() {
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

        val accepted = stream.accept(activePcm(32_000 + 1_280)).last()

        assertEquals(ApprovedWakePhrase.HEY_DREAM_LOG, accepted.detectedPhrase)
        assertEquals(1.8f, accepted.candidateTelemetry?.maxDreamLogThresholdRatio)
        assertEquals(1.8f, accepted.candidateTelemetry?.maxHeyDreamLogThresholdRatio)
    }

    @Test
    fun quietThresholdCrossingsFailCandidateAcousticGuard() {
        val scorer = RecordingScorer(ArrayDeque(List(6) { scores(dreamLog = 0.8f) }))
        val evaluations = stream(scorer).accept(ShortArray(32_000 + 5 * 1_280))

        assertTrue(evaluations.none { it.detectedPhrase != null })
        val telemetry = evaluations.last().candidateTelemetry!!
        assertEquals(LiveKitWakeCandidateGuardOutcome.FAILED, telemetry.guardOutcome)
        assertEquals(LiveKitWakeCandidateDecisionReason.ACOUSTIC_GUARD_REJECTED, telemetry.reason)
        assertEquals(6, telemetry.observedHopCount)
    }

    @Test
    fun nearThresholdEpisodeProducesOneBoundedTelemetryRecord() {
        val scorer = RecordingScorer(ArrayDeque(List(6) { scores(dreamLog = 0.4f) }))
        val evaluations = stream(scorer).accept(activePcm(32_000 + 5 * 1_280))

        assertEquals(1, evaluations.count { it.candidateTelemetry != null })
        val telemetry = evaluations.last().candidateTelemetry!!
        assertEquals(6, telemetry.observedHopCount)
        assertEquals(0, telemetry.maxAdjacentQualifyingHopCount)
        assertEquals(LiveKitWakeCandidateGuardOutcome.NOT_EVALUATED, telemetry.guardOutcome)
        assertEquals(LiveKitWakeCandidateDecisionReason.EPISODE_LIMIT_REACHED, telemetry.reason)
    }

    @Test
    fun isolatedActiveHopDoesNotPassAcousticGuard() {
        val window = ShortArray(LiveKitWakeWordPolicy.WINDOW_SAMPLES)
        window.fill(1_000, window.size - LiveKitWakeWordPolicy.HOP_SAMPLES, window.size)

        assertFalse(LiveKitWakeWordPolicy.passesCandidateAcousticGuard(window))
        window.fill(
            1_000,
            window.size - 2 * LiveKitWakeWordPolicy.HOP_SAMPLES,
            window.size - LiveKitWakeWordPolicy.HOP_SAMPLES,
        )
        assertTrue(LiveKitWakeWordPolicy.passesCandidateAcousticGuard(window))
    }

    @Test
    fun resetDropsPendingCandidateAndRestartsWithFreshWindow() {
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
        assertEquals(0, stream.accept(activePcm(31_999)).size)
        assertNull(stream.accept(activePcm(1)).single().detectedPhrase)
        val accepted = stream.accept(activePcm(1_280)).single()

        assertEquals(ApprovedWakePhrase.DREAM_LOG, accepted.detectedPhrase)
        assertEquals(2L, accepted.evaluationIndex)
        assertEquals(33_280L, accepted.sampleExclusive)
    }

    @Test
    fun rawPolicyStillTreatsThresholdEqualityAsStandalonePhraseHit() {
        val phrase = LiveKitWakeWordPolicy.detectedPhrase(
            scores = LiveKitWakeWordScores(dreamLog = 0.25f, heyDreamLog = 0.19f),
            thresholds = LiveKitWakeWordThresholds(dreamLog = 0.25f, heyDreamLog = 0.2f),
        )

        assertEquals(ApprovedWakePhrase.DREAM_LOG, phrase)
    }

    @Test
    fun slidingRingIsPresentedInChronologicalOrder() {
        val scorer = RecordingScorer()
        val stream = stream(scorer)
        val initial = ShortArray(32_000) { index -> index.toShort() }
        val hop = ShortArray(1_280) { index -> (10_000 + index).toShort() }

        stream.accept(initial)
        stream.accept(hop)

        val second = scorer.windows[1]
        assertEquals(initial[1_280], second.first())
        assertEquals(initial.last(), second[30_719])
        assertEquals(hop.first(), second[30_720])
        assertEquals(hop.last(), second.last())
    }

    private fun stream(scorer: RecordingScorer) = LiveKitWakeWordStream(
        scorer = scorer,
        thresholds = LiveKitWakeWordThresholds(dreamLog = 0.5f, heyDreamLog = 0.5f),
    )

    private fun scores(
        dreamLog: Float = 0f,
        heyDreamLog: Float = 0f,
    ) = LiveKitWakeWordScores(dreamLog = dreamLog, heyDreamLog = heyDreamLog)

    private fun activePcm(size: Int) = ShortArray(size) { 1_000 }

    private class RecordingScorer(
        private val scores: ArrayDeque<LiveKitWakeWordScores> = ArrayDeque(),
    ) : LiveKitWakeWordWindowScorer {
        val windows = mutableListOf<ShortArray>()

        override fun score(window: ShortArray): LiveKitWakeWordScoreMeasurement {
            windows += window.copyOf()
            return LiveKitWakeWordScoreMeasurement(
                scores = scores.removeFirstOrNull()
                    ?: LiveKitWakeWordScores(dreamLog = 0f, heyDreamLog = 0f),
                latencyNanos = 1L,
            )
        }
    }
}
