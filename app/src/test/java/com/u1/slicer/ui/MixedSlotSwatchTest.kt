package com.u1.slicer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MixedSlotSwatchTest {
    @Test fun segmentFractions_cumulativeOffsets() {
        val offs = mixSegmentOffsets(listOf(50, 30, 20))
        assertEquals(listOf(0f, 0.5f, 0.8f), offs.map { it.first })
        assertEquals(listOf(0.5f, 0.3f, 0.2f), offs.map { it.second })
    }
}
