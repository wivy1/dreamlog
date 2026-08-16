package com.wivy.dreamlog.export

import com.wivy.dreamlog.history.HistoryFormatters
import java.nio.charset.StandardCharsets

enum class DreamLogExportFormat(
    val extension: String,
    val mimeType: String,
) {
    TXT(extension = "txt", mimeType = "text/plain"),
    JSON(extension = "json", mimeType = "application/json"),
    CSV(extension = "csv", mimeType = "text/csv"),
}

data class DreamLogExportDocument(
    val fileName: String,
    val mimeType: String,
    val content: String,
) {
    val utf8Bytes: ByteArray
        get() = content.toByteArray(StandardCharsets.UTF_8)
}

object DreamLogExportFormatter {
    fun document(
        export: DreamLogExportV1,
        format: DreamLogExportFormat,
        suggestedBaseName: String = "dreamlog-export",
    ): DreamLogExportDocument {
        val normalized = export.normalized()
        val content = when (format) {
            DreamLogExportFormat.TXT -> formatTxt(normalized)
            DreamLogExportFormat.JSON -> DreamLogJsonV1.encode(normalized) + "\n"
            DreamLogExportFormat.CSV -> formatCsv(normalized)
        }
        return DreamLogExportDocument(
            fileName = "${safeFileStem(suggestedBaseName, format)}.${format.extension}",
            mimeType = format.mimeType,
            content = content,
        )
    }

    fun formatTxt(export: DreamLogExportV1): String = buildString {
        val normalized = export.normalized()
        if (normalized.nights.isEmpty()) {
            appendLine("0 nights")
        } else if (normalized.nights.size > 1) {
            appendLine("${normalized.nights.size} nights")
            appendLine(
                "${normalized.nights.first().displayDate} - " +
                    normalized.nights.last().displayDate,
            )
            appendLine()
        }

        normalized.nights.forEachIndexed { nightIndex, night ->
            if (nightIndex > 0) {
                appendLine()
                appendLine(NIGHT_DIVIDER)
                appendLine()
            }
            appendLine(night.displayDate)

            if (night.dreams.isEmpty()) {
                appendLine()
                appendLine("No dreams")
            } else {
                val sessionsById = night.sessions.associateBy(ExportSessionV1::sessionId)
                night.dreams.forEachIndexed { dreamIndex, dream ->
                    appendLine()
                    appendLine("Time: ${formatDreamNarrationTime(dream, sessionsById)}")
                    appendLine(dreamLabel(dreamIndex))
                    exportDreamVisibleTitle(dream)?.let(::appendLine)
                    appendLine(dream.currentText.trimEnd())
                }
            }
        }
    }

    fun formatCsv(export: DreamLogExportV1): String = buildString {
        val normalized = export.normalized()
        append(CSV_COLUMNS.joinToString(","))
        append(CSV_RECORD_SEPARATOR)
        normalized.nights.forEach { night ->
            val sessionsById = night.sessions.associateBy(ExportSessionV1::sessionId)
            night.dreams.forEachIndexed { dreamIndex, dream ->
                val values = listOf(
                    night.displayDate,
                    formatDreamNarrationTime(dream, sessionsById),
                    dreamLabel(dreamIndex),
                    exportDreamVisibleTitle(dream).orEmpty(),
                    dream.currentText,
                )
                append(values.joinToString(",", transform = ::escapeCsvField))
                append(CSV_RECORD_SEPARATOR)
            }
        }
    }

    private fun formatDreamNarrationTime(
        dream: ExportDreamV1,
        sessionsById: Map<String, ExportSessionV1>,
    ): String = dream.sourceSpans.firstNotNullOfOrNull { span ->
        val session = sessionsById[span.sessionId] ?: return@firstNotNullOfOrNull null
        sourceWallClockTime(session, span.sourceStartMillis)
    } ?: "Unknown"

    private fun sourceWallClockTime(
        session: ExportSessionV1,
        sourceStartMillis: Long,
    ): String? {
        val startedAt = session.startedAtEpochMillis ?: return null
        val utcOffsetSeconds = session.startedUtcOffsetSeconds ?: return null
        val sourceEpochMillis = runCatching {
            Math.addExact(
                startedAt,
                Math.subtractExact(
                    sourceStartMillis.coerceAtLeast(0L),
                    session.preRollDurationMillis(),
                ),
            )
        }.getOrNull() ?: return null
        return HistoryFormatters.time(sourceEpochMillis, utcOffsetSeconds)
            .takeUnless { it == "Unknown" }
    }

    private fun ExportSessionV1.preRollDurationMillis(): Long {
        val samples = preRollSampleCount ?: return 0L
        val rate = sampleRateHz?.takeIf { it > 0 } ?: return 0L
        return runCatching {
            Math.multiplyExact(samples.coerceAtLeast(0L), 1_000L) / rate
        }.getOrDefault(0L)
    }

    private fun safeFileStem(
        suggestedBaseName: String,
        format: DreamLogExportFormat,
    ): String {
        val withoutExtension = suggestedBaseName.trim().let { candidate ->
            val suffix = ".${format.extension}"
            if (candidate.endsWith(suffix, ignoreCase = true)) {
                candidate.dropLast(suffix.length)
            } else {
                candidate
            }
        }
        val sanitized = buildString {
            withoutExtension.forEach { character ->
                append(
                    if (character.isLetterOrDigit() || character in setOf('-', '_', '.')) {
                        character
                    } else {
                        '-'
                    },
                )
            }
        }
            .replace(Regex("-+"), "-")
            .trim('-', '_', '.')
            .take(MAX_FILE_STEM_CHARACTERS)
            .trimEnd('-', '_', '.')
        return sanitized.ifBlank { "dreamlog-export" }
    }

    private fun dreamLabel(index: Int): String = "Dream ${index + 1}"

    private fun exportDreamVisibleTitle(dream: ExportDreamV1): String? = dream.currentTitle
        ?.takeIf(String::isNotBlank)
        ?: dream.generatedTitle?.takeIf(String::isNotBlank)

    private fun escapeCsvField(value: String): String {
        val mustQuote = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' } ||
            value.firstOrNull()?.isWhitespace() == true ||
            value.lastOrNull()?.isWhitespace() == true
        if (!mustQuote) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private val CSV_COLUMNS = listOf(
        "night_date",
        "time",
        "dream",
        "title",
        "text",
    )
    private const val NIGHT_DIVIDER = "========="
    private const val CSV_RECORD_SEPARATOR = "\r\n"
    private const val MAX_FILE_STEM_CHARACTERS = 80
}
