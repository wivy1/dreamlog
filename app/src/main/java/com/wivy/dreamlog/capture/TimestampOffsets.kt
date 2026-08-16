package com.wivy.dreamlog.capture

import java.time.Instant
import java.time.ZoneId

internal const val MAX_UTC_OFFSET_SECONDS = 18 * 60 * 60

internal fun systemUtcOffsetSeconds(epochMillis: Long): Int =
    ZoneId.systemDefault()
        .rules
        .getOffset(Instant.ofEpochMilli(epochMillis))
        .totalSeconds

internal fun requireUtcOffsetSeconds(offsetSeconds: Int) {
    require(offsetSeconds in -MAX_UTC_OFFSET_SECONDS..MAX_UTC_OFFSET_SECONDS) {
        "UTC offset must be between -18:00 and +18:00."
    }
}
