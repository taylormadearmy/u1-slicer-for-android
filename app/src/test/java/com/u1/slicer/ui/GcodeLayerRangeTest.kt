package com.u1.slicer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B129 — the G-code preview layer slider must keep its position when the user
 * leaves the preview (to move/rotate the model) and returns, and must reset to
 * the full range only when a genuinely new slice arrives.
 *
 * The persistence itself lives in the ViewModel (`previewLayerRange` StateFlow)
 * and Compose navigation wiring (structurally guarded in
 * [GcodeViewer3DScreenLayerRangeTest]). This unit test pins the pure contract
 * that decides the slider's INITIAL range from the saved value:
 *
 *   - saved == null  -> full range (0 .. layerCount-1)  [reset / new slice]
 *   - saved != null  -> the saved range, clamped to the current layer count
 *                       [preserved across navigation]
 */
class GcodeLayerRangeTest {

    @Test
    fun nullSaved_returnsFullRange() {
        assertEquals(0 to 9, resolveInitialLayerRange(null, 10))
    }

    @Test
    fun savedRange_isPreserved() {
        assertEquals(3 to 7, resolveInitialLayerRange(3 to 7, 10))
    }

    @Test
    fun savedRange_clampedToShorterLayerCount() {
        // A new slice with fewer layers should never leave the slider past the end.
        assertEquals(3 to 4, resolveInitialLayerRange(3 to 7, 5))
    }

    @Test
    fun savedRange_lowAboveMax_clampsBoth() {
        assertEquals(4 to 4, resolveInitialLayerRange(8 to 12, 5))
    }

    @Test
    fun savedRange_negativeAndOver_clampsToBounds() {
        assertEquals(0 to 9, resolveInitialLayerRange(-1 to 100, 10))
    }

    @Test
    fun singleLayer_returnsZeroZero() {
        assertEquals(0 to 0, resolveInitialLayerRange(null, 1))
        assertEquals(0 to 0, resolveInitialLayerRange(3 to 7, 1))
    }

    @Test
    fun zeroLayers_returnsZeroZero() {
        assertEquals(0 to 0, resolveInitialLayerRange(null, 0))
    }

    // ── B143: computeDisplayLayer — slider position → "Layer N/M" label ──

    @Test
    fun displayLayer_topOfSlider_reachesFullCount() {
        // B143 regression: 122-layer print, slider at max (index 121) must
        // read "Layer 122/122", not "Layer 121/122".
        assertEquals(122, computeDisplayLayer(121, 122, 122))
    }

    @Test
    fun displayLayer_bottomOfSlider_isLayerOne() {
        assertEquals(1, computeDisplayLayer(0, 122, 122))
    }

    @Test
    fun displayLayer_midSlider_mapsOneBased() {
        // Index 60 = the 61st parsed layer.
        assertEquals(61, computeDisplayLayer(60, 122, 122))
    }

    @Test
    fun displayLayer_slicerCountDiffers_scalesAndReachesTop() {
        // Parsed 121 layers but slicer reports 122: top of slider still maps
        // to the full display count.
        assertEquals(122, computeDisplayLayer(120, 122, 121))
        assertEquals(1, computeDisplayLayer(0, 122, 121))
    }

    @Test
    fun displayLayer_degenerateCounts_returnOne() {
        assertEquals(1, computeDisplayLayer(0, 0, 0))
        assertEquals(1, computeDisplayLayer(5, 10, 0))
        assertEquals(1, computeDisplayLayer(5, 0, 10))
    }
}
