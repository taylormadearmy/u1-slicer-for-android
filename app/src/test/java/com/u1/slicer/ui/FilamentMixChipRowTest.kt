package com.u1.slicer.ui

import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.MixedFilamentRow.MixDistributionMode.LAYER_CYCLE
import org.junit.Assert.assertEquals
import org.junit.Test

class FilamentMixChipRowTest {
    private fun mix(id: Long, a: Int, b: Int) = MixedFilamentRow(id, a, b, 50, LAYER_CYCLE, "E$a+E$b @ 50%", false)

    @Test fun physicalChipSlotIds_areTheirIndex() {
        assertEquals(0, FilamentMixChipRow.physicalSlotId(0))
        assertEquals(3, FilamentMixChipRow.physicalSlotId(3))
    }

    @Test fun mixChipSlotId_isNumPhysicalPlusIndex() {
        assertEquals(4, FilamentMixChipRow.mixSlotId(0, numPhysical = 4))
        assertEquals(5, FilamentMixChipRow.mixSlotId(1, numPhysical = 4))
    }
}
