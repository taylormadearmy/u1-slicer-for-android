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
    fun ensureMultiSlotMapping_allZero_fourColors_distributesToAtLeastTwoSlots() {
        val rawMapping = listOf(0, 0, 0, 0)  // four colours, all to slot 0
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 4)
        assertTrue(
            "Four-colour model with empty presets must use >= 2 slots, got $result",
            result.distinct().size >= 2
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

    /**
     * Four colours with two different slots already: no change.
     */
    @Test
    fun ensureMultiSlotMapping_partiallyDistinct_unchanged() {
        val rawMapping = listOf(0, 1, 0, 2)  // three distinct slots
        val result = ensureMultiSlotMapping(rawMapping, colorCount = 4)
        assertEquals("Mapping with 3 distinct slots should be unchanged", rawMapping, result)
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
}
