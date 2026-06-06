package com.u1.slicer.ui

import org.junit.Assert.assertFalse
import org.junit.Test

class AiPaintPaletteWiringTest {
    private val src: String = listOf(
        "app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        "../app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }
        ?.readText() ?: error("AiPaintResultScreen.kt not found from ${java.io.File(".").absolutePath}")

    @Test fun slotPaletteNotHardCappedAtTargetSlots() {
        assertFalse(
            "slotPalette still iterates only physical TARGET_SLOTS",
            src.contains("(0 until com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS).map { slot ->")
        )
    }
    @Test fun paletteUsesMixColours() {
        assert(src.contains("naiveBlendHex") || src.contains("activeOrder"))
    }
}
