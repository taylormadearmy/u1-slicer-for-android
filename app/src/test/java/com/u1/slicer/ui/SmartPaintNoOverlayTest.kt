package com.u1.slicer.ui
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class SmartPaintNoOverlayTest {
    private val src: String = listOf(
        "app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "../app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText() ?: error("not found")
    @Test fun noCoveringSectionedSlotPickerOverlay() {
        assertFalse("the covering SectionedSlotPicker overlay must be removed", src.contains("SectionedSlotPicker("))
    }
    @Test fun usesFilamentMixChipRow() {
        assertTrue("Smart Paint must use FilamentMixChipRow", src.contains("FilamentMixChipRow("))
    }
}
