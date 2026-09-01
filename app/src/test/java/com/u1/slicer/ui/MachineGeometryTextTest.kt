package com.u1.slicer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MachineGeometryTextTest {

    @Test
    fun bedFootprint_formatsProfileDimensions() {
        assertEquals("180×300 mm", formatBedFootprint(180f, 300f))
        assertEquals("270×270 mm", formatBedFootprint(270f, 270f))
    }

    @Test
    fun buildVolume_preservesU1CopyAndFormatsOtherProfiles() {
        assertEquals("270 x 270 x 270 mm", formatBuildVolume(270f, 270f, 270f))
        assertEquals("300 x 280 x 400 mm", formatBuildVolume(300f, 280f, 400f))
    }

    @Test
    fun buildVolumeWarning_includesActiveDimensionsAndExceededAxes() {
        assertNull(formatBuildVolumeWarning(emptyList(), 300f, 280f, 400f))
        assertEquals(
            "Exceeds 300 x 280 x 400 mm build volume on X/Z",
            formatBuildVolumeWarning(listOf("X", "Z"), 300f, 280f, 400f),
        )
    }

    @Test
    fun recenterForBed_usesRectangularProfileCenter() {
        val positions = floatArrayOf(
            10f, 20f, 5f,
            30f, 60f, 15f,
        )

        assertArrayEquals(
            floatArrayOf(
                140f, 70f, 0f,
                160f, 110f, 10f,
            ),
            recenterForBed(positions, bedSizeXmm = 300f, bedSizeYmm = 180f),
            0.001f,
        )
    }
}
