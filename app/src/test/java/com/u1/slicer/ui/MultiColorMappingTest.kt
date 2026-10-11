package com.u1.slicer.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ensureMultiSlotMapping().
 *
 * Regression: when no extruder presets are configured, findClosestExtruder returns null
 * for every detected color, so the raw mapping collapses to [0,0,…].  Passing that into
 * applyMultiColorAssignments produces usedSlots=[0] → extruderCount=1 → single-extruder
 * G-code even for multi-colour models.
 *
 * ensureMultiSlotMapping() must detect the collapse and distribute colours sequentially
 * so the initial auto-apply always starts as multi-extruder.
 *
 * Tests marked with "MUST FAIL on STUB" fail on the current stub implementation
 * (which returns rawMapping unchanged) and pass once the real fix is in place.
 */
class MultiColorMappingTest {

    @Test
    fun `single slot never invents a second slot`() {
        assertEquals(listOf(0, 0, 0), ensureMultiSlotMapping(listOf(0, 0, 0), 3, slotCount = 1))
    }

    @Test
    fun `two slots distribute collapsed mapping within active range`() {
        val result = ensureMultiSlotMapping(listOf(0, 0, 0, 0), 4, slotCount = 2)
        assertEquals(listOf(0, 1, 0, 1), result)
        assertTrue(result.all { it in 0..1 })
    }

    // ─── Tests that MUST FAIL on the stub ─────────────────────────────────────

    /**
     * Core regression: two colours both mapped to slot 0 (empty presets scenario).
     * After fix: mapping must use at least 2 distinct slots.
     * MUST FAIL on STUB.
     */
    @Test
    fun ensureMultiSlotMapping_allZero_twoColors_distributesToTwoSlots() {
        val rawMapping = listOf(0, 0)   // both colours collapsed to slot 0 — buggy state
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 2)
        assertTrue(
            "Two-colour model with empty presets must use >= 2 slots, got $result",
            result.distinct().size >= 2
        )
    }

    /**
     * Same as above for a four-colour model (Shashibo plate 5 scenario).
     * MUST FAIL on STUB.
     */
    @Test
    fun ensureMultiSlotMapping_allZero_fourColors_distributesToFourSlots() {
        val rawMapping = listOf(0, 0, 0, 0)  // four colours, all to slot 0
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 4)
        assertEquals(
            "Four-colour model should start with 4 distinct slots when possible, got $result",
            4,
            result.distinct().size
        )
    }

    /**
     * Dragon Scale scenario: six colours, all defaulting to slot 0.
     * MUST FAIL on STUB.
     */
    @Test
    fun ensureMultiSlotMapping_allZero_sixColors_distributesToAtLeastTwoSlots() {
        val rawMapping = listOf(0, 0, 0, 0, 0, 0)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 6)
        assertTrue(
            "Six-colour model with empty presets must use >= 2 slots, got $result",
            result.distinct().size >= 2
        )
    }

    // ─── Tests that must pass on both stub and fix ─────────────────────────────

    /**
     * Single colour: mapping is [0] — no change needed.
     */
    @Test
    fun ensureMultiSlotMapping_singleColor_unchanged() {
        val rawMapping = listOf(0)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 1)
        assertEquals("Single-colour mapping should be unchanged", listOf(0), result)
    }

    /**
     * Two colours already on different slots: no change.
     */
    @Test
    fun ensureMultiSlotMapping_alreadyDistinct_unchanged() {
        val rawMapping = listOf(0, 1)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 2)
        assertEquals("Already-distinct mapping should be unchanged", rawMapping, result)
    }

    @Test
    fun ensureMultiSlotMapping_fourColorsCollapsedToThree_distributesToFourSlots() {
        val rawMapping = listOf(0, 1, 0, 2)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 4)
        assertEquals(
            "Four-colour model should start with 4 distinct slots when possible, got $result",
            listOf(0, 1, 2, 3),
            result
        )
    }

    /**
     * Dragon plate 3 style regression: a three-colour plate can still collapse to only
     * two slots if closest-colour matching picks duplicates. The initial mapping should
     * preserve all 3 colours across 3 slots when 4 physical slots are available.
     */
    @Test
    fun ensureMultiSlotMapping_threeColorsCollapsedToTwo_distributesToThreeSlots() {
        val rawMapping = listOf(0, 1, 1)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 3)
        assertEquals(
            "Three-colour model should start with 3 distinct slots when possible, got $result",
            3,
            result.distinct().size
        )
    }

    /**
     * All-same non-zero slot with 2+ colours: should distribute.
     * MUST FAIL on STUB.
     */
    @Test
    fun ensureMultiSlotMapping_allSameNonZeroSlot_distributes() {
        val rawMapping = listOf(2, 2, 2)  // all map to slot 2 (non-zero)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 3)
        assertTrue(
            "All-same-slot mapping must be distributed for 3-colour model, got $result",
            result.distinct().size >= 2
        )
    }

    @Test
    fun ensureMultiSlotMapping_fourColorsCollapsedToTwo_distributesToFourSlots() {
        val rawMapping = listOf(0, 1, 0, 1)
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 4)
        assertEquals(
            "Four-colour model should not collapse to two slots, got $result",
            listOf(0, 1, 2, 3),
            result
        )
    }
}
