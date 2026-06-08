package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MixedFilamentRowMigrationTest {
    @Test fun derivedAccessors_matchFirstTwoComponents() {
        val row = MixedFilamentRow(
            id = 1, components = listOf(1, 3, 4), weights = listOf(50, 30, 20),
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            label = "x", inLibrary = false,
        )
        assertEquals(1, row.componentA)
        assertEquals(3, row.componentB)
        assertEquals(30, row.mixBPercent)
    }

    @Test fun fromLegacy_reconstructsComponentsAndWeights() {
        val row = MixedFilamentRow.fromLegacy(
            id = 7, componentA = 1, componentB = 2, mixBPercent = 30,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            label = "L", inLibrary = true,
        )
        assertEquals(listOf(1, 2), row.components)
        assertEquals(listOf(70, 30), row.weights)
        assertEquals(true, row.inLibrary)
    }

    @Test fun autoLabel_listForm() {
        assertEquals("E1+E2+E3", MixedFilamentRow.autoLabel(listOf(1, 2, 3)))
        assertEquals("E1+E3", MixedFilamentRow.autoLabel(listOf(1, 3)))
    }

    @Test fun init_rejectsBadComponentCountAndMismatchedWeights() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MixedFilamentRow(1, listOf(1), listOf(100),
                MixedFilamentRow.MixDistributionMode.LAYER_CYCLE, "x", false)   // 1 component
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MixedFilamentRow(1, listOf(1, 2, 3, 4, 5), listOf(20, 20, 20, 20, 20),
                MixedFilamentRow.MixDistributionMode.LAYER_CYCLE, "x", false)   // 5 components
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MixedFilamentRow(1, listOf(1, 2, 3), listOf(50, 50),
                MixedFilamentRow.MixDistributionMode.LAYER_CYCLE, "x", false)   // weights size mismatch
        }
    }
}
