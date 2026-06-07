package com.u1.slicer.ui
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-region / brush slot-assignment wiring in AiPaintResultScreen, for the RESTORED compact
 * Smart Paint layout (post-overhaul reversal, per user direction). The covering
 * SectionedSlotPicker is gone; the brush/palette row is the "Extruders →" [SlotPaletteRow];
 * mix create/edit are threaded into the region tree.
 */
class SectionedPickerWiringTest {
    private val src: String = listOf(
        "app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "../app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText()
        ?: error("AiPaintResultScreen.kt not found")

    @Test fun noSectionedSlotPickerOverlay() {
        assertFalse(
            "SectionedSlotPicker covering overlay must stay gone",
            src.contains("SectionedSlotPicker("),
        )
    }

    @Test fun usesSlotPaletteRowForBrushPalette() {
        assertTrue(
            "SlotPaletteRow must drive the 'Extruders →' palette row",
            src.contains("SlotPaletteRow("),
        )
    }

    @Test fun threadsOnCreateMixAndOnEditMixToTree() {
        assertTrue("onCreateMix must be passed to AiPaintTree", src.contains("onCreateMix = onCreateMix"))
        assertTrue("onEditMix must be passed to AiPaintTree", src.contains("onEditMix = onEditMix"))
    }
}
