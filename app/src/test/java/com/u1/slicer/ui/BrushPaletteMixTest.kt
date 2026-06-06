package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class BrushPaletteMixTest {
    private fun read(p: String) = listOf(
        "app/src/main/java/com/u1/slicer/ui/$p",
        "src/main/java/com/u1/slicer/ui/$p",
        "../app/src/main/java/com/u1/slicer/ui/$p",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText()
        ?: error("$p not found")

    // SlotPaletteRow is defined in AiPaintViewer.kt
    private val viewerSrc = read("AiPaintViewer.kt")

    @Test fun brushPaletteRendersMixSwatches() {
        // The brush palette row must render MixedSlotSwatch for mix entries.
        assertTrue(
            "SlotPaletteRow must render MixedSlotSwatch for mix slots",
            viewerSrc.contains("MixedSlotSwatch"),
        )
    }

    @Test fun brushPaletteIteratesFullPalette() {
        // SlotPaletteRow must NOT cap at TARGET_SLOTS (hardcoded 4).
        assertTrue(
            "SlotPaletteRow must not use .take(AiPaintViewModel.TARGET_SLOTS) to cap the palette",
            !viewerSrc.contains("take(AiPaintViewModel.TARGET_SLOTS)"),
        )
    }

    @Test fun brushPaletteAcceptsActiveMixesParam() {
        // SlotPaletteRow must accept activeMixes parameter so it knows which mixes to render.
        assertTrue(
            "SlotPaletteRow must accept an activeMixes parameter",
            viewerSrc.contains("activeMixes"),
        )
    }
}
