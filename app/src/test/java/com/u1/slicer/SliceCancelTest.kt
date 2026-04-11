package com.u1.slicer

import com.u1.slicer.data.SliceResult
import org.junit.Assert.*
import org.junit.Test

class SliceCancelTest {

    @Test
    fun `SliceResult cancelled field defaults to false`() {
        val result = SliceResult(
            success = true,
            cancelled = false,
            errorMessage = "",
            gcodePath = "/tmp/out.gcode",
            totalLayers = 100,
            estimatedTimeSeconds = 3600f,
            estimatedFilamentMm = 1000f,
            estimatedFilamentGrams = 5f
        )
        assertFalse(result.cancelled)
    }

    @Test
    fun `SliceResult cancelled true when slice was cancelled`() {
        val result = SliceResult(
            success = false,
            cancelled = true,
            errorMessage = "Cancelled by user",
            gcodePath = "",
            totalLayers = 0,
            estimatedTimeSeconds = 0f,
            estimatedFilamentMm = 0f,
            estimatedFilamentGrams = 0f
        )
        assertTrue(result.cancelled)
        assertFalse(result.success)
    }

    @Test
    fun `SlicerState Cancelling is distinct from Slicing`() {
        val cancelling = SlicerViewModel.SlicerState.Cancelling
        val slicing = SlicerViewModel.SlicerState.Slicing(50, "Processing...")
        assertNotEquals(cancelling, slicing)
    }

    @Test
    fun `SlicerState Cancelling is an object singleton`() {
        val a = SlicerViewModel.SlicerState.Cancelling
        val b = SlicerViewModel.SlicerState.Cancelling
        assertSame(a, b)
    }

    @Test
    fun `cancelled SliceResult has empty gcode path`() {
        val result = SliceResult(
            success = false,
            cancelled = true,
            errorMessage = "Cancelled by user",
            gcodePath = "",
            totalLayers = 0,
            estimatedTimeSeconds = 0f,
            estimatedFilamentMm = 0f,
            estimatedFilamentGrams = 0f
        )
        assertTrue(result.gcodePath.isEmpty())
    }
}
