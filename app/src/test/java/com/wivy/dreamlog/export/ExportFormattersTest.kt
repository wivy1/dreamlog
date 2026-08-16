package com.wivy.dreamlog.export

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFormattersTest {
    private val export by lazy {
        createDreamLogExportV1(
            availableNights = ExportTestFixtures.historyNewestFirst(),
            selection = DreamLogExportSelection.AllNights,
        )
    }

    @Test
    fun txtUsesNightDividersAndSourceBackedNarrationTimes() {
        val text = DreamLogExportFormatter.formatTxt(export)

        assertTrue(text.startsWith("2 nights\n2026-10-31 - 2026-11-02\n"))
        assertTrue(text.contains("2026-10-31\n\nTime: 01:29:59\nDream 1"))
        assertTrue(text.contains("=========\n\n2026-11-02\n\nTime: 22:00:59\nDream 1"))
        assertEquals(1, text.lineSequence().count { line -> line == "=========" })
        assertEquals(
            listOf("Time: 01:29:59", "Time: 22:00:59"),
            text.lineSequence().filter { line -> line.startsWith("Time: ") }.toList(),
        )
        assertEquals(2, text.lineSequence().count { line -> line == "Dream 1" })
        assertTrue(text.contains(ExportTestFixtures.EDITED_TITLE))
        assertTrue(text.contains(ExportTestFixtures.EDITED_TEXT))
        assertFalse(text.contains("DreamLog export"))
        assertFalse(text.contains("Format version"))
        assertFalse(text.contains("Night "))
        assertFalse(text.contains("Monitoring:"))
        assertFalse(text.contains("UTC"))
        assertFalse(text.contains("Status:"))
        assertFalse(text.contains("Raw audio:"))
        assertFalse(text.lineSequence().any { line -> line == "Dreams" })
        assertFalse(text.contains("Edited"))
        assertFalse(text.contains("Uncertain fragment"))
        assertFalse(text.contains("source range"))
        assertFalse(text.contains("Raw transcripts"))
        assertFalse(text.contains("Fragment 1"))
        assertFalse(text.contains(ExportTestFixtures.RAW_TEXT))
        assertFalse(text.contains(ExportTestFixtures.DELETED_TEXT))
        assertFalse(text.contains("01:15:00"))
        assertFalse(text.contains("01:45:00"))
        assertFalse(text.contains("22:20:00"))
        assertTrue(
            text.indexOf("2026-10-31\n\nTime:") < text.indexOf("2026-11-02\n\nTime:"),
        )
    }

    @Test
    fun csvMirrorsCompactTxtFieldsAndHasOneRowPerNondeletedDream() {
        val csv = DreamLogExportFormatter.formatCsv(export)
        val rows = parseCsv(csv)

        assertTrue(csv.endsWith("\r\n"))
        assertEquals(3, rows.size)
        assertEquals(
            listOf(
                "night_date",
                "time",
                "dream",
                "title",
                "text",
            ),
            rows.first(),
        )
        assertEquals(
            listOf(
                "2026-10-31",
                "01:29:59",
                "Dream 1",
                ExportTestFixtures.EDITED_TITLE,
                ExportTestFixtures.EDITED_TEXT,
            ),
            rows.single { row -> row.first() == "2026-10-31" },
        )
        assertEquals(
            listOf(
                "2026-11-02",
                "22:00:59",
                "Dream 1",
                "",
                "A fragment in a tower.",
            ),
            rows.single { row -> row.first() == "2026-11-02" },
        )
        assertFalse(rows.any { row -> ExportTestFixtures.DELETED_DREAM_ID in row })
        assertFalse(csv.contains(ExportTestFixtures.RAW_TEXT))
        assertFalse(csv.contains(ExportTestFixtures.DELETED_TEXT))
    }

    @Test
    fun dreamNumbersIncreaseWithinEachNight() {
        val night = export.nights.first()
        val firstDream = night.dreams.single()
        val laterSourceSpan = firstDream.sourceSpans.last().copy(
            dreamId = "second-retained-dream",
            spanOrder = 0,
            sourceStartMillis = 62_000L,
            sourceEndMillis = 63_000L,
        )
        val secondDream = firstDream.copy(
            dreamId = "second-retained-dream",
            dreamOrder = firstDream.dreamOrder + 1,
            currentTitle = null,
            generatedTitle = null,
            currentText = "A second retained dream.",
            sourceSpans = listOf(laterSourceSpan),
        )
        val twoDreamExport = export.copy(
            nights = listOf(night.copy(dreams = listOf(secondDream, firstDream))),
        )

        val text = DreamLogExportFormatter.formatTxt(twoDreamExport)
        val csvRows = parseCsv(DreamLogExportFormatter.formatCsv(twoDreamExport))

        assertTrue(text.startsWith("2026-10-31\n\nTime: 01:29:59\nDream 1"))
        assertFalse(text.contains("1 night"))
        assertFalse(text.contains("========="))
        assertEquals(1, text.lineSequence().count { line -> line == "2026-10-31" })
        assertEquals(
            listOf("Time: 01:29:59", "Time: 01:31:00"),
            text.lineSequence().filter { line -> line.startsWith("Time: ") }.toList(),
        )
        assertTrue(text.indexOf("Dream 1") < text.indexOf("Dream 2"))
        assertEquals(listOf("Dream 1", "Dream 2"), csvRows.drop(1).map { row -> row[2] })
        assertEquals(listOf("01:29:59", "01:31:00"), csvRows.drop(1).map { row -> row[1] })
    }

    @Test
    fun singleNightWithoutDreamsOmitsCountHeaderAndTime() {
        val openNight = export.nights.first().copy(
            endedAtEpochMillis = null,
            endedUtcOffsetSeconds = null,
            dreams = emptyList(),
        )

        assertEquals(
            "2026-10-31\n\nNo dreams\n",
            DreamLogExportFormatter.formatTxt(export.copy(nights = listOf(openNight))),
        )
    }

    @Test
    fun dreamWithoutResolvableSourceTimeDoesNotUseNightMonitoringStart() {
        val night = export.nights.first()
        val unresolvedDream = night.dreams.single().copy(sourceSpans = emptyList())
        val unresolvedExport = export.copy(
            nights = listOf(night.copy(dreams = listOf(unresolvedDream))),
        )

        val text = DreamLogExportFormatter.formatTxt(unresolvedExport)
        val csvRows = parseCsv(DreamLogExportFormatter.formatCsv(unresolvedExport))

        assertTrue(text.contains("Time: Unknown\nDream 1"))
        assertFalse(text.contains("Time: 01:15:00"))
        assertEquals("Unknown", csvRows.single { row -> row.first() == "2026-10-31" }[1])
    }

    @Test
    fun documentUsesExactMimeExtensionSafeNameAndUtf8() {
        val document = DreamLogExportFormatter.document(
            export = export,
            format = DreamLogExportFormat.JSON,
            suggestedBaseName = "../Owner's export.json",
        )

        assertEquals("Owner-s-export.json", document.fileName)
        assertEquals("application/json", document.mimeType)
        assertEquals(
            document.content,
            String(document.utf8Bytes, StandardCharsets.UTF_8),
        )
        assertTrue(document.content.endsWith("\n"))
        assertEquals(export, DreamLogJsonV1.decode(document.content))
    }

    private fun parseCsv(source: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < source.length) {
            val character = source[index]
            when {
                character == '"' && inQuotes && source.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index += 1
                }

                character == '"' -> inQuotes = !inQuotes

                character == ',' && !inQuotes -> {
                    row += field.toString()
                    field.setLength(0)
                }

                character == '\r' && !inQuotes && source.getOrNull(index + 1) == '\n' -> {
                    row += field.toString()
                    field.setLength(0)
                    rows += row.toList()
                    row.clear()
                    index += 1
                }

                else -> field.append(character)
            }
            index += 1
        }
        require(!inQuotes) { "CSV fixture ended inside a quoted field." }
        require(row.isEmpty() && field.isEmpty()) { "CSV fixture did not end at a record boundary." }
        return rows
    }
}
