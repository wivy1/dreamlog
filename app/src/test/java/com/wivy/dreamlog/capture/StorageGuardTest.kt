package com.wivy.dreamlog.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGuardTest {
    @Test
    fun `reserve is breached at and below exactly one GiB`() {
        val reserve = StorageGuard.PROTECTED_RESERVE_BYTES

        assertTrue(StorageGuard.isReserveBreached(reserve - 1))
        assertTrue(StorageGuard.isReserveBreached(reserve))
        assertFalse(StorageGuard.isReserveBreached(reserve + 1))
    }

    @Test
    fun `write is rejected when it would reach the protected reserve`() {
        val reserve = StorageGuard.PROTECTED_RESERVE_BYTES

        assertTrue(StorageGuard.canWrite(availableBytes = reserve + 2, requestedBytes = 1))
        assertFalse(StorageGuard.canWrite(availableBytes = reserve + 1, requestedBytes = 1))
        assertFalse(StorageGuard.canWrite(availableBytes = reserve, requestedBytes = 0))
    }

    @Test
    fun `negative byte values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            StorageGuard.isReserveBreached(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageGuard.canWrite(
                availableBytes = StorageGuard.PROTECTED_RESERVE_BYTES + 1,
                requestedBytes = -1,
            )
        }
    }
}
