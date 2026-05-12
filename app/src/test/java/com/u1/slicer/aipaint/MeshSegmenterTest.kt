package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class MeshSegmenterTest {

    private fun flatQuadPositions(): FloatArray = floatArrayOf(
        0f, 0f, 0f,  1f, 0f, 0f,  1f, 1f, 0f,   // tri 0 — z=0
        0f, 0f, 0f,  1f, 1f, 0f,  0f, 1f, 0f    // tri 1 — z=0
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

    private fun equalBounds() = floatArrayOf(0f, 25f, 50f, 75f, 100f)

    @Test
    fun `flat quad produces single region`() {
        // Both triangles are at z=0; zRange=0 so all get pct=50 → same band.
        val regionIds = MeshSegmenter.segmentByBounds(flatQuadPositions(), equalBounds())
        assertEquals(2, regionIds.size)
        assertEquals(regionIds[0], regionIds[1])
    }

    @Test
    fun `output size matches triangle count`() {
        val positions = cubePositions()
        val triCount = positions.size / 9
        val regionIds = MeshSegmenter.segmentByBounds(positions, equalBounds())
        assertEquals(triCount, regionIds.size)
    }

    @Test
    fun `cube produces exactly 4 distinct region ids`() {
        val regionIds = MeshSegmenter.segmentByBounds(cubePositions(), equalBounds())
        val distinct = regionIds.toSet()
        assertEquals("Expected exactly 4 regions", 4, distinct.size)
    }

    @Test
    fun `all region ids are in range 0 to 3`() {
        val regionIds = MeshSegmenter.segmentByBounds(cubePositions(), equalBounds())
        regionIds.forEach { id: Int ->
            assertTrue("region id $id out of range", id in 0..3)
        }
    }

    @Test
    fun `coverage fractions sum to 1`() {
        val positions = cubePositions()
        val regionIds = MeshSegmenter.segmentByBounds(positions, equalBounds())
        val fractions = MeshSegmenter.coverageFractions(regionIds, targetRegions = 4)
        assertEquals(4, fractions.size)
        assertEquals(1f, fractions.sum(), 0.001f)
    }

    // ---- segmentByThickness tests ----

    @Test
    fun `segmentByThickness output size matches triangle count`() {
        val positions = cubePositions()
        val ids = MeshSegmenter.segmentByThickness(positions, 4)
        assertEquals(positions.size / 9, ids.size)
    }

    @Test
    fun `segmentByThickness on cube produces 4 distinct regions`() {
        val ids = MeshSegmenter.segmentByThickness(cubePositions(), 4)
        assertEquals(4, ids.toSet().size)
    }

    @Test
    fun `segmentByThickness all ids in range 0 to 3`() {
        val ids = MeshSegmenter.segmentByThickness(cubePositions(), 4)
        ids.forEach { assertTrue("region id $it out of range", it in 0..3) }
    }

    @Test
    fun `segmentByThickness cube bottom face is region 0 top face is region 3`() {
        // Cube bottom face (first 2 tris, z=0) should be the lowest region.
        // Cube top face (tris 2-3, z=1) should be the highest region.
        val ids = MeshSegmenter.segmentByThickness(cubePositions(), 4)
        assertTrue("bottom face in region 0", ids.take(2).all { it == 0 })
        assertTrue("top face in region 3",    ids.slice(2..3).all { it == 3 })
    }

    @Test
    fun `segmentByThickness fewer triangles than regions returns single region`() {
        val ids = MeshSegmenter.segmentByThickness(flatQuadPositions(), 4)
        assertEquals(2, ids.size)
        assertTrue("all in region 0", ids.all { it == 0 })
    }

    @Test
    fun `segmentByThickness coverage fractions sum to 1`() {
        val ids = MeshSegmenter.segmentByThickness(cubePositions(), 4)
        val fractions = MeshSegmenter.coverageFractions(ids, 4)
        assertEquals(1f, fractions.sum(), 0.001f)
    }

    @Test
    fun `narrow top band gets only high-z triangles`() {
        // bounds: region 0 = 0-90%, region 1 = 90-100%
        val bounds = floatArrayOf(0f, 90f, 100f)
        val positions = cubePositions()
        val ids = MeshSegmenter.segmentByBounds(positions, bounds)
        // Top-face triangles (z=1, pct=100) should be in region 1
        // Bottom-face triangles (z=0, pct=0) should be in region 0
        val bottomFaceIds = ids.take(2)   // first 2 tris are bottom face (z=0)
        val topFaceIds = ids.slice(2..3)  // next 2 are top face (z=1)
        assertTrue("bottom face in region 0", bottomFaceIds.all { it == 0 })
        assertTrue("top face in region 1", topFaceIds.all { it == 1 })
    }
}
