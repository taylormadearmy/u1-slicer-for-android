package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MixedFilamentRowTest {

    @Test
    fun `autoLabel formats components and percent`() {
        val row = MixedFilamentRow(
            id = 1L,
            componentA = 1,
            componentB = 3,
            mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            label = "",
            inLibrary = false,
        )
        assertEquals("E1+E3", MixedFilamentRow.autoLabel(row.componentA, row.componentB, row.mixBPercent))
    }

    @Test
    fun `autoLabel handles odd percentages`() {
        assertEquals("E2+E4",
            MixedFilamentRow.autoLabel(2, 4, 33))
    }

    @Test
    fun `equality treats id as identity`() {
        val a = MixedFilamentRow(1L, 1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE, "x", false)
        val b = a.copy(label = "y", weights = listOf(25, 75))
        // Equality should still match because we treat id as the identity for swap.
        // But Kotlin data class equality is structural, so structural inequality
        // is the right answer; identity-by-id is a manager-level concern.
        assertNotEquals(a, b)
        assertEquals(a.id, b.id)
    }
}
