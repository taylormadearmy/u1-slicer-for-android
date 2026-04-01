package com.u1.slicer

import com.u1.slicer.data.WipeTowerDepthEstimator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that wipeTowerClampBounds() uses estimated depth (not width) for the Y axis.
 *
 * The regression: depth is 20mm for short models (≤100mm), but pre-slice clamp was
 * using wipeTowerWidth (30mm) for both axes, creating a 10mm discrepancy with the
 * drag clamp in the UI.
 */
class WipeTowerClampTest {

    @Test fun `maxY uses estimated depth not width for short model`() {
        val modelHeightMm = 6f  // F1 calendar is very flat
        val bedSize = 270f
        val towerWidth = 60f
        val estimatedDepth = WipeTowerDepthEstimator.estimateDepth(modelHeightMm)
        val (maxX, maxY) = wipeTowerClampBounds(
            bedSizeX = bedSize, bedSizeY = bedSize,
            towerWidth = towerWidth, towerDepth = estimatedDepth
        )
        assertEquals(bedSize - towerWidth, maxX, 0.01f)   // X uses width
        assertEquals(bedSize - estimatedDepth, maxY, 0.01f) // Y uses depth (20mm, not 30mm)
    }

    @Test fun `maxY uses estimated depth for tall model`() {
        val modelHeightMm = 250f  // at cap
        val bedSize = 270f
        val towerWidth = 60f
        val estimatedDepth = WipeTowerDepthEstimator.estimateDepth(modelHeightMm) // 40mm
        val (maxX, maxY) = wipeTowerClampBounds(
            bedSizeX = bedSize, bedSizeY = bedSize,
            towerWidth = towerWidth, towerDepth = estimatedDepth
        )
        assertEquals(bedSize - towerWidth, maxX, 0.01f)
        assertEquals(bedSize - estimatedDepth, maxY, 0.01f) // 230mm, not 240mm
    }

    @Test fun `clamp moves out-of-bounds tower inside bed`() {
        val bounds = wipeTowerClampBounds(
            bedSizeX = 270f, bedSizeY = 270f,
            towerWidth = 60f, towerDepth = 20f
        )
        val clampedX = 300f.coerceIn(0f, bounds.first)
        val clampedY = 260f.coerceIn(0f, bounds.second)
        assertEquals(210f, clampedX, 0.01f) // 270 - 60
        assertEquals(250f, clampedY, 0.01f) // 270 - 20
    }
}
