package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class AiRegionTest {
    @Test
    fun `effectiveColour returns userColour when set`() {
        val region = AiRegion(0, "Head", "#FFCC00", userColour = "#FF0000")
        assertEquals("#FF0000", region.effectiveColour)
    }

    @Test
    fun `effectiveColour falls back to suggestedColour when userColour is null`() {
        val region = AiRegion(0, "Head", "#FFCC00")
        assertEquals("#FFCC00", region.effectiveColour)
    }

    @Test
    fun `AiPaintProvider fromId returns DEFAULT for unknown`() {
        assertEquals(AiPaintProvider.POLLINATIONS, AiPaintProvider.fromId("UNKNOWN"))
    }

    @Test
    fun `AiPaintProvider fromId round-trips all entries`() {
        AiPaintProvider.entries.forEach { p ->
            assertEquals(p, AiPaintProvider.fromId(p.name))
        }
    }
}
