package com.wivy.dreamlog.history

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFormattersTest {
    @Test
    fun dateAndTimeUseExactStablePatterns() {
        val epochMillis = Instant.parse("2026-07-30T05:06:07Z").toEpochMilli()

        assertEquals("2026-07-30", HistoryFormatters.date("2026-07-30"))
        assertEquals("00:06:07", HistoryFormatters.time(epochMillis, -5 * 60 * 60))
        assertEquals(
            "2026-07-30 00:06:07",
            HistoryFormatters.dateTime(epochMillis, -5 * 60 * 60),
        )
    }

    @Test
    fun storedOffsetsDistinguishRepeatedFallBackClockTime() {
        val daylightOccurrence = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()
        val standardOccurrence = Instant.parse("2026-11-01T07:30:00Z").toEpochMilli()

        assertEquals(
            "01:30:00",
            HistoryFormatters.time(daylightOccurrence, -5 * 60 * 60),
        )
        assertEquals(
            "01:30:00",
            HistoryFormatters.time(standardOccurrence, -6 * 60 * 60),
        )
        assertEquals("UTC-05:00", HistoryFormatters.utcOffset(-5 * 60 * 60))
        assertEquals("UTC-06:00", HistoryFormatters.utcOffset(-6 * 60 * 60))
        assertEquals("UTC+00:00", HistoryFormatters.utcOffset(0))
    }

    @Test
    fun durationUsesCapturedSampleCountAndStableDisplayUnits() {
        assertEquals(6_000L, HistoryFormatters.durationMillis(96_000L, 16_000))
        assertEquals("6 sec", HistoryFormatters.duration(6_000L))
        assertEquals("2 min 05 sec", HistoryFormatters.duration(125_999L))
    }
}
