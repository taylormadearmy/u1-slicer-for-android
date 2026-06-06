package com.u1.slicer.ui
import org.junit.Assert.assertTrue
import org.junit.Test
class SectionedPickerWiringTest {
    private val src: String = listOf(
        "app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "../app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText()
        ?: error("AiPaintResultScreen.kt not found")
    @Test fun usesSectionedSlotPickerForRegionAssignment() {
        assertTrue("SectionedSlotPicker should drive per-region assignment",
            src.contains("SectionedSlotPicker("))
    }
    @Test fun filtersLibraryToMatchActiveOrder() {
        // the invariant filter must be present (id-not-in-project + extruder bound)
        assertTrue(src.contains("componentA <= numPhysical") || src.contains("componentA <= numPhysical"))
        assertTrue(src.contains("none { it.id =="))
    }
}
