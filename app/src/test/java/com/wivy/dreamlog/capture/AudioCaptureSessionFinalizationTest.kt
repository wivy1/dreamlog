package com.wivy.dreamlog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioCaptureSessionFinalizationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `audio gap preserves the session until normal narrative completion`() {
        val plan = resolveSessionFinalization(
            audioGapObserved = true,
            requestedIncompleteReason = null,
            automaticSilenceTailSampleCount = 160_256L,
        )

        assertEquals(SessionIncompleteReason.AUDIO_GAP, plan.incompleteReason)
        assertNull(plan.automaticSilenceTailSampleCount)
    }

    @Test
    fun `normal session retains its measured silence tail`() {
        val plan = resolveSessionFinalization(
            audioGapObserved = false,
            requestedIncompleteReason = null,
            automaticSilenceTailSampleCount = 160_256L,
        )

        assertNull(plan.incompleteReason)
        assertEquals(160_256L, plan.automaticSilenceTailSampleCount)
    }

    @Test
    fun `explicit stop reason remains authoritative after an audio gap`() {
        val plan = resolveSessionFinalization(
            audioGapObserved = true,
            requestedIncompleteReason = SessionIncompleteReason.NIGHT_ENDED,
            automaticSilenceTailSampleCount = null,
        )

        assertEquals(SessionIncompleteReason.NIGHT_ENDED, plan.incompleteReason)
        assertNull(plan.automaticSilenceTailSampleCount)
    }

    @Test
    fun `one incomplete wav retains samples appended before and after a gap`() {
        val audioDirectory = temporaryFolder.newFolder("audio")
        val writer = SessionAudioWriter(
            audioDirectory = audioDirectory,
            clock = { 20L },
            opaqueId = { "0123456789abcdef0123456789abcdef" },
        )
        val session = writer.startSession(
            preRoll = shortArrayOf(1, 2),
            startedAtEpochMillis = 10L,
        )
        session.append(shortArrayOf(3, 4))

        val gapPlan = resolveSessionFinalization(
            audioGapObserved = true,
            requestedIncompleteReason = null,
            automaticSilenceTailSampleCount = 160_256L,
        )
        session.append(shortArrayOf(5, 6))
        val metadata = session.finalizeIncomplete(
            checkNotNull(gapPlan.incompleteReason),
        )

        assertEquals(6L, metadata.sampleCount)
        assertEquals(SessionIncompleteReason.AUDIO_GAP, metadata.incompleteReason)
        assertTrue(
            audioDirectory.resolve(metadata.audioFileName).length() ==
                SessionAudioWriter.WAV_HEADER_BYTES + 6L * 2L,
        )
    }
}
