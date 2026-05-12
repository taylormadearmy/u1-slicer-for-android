package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class MeshSegmenterTest {

    private fun flatQuadPositions(): FloatArray = floatArrayOf(
        0f, 0f, 0f,  1f, 0f, 0f,  1f, 1f, 0f,   // tri 0
        0f, 0f, 0f,  1f, 1f, 0f,  0f, 1f, 0f    // tri 1 — shares edge with tri 0
    )

    private fun cubePositions(): FloatArray {
        val b = floatArrayOf(
            0f, 0f, 0f,  1f, 0f, 0f,  1f, 1f, 0f,
            0f, 0f, 0f,  1f, 1f, 0f,  0f, 1f, 0f
        )
        val t = floatArrayOf(
            0f, 0f, 1f,  1f, 1f, 1f,  1f, 0f, 1f,
            0f, 0f, 1f,  0f, 1f, 1f,  1f, 1f, 1f
        )
        val f = floatArrayOf(
            0f, 0f, 0f,  1f, 0f, 1f,  1f, 0f, 0f,
            0f, 0f, 0f,  0f, 0f, 1f,  1f, 0f, 1f
        )
        val bk = floatArrayOf(
            0f, 1f, 0f,  1f, 1f, 0f,  1f, 1f, 1f,
            0f, 1f, 0f,  1f, 1f, 1f,  0f, 1f, 1f
        )
        val l = floatArrayOf(
            0f, 0f, 0f,  0f, 1f, 1f,  0f, 1f, 0f,
            0f, 0f, 0f,  0f, 0f, 1f,  0f, 1f, 1f
        )
        val r = floatArrayOf(
            1f, 0f, 0f,  1f, 1f, 0f,  1f, 1f, 1f,
            1f, 0f, 0f,  1f, 1f, 1f,  1f, 0f, 1f
        )
        return b + t + f + bk + l + r
    }

    @Test
    fun `flat quad produces single region`() {
        val regionIds = MeshSegmenter.segment(flatQuadPositions(), targetRegions = 4)
        assertEquals(2, regionIds.size)
        assertEquals(regionIds[0], regionIds[1])
    }

    @Test
    fun `output size matches triangle count`() {
        val positions = cubePositions()
        val triCount = positions.size / 9
        val regionIds = MeshSegmenter.segment(positions, targetRegions = 4)
        assertEquals(triCount, regionIds.size)
    }

    @Test
    fun `cube produces exactly 4 distinct region ids`() {
        val regionIds = MeshSegmenter.segment(cubePositions(), targetRegions = 4)
        val distinct = regionIds.toSet()
        assertEquals("Expected exactly 4 regions", 4, distinct.size)
    }

    @Test
    fun `all region ids are in range 0 to 3`() {
        val regionIds = MeshSegmenter.segment(cubePositions(), targetRegions = 4)
        regionIds.forEach { id: Int ->
            assertTrue("region id $id out of range", id in 0..3)
        }
    }

    @Test
    fun `coverage fractions sum to 1`() {
        val positions = cubePositions()
        val regionIds = MeshSegmenter.segment(positions, targetRegions = 4)
        val fractions = MeshSegmenter.coverageFractions(regionIds, targetRegions = 4)
        assertEquals(4, fractions.size)
        assertEquals(1f, fractions.sum(), 0.001f)
    }
}
