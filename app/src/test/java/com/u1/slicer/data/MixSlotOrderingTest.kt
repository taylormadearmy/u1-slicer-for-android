package com.u1.slicer.data

import com.u1.slicer.data.MixedFilamentRow.MixDistributionMode.LAYER_CYCLE
import org.junit.Assert.assertEquals
import org.junit.Test

class MixSlotOrderingTest {
    private fun row(id: Long, a: Int, b: Int, lib: Boolean) =
        MixedFilamentRow(id, a, b, 50, LAYER_CYCLE, "E$a+E$b @ 50%", lib)

    @Test fun projectFirst_thenLibrary_skippingDupesAndMissingExtruders() {
        val project = listOf(row(1, 1, 2, false), row(2, 1, 3, false))
        val library = listOf(
            row(2, 1, 3, true),   // dupe of a project id -> skipped
            row(3, 1, 4, true),   // valid for 4 physical
            row(4, 1, 5, true),   // references E5 > numPhysical -> skipped
        )
        val ordered = MixSlotOrdering.activeOrder(project, library, numPhysical = 4)
        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.id })
        assertEquals(4, MixSlotOrdering.slotIdFor(ordered, 0, numPhysical = 4))
        assertEquals(6, MixSlotOrdering.slotIdFor(ordered, 2, numPhysical = 4))
    }

    @Test fun emptyWhenNoMixes() {
        assertEquals(emptyList<MixedFilamentRow>(),
            MixSlotOrdering.activeOrder(emptyList(), emptyList(), 4))
    }
}
