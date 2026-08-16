package com.wivy.dreamlog.capture

object StorageGuard {
    const val PROTECTED_RESERVE_BYTES: Long = 1_073_741_824L

    fun isReserveBreached(availableBytes: Long): Boolean {
        require(availableBytes >= 0) { "Available storage must not be negative." }
        return availableBytes <= PROTECTED_RESERVE_BYTES
    }

    fun canWrite(
        availableBytes: Long,
        requestedBytes: Long,
    ): Boolean {
        require(availableBytes >= 0) { "Available storage must not be negative." }
        require(requestedBytes >= 0) { "Requested storage must not be negative." }

        if (isReserveBreached(availableBytes)) {
            return false
        }

        return requestedBytes < availableBytes - PROTECTED_RESERVE_BYTES
    }
}
