package com.wivy.dreamlog.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPeriodTest {
    @Test
    fun thirtyDaysIsTheDefaultAndOnlyBoundedPeriodsAreExposed() {
        assertEquals(RetentionPeriod.THIRTY_DAYS, RetentionPeriod.DEFAULT)
        assertEquals(
            listOf(1, 7, 30),
            RetentionPeriod.SUPPORTED.map(RetentionPeriod::days),
        )
    }

    @Test
    fun supportedStoredValuesResolveWithoutRepair() {
        RetentionPeriod.SUPPORTED.forEach { period ->
            val resolved = resolveStoredRetentionPeriod(period.days)

            assertEquals(period, resolved.period)
            assertFalse(resolved.repairStoredValue)
            assertTrue(isSupportedRetentionDays(period.days))
            assertEquals(period.displayLabel, retentionPeriodLabel(period.days))
        }
    }

    @Test
    fun missingAndInvalidStoredValuesFallBackToThirtyDaysAndRequestRepair() {
        listOf<Int?>(null, Int.MIN_VALUE, -1, 0, 2, 6, 8, 31, Int.MAX_VALUE)
            .forEach { storedDays ->
                val resolved = resolveStoredRetentionPeriod(storedDays)

                assertEquals(RetentionPeriod.THIRTY_DAYS, resolved.period)
                assertTrue(resolved.repairStoredValue)
            }
    }

    @Test
    fun labelsUseCorrectSingularAndPluralForms() {
        assertEquals("1 day", retentionPeriodLabel(1))
        assertEquals("7 days", retentionPeriodLabel(7))
        assertEquals("30 days", retentionPeriodLabel(30))
    }

    @Test
    fun unsupportedRequestedPeriodsAreRejected() {
        listOf(Int.MIN_VALUE, -1, 0, 2, 6, 8, 31, Int.MAX_VALUE).forEach { days ->
            assertFalse(isSupportedRetentionDays(days))
            assertThrows(IllegalArgumentException::class.java) {
                requireRetentionPeriod(days)
            }
            assertThrows(IllegalArgumentException::class.java) {
                retentionPeriodLabel(days)
            }
        }
    }
}
