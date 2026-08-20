package com.wivy.dreamlog

import com.wivy.dreamlog.enrichment.EnrichmentModelPhase
import com.wivy.dreamlog.enrichment.EnrichmentRuntimePhase
import com.wivy.dreamlog.transcription.TranscriptionModelPhase
import com.wivy.dreamlog.transcription.TranscriptionRuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityReprocessStateTest {
    @Test
    fun `only enrichment keeps the activity screen on`() {
        assertFalse(
            shouldKeepScreenOnForLocalProcessing(
                enrichmentPhase = EnrichmentRuntimePhase.IDLE,
            ),
        )
        assertTrue(
            shouldKeepScreenOnForLocalProcessing(
                enrichmentPhase = EnrichmentRuntimePhase.RUNNING,
            ),
        )
        assertFalse(
            shouldKeepScreenOnForLocalProcessing(
                enrichmentPhase = EnrichmentRuntimePhase.IDLE,
            ),
        )
    }

    @Test
    fun sameProcessConfigurationRecreationKeepsActiveHandoff() {
        val state = reconcileNightReprocessProcessState(
            savedOwnerProcessInstanceId = "process-a",
            currentProcessInstanceId = "process-a",
            phaseName = NightReprocessPhase.TRANSCRIBING.name,
            message = "Re-transcribing",
        )

        assertEquals(NightReprocessPhase.TRANSCRIBING.name, state.phaseName)
        assertEquals("Re-transcribing", state.message)
    }

    @Test
    fun processDeathMakesEitherActivePhaseRetryable() {
        listOf(
            NightReprocessPhase.TRANSCRIBING,
            NightReprocessPhase.ENRICHING,
            NightReprocessPhase.ENRICHING_PRESERVED_TRANSCRIPT,
        ).forEach { phase ->
            val state = reconcileNightReprocessProcessState(
                savedOwnerProcessInstanceId = "process-a",
                currentProcessInstanceId = "process-b",
                phaseName = phase.name,
                message = "Running",
            )

            assertEquals("process-b", state.ownerProcessInstanceId)
            assertEquals(NightReprocessPhase.IDLE.name, state.phaseName)
            assertTrue(state.message.orEmpty().contains("interrupted"))
            assertTrue(state.message.orEmpty().contains("retry"))
        }
    }

    @Test
    fun processDeathDoesNotOverwriteAnIdleTerminalMessage() {
        val state = reconcileNightReprocessProcessState(
            savedOwnerProcessInstanceId = "process-a",
            currentProcessInstanceId = "process-b",
            phaseName = NightReprocessPhase.IDLE.name,
            message = "Reprocessing complete with the latest local models.",
        )

        assertEquals(NightReprocessPhase.IDLE.name, state.phaseName)
        assertEquals("Reprocessing complete with the latest local models.", state.message)
    }

    @Test
    fun automaticTranscriptionWaitsForTheWholeReprocessHandoff() {
        listOf(
            NightReprocessPhase.TRANSCRIBING,
            NightReprocessPhase.ENRICHING,
            NightReprocessPhase.ENRICHING_PRESERVED_TRANSCRIPT,
        ).forEach { phase ->
            assertFalse(
                canStartAutomaticTranscription(
                    captureActive = false,
                    transcriptionBusy = false,
                    enrichmentBusy = false,
                    reprocessPhaseName = phase.name,
                ),
            )
        }
        assertTrue(
            canStartAutomaticTranscription(
                captureActive = false,
                transcriptionBusy = false,
                enrichmentBusy = false,
                reprocessPhaseName = NightReprocessPhase.IDLE.name,
            ),
        )
    }

    @Test
    fun idleInstalledRuntimesDoNotBlockAnotherEligibleNight() {
        assertNull(
            nightReprocessGlobalUnavailableReason(
                captureActive = false,
                archiveMutationRunning = false,
                transcriptionModelPhase = TranscriptionModelPhase.INSTALLED,
                transcriptionRuntimePhase = TranscriptionRuntimePhase.IDLE,
                enrichmentModelPhase = EnrichmentModelPhase.INSTALLED,
                enrichmentRuntimePhase = EnrichmentRuntimePhase.IDLE,
            ),
        )
    }

    @Test
    fun currentCompleteTranscriptSkipsRedundantRetranscription() {
        assertEquals(
            NightReprocessMode.ENRICHMENT_ONLY,
            selectNightReprocessMode(
                hasCompleteEnrichmentSource = true,
                everyTranscriptUsesCurrentPipeline = true,
            ),
        )
        listOf(
            false to true,
            true to false,
            false to false,
        ).forEach { (hasCompleteSource, currentPipeline) ->
            assertEquals(
                NightReprocessMode.RETRANSCRIBE_THEN_ENRICH,
                selectNightReprocessMode(
                    hasCompleteEnrichmentSource = hasCompleteSource,
                    everyTranscriptUsesCurrentPipeline = currentPipeline,
                ),
            )
        }
    }

    @Test
    fun enrichmentOnlyReprocessDoesNotRequireTheSpeechModel() {
        assertNull(
            nightReprocessGlobalUnavailableReason(
                captureActive = false,
                archiveMutationRunning = false,
                transcriptionModelPhase = TranscriptionModelPhase.ERROR,
                transcriptionRuntimePhase = TranscriptionRuntimePhase.IDLE,
                enrichmentModelPhase = EnrichmentModelPhase.INSTALLED,
                enrichmentRuntimePhase = EnrichmentRuntimePhase.IDLE,
                requiresTranscriptionModel = false,
            ),
        )
        assertTrue(
            nightReprocessGlobalUnavailableReason(
                captureActive = false,
                archiveMutationRunning = false,
                transcriptionModelPhase = TranscriptionModelPhase.ERROR,
                transcriptionRuntimePhase = TranscriptionRuntimePhase.IDLE,
                enrichmentModelPhase = EnrichmentModelPhase.INSTALLED,
                enrichmentRuntimePhase = EnrichmentRuntimePhase.IDLE,
                requiresTranscriptionModel = true,
            ).orEmpty().contains("transcription model"),
        )
    }

    @Test
    fun globalReprocessBlockersExplainRunningWorkAndDeferredVerification() {
        val running = nightReprocessGlobalUnavailableReason(
            captureActive = false,
            archiveMutationRunning = false,
            transcriptionModelPhase = TranscriptionModelPhase.INSTALLED,
            transcriptionRuntimePhase = TranscriptionRuntimePhase.RUNNING,
            enrichmentModelPhase = EnrichmentModelPhase.INSTALLED,
            enrichmentRuntimePhase = EnrichmentRuntimePhase.IDLE,
        )
        val checking = nightReprocessGlobalUnavailableReason(
            captureActive = false,
            archiveMutationRunning = false,
            transcriptionModelPhase = TranscriptionModelPhase.INSTALLED,
            transcriptionRuntimePhase = TranscriptionRuntimePhase.IDLE,
            enrichmentModelPhase = EnrichmentModelPhase.VERIFICATION_DEFERRED,
            enrichmentRuntimePhase = EnrichmentRuntimePhase.IDLE,
        )

        assertTrue(running.orEmpty().contains("transcription is still running"))
        assertTrue(checking.orEmpty().contains("still being checked"))
        assertFalse(checking.orEmpty().contains("Install"))
    }
}
