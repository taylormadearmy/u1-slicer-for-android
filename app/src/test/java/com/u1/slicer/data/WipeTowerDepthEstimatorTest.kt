package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WipeTowerDepthEstimatorTest {

    @Test fun `below 100mm returns minimum 20mm`() {
        assertEquals(20f, WipeTowerDepthEstimator.estimateDepth(0f), 0.01f)
        assertEquals(20f, WipeTowerDepthEstimator.estimateDepth(50f), 0.01f)
    }

    @Test fun `at 100mm returns 20mm`() {
        assertEquals(20f, WipeTowerDepthEstimator.estimateDepth(100f), 0.01f)
    }

    @Test fun `at 175mm returns 30mm midpoint`() {
        assertEquals(30f, WipeTowerDepthEstimator.estimateDepth(175f), 0.01f)
    }

    @Test fun `at 250mm returns 40mm`() {
        assertEquals(40f, WipeTowerDepthEstimator.estimateDepth(250f), 0.01f)
    }

    @Test fun `above 250mm clamped to 40mm`() {
        assertEquals(40f, WipeTowerDepthEstimator.estimateDepth(300f), 0.01f)
        assertEquals(40f, WipeTowerDepthEstimator.estimateDepth(1000f), 0.01f)
    }
}
