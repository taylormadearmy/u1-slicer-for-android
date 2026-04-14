package com.u1.slicer

import org.junit.Assert.*
import org.junit.Test

class SemmColorPermutationTest {

    @Test
    fun identityMapping_returnsNull() {
        val result = computeSemmColorPermutation(
            colorMapping = listOf(0, 1, 2, 3),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun permutedMapping_returnsPermutation() {
        val result = computeSemmColorPermutation(
            colorMapping = listOf(3, 0, 2, 1),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertEquals(listOf(3, 0, 2, 1), result)
    }

    @Test
    fun h2cModel_returnsNull() {
        val result = computeSemmColorPermutation(
            colorMapping = listOf(3, 0, 2, 1),
            hasPaintData = true,
            isH2cStyle = true
        )
        assertNull(result)
    }

    @Test
    fun nonSemm_returnsNull() {
        val result = computeSemmColorPermutation(
            colorMapping = listOf(3, 0, 2, 1),
            hasPaintData = false,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun sparseSlots_permutation_subsumesCompaction() {
        val result = computeSemmColorPermutation(
            colorMapping = listOf(2, 0),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertEquals(listOf(2, 0), result)
    }

    @Test
    fun twoColor_identity_returnsNull() {
        val result = computeSemmColorPermutation(
            colorMapping = listOf(0, 1),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertNull(result)
    }

    @Test
    fun twoColor_swapped_returnsPermutation() {
        val result = computeSemmColorPermutation(
            colorMapping = listOf(1, 0),
            hasPaintData = true,
            isH2cStyle = false
        )
        assertEquals(listOf(1, 0), result)
    }

    // --- composeSemmRemap tests ---

    @Test
    fun compose_onlyToolRemap() {
        val result = composeSemmRemap(
            toolRemapSlots = listOf(2, 3),
            semmColorPermutation = null
        )
        assertEquals(listOf(2, 3), result)
    }

    @Test
    fun compose_onlyPermutation() {
        val result = composeSemmRemap(
            toolRemapSlots = null,
            semmColorPermutation = listOf(3, 0, 2, 1)
        )
        assertEquals(listOf(3, 0, 2, 1), result)
    }

    @Test
    fun compose_bothPresent_permutationWins() {
        val result = composeSemmRemap(
            toolRemapSlots = listOf(0, 2),
            semmColorPermutation = listOf(2, 0)
        )
        assertEquals(listOf(2, 0), result)
    }

    @Test
    fun compose_bothNull() {
        val result = composeSemmRemap(
            toolRemapSlots = null,
            semmColorPermutation = null
        )
        assertNull(result)
    }
}
