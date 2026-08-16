package com.wivy.dreamlog.capture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class NightDateMapperTest {
    @Test
    fun `midnight through the final instant before 06 maps to prior date`() {
        val date = LocalDate.of(2026, 7, 30)

        assertEquals(
            date.minusDays(1),
            NightDateMapper.displayDate(date.atStartOfDay()),
        )
        assertEquals(
            date.minusDays(1),
            NightDateMapper.displayDate(
                LocalDateTime.of(2026, 7, 30, 5, 59, 59, 999_999_999),
            ),
        )
    }

    @Test
    fun `exactly 06 and later map to start date`() {
        val date = LocalDate.of(2026, 7, 30)

        assertEquals(
            date,
            NightDateMapper.displayDate(LocalDateTime.of(2026, 7, 30, 6, 0)),
        )
        assertEquals(
            date,
            NightDateMapper.displayDate(LocalDateTime.of(2026, 7, 30, 23, 59, 59)),
        )
    }

    @Test
    fun `zoned start uses its local wall clock date`() {
        val startedAt =
            ZonedDateTime.of(
                2026,
                7,
                30,
                2,
                30,
                0,
                0,
                ZoneId.of("America/Chicago"),
            )

        assertEquals(
            LocalDate.of(2026, 7, 29),
            NightDateMapper.displayDate(startedAt),
        )
    }
}
