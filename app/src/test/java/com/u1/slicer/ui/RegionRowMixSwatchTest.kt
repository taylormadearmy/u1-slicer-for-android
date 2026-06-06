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
        // Guard the actual mix-leaf branch, not just that MixedSlotSwatch appears (it was already
        // used for parent nodes pre-Phase-B). The branch keys a leaf's mix slot off numPhysical
        // and looks the row up in the activeMixes list by (slot - numPhysical).
        assertTrue("row must render the two-tone MixedSlotSwatch",
            row.contains("MixedSlotSwatch"))
        assertTrue("row must branch leaf mix slots on numPhysical",
            row.contains("primarySlot >= numPhysical"))
        assertTrue("row must resolve the mix row via activeMixes[slot - numPhysical]",
            row.contains("activeMixes.getOrNull(primarySlot - numPhysical)"))
    }

    @Test fun rowBranchesOnNumPhysical() {
        val row = read("AiPaintTreeRow.kt")
        assertTrue(row.contains("numPhysical"))
    }
}
