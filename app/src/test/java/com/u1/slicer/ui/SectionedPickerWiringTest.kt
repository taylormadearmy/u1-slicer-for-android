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
        assertTrue("library filter must bound componentA", src.contains("componentA <= numPhysical"))
        assertTrue("library filter must bound componentB", src.contains("componentB <= numPhysical"))
        assertTrue("library filter must dedup against project ids", src.contains("none { it.id =="))
    }
}
