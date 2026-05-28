package com.u1.slicer.ui

import org.junit.Assert.*
import org.junit.Test

class SelectionStateMachineTest {

    @Test
    fun defaultSelection_isEmpty() {
        val s = ObjectSelection()
        assertNull(s.objectIndex)
        assertNull(s.volumeIndex)
    }

    @Test
    fun withObject_setsIndex_clearsVolume() {
        val s = ObjectSelection(objectIndex = 1, volumeIndex = 2).withObject(3)
        assertEquals(3, s.objectIndex)
        assertNull(s.volumeIndex)
    }

    @Test
    fun withVolume_noObject_isNoOp() {
        val s = ObjectSelection().withVolume(2)
        assertNull(s.objectIndex)
        assertNull(s.volumeIndex)
    }

    @Test
    fun withVolume_objectSelected_setsBoth() {
        val s = ObjectSelection().withObject(1).withVolume(0)
        assertEquals(1, s.objectIndex)
        assertEquals(0, s.volumeIndex)
    }

    @Test
    fun withVolume_clearsToNull() {
        val s = ObjectSelection().withObject(1).withVolume(0).withVolume(null)
        assertEquals(1, s.objectIndex)
        assertNull(s.volumeIndex)
    }

    @Test
    fun cleared_clearsBoth() {
        val s = ObjectSelection().withObject(5).withVolume(2).cleared()
        assertNull(s.objectIndex)
        assertNull(s.volumeIndex)
    }

    @Test
    fun onSplit_atSplitIndex_advancesToFirstNew() {
        // Before: object 5 selected. Split 5 into 3 pieces → new pieces at 5,6,7.
        // Selection auto-points to the first new piece (5).
        val s = ObjectSelection().withObject(5).onSplit(removedIdx = 5, addedCount = 3)
        assertEquals(5, s.objectIndex)
    }

    @Test
    fun onSplit_aboveSplitIndex_shiftsUpByAddedCountMinusOne() {
        // Before: object 7 selected. Split 5 into 3 pieces → net +2.
        // Old 7 now at 9.
        val s = ObjectSelection().withObject(7).onSplit(removedIdx = 5, addedCount = 3)
        assertEquals(9, s.objectIndex)
    }

    @Test
    fun onSplit_belowSplitIndex_unchanged() {
        val s = ObjectSelection().withObject(2).onSplit(removedIdx = 5, addedCount = 3)
        assertEquals(2, s.objectIndex)
    }

    @Test
    fun onSplit_noSelection_unchanged() {
        val s = ObjectSelection().onSplit(removedIdx = 5, addedCount = 3)
        assertNull(s.objectIndex)
    }

    @Test
    fun onSplit_splitIntoTwo_shiftIsOne() {
        // Above split: 7 + (2 - 1) = 8.
        val s = ObjectSelection().withObject(7).onSplit(removedIdx = 5, addedCount = 2)
        assertEquals(8, s.objectIndex)
    }
}
