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

    @Test fun `large primeVolume overrides height-based minimum`() {
        // height-based min for 50mm model is 20mm; prime volume of 80mm should win
        assertEquals(80f, WipeTowerDepthEstimator.estimateDepth(50f, 80f), 0.01f)
    }

    @Test fun `height-based minimum wins over small primeVolume`() {
        // height-based min for 50mm model is 20mm; prime volume of 5mm loses
        assertEquals(20f, WipeTowerDepthEstimator.estimateDepth(50f, 5f), 0.01f)
    }

    @Test fun `zero primeVolume behaves same as no primeVolume`() {
        assertEquals(WipeTowerDepthEstimator.estimateDepth(175f), WipeTowerDepthEstimator.estimateDepth(175f, 0f), 0.01f)
    }
}
