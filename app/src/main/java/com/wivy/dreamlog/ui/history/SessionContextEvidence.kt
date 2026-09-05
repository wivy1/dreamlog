package com.wivy.dreamlog.ui.history

import com.wivy.dreamlog.history.DreamRecord
import com.wivy.dreamlog.history.NightEventEntity

internal const val POSSIBLE_MEDIA_FALSE_WAKE_MESSAGE =
    "Possible false wake: media was playing. Review the source."

internal fun mediaPlaybackActiveAtWakeSessionIds(
    events: List<NightEventEntity>,
): Set<String> = events.asSequence()
    .filter { event -> event.type == "session_started" }
    .mapNotNull { event ->
        val attributes = decodePersistedEventAttributes(event.encodedAttributes)
        if (attributes["music_playback_active_at_wake"] != "true") {
            null
        } else {
            event.sessionId ?: attributes["session_id"]
        }
    }
    .toSet()

internal fun DreamRecord.hasMediaPlaybackActiveAtWake(
    sessionIds: Set<String>,
): Boolean = sourceSpans.any { span -> span.sessionId in sessionIds }
