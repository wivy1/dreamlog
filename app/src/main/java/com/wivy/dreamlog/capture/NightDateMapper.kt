package com.wivy.dreamlog.capture

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

object NightDateMapper {
    val DATE_ROLLOVER_TIME: LocalTime = LocalTime.of(6, 0)

    fun displayDate(startedAt: ZonedDateTime): LocalDate =
        displayDate(startedAt.toLocalDateTime())

    fun displayDate(startedAt: LocalDateTime): LocalDate =
        if (startedAt.toLocalTime().isBefore(DATE_ROLLOVER_TIME)) {
            startedAt.toLocalDate().minusDays(1)
        } else {
            startedAt.toLocalDate()
        }
}
