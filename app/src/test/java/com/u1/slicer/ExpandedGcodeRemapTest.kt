package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [computeExpandedGcodeRemap].
 *
 * The function returns the post-slice tool remap that maps slicer T-indices
 * (`T0..T(embeddedFilamentCount-1)`) to user physical slots (0..3) via
 * `colorMapping`. Returns `null` when the embed was not bumped (i.e.
 * `embeddedFilamentCount <= colorMapping.distinct().size`) so the legacy
 * `semmColorPermutation` / `toolRemapSlots` path drives the remap instead.
 *
 * Prior versions iterated `usedExtruderIndices` (sparse for SEMM-paint files
 * where only the object default extruder is registered) and fell through to
 * identity defaults for slicer T-indices not in that set. That left
 * physical T0 unreached for files like H2C benchy whose user mapping
 * `colorMapping=[2,0,3,2,0,1,0]` placed multiple model colours on slot 0.
 *
 * The current implementation drives the remap directly off `colorMapping[i]`
 * for every emitted tool, padding any tail beyond `colorMapping.size` with
 * identity-coerced defaults. These tests pin that behaviour.
 */
class ExpandedGcodeRemapTest {

    @Test
    fun returnsNullWhenColorMappingEmpty() {
        assertNull(computeExpandedGcodeRemap(usedExtruderIndices = listOf(1), colorMapping = emptyList(), embeddedFilamentCount = 4))
        assertNull(computeExpandedGcodeRemap(usedExtruderIndices = listOf(1), colorMapping = null, embeddedFilamentCount = 4))
    }

    @Test
    fun returnsNullWhenEmbeddedCountAtOrBelowDistinctSlots() {
        // 4 entries, 4 distinct slots → no bump; legacy permutation path applies.
        assertNull(
            computeExpandedGcodeRemap(
                usedExtruderIndices = listOf(1, 2, 3, 4),
                colorMapping = listOf(0, 1, 2, 3),
                embeddedFilamentCount = 4
            )
        )
    }

    @Test
    fun h2cBenchyShape_emitsAllFourPhysicalSlots() {
        // The H2C benchy reproducer: 7 model colours, user-mapped to all 4 physical
        // slots with multiple model colours sharing slot 0. Old behaviour produced
        // [2, 1, 2, 3, 3, 3, 3] (no slot 0). Correct behaviour propagates colorMapping
        // directly so every emitted slicer-T lands on the user's chosen slot.
        val result = computeExpandedGcodeRemap(
            usedExtruderIndices = listOf(1),  // sparse — was the bug trigger
            colorMapping = listOf(2, 0, 3, 2, 0, 1, 0),
            embeddedFilamentCount = 7
        )
        assertEquals(listOf(2, 0, 3, 2, 0, 1, 0), result)
        // Sanity: all four physical slots are reachable.
        assertEquals(setOf(0, 1, 2, 3), result?.toSet())
    }

    @Test
    fun colorMappingShorterThanEmbedded_padsTailWithIdentityCoerced() {
        // B95 case: maxSourceFilamentIndex bumped the embed beyond the user's
        // colorMapping size. Tail entries fall back to identity-coerced defaults
        // so stray out-of-band T-indices land on valid 0..3 slots.
        val result = computeExpandedGcodeRemap(
            usedExtruderIndices = listOf(1, 11),
            colorMapping = listOf(2, 0),
            embeddedFilamentCount = 11
        )
        // out[0] = colorMapping[0] = 2, out[1] = colorMapping[1] = 0,
        // out[2..10] fall through to i.coerceIn(0, 3) = 2, 3, 3, 3, 3, 3, 3, 3, 3
        assertEquals(11, result?.size)
        assertEquals(2, result?.get(0))
        assertEquals(0, result?.get(1))
        assertEquals(3, result?.get(10))
    }

    @Test
    fun ignoresOutOfRangeColorMappingEntries() {
        // Defensive: colorMapping should always be in 0..3 from upstream, but if it
        // isn't, fall through to identity rather than emit invalid T-indices.
        val result = computeExpandedGcodeRemap(
            usedExtruderIndices = listOf(1),
            colorMapping = listOf(7, 1, 2, 3, 0),
            embeddedFilamentCount = 5
        )
        // out[0] = colorMapping[0]=7 invalid → identity 0; rest as supplied.
        assertEquals(listOf(0, 1, 2, 3, 0), result)
    }

    @Test
    fun usedExtruderIndicesNoLongerInfluencesResult() {
        // Guard against regressions to the old "use usedExtruderIndices to choose
        // which slicer-T to populate" behaviour. Two different used-sets must
        // produce the same dense remap.
        val sparse = computeExpandedGcodeRemap(
            usedExtruderIndices = listOf(1),
            colorMapping = listOf(2, 0, 3, 2, 0, 1, 0),
            embeddedFilamentCount = 7
        )
        val dense = computeExpandedGcodeRemap(
            usedExtruderIndices = listOf(1, 2, 3, 4, 5, 6, 7),
            colorMapping = listOf(2, 0, 3, 2, 0, 1, 0),
            embeddedFilamentCount = 7
        )
        assertEquals(sparse, dense)
    }
}
