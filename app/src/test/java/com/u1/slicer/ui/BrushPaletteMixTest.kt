package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that [FilamentMixChipRow] — the replacement for the now-retired SlotPaletteRow —
 * correctly renders mix swatches and does not cap the palette at TARGET_SLOTS.
 *
 * Task 5 (Prepare UX Consolidation): SlotPaletteRow in AiPaintViewer.kt was retired; these
 * guards were retargeted from AiPaintViewer.kt to FilamentMixChipRow.kt to preserve coverage
 * of the live behaviour.
 */
class BrushPaletteMixTest {
    private fun read(p: String) = listOf(
        "app/src/main/java/com/u1/slicer/ui/$p",
        "src/main/java/com/u1/slicer/ui/$p",
        "../app/src/main/java/com/u1/slicer/ui/$p",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText()
        ?: error("$p not found")

    // FilamentMixChipRow is the live replacement for the retired SlotPaletteRow
    private val chipRowSrc = read("FilamentMixChipRow.kt")

    @Test fun chipRowRendersMixSwatches() {
        // FilamentMixChipRow must render MixedSlotSwatch for mix entries.
        assertTrue(
            "FilamentMixChipRow must render MixedSlotSwatch for mix slots",
            chipRowSrc.contains("MixedSlotSwatch"),
        )
    }

    @Test fun chipRowDoesNotCapAtTargetSlots() {
        // FilamentMixChipRow must NOT cap at TARGET_SLOTS (hardcoded 4).
        assertTrue(
            "FilamentMixChipRow must not use .take(AiPaintViewModel.TARGET_SLOTS) to cap the palette",
            !chipRowSrc.contains("take(AiPaintViewModel.TARGET_SLOTS)"),
        )
    }

    @Test fun chipRowAcceptsMixesParam() {
        // FilamentMixChipRow must accept a mixes parameter so it knows which mixes to render.
        assertTrue(
            "FilamentMixChipRow must accept a mixes parameter",
            chipRowSrc.contains("mixes"),
        )
    }
}
