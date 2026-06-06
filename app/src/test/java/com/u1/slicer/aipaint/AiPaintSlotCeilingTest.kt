package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPaintSlotCeilingTest {
    private val src: String = listOf(
        "app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt",
        "src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt",
        "../app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }
        ?.readText() ?: error("AiPaintViewModel.kt not found from ${java.io.File(".").absolutePath}")

    @Test fun guardsUseDynamicCeiling_notTargetSlots() {
        val badGuards = Regex("""!in 0 until TARGET_SLOTS""").findAll(src).count()
        assertEquals(0, badGuards)
    }

    @Test fun slotCeilingProviderExists() {
        assert(src.contains("slotCeiling")) { "expected a slotCeiling provider/field" }
    }
}
