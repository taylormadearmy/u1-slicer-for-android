package com.u1.slicer.data

import com.u1.slicer.data.MixedFilamentRow.MixDistributionMode.LAYER_CYCLE
import org.junit.Assert.assertEquals
import org.junit.Test

class MixSlotOrderingTest {
    private fun row(id: Long, a: Int, b: Int, lib: Boolean) =
        MixedFilamentRow.fromLegacy(id, a, b, 50, LAYER_CYCLE, "E$a+E$b @ 50%", lib)

    @Test fun projectFirst_thenLibrary_skippingDupesAndMissingExtruders() {
        val project = listOf(row(1, 1, 2, false), row(2, 1, 3, false))
        val library = listOf(
            row(2, 1, 3, true),   // dupe of a project id -> skipped
            row(3, 1, 4, true),   // valid for 4 physical
            row(4, 1, 5, true),   // references E5 > numPhysical -> skipped
        )
        val ordered = MixSlotOrdering.activeOrder(project, library, numPhysical = 4)
        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.id })
        assertEquals(4, MixSlotOrdering.slotIdFor(0, numPhysical = 4))
        assertEquals(6, MixSlotOrdering.slotIdFor(2, numPhysical = 4))
    }

    @Test fun emptyWhenNoMixes() {
        assertEquals(emptyList<MixedFilamentRow>(),
            MixSlotOrdering.activeOrder(emptyList(), emptyList(), 4))
    }

    @Test fun indexForSlot_isInverseOfSlotId_andGuardsRange() {
        // 3 active mixes, numPhysical = 4 → slots 4,5,6
        assertEquals(0, MixSlotOrdering.indexForSlot(4, numPhysical = 4, orderedSize = 3))
        assertEquals(2, MixSlotOrdering.indexForSlot(6, numPhysical = 4, orderedSize = 3))
        assertEquals(-1, MixSlotOrdering.indexForSlot(3, numPhysical = 4, orderedSize = 3)) // physical slot
        assertEquals(-1, MixSlotOrdering.indexForSlot(7, numPhysical = 4, orderedSize = 3)) // out of range
    }
}
