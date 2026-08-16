package com.wivy.dreamlog.history

import java.util.concurrent.atomic.AtomicBoolean

/** Pure selection policy for automatic raw-audio expiry. */
internal object RawAudioRetentionPolicy {
    const val DEFAULT_RETENTION_DAYS = 30
    const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    const val DEFAULT_RETENTION_MILLIS = DEFAULT_RETENTION_DAYS * MILLIS_PER_DAY

    /**
     * Returns finalized nights whose retained raw audio has reached the configured age.
     *
     * The durable night completion timestamp is authoritative; file modification times are not.
     * Ambiguous duplicate records and inconsistent or recovery-pending capture graphs are retained
     * for review instead of being selected for destructive cleanup.
     */
    fun selectExpiredNightIds(
        records: Iterable<NightRecord>,
        nowEpochMillis: Long,
        retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
        inUseNightIds: Set<String> = emptySet(),
    ): List<String> {
        require(nowEpochMillis >= 0L) { "The retention clock must be nonnegative." }
        require(retentionMillis >= 0L) { "The raw-audio retention period must be nonnegative." }

        return records
            .groupBy { it.night.nightId }
            .values
            .asSequence()
            .mapNotNull { matchingRecords -> matchingRecords.singleOrNull() }
            .filter { record ->
                record.isExpiredRawAudioCandidate(
                    nowEpochMillis = nowEpochMillis,
                    retentionMillis = retentionMillis,
                    inUseNightIds = inUseNightIds,
                )
            }
            .sortedWith(
                compareBy<NightRecord> { requireNotNull(it.night.endedAtEpochMillis) }
                    .thenBy { it.night.nightId },
            )
            .map { it.night.nightId }
            .toList()
    }

    private fun NightRecord.isExpiredRawAudioCandidate(
        nowEpochMillis: Long,
        retentionMillis: Long,
        inUseNightIds: Set<String>,
    ): Boolean {
        val nightId = night.nightId
        if (nightId.isBlank() || nightId in inUseNightIds) return false
        if (
            night.captureState != NightCaptureState.ENDED &&
            night.captureState != NightCaptureState.INTERRUPTED
        ) {
            return false
        }

        val completedAt = night.endedAtEpochMillis ?: return false
        if (completedAt < 0L || completedAt > nowEpochMillis) return false
        if (nowEpochMillis - completedAt < retentionMillis) return false
        if (sessions.isEmpty() || sessions.any { it.nightId != nightId }) return false
        if (sessions.any { it.audioState == AudioEvidenceState.PENDING_RECOVERY }) return false

        val retainedSessions = sessions.filter { it.audioState == AudioEvidenceState.RETAINED }
        if (retainedSessions.isEmpty()) return false
        return retainedSessions.all { session ->
            session.finalizedAtEpochMillis?.let { finalizedAt ->
                finalizedAt in 0L..completedAt
            } == true
        }
    }
}

/** A process-local claim on one or more nights' raw-audio resources. */
internal fun interface RawAudioUseLease : AutoCloseable {
    override fun close()
}

/**
 * Coordinates shared raw-audio consumers with exclusive destructive operations per night.
 *
 * Playback, transcription, and export take shared use leases. Expiry and owner deletion take an
 * exclusive deletion lease. Multi-night shared acquisition is all-or-nothing, closing is
 * thread-safe and idempotent, and unrelated nights never block one another.
 */
internal class RawAudioUseRegistry {
    private val lock = Any()
    private val sharedUseCounts = mutableMapOf<String, Int>()
    private val exclusivelyHeldNightIds = mutableSetOf<String>()

    fun tryAcquireUse(nightId: String): RawAudioUseLease? =
        tryAcquireUse(listOf(nightId))

    fun tryAcquireUse(nightIds: Collection<String>): RawAudioUseLease? {
        val normalizedNightIds = normalizeNightIds(nightIds)
        synchronized(lock) {
            if (normalizedNightIds.any(exclusivelyHeldNightIds::contains)) return null
            if (normalizedNightIds.any { sharedUseCounts.getOrDefault(it, 0) == Int.MAX_VALUE }) {
                error("Too many raw-audio use leases are active for one night.")
            }
            normalizedNightIds.forEach { nightId ->
                sharedUseCounts[nightId] = sharedUseCounts.getOrDefault(nightId, 0) + 1
            }
        }
        return IdempotentRawAudioUseLease {
            synchronized(lock) {
                normalizedNightIds.forEach { nightId ->
                    val currentCount = checkNotNull(sharedUseCounts[nightId]) {
                        "The raw-audio use lease was not registered."
                    }
                    check(currentCount > 0) { "The raw-audio use lease count is invalid." }
                    if (currentCount == 1) {
                        sharedUseCounts.remove(nightId)
                    } else {
                        sharedUseCounts[nightId] = currentCount - 1
                    }
                }
            }
        }
    }

    fun tryAcquireDeletion(nightId: String): RawAudioUseLease? {
        requireValidNightId(nightId)
        synchronized(lock) {
            if (
                sharedUseCounts.getOrDefault(nightId, 0) > 0 ||
                nightId in exclusivelyHeldNightIds
            ) {
                return null
            }
            exclusivelyHeldNightIds += nightId
        }
        return IdempotentRawAudioUseLease {
            synchronized(lock) {
                check(exclusivelyHeldNightIds.remove(nightId)) {
                    "The raw-audio deletion lease was not registered."
                }
            }
        }
    }

    fun snapshotInUseNightIds(): Set<String> = synchronized(lock) {
        (sharedUseCounts.keys + exclusivelyHeldNightIds)
            .sorted()
            .toSet()
    }

    private fun normalizeNightIds(nightIds: Collection<String>): List<String> {
        require(nightIds.isNotEmpty()) { "At least one night ID is required." }
        nightIds.forEach(::requireValidNightId)
        return nightIds.distinct().sorted()
    }

    private fun requireValidNightId(nightId: String) {
        require(nightId.isNotBlank()) { "A nonblank night ID is required." }
    }

    companion object {
        /** Shared by production consumers; tests can construct an isolated registry. */
        val processWide = RawAudioUseRegistry()
    }
}

private class IdempotentRawAudioUseLease(
    private val release: () -> Unit,
) : RawAudioUseLease {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
