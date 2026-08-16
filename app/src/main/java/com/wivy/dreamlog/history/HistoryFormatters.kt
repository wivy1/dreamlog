package com.wivy.dreamlog.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object HistoryFormatters {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val dateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun date(displayDate: String?): String {
        if (displayDate == null) return "Unknown"
        return runCatching {
            LocalDate.parse(displayDate).toString()
        }.getOrDefault(displayDate)
    }

    fun time(
        epochMillis: Long?,
        utcOffsetSeconds: Int?,
    ): String {
        if (epochMillis == null || utcOffsetSeconds == null) return "Unknown"
        return runCatching {
            Instant.ofEpochMilli(epochMillis)
                .atOffset(ZoneOffset.ofTotalSeconds(utcOffsetSeconds))
                .format(timeFormatter)
        }.getOrDefault("Unknown")
    }

    fun dateTime(
        epochMillis: Long?,
        utcOffsetSeconds: Int?,
    ): String {
        if (epochMillis == null || utcOffsetSeconds == null) return "Unknown"
        return runCatching {
            Instant.ofEpochMilli(epochMillis)
                .atOffset(ZoneOffset.ofTotalSeconds(utcOffsetSeconds))
                .format(dateTimeFormatter)
        }.getOrDefault("Unknown")
    }

    fun utcOffset(utcOffsetSeconds: Int?): String {
        if (utcOffsetSeconds == null) return "UTC offset unknown"
        return runCatching {
            val offsetId = ZoneOffset.ofTotalSeconds(utcOffsetSeconds).id
                .let { if (it == "Z") "+00:00" else it }
            "UTC$offsetId"
        }.getOrDefault("UTC offset unknown")
    }

    fun durationMillis(
        sampleCount: Long?,
        sampleRateHz: Int?,
    ): Long? {
        if (sampleCount == null || sampleRateHz == null || sampleRateHz <= 0) return null
        return runCatching {
            Math.multiplyExact(sampleCount, 1_000L) / sampleRateHz
        }.getOrNull()
    }

    fun duration(durationMillis: Long?): String {
        if (durationMillis == null || durationMillis < 0L) return "Duration unknown"
        val totalSeconds = durationMillis / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes == 0L) {
            "$seconds sec"
        } else {
            "$minutes min ${seconds.toString().padStart(2, '0')} sec"
        }
    }
}
