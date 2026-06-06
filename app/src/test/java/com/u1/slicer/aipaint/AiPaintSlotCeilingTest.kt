package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPaintSlotCeilingTest {
    private val src = java.io.File(
        "src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt"
    ).readText()

    @Test fun guardsUseDynamicCeiling_notTargetSlots() {
        val badGuards = Regex("""!in 0 until TARGET_SLOTS""").findAll(src).count()
        assertEquals(0, badGuards)
    }

    @Test fun slotCeilingProviderExists() {
        assert(src.contains("slotCeiling")) { "expected a slotCeiling provider/field" }
    }
}
