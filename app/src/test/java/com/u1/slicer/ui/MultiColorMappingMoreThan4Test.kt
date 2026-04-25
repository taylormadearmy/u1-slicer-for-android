package com.u1.slicer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ensureMultiSlotMapping]'s `colorCount > 4` branch — files with
 * more model colours than physical extruders. The legacy `colorCount <= 4`
 * branch wholesale-redistributes; this branch uses duplicate-only
 * redistribution so first-occurrence assignments from `findClosestExtruder`
 * survive when the user has populated some preset slots intentionally.
 *
 * F1 calendar regression: 5 model colours auto-mapped to `[0, 0, 3, 3, 0]`
 * on a device with only two populated extruder presets. Previously the
 * `colorCount <= 4` guard skipped redistribution and the bad mapping shipped
 * to the slicer (only 2 active tools). Correct behaviour: redistribute
 * duplicates while preserving the unique 0 and 3 hits → `[0, 1, 3, 2, 0]`.
 */
class MultiColorMappingMoreThan4Test {

    @Test
    fun f1Calendar_fiveColours_collapsedToTwoSlots_redistributesAcrossAllFour() {
        val rawMapping = listOf(0, 0, 3, 3, 0)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 5)
        assertEquals(
            "F1 [0,0,3,3,0] should redistribute to [0,1,3,2,0]: positions 0+2 keep their match-by-color hits, duplicates fill unused 1+2",
            listOf(0, 1, 3, 2, 0),
            result
        )
        assertEquals("All 4 physical slots in use", setOf(0, 1, 2, 3), result.toSet())
    }

    @Test
    fun fiveColours_allCollapsedToOneSlot_redistributesWithSlotZeroHit() {
        val rawMapping = listOf(2, 2, 2, 2, 2)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 5)
        // position 0 keeps the 2; positions 1..3 take unused 0,1,3; position 4 is leftover.
        assertEquals(listOf(2, 0, 1, 3, 2), result)
        assertEquals(setOf(0, 1, 2, 3), result.toSet())
    }

    @Test
    fun sevenColours_alreadyUsesAllFourSlots_leftAlone() {
        // H2C benchy shape: distinct slots = {0,1,2,3} = 4 already; redistribution
        // is unnecessary and would discard the user's match-by-color choices.
        val rawMapping = listOf(2, 0, 3, 2, 0, 1, 0)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 7)
        assertEquals(rawMapping, result)
    }

    @Test
    fun fiveColours_allFourSlotsAlreadyDistinct_leftAlone() {
        val rawMapping = listOf(0, 1, 2, 3, 0)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 5)
        assertEquals(rawMapping, result)
    }

    @Test
    fun sevenColours_collapsedToTwo_redistributesDuplicatesOnly() {
        val rawMapping = listOf(0, 0, 0, 3, 3, 3, 0)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 7)
        // Position 0 (first 0) and position 3 (first 3) preserved; duplicates
        // take unused [1, 2] in order; trailing duplicates stay as-is once
        // unused is exhausted.
        assertEquals(listOf(0, 1, 2, 3, 3, 3, 0), result)
        assertTrue("All four physical slots reached", result.toSet().containsAll(setOf(0, 1, 2, 3)))
    }
}
