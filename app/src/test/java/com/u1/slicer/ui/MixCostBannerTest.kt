package com.u1.slicer.ui
import org.junit.Assert.assertEquals
import org.junit.Test
class MixCostBannerTest {
    @Test fun countsRegionsOnMixSlots() {
        assertEquals(2, mixRegionCount(listOf(0, 1, 5, 5, 2), numPhysical = 4))
    }
    @Test fun zeroWhenNoMixSlots() {
        assertEquals(0, mixRegionCount(listOf(0, 1, 2, 3), numPhysical = 4))
    }
    @Test fun countsEachMixRegion_includingDistinctMixSlots() {
        assertEquals(3, mixRegionCount(listOf(4, 5, 6, 0), numPhysical = 4))
    }
}
