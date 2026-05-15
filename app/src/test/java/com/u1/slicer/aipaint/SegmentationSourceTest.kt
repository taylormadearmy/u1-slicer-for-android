package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationSourceTest {
    @Test
    fun `all expected sources are defined`() {
        val names = SegmentationSource.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf(
            "PAINT_STATE", "VOLUME", "OBJECT", "TRIANGLE_INDEX",
            "TOPOLOGY", "TOPOLOGY_RECURSIVE", "Z_BAND", "BRUSH",
        )))
    }

    @Test
    fun `displayLabel returns a human-readable string`() {
        assertEquals("Painted", SegmentationSource.PAINT_STATE.displayLabel)
        assertEquals("Per-volume", SegmentationSource.VOLUME.displayLabel)
        assertEquals("Per-object", SegmentationSource.OBJECT.displayLabel)
        assertEquals("Height bands", SegmentationSource.Z_BAND.displayLabel)
    }
}
