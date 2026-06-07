package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MixWeightsTest {
    @Test fun even_splitsToHundredWithRemainderOnFirst() {
        assertEquals(listOf(34, 33, 33), MixWeights.even(3))
        assertEquals(listOf(50, 50), MixWeights.even(2))
        assertEquals(listOf(25, 25, 25, 25), MixWeights.even(4))
    }

    @Test fun normalize_scalesToHundredAndKeepsMinOne() {
        assertEquals(100, MixWeights.normalize(listOf(1, 1, 1)).sum())
        assertEquals(100, MixWeights.normalize(listOf(60, 30, 10)).sum())
        assertEquals(true, MixWeights.normalize(listOf(99, 1, 1)).all { it >= 1 })
        assertEquals(listOf(34, 33, 33), MixWeights.normalize(listOf(1, 1, 1)))
    }

    @Test fun rebalanceAfterType_lockedValueExactOthersScaleToFill() {
        val out = MixWeights.rebalanceAfterType(listOf(33, 33, 34), index = 0, value = 60)
        assertEquals(60, out[0])
        assertEquals(100, out.sum())
        assertEquals(true, out.all { it >= 1 })
        assertEquals(listOf(60, 20, 20), out)
    }

    @Test fun rebalanceAfterType_clampsAndLeavesMinOneForOthers() {
        val out = MixWeights.rebalanceAfterType(listOf(50, 50), index = 0, value = 100)
        assertEquals(99, out[0])
        assertEquals(1, out[1])
        assertEquals(100, out.sum())
    }

    @Test fun rebalanceAfterDrag_movesBudgetBetweenTwoAdjacentOnly() {
        val out = MixWeights.rebalanceAfterDrag(listOf(30, 40, 30), leftIndex = 0, leftValue = 40)
        assertEquals(40, out[0])
        assertEquals(30, out[1])
        assertEquals(30, out[2])
        assertEquals(100, out.sum())
    }

    @Test fun addEven_appendsAndTrimsExistingProportionally() {
        val out = MixWeights.addEven(listOf(50, 50))
        assertEquals(3, out.size)
        assertEquals(100, out.sum())
        assertEquals(true, out.all { it >= 1 })
        assertEquals(listOf(34, 33, 33), out)
    }

    @Test fun remove_dropsIndexAndRenormalizes() {
        val out = MixWeights.remove(listOf(60, 30, 10), index = 2)
        assertEquals(2, out.size)
        assertEquals(100, out.sum())
        val out0 = MixWeights.remove(listOf(60, 30, 10), 0)
        assertEquals(2, out0.size)
        assertEquals(100, out0.sum())
    }

    @Test fun even_singleComponentIsHundred() {
        assertEquals(listOf(100), MixWeights.even(1))
    }

    @Test fun rebalanceAfterDrag_clampsAtBoundaries() {
        val base = listOf(30, 40, 30)
        val low = MixWeights.rebalanceAfterDrag(base, leftIndex = 0, leftValue = 1)
        assertEquals(1, low[0])
        assertEquals(69, low[1])
        assertEquals(100, low.sum())
        val high = MixWeights.rebalanceAfterDrag(base, leftIndex = 0, leftValue = 100)
        assertEquals(69, high[0])
        assertEquals(1, high[1])
        assertEquals(100, high.sum())
    }

    @Test fun even_alwaysSumsHundred_forTwoToFour() {
        (2..4).forEach { assertEquals(100, MixWeights.even(it).sum()) }
    }

    @Test fun encodeIds_compactUnderTenElseSlash() {
        assertEquals("123", MixWeights.encodeIds(listOf(1, 2, 3)))
        assertEquals("1/12/3", MixWeights.encodeIds(listOf(1, 12, 3)))
        assertEquals("/12", MixWeights.encodeIds(listOf(12)))
    }

    @Test fun encodeWeights_slashJoined() {
        assertEquals("50/30/20", MixWeights.encodeWeights(listOf(50, 30, 20)))
    }
}
