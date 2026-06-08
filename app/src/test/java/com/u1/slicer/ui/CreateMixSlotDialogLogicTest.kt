package com.u1.slicer.ui

import com.u1.slicer.data.MixWeights
import org.junit.Assert.assertEquals
import org.junit.Test

class CreateMixSlotDialogLogicTest {
    @Test fun addComponent_thenTypeWeight_keepsSumHundred() {
        var comps = listOf(1, 2); var weights = listOf(50, 50)
        comps = comps + 3; weights = MixWeights.addEven(weights)
        assertEquals(3, comps.size); assertEquals(100, weights.sum())
        weights = MixWeights.rebalanceAfterType(weights, 0, 60)
        assertEquals(60, weights[0]); assertEquals(100, weights.sum())
    }

    @Test fun removeComponent_floorOfTwo() {
        val comps = listOf(1, 2, 3); val weights = listOf(40, 40, 20)
        val (c2, w2) = removeMixComponent(comps, weights, index = 2)
        assertEquals(listOf(1, 2), c2); assertEquals(100, w2.sum())
    }
}
