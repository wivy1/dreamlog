package com.wivy.dreamlog.capture

data class NightStartRequest(
    val nightId: String,
    val displayDate: String,
    val startedAtEpochMillis: Long,
    val startedAtUtcOffsetSeconds: Int,
)
