package com.wivy.dreamlog.history

import com.wivy.dreamlog.capture.AudioGapEvidence
import com.wivy.dreamlog.capture.SessionIncompleteReason
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Identifies the durable capture graph that justified an owner-facing capture issue.
 *
 * Processing records, owner edits, tombstones, and readable dream content are deliberately not
 * part of this fingerprint. Any change to the persisted capture graph, including a newly
 * imported operational event or changed source-session evidence, produces a new value.
 */
internal object CaptureIssueFingerprint {
    private const val VERSION = "capture-issue-v1"

    fun current(record: NightRecord): String? {
        if (!hasOwnerFacingIssue(record)) return null

        val night = record.night
        val values = buildList {
            add(VERSION)
            add(night.nightId)
            add(night.startedAtEpochMillis.toString())
            add(night.startedUtcOffsetSeconds.toString())
            add(night.endedAtEpochMillis.asValue())
            add(night.endedUtcOffsetSeconds.asValue())
            add(night.captureState)
            add(night.endReason.asValue())
            add(night.interrupted.toString())
            add(night.lastHeartbeatEpochMillis.asValue())
            add(night.lastHeartbeatUtcOffsetSeconds.asValue())
            add(night.reportedSessionCount.toString())
            add(night.reportedIncompleteSessionCount.toString())
            add(night.hadMicrophoneSilencing.toString())
            add(night.hadAudioGap.toString())
            add(night.rawAudioState)
            add(night.importWarning.asValue())

            record.sessions
                .sortedWith(
                    compareBy<CaptureSessionEntity> { it.captureOrder }
                        .thenBy { it.sessionId },
                )
                .forEach { session ->
                    add("session")
                    add(session.sessionId)
                    add(session.nightId)
                    add(session.captureOrder.toString())
                    add(session.startedAtEpochMillis.asValue())
                    add(session.startedUtcOffsetSeconds.asValue())
                    add(session.finalizedAtEpochMillis.asValue())
                    add(session.finalizedUtcOffsetSeconds.asValue())
                    add(session.incompleteReason.asValue())
                    add(session.audioFileName)
                    add(session.audioState)
                    add(session.sampleRateHz.asValue())
                    add(session.channelCount.asValue())
                    add(session.bitsPerSample.asValue())
                    add(session.sampleCount.asValue())
                    add(session.preRollSampleCount.asValue())
                    add(session.cueStartSample.asValue())
                    add(session.cueEndSampleExclusive.asValue())
                    add(session.automaticSilenceTailSampleCount.asValue())
                }

            record.events
                .sortedWith(
                    compareBy<NightEventEntity> { it.eventId }
                        .thenBy { it.epochMillis },
                )
                .forEach { event ->
                    add("event")
                    add(event.nightId)
                    add(event.eventId)
                    add(event.sessionId.asValue())
                    add(event.epochMillis.toString())
                    add(event.utcOffsetSeconds.toString())
                    add(event.type)
                    add(event.encodedAttributes)
                }
        }
        val canonical = values.joinToString(separator = "\n") { value ->
            "${value.length}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }

    fun isReviewed(record: NightRecord): Boolean =
        current(record)?.let { it == record.night.captureIssueReviewedFingerprint } == true

    fun isOwnerFacingIncompleteSession(
        record: NightRecord,
        session: CaptureSessionEntity,
    ): Boolean = when (session.incompleteReason) {
        null,
        SessionIncompleteReason.NIGHT_ENDED,
        -> false

        SessionIncompleteReason.AUDIO_GAP -> isConfirmedAudioGap(record, session.sessionId)
        else -> true
    }

    fun isConfirmedAudioGap(record: NightRecord, sessionId: String): Boolean =
        record.events.any { event ->
            event.sessionId == sessionId && isConfirmedAudioGap(event)
        }

    fun isConfirmedAudioGap(event: NightEventEntity): Boolean =
        event.type == SessionIncompleteReason.AUDIO_GAP &&
            decodeAttributes(event.encodedAttributes)[AudioGapEvidence.ATTRIBUTE_KEY] ==
            AudioGapEvidence.CONFIRMED_PERSISTENT_TIMESTAMP_DEFICIT

    fun hasOwnerFacingIssue(record: NightRecord): Boolean {
        val night = record.night
        val persistedIncompleteSessionCount = record.sessions.count {
            it.incompleteReason != null
        }
        return night.interrupted ||
            night.captureState == NightCaptureState.INTERRUPTED ||
            night.captureState == NightCaptureState.RECOVERY_REQUIRED ||
            record.events.any { it.type == "capture_failure" } ||
            record.sessions.any { session ->
                session.audioState == AudioEvidenceState.MISSING ||
                    session.audioState == AudioEvidenceState.CORRUPT ||
                    session.audioState == AudioEvidenceState.PENDING_RECOVERY ||
                    isOwnerFacingIncompleteSession(record, session)
            } ||
            night.reportedIncompleteSessionCount > persistedIncompleteSessionCount ||
            night.reportedSessionCount > record.sessions.size
    }

    private fun decodeAttributes(encoded: String): Map<String, String> = runCatching {
        if (encoded.isBlank()) {
            emptyMap()
        } else {
            encoded.split(';').associate { item ->
                val separator = item.indexOf('=')
                require(separator > 0)
                item.substring(0, separator) to String(
                    Base64.getUrlDecoder().decode(item.substring(separator + 1)),
                    StandardCharsets.UTF_8,
                )
            }
        }
    }.getOrDefault(emptyMap())

    private fun Any?.asValue(): String = this?.toString() ?: "<null>"
}
