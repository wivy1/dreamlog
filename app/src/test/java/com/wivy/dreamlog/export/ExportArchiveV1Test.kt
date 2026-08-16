package com.wivy.dreamlog.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportArchiveV1Test {
    @Test
    fun oneSelectedAndAllSelectionsResolveExactlyAndChronologically() {
        val history = ExportTestFixtures.historyNewestFirst()

        assertEquals(
            listOf(ExportTestFixtures.NEWER_NIGHT_ID),
            selectNightsForExport(
                history,
                DreamLogExportSelection.OneNight(ExportTestFixtures.NEWER_NIGHT_ID),
            ).map { it.night.nightId },
        )
        assertEquals(
            listOf(
                ExportTestFixtures.OLDER_NIGHT_ID,
                ExportTestFixtures.NEWER_NIGHT_ID,
            ),
            selectNightsForExport(
                history,
                DreamLogExportSelection.SelectedNights(
                    setOf(
                        ExportTestFixtures.NEWER_NIGHT_ID,
                        ExportTestFixtures.OLDER_NIGHT_ID,
                    ),
                ),
            ).map { it.night.nightId },
        )
        assertEquals(
            listOf(
                ExportTestFixtures.OLDER_NIGHT_ID,
                ExportTestFixtures.NEWER_NIGHT_ID,
            ),
            selectNightsForExport(
                history,
                DreamLogExportSelection.AllNights,
            ).map { it.night.nightId },
        )
    }

    @Test
    fun changedOrEmptySelectionsFailInsteadOfSilentlyChangingTheExport() {
        val history = ExportTestFixtures.historyNewestFirst()

        assertNotNull(
            runCatching {
                selectNightsForExport(
                    history,
                    DreamLogExportSelection.OneNight("night-no-longer-present"),
                )
            }.exceptionOrNull() as? DreamLogExportSelectionException,
        )
        assertNotNull(
            runCatching {
                selectNightsForExport(emptyList(), DreamLogExportSelection.AllNights)
            }.exceptionOrNull() as? DreamLogExportSelectionException,
        )
    }

    @Test
    fun mapperNormalizesEveryGraphLevelAndExcludesDeletedDreams() {
        val export = createDreamLogExportV1(
            availableNights = ExportTestFixtures.historyNewestFirst(),
            selection = DreamLogExportSelection.AllNights,
        )

        assertEquals(DreamLogExportV1.SCHEMA_VERSION, export.schemaVersion)
        assertEquals(
            listOf(
                ExportTestFixtures.OLDER_NIGHT_ID,
                ExportTestFixtures.NEWER_NIGHT_ID,
            ),
            export.nights.map(ExportNightV1::nightId),
        )
        val older = export.nights.first()
        assertEquals(listOf(0, 1), older.sessions.map(ExportSessionV1::captureOrder))
        assertEquals(listOf("event-1", "event-2"), older.events.map(ExportNightEventV1::eventId))
        assertEquals(
            listOf("session-old-1", "session-old-2"),
            older.transcripts.map(ExportSessionTranscriptV1::sessionId),
        )
        assertEquals(
            listOf(0, 1),
            older.transcripts.first().segments.map(ExportTranscriptSegmentV1::segmentIndex),
        )
        assertEquals(listOf(1, 2), older.enrichmentRuns.map(ExportEnrichmentRunV1::attemptNumber))
        assertEquals(listOf(ExportTestFixtures.EDITED_DREAM_ID), older.dreams.map { it.dreamId })
        assertEquals(listOf(0, 1), older.dreams.single().sourceSpans.map { it.spanOrder })
        assertFalse(
            export.nights.flatMap(ExportNightV1::dreams)
                .any { it.dreamId == ExportTestFixtures.DELETED_DREAM_ID },
        )
    }

    @Test
    fun canonicalJsonRoundTripsFullFixtureAndReencodesDeterministically() {
        val export = createDreamLogExportV1(
            availableNights = ExportTestFixtures.historyNewestFirst(),
            selection = DreamLogExportSelection.AllNights,
        )

        val encoded = DreamLogJsonV1.encode(export)
        val decoded = DreamLogJsonV1.decode(encoded)

        assertEquals(export, decoded)
        assertEquals(encoded, DreamLogJsonV1.encode(decoded))
        assertTrue(encoded.contains("café"))
        assertTrue(encoded.contains("Raw words"))
        assertTrue(encoded.contains("\"modelBytes\""))
        assertTrue(encoded.contains("\"sourceSpans\""))
        assertFalse(encoded.contains(ExportTestFixtures.DELETED_TEXT))
    }

    @Test
    fun canonicalJsonRejectsUnsupportedSchemaVersions() {
        val encoded = DreamLogJsonV1.encode(
            createDreamLogExportV1(
                availableNights = ExportTestFixtures.historyNewestFirst(),
                selection = DreamLogExportSelection.AllNights,
            ),
        )
        val unsupported = encoded.replaceFirst(
            "\"schemaVersion\": 1",
            "\"schemaVersion\": 2",
        )
        assertTrue(unsupported != encoded)

        assertNotNull(runCatching { DreamLogJsonV1.decode(unsupported) }.exceptionOrNull())
    }
}
