package com.u1.slicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceMixToolSpaceDecisionTest {

    @Test
    fun `painted mix keeps slice in mix tool space even with zero active mix rows`() {
        val decision = decideSliceMixToolSpace(
            numPhysical = 4,
            canonicalCount = 4,
            hasActiveMixRows = false,
            objectMixAssigned = false,
            paintedMixAssigned = true,
        )

        assertTrue(decision.anyMixAssigned)
        assertTrue(decision.mixToolSpace)
        assertTrue(decision.mixPhysicalBase == 4)
    }

    @Test
    fun `object mix without active rows does not force mix tool space`() {
        val decision = decideSliceMixToolSpace(
            numPhysical = 4,
            canonicalCount = 4,
            hasActiveMixRows = false,
            objectMixAssigned = true,
            paintedMixAssigned = false,
        )

        assertFalse(decision.anyMixAssigned)
        assertFalse(decision.mixToolSpace)
        assertTrue(decision.mixPhysicalBase == 0)
    }

    @Test
    fun `canonical remap uses the physical tool space without becoming a mix`() {
        val decision = decideSliceMixToolSpace(
            numPhysical = 4,
            canonicalCount = 7,
            hasActiveMixRows = false,
            objectMixAssigned = false,
            paintedMixAssigned = false,
            canonicalColourRemapActive = true,
        )

        assertFalse(decision.anyMixAssigned)
        assertTrue(decision.mixToolSpace)
        assertTrue(decision.mixPhysicalBase == 4)
    }
}
