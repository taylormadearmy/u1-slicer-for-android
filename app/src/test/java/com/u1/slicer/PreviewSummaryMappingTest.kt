package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSummaryMappingTest {

    @Test
    fun `buildPerExtruderDisplaySlots prioritizes mapped physical slots`() {
        // Compact tools [0,1] mapped to physical E3,E2 (slots 2,1).
        val slots = buildPerExtruderDisplaySlots(
            count = 3,
            colorMapping = listOf(2, 1)
        )
        assertEquals(listOf(2, 1, 0), slots)
    }

    @Test
    fun `buildPerExtruderDisplaySlots falls back to identity when no mapping`() {
        val slots = buildPerExtruderDisplaySlots(
            count = 3,
            colorMapping = null
        )
        assertEquals(listOf(0, 1, 2), slots)
    }
}
