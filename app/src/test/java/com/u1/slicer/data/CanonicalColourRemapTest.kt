package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalColourRemapTest {
    private val greyMix = MixedFilamentRow(
        id = 42, components = listOf(1, 2), weights = listOf(50, 50),
        distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
        label = "Grey", inLibrary = false,
    )

    @Test fun `one physical slot clamps implicit and explicit destinations`() {
        assertEquals(
            mapOf(0 to 1, 1 to 1),
            resolveCanonicalColourRemap(
                canonicalSize = 2,
                remaps = listOf(
                    CanonicalColourRemap(0, CanonicalColourDestination.PhysicalSlot(0)),
                    CanonicalColourRemap(1, CanonicalColourDestination.PhysicalSlot(0)),
                ),
                projectMixes = emptyList(), libraryMixes = emptyList(), physicalCount = 1,
            ),
        )
    }

    @Test fun `two physical slots use the active count for implicit mapping`() {
        assertEquals(
            mapOf(0 to 1, 1 to 2, 2 to 1),
            resolveCanonicalColourRemap(3, emptyList(), emptyList(), emptyList(), physicalCount = 2),
        )
    }

    @Test fun `resolves by canonical file index and stable mix id`() {
        val result = resolveCanonicalColourRemap(
            canonicalSize = 3,
            remaps = listOf(
                CanonicalColourRemap(0, CanonicalColourDestination.PhysicalSlot(3)),
                CanonicalColourRemap(1, CanonicalColourDestination.Mix(42)),
                CanonicalColourRemap(2, CanonicalColourDestination.PhysicalSlot(0)),
            ),
            projectMixes = listOf(greyMix), libraryMixes = emptyList(),
        )
        assertEquals(mapOf(0 to 4, 1 to 5, 2 to 1), result)
    }

    @Test fun `rejects stale mix and invalid physical slot`() {
        assertNull(resolveCanonicalColourRemap(1, listOf(CanonicalColourRemap(0, CanonicalColourDestination.Mix(99))), emptyList(), emptyList()))
        assertNull(resolveCanonicalColourRemap(1, listOf(CanonicalColourRemap(0, CanonicalColourDestination.PhysicalSlot(4))), emptyList(), emptyList()))
    }

    @Test fun `rejects a mix whose components are not available on U1`() {
        val unavailable = greyMix.copy(id = 43, components = listOf(1, 5))
        assertNull(
            resolveCanonicalColourRemap(
                canonicalSize = 1,
                remaps = listOf(CanonicalColourRemap(0, CanonicalColourDestination.Mix(43))),
                projectMixes = listOf(unavailable),
                libraryMixes = emptyList(),
            )
        )
    }
}
