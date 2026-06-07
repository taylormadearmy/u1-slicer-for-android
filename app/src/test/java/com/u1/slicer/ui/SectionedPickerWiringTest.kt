package com.u1.slicer.ui
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * Guards the per-region slot assignment wiring in AiPaintResultScreen.
 *
 * Replaced the old SectionedSlotPicker overlay checks with FilamentMixChipRow
 * wiring checks (Task 2 — Smart Paint UX consolidation). The covering overlay was
 * removed; assignment now lives in the region list rows and the brush palette row.
 */
class SectionedPickerWiringTest {
    private val src: String = listOf(
        "app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "../app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText()
        ?: error("AiPaintResultScreen.kt not found")
    @Test fun noSectionedSlotPickerOverlay() {
        assertFalse("SectionedSlotPicker covering overlay must be gone",
            src.contains("SectionedSlotPicker("))
    }
    @Test fun usesFilamentMixChipRowForBrushPalette() {
        assertTrue("FilamentMixChipRow must drive the brush palette row",
            src.contains("FilamentMixChipRow("))
    }
    @Test fun threadsOnCreateMixAndOnEditMixToTree() {
        assertTrue("onCreateMix must be passed to AiPaintTree",
            src.contains("onCreateMix = onCreateMix"))
        assertTrue("onEditMix must be passed to AiPaintTree",
            src.contains("onEditMix = onEditMix"))
    }
}
