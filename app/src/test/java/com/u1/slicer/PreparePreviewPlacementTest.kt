package com.u1.slicer

import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.SliceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparePreviewPlacementTest {

    @Test
    fun `native 3mf preview keeps object placement and wipe tower available`() {
        val config = buildPreparePreviewPlacementConfig(
            nativeThreeMfPreview = true,
            objectPositionsPresent = true,
            onPositionsChangedPresent = true,
            wipeTowerEnabled = true
        )

        assertTrue(config.objectPlacementEnabled)
        assertTrue(config.wipeTowerVisible)
    }

    @Test
    fun `stl preview keeps wipe tower tied to object placement mode`() {
        val config = buildPreparePreviewPlacementConfig(
            nativeThreeMfPreview = false,
            objectPositionsPresent = true,
            onPositionsChangedPresent = true,
            wipeTowerEnabled = true
        )

        assertTrue(config.objectPlacementEnabled)
        assertTrue(config.wipeTowerVisible)
    }

    @Test
    fun `wipe tower still shows when placement callbacks are absent`() {
        val config = buildPreparePreviewPlacementConfig(
            nativeThreeMfPreview = true,
            objectPositionsPresent = false,
            onPositionsChangedPresent = false,
            wipeTowerEnabled = true
        )

        assertFalse(config.objectPlacementEnabled)
        assertTrue(config.wipeTowerVisible)
    }

    @Test
    fun `slice complete keeps cached model info for large preview fallback`() {
        val cached = ModelInfo("cached.3mf", "3mf", 120f, 80f, 40f, 2_000_000, 1, true)
        val resolved = resolvePreparePreviewModelInfo(
            SlicerViewModel.SlicerState.SliceComplete(
                SliceResult(true, false, "", "/tmp/out.gcode", 42, 0f, 0f, 0f)
            ),
            cached
        )

        assertEquals(cached, resolved)
    }

    @Test
    fun `model loaded state prefers fresh info over cached info`() {
        val cached = ModelInfo("cached.3mf", "3mf", 120f, 80f, 40f, 2_000_000, 1, true)
        val fresh = ModelInfo("fresh.3mf", "3mf", 10f, 20f, 30f, 1234, 1, false)

        val resolved = resolvePreparePreviewModelInfo(
            SlicerViewModel.SlicerState.ModelLoaded(fresh),
            cached
        )

        assertEquals(fresh, resolved)
    }
}
