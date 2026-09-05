package com.wivy.dreamlog.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class EnrichmentRuntimeStoreTest {
    @Test
    fun initializationThreadFailureLeavesTheStartupLoadingState() {
        val preparing = EnrichmentRuntimeSnapshot(historyRevision = 4L)

        val failed = preparing.withThreadStartFailure(initializing = true)

        assertEquals(EnrichmentModelPhase.ERROR, failed.modelPhase)
        assertEquals(EnrichmentRuntimePhase.ERROR, failed.runtimePhase)
        assertFalse(failed.initialized)
        assertFalse(failed.busy)
        assertNotNull(failed.runtimeError)
        assertEquals(4L, failed.historyRevision)
    }

    @Test
    fun otherThreadFailuresPreserveTheirModelAndRecoveryState() {
        val working = EnrichmentRuntimeSnapshot(
            initialized = true,
            modelPhase = EnrichmentModelPhase.INSTALLED,
            runtimePhase = EnrichmentRuntimePhase.RUNNING,
            nightId = "synthetic-night",
            interruptionCause = EnrichmentInterruptionCause.USER_CANCELLED,
            historyRevision = 7L,
        )

        val failed = working.withThreadStartFailure(initializing = false)

        assertEquals(
            working.copy(
                runtimePhase = EnrichmentRuntimePhase.ERROR,
                runtimeError = "Enrichment could not start. Try again.",
            ),
            failed,
        )
    }
}
