package com.u1.slicer.aipaint
import org.junit.Assert.assertEquals
import org.junit.Test
class ImportAutoAssignTest {
    @Test fun assignsClosestExistingSlot_andCountsUnmatched() {
        val targets = listOf("#0000FF", "#FFFF00", "#FF00FF")
        val palette = listOf("#0000FF", "#FFFF00")
        val (slots, unmatched) = autoAssignRegions(targets, palette, deltaThreshold = 25.0)
        assertEquals(0, slots[0]); assertEquals(1, slots[1])     // exact matches
        assertEquals(1, unmatched)                               // magenta exceeds threshold
        assertEquals(0, slots[2])                                // magenta still maps to its closest (blue here)
    }
    @Test fun nothingUnmatchedWhenAllClose() {
        val (_, unmatched) = autoAssignRegions(listOf("#0000FF","#FFFF00"), listOf("#0000FF","#FFFF00"))
        assertEquals(0, unmatched)
    }
}
