package com.wivy.dreamlog.capture

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class KeywordStreamProgress(
    val acceptedFrameCount: Long,
    val decodeCount: Long,
    val resetCount: Long,
    val lastResetReason: String?,
) {
    init {
        require(acceptedFrameCount >= 0L)
        require(decodeCount >= 0L)
        require(resetCount >= 0L)
    }
}

internal enum class KeywordStreamResetReason(
    val journalCode: String,
) {
    AUDIO_DISCONTINUITY("audio_discontinuity"),
    CUE_SESSION_ENDED("cue_session_ended"),
    INPUT_CONFIGURATION_MISSING("input_configuration_missing"),
    INPUT_CONFIGURATION_READY("input_configuration_ready"),
    INPUT_CONFIGURATION_MISMATCH("input_configuration_mismatch"),
    MICROPHONE_SILENCED("microphone_silenced"),
    NARRATIVE_COMPLETED("narrative_completed"),
    SESSION_FINALIZED("session_finalized"),
    SESSION_MISSING("session_missing"),
    WAKE_DETECTED("wake_detected"),
}

internal class KeywordStreamHealthTracker {
    private val acceptedFrameCount = AtomicLong(0L)
    private val decodeCount = AtomicLong(0L)
    private val resetCount = AtomicLong(0L)
    private val lastResetReason = AtomicReference<String?>(null)

    fun recordAcceptedFrame() {
        acceptedFrameCount.incrementAndGet()
    }

    fun recordDecode() {
        decodeCount.incrementAndGet()
    }

    fun recordReset(reason: KeywordStreamResetReason) {
        lastResetReason.set(reason.journalCode)
        resetCount.incrementAndGet()
    }

    fun snapshot(): KeywordStreamProgress = KeywordStreamProgress(
        acceptedFrameCount = acceptedFrameCount.get(),
        decodeCount = decodeCount.get(),
        resetCount = resetCount.get(),
        lastResetReason = lastResetReason.get(),
    )
}

internal val CaptureReadiness.journalCode: String
    get() = when (this) {
        CaptureReadiness.WAITING_FOR_RECORDING_CONFIGURATION ->
            "waiting_for_recording_configuration"

        CaptureReadiness.WAITING_FOR_VERIFIED_FRAME -> "waiting_for_verified_frame"
        CaptureReadiness.READY -> "ready"
        CaptureReadiness.MICROPHONE_SILENCED -> "microphone_silenced"
        CaptureReadiness.RECORDING_CONFIGURATION_MISMATCH ->
            "recording_configuration_mismatch"

        CaptureReadiness.AUDIO_DISCONTINUITY -> "audio_discontinuity"
        CaptureReadiness.STOPPED -> "stopped"
    }

data class CaptureHeartbeatHealth(
    val epochMillis: Long,
    val framesRead: Long?,
    val gapCount: Int?,
    val microphoneSilenced: Boolean?,
    val readiness: String?,
    val charging: Boolean?,
    val sessionActive: Boolean?,
    val keywordAcceptedFrameCount: Long?,
    val keywordDecodeCount: Long?,
    val keywordResetCount: Long?,
    val lastKeywordResetReason: String?,
)

enum class CaptureListeningHealthIssue {
    LATEST_HEARTBEAT_NOT_READY,
    KEYWORD_INPUT_NOT_ADVANCING,
    KEYWORD_DECODE_NOT_ADVANCING,
}

data class CaptureListeningHealthSummary(
    val latestHeartbeat: CaptureHeartbeatHealth?,
    val issues: Set<CaptureListeningHealthIssue>,
)

fun summarizeCaptureListeningHealth(
    events: List<CaptureJournalEvent>,
): CaptureListeningHealthSummary {
    val heartbeats = events
        .asSequence()
        .mapNotNull(CaptureJournalEvent::captureHeartbeatHealthOrNull)
        .sortedBy(CaptureHeartbeatHealth::epochMillis)
        .toList()
    val latest = heartbeats.lastOrNull()
        ?: return CaptureListeningHealthSummary(
            latestHeartbeat = null,
            issues = emptySet(),
        )
    val issues = buildSet {
        if (latest.readiness != null && latest.readiness != "ready") {
            add(CaptureListeningHealthIssue.LATEST_HEARTBEAT_NOT_READY)
        }

        heartbeats.zipWithNext().forEach { (previous, current) ->
            if (!previous.isComparableIdleReadyHeartbeat(current)) return@forEach
            val previousAccepted = previous.keywordAcceptedFrameCount
            val currentAccepted = current.keywordAcceptedFrameCount
            if (
                previousAccepted != null &&
                currentAccepted != null &&
                currentAccepted <= previousAccepted
            ) {
                add(CaptureListeningHealthIssue.KEYWORD_INPUT_NOT_ADVANCING)
            } else {
                val previousDecodes = previous.keywordDecodeCount
                val currentDecodes = current.keywordDecodeCount
                if (
                    previousDecodes != null &&
                    currentDecodes != null &&
                    currentDecodes <= previousDecodes
                ) {
                    add(CaptureListeningHealthIssue.KEYWORD_DECODE_NOT_ADVANCING)
                }
            }
        }
    }
    return CaptureListeningHealthSummary(
        latestHeartbeat = latest,
        issues = issues,
    )
}

/**
 * Interprets persisted microphone state without letting older, less precise fields override the
 * service's effective state. Journals written before `effective_silenced` remain readable through
 * the legacy client/heartbeat/configuration fields.
 */
internal fun captureMicrophoneSilencedState(
    eventType: String,
    attributes: Map<String, String>,
): Boolean? {
    if (eventType !in setOf("microphone_state", "heartbeat")) return null
    if ("effective_silenced" in attributes) {
        return attributes.strictBoolean("effective_silenced")
    }

    val clientSilenced = attributes.strictBoolean("client_silenced")
    val heartbeatSilenced = attributes.strictBoolean("microphone_silenced")
    if (
        clientSilenced == true ||
        heartbeatSilenced == true ||
        (
            eventType == "microphone_state" &&
                attributes["own_configuration"] == "missing"
            )
    ) {
        return true
    }
    return when {
        clientSilenced != null -> false
        heartbeatSilenced != null -> false
        eventType == "microphone_state" &&
            attributes["own_configuration"] == "observed" -> false

        else -> null
    }
}

private fun CaptureJournalEvent.captureHeartbeatHealthOrNull(): CaptureHeartbeatHealth? {
    if (type != "heartbeat") return null
    return CaptureHeartbeatHealth(
        epochMillis = epochMillis,
        framesRead = attributes.nonnegativeLong("frames_read"),
        gapCount = attributes.nonnegativeInt("gap_count"),
        microphoneSilenced = attributes.strictBoolean("microphone_silenced"),
        readiness = attributes["readiness"],
        charging = attributes.strictBoolean("charging"),
        sessionActive = attributes.strictBoolean("session_active"),
        keywordAcceptedFrameCount = attributes.nonnegativeLong("kws_accepted_frame_count"),
        keywordDecodeCount = attributes.nonnegativeLong("kws_decode_count"),
        keywordResetCount = attributes.nonnegativeLong("kws_reset_count"),
        lastKeywordResetReason = attributes["kws_last_reset_reason"],
    )
}

private fun CaptureHeartbeatHealth.isComparableIdleReadyHeartbeat(
    latest: CaptureHeartbeatHealth,
): Boolean {
    val earlierFrames = framesRead
    val laterFrames = latest.framesRead
    return readiness == "ready" &&
        latest.readiness == "ready" &&
        sessionActive == false &&
        latest.sessionActive == false &&
        earlierFrames != null &&
        laterFrames != null &&
        laterFrames > earlierFrames &&
        keywordResetCount != null &&
        keywordResetCount == latest.keywordResetCount
}

private fun Map<String, String>.nonnegativeLong(key: String): Long? =
    get(key)?.toLongOrNull()?.takeIf { it >= 0L }

private fun Map<String, String>.nonnegativeInt(key: String): Int? =
    get(key)?.toIntOrNull()?.takeIf { it >= 0 }

private fun Map<String, String>.strictBoolean(key: String): Boolean? =
    get(key)?.toBooleanStrictOrNull()
