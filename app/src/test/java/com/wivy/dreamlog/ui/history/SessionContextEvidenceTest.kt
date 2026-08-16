package com.wivy.dreamlog.ui.history

import com.wivy.dreamlog.history.DreamEntity
import com.wivy.dreamlog.history.DreamRecord
import com.wivy.dreamlog.history.DreamSourceRole
import com.wivy.dreamlog.history.DreamSourceSpanEntity
import com.wivy.dreamlog.history.NightEventEntity
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContextEvidenceTest {
    @Test
    fun mediaPlaybackEvidenceFlagsOnlyTheMatchingSessionAndDream() {
        val events = listOf(
            sessionStarted("session-a", "true"),
            sessionStarted("session-b", "false"),
            sessionStarted("session-c", null),
            sessionStarted("session-d", "TRUE"),
        )

        val flagged = mediaPlaybackActiveAtWakeSessionIds(events)

        assertEquals(setOf("session-a"), flagged)
        assertTrue(dream("session-a").hasMediaPlaybackActiveAtWake(flagged))
        assertFalse(dream("session-b").hasMediaPlaybackActiveAtWake(flagged))
    }

    @Test
    fun malformedAndUnrelatedEventsFailClosed() {
        val events = listOf(
            event("session-a", "heartbeat", encode(mapOf("music_playback_active_at_wake" to "true"))),
            event("session-b", "session_started", "not-valid-persisted-attributes"),
        )

        assertTrue(mediaPlaybackActiveAtWakeSessionIds(events).isEmpty())
    }

    private fun sessionStarted(sessionId: String, active: String?): NightEventEntity = event(
        sessionId = sessionId,
        type = "session_started",
        encodedAttributes = encode(
            buildMap {
                put("session_id", sessionId)
                active?.let { put("music_playback_active_at_wake", it) }
            },
        ),
    )

    private fun event(
        sessionId: String?,
        type: String,
        encodedAttributes: String,
    ) = NightEventEntity(
        nightId = NIGHT_ID,
        eventId = "event-${eventCounter++}",
        sessionId = sessionId,
        epochMillis = 1L,
        utcOffsetSeconds = 0,
        type = type,
        encodedAttributes = encodedAttributes,
    )

    private fun dream(sessionId: String) = DreamRecord(
        dream = DreamEntity(
            dreamId = "dream-$sessionId",
            nightId = NIGHT_ID,
            runId = "run",
            dreamOrder = 0,
            kind = "dream",
            isUncertain = false,
            generatedTitle = null,
            generatedText = "text",
            currentTitle = null,
            currentText = "text",
            ownerEdited = false,
            editedAtEpochMillis = null,
            deletedAtEpochMillis = null,
        ),
        sourceSpans = listOf(
            DreamSourceSpanEntity(
                dreamId = "dream-$sessionId",
                spanOrder = 0,
                sessionId = sessionId,
                sourceTranscriptAttemptCount = 1,
                firstSegmentIndex = 0,
                lastSegmentIndex = 0,
                sourceStartMillis = 0L,
                sourceEndMillis = 1L,
                sourceText = "text",
                role = DreamSourceRole.NARRATIVE,
            ),
        ),
    )

    private fun encode(attributes: Map<String, String>): String =
        attributes.toSortedMap().entries.joinToString(";") { (key, value) ->
            "$key=${Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))}"
        }

    private companion object {
        const val NIGHT_ID = "night"
        var eventCounter = 0
    }
}
