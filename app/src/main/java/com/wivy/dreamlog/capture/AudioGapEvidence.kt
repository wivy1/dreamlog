package com.wivy.dreamlog.capture

/** Durable evidence marker for timestamp gaps that passed persistence confirmation. */
internal object AudioGapEvidence {
    const val ATTRIBUTE_KEY = "evidence"
    const val CONFIRMED_PERSISTENT_TIMESTAMP_DEFICIT =
        "confirmed_persistent_timestamp_deficit"
}
