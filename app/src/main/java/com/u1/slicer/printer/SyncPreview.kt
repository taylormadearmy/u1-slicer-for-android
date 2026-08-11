package com.u1.slicer.printer

import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentLibrary
import com.u1.slicer.data.FilamentLibraryMatcher
import com.u1.slicer.network.FilamentSlot

/**
 * Builds the printer-page sync preview. U1 keeps its fixed four logical slots;
 * Bambu callers opt into every live AMS, AMS-HT, and external route. When the
 * library is loaded, each occupied slot is run through FilamentLibraryMatcher;
 * a confident match replaces the raw RFID colour/type and carries its name for the
 * dialog. No match (or library == null) → exact pre-library behaviour.
 */
fun buildSyncPreviewEntries(
    presets: List<ExtruderPreset>,
    slots: List<FilamentSlot>,
    library: FilamentLibrary?,
    includeAllPrinterSlots: Boolean = false,
): List<PrinterViewModel.SyncPreviewEntry> {
    val slotIndices = if (includeAllPrinterSlots) {
        slots.map { it.index }.distinct().sorted()
    } else {
        (0..3).toList()
    }
    return slotIndices.map { i ->
        val preset = presets.firstOrNull { it.index == i } ?: ExtruderPreset(i)
        val printerSlot = slots.firstOrNull { it.index == i }
        val match = if (library != null && printerSlot?.loaded == true) {
            FilamentLibraryMatcher.match(
                library,
                vendor = printerSlot.manufacturer,
                material = printerSlot.materialType,
                subType = printerSlot.subType,
                hex = printerSlot.color,
            )
        } else null
        PrinterViewModel.SyncPreviewEntry(
            slotIndex = i,
            label = printerSlot?.label ?: "E${i + 1}",
            currentColor = preset.color,
            newColor = match?.entry?.hex ?: printerSlot?.color,
            currentType = preset.materialType,
            newType = match?.entry?.material ?: printerSlot?.materialType,
            matchedSlug = match?.entry?.slug,
            matchedName = match?.entry?.displayName,
        )
    }
}

/** Applies a reviewed sync result and creates missing live Bambu route presets. */
fun applySyncPreviewEntries(
    presets: List<ExtruderPreset>,
    entries: List<PrinterViewModel.SyncPreviewEntry>,
    applyColors: Boolean,
    applyTypes: Boolean,
): List<ExtruderPreset> {
    val current = presets.associateBy { it.index }.toMutableMap()
    entries.forEach { entry ->
        val existing = current[entry.slotIndex]
        if (existing == null && entry.newColor == null && entry.newType == null) return@forEach
        val base = existing ?: ExtruderPreset(
            index = entry.slotIndex,
            color = entry.currentColor,
            materialType = entry.currentType,
            displayLabel = entry.label,
        )
        val applyingType = applyTypes && entry.newType != null
        current[entry.slotIndex] = base.copy(
            color = if (applyColors && entry.newColor != null) entry.newColor else base.color,
            materialType = if (applyingType) entry.newType!! else base.materialType,
            filamentProfileId = if (applyingType) null else base.filamentProfileId,
        )
    }
    return current.values.sortedBy { it.index }
}
