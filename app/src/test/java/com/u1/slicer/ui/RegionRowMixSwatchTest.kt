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
        // used for parent nodes pre-Phase-B). M4/#2: the branch now keys a leaf's mix slot off
        // mixBase = maxOf(numPhysical, canonicalCount) and looks the row up by (slot - mixBase),
        // so a mix id never collides with a canonical file-filament index.
        assertTrue("row must render the MixedSlotSwatch",
            row.contains("MixedSlotSwatch"))
        assertTrue("row must branch leaf mix slots on mixBase",
            row.contains("primarySlot >= mixBase"))
        assertTrue("row must resolve the mix row via activeMixes[primarySlot - mixBase]",
            row.contains("activeMixes.getOrNull(primarySlot - mixBase)"))
    }

    @Test fun rowBranchesOnNumPhysical() {
        val row = read("AiPaintTreeRow.kt")
        assertTrue(row.contains("numPhysical"))
    }
}
