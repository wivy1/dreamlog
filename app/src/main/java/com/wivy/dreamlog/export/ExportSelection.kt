package com.wivy.dreamlog.export

import com.wivy.dreamlog.history.NightRecord

sealed interface DreamLogExportSelection {
    data class OneNight(
        val nightId: String,
    ) : DreamLogExportSelection {
        init {
            require(nightId.isNotBlank()) { "A night must be selected for export." }
        }
    }

    data class SelectedNights(
        val nightIds: Set<String>,
    ) : DreamLogExportSelection {
        init {
            require(nightIds.isNotEmpty()) { "At least one night must be selected for export." }
            require(nightIds.none(String::isBlank)) {
                "Every selected export night must have an identifier."
            }
        }
    }

    data object AllNights : DreamLogExportSelection
}

class DreamLogExportSelectionException(message: String) : IllegalArgumentException(message)

internal val EXPORT_NIGHT_COMPARATOR: Comparator<NightRecord> =
    compareBy<NightRecord> { it.night.startedAtEpochMillis }
        .thenBy { it.night.nightId }

fun selectNightsForExport(
    availableNights: List<NightRecord>,
    selection: DreamLogExportSelection,
): List<NightRecord> {
    val nightsById = availableNights.associateBy { it.night.nightId }
    if (nightsById.size != availableNights.size) {
        throw DreamLogExportSelectionException(
            "Private history contains duplicate night identifiers and cannot be exported safely.",
        )
    }

    val selectedIds = when (selection) {
        is DreamLogExportSelection.OneNight -> setOf(selection.nightId)
        is DreamLogExportSelection.SelectedNights -> selection.nightIds
        DreamLogExportSelection.AllNights -> nightsById.keys
    }
    if (selectedIds.isEmpty()) {
        throw DreamLogExportSelectionException("No nights are available to export.")
    }

    val missingCount = selectedIds.count { it !in nightsById }
    if (missingCount > 0) {
        throw DreamLogExportSelectionException(
            "The export selection changed because one or more nights are no longer available.",
        )
    }

    return selectedIds
        .map { nightId -> requireNotNull(nightsById[nightId]) }
        .sortedWith(EXPORT_NIGHT_COMPARATOR)
}
