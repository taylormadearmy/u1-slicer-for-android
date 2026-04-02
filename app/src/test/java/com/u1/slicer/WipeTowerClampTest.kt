package com.u1.slicer

import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.data.WipeTowerDepthEstimator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that wipeTowerClampBounds() uses estimated depth (not width) for the Y axis.
 *
 * The regression: depth is 20mm for short models (≤100mm), but pre-slice clamp was
 * using wipeTowerWidth (60mm) for both axes, creating a 40mm discrepancy with the
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
        assertEquals(bedSize - estimatedDepth, maxY, 0.01f) // Y uses depth (20mm, not width 60mm)
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

    @Test fun `resolveWipeTowerWidth returns override width when override is active`() {
        val config = SliceConfig(wipeTowerWidth = 60f)
        val overrides = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.OVERRIDE, 45f))
        assertEquals(45f, resolveWipeTowerWidth(config, overrides), 0.01f)
    }

    @Test fun `resolveWipeTowerWidth returns config width when override is USE_FILE`() {
        val config = SliceConfig(wipeTowerWidth = 60f)
        val overrides = SlicingOverrides(primeTowerWidth = OverrideValue(OverrideMode.USE_FILE, null))
        assertEquals(60f, resolveWipeTowerWidth(config, overrides), 0.01f)
    }

    @Test fun `resolveWipeTowerWidth returns config width when override value is null`() {
        val config = SliceConfig(wipeTowerWidth = 60f)
        val overrides = SlicingOverrides()
        assertEquals(60f, resolveWipeTowerWidth(config, overrides), 0.01f)
    }

    @Test fun `resolveWipeTowerDepth uses override prime volume when active`() {
        val overrides = SlicingOverrides(primeVolume = OverrideValue(OverrideMode.OVERRIDE, 80))
        val depth = resolveWipeTowerDepth(modelHeightMm = 50f, overrides = overrides)
        assertEquals(80f, depth, 0.01f)
    }

    @Test fun `resolveWipeTowerDepth falls back to height-based estimate when USE_FILE`() {
        val overrides = SlicingOverrides(primeVolume = OverrideValue(OverrideMode.USE_FILE, null))
        val depth = resolveWipeTowerDepth(modelHeightMm = 50f, overrides = overrides)
        assertEquals(20f, depth, 0.01f)
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
