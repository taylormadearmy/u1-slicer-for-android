package com.u1.slicer.printer

import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentLibrary
import com.u1.slicer.data.FilamentLibraryMatcher
import com.u1.slicer.network.FilamentSlot

/**
 * Builds the 4-slot sync preview. When the library is loaded, each slot is run
 * through FilamentLibraryMatcher; a confident match replaces the raw RFID
 * colour/type with catalogue values and carries the catalogue name for the
 * dialog. No match (or library == null) → exact pre-library behaviour.
 */
fun buildSyncPreviewEntries(
    presets: List<ExtruderPreset>,
    slots: List<FilamentSlot>,
    library: FilamentLibrary?,
): List<PrinterViewModel.SyncPreviewEntry> = (0..3).map { i ->
    val preset = presets.getOrElse(i) { ExtruderPreset(i) }
    val printerSlot = slots.firstOrNull { it.index == i }
    val match = if (library != null && printerSlot != null) {
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
        label = "E${i + 1}",
        currentColor = preset.color,
        newColor = match?.entry?.hex ?: printerSlot?.color,
        currentType = preset.materialType,
        newType = match?.entry?.material ?: printerSlot?.materialType,
        matchedSlug = match?.entry?.slug,
        matchedName = match?.entry?.displayName,
    )
}
