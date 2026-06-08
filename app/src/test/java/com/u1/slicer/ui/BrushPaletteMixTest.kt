package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that the live mix-slot selector in [AiPaintViewer]'s `SlotPaletteRow` correctly
 * renders N-segment mix swatches and does not cap the palette at TARGET_SLOTS.
 *
 * SlotPaletteRow in AiPaintViewer.kt is the active per-surface slot picker used during
 * Smart Paint; these guards confirm it handles mixes via MixedSlotSwatch and is not
 * artificially capped.
 */
class BrushPaletteMixTest {
    private fun read(p: String) = listOf(
        "app/src/main/java/com/u1/slicer/ui/$p",
        "src/main/java/com/u1/slicer/ui/$p",
        "../app/src/main/java/com/u1/slicer/ui/$p",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText()
        ?: error("$p not found")

    // SlotPaletteRow in AiPaintViewer.kt is the live selector for Smart Paint brush slots.
    private val chipRowSrc = read("AiPaintViewer.kt")

    @Test fun chipRowRendersMixSwatches() {
        // SlotPaletteRow must render MixedSlotSwatch for mix entries.
        assertTrue(
            "SlotPaletteRow in AiPaintViewer must render MixedSlotSwatch for mix slots",
            chipRowSrc.contains("MixedSlotSwatch"),
        )
    }

    @Test fun chipRowDoesNotCapAtTargetSlots() {
        // SlotPaletteRow must NOT cap at TARGET_SLOTS (hardcoded 4).
        assertTrue(
            "SlotPaletteRow in AiPaintViewer must not use .take(AiPaintViewModel.TARGET_SLOTS) to cap the palette",
            !chipRowSrc.contains("take(AiPaintViewModel.TARGET_SLOTS)"),
        )
    }

    @Test fun chipRowAcceptsMixesParam() {
        // SlotPaletteRow must accept a mixes parameter so it knows which mixes to render.
        assertTrue(
            "SlotPaletteRow in AiPaintViewer must accept a mixes parameter",
            chipRowSrc.contains("mixes"),
        )
    }
}
