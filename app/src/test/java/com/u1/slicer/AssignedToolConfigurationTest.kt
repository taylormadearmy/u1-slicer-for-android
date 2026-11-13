package com.u1.slicer

import org.junit.Assert.*
import org.junit.Test

class AssignedToolConfigurationTest {
    @Test fun `sparse E4 assignment requires four tool entries`() {
        assertEquals(4, requiredAssignedToolCount(listOf(4), 1, 4))
        assertEquals(4, requiredAssignedToolCount(listOf(1, 4), 1, 4))
    }
    @Test fun `virtual mixes do not become physical tool capacity`() {
        assertEquals(1, requiredAssignedToolCount(listOf(5), 1, 4))
        assertEquals(3, requiredAssignedToolCount(listOf(3, 5), 1, 4))
        assertEquals(8, requiredAssignedToolCount(listOf(8, 10), 9, 4))
    }
    @Test fun `single used tool allows adaptive layers regardless of its index`() {
        assertNull(adaptiveLayerCompatibilityError(1, false, false))
        assertNotNull(adaptiveLayerCompatibilityError(2, false, false))
        assertTrue(adaptiveLayerCompatibilityError(1, true, false)!!.contains("ColorMix"))
    }
    @Test fun `organic supports explain their variable layer limitation`() {
        assertTrue(adaptiveLayerCompatibilityError(1, false, true)!!.contains("Organic supports"))
    }
}
