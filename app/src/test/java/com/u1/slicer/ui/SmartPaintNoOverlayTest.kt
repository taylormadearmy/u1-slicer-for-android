package com.u1.slicer.ui
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart Paint result-screen selector guards.
 *
 * History: the UX overhaul briefly replaced the Smart Paint selectors with FilamentMixChipRow
 * and removed all overlays. Per explicit user direction the pre-full-spectrum compact layout was
 * then RESTORED — the small non-covering "Move to slot" [HighlightSlotPicker] overlay, the
 * "Extruders →" [SlotPaletteRow], and compact [AiPaintTreeRow] rows — each augmented with mix
 * chips + a "+". These guards lock in that restored design. The tall *covering*
 * SectionedSlotPicker (the original issue #4 complaint) stays gone.
 */
class SmartPaintNoOverlayTest {
    private val src: String = listOf(
        "app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "../app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText() ?: error("not found")

    @Test fun noCoveringSectionedSlotPickerOverlay() {
        assertFalse(
            "the tall covering SectionedSlotPicker overlay must stay removed",
            src.contains("SectionedSlotPicker("),
        )
    }

    @Test fun usesRestoredHighlightOverlayAndExtrudersRow() {
        assertTrue(
            "the model-tap selector must be the small HighlightSlotPicker overlay",
            src.contains("HighlightSlotPicker("),
        )
        assertTrue(
            "the brush/palette row must be the 'Extruders →' SlotPaletteRow",
            src.contains("SlotPaletteRow("),
        )
    }
}
