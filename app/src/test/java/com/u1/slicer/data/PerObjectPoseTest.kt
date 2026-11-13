package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class PerObjectPoseTest {

    @Test
    fun default_isIdentity() {
        assertTrue(PerObjectPose().isIdentity())
    }

    @Test
    fun nonZeroRotation_notIdentity() {
        assertFalse(PerObjectPose(rotXDeg = 1f).isIdentity())
        assertFalse(PerObjectPose(rotYDeg = 1f).isIdentity())
        assertFalse(PerObjectPose(rotZDeg = 1f).isIdentity())
    }

    @Test
    fun nonUnitScale_notIdentity() {
        assertFalse(PerObjectPose(scaleX = 1.1f).isIdentity())
        assertFalse(PerObjectPose(scaleY = 0.9f).isIdentity())
        assertFalse(PerObjectPose(scaleZ = 2f).isIdentity())
    }

    @Test
    fun remap_emptyMap_returnsEmpty() {
        val out = remapPerObjectMapOnSplit(emptyMap<Int, String>(), 5, 3)
        assertTrue(out.isEmpty())
    }

    @Test
    fun remap_belowSplitIndex_unchanged() {
        val before = mapOf(0 to "a", 4 to "b")
        val after = remapPerObjectMapOnSplit(before, removedIdx = 5, addedCount = 3)
        assertEquals("a", after[0])
        assertEquals("b", after[4])
    }

    @Test
    fun remap_atSplitIndex_dropsKey() {
        val before = mapOf(5 to "split-me")
        val after = remapPerObjectMapOnSplit(before, removedIdx = 5, addedCount = 3)
        assertNull(after[5])
        assertTrue(after.isEmpty())
    }

    @Test
    fun remap_aboveSplitIndex_shiftsUpByAddedCountMinusOne() {
        val before = mapOf(7 to "above", 9 to "high")
        val after = remapPerObjectMapOnSplit(before, removedIdx = 5, addedCount = 3)
        // shift = 3 - 1 = 2
        assertNull(after[7])
        assertEquals("above", after[9])
        assertEquals("high", after[11])
    }

    @Test
    fun remap_mixedMap_correctEverywhere() {
        val before = mapOf(0 to "a", 4 to "b", 5 to "drop", 6 to "c", 9 to "d")
        val after = remapPerObjectMapOnSplit(before, removedIdx = 5, addedCount = 3)
        // shift = 2 for keys > 5
        assertEquals("a", after[0])
        assertEquals("b", after[4])
        assertNull(after[5])
        assertEquals("c", after[8])
        assertEquals("d", after[11])
        assertEquals(4, after.size)
    }

    @Test
    fun remap_addedCountOne_isStillShiftZero() {
        // Edge case: split that produced one piece (caller should treat as no-split,
        // but the helper itself must still behave correctly).
        val before = mapOf(7 to "x")
        val after = remapPerObjectMapOnSplit(before, removedIdx = 5, addedCount = 1)
        // shift = 0 → key 7 stays at 7.
        assertEquals("x", after[7])
    }

    @Test
    fun remapOnDelete_dropsDeletedObjectAndShiftsLaterObjectsDown() {
        val before = mapOf(0 to "first", 2 to "deleted", 3 to "later")

        val after = remapPerObjectMapOnDelete(before, removedIdx = 2)

        assertEquals(mapOf(0 to "first", 2 to "later"), after)
    }
}
