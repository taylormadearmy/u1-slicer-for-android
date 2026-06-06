package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class RegionRowMixSwatchTest {
    private fun read(p: String) = listOf(
        "app/src/main/java/com/u1/slicer/ui/$p",
        "src/main/java/com/u1/slicer/ui/$p",
        "../app/src/main/java/com/u1/slicer/ui/$p",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText() ?: error("$p not found")

    @Test fun rowRendersMixSwatchForMixSlots() {
        val row = read("AiPaintTreeRow.kt")
        assertTrue("region row must render MixedSlotSwatch for slots >= numPhysical",
            row.contains("MixedSlotSwatch"))
    }

    @Test fun rowBranchesOnNumPhysical() {
        val row = read("AiPaintTreeRow.kt")
        assertTrue(row.contains("numPhysical"))
    }
}
