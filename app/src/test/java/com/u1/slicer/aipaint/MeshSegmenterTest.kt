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

    // ---- segmentBySpatialKMeans tests ----

    @Test
    fun `spatial kMeans returns exactly k components for adequately sized meshes`() {
        val (ids, n) = MeshSegmenter.segmentBySpatialKMeans(cubePositions(), k = 4)
        assertEquals(4, n)
        assertEquals(cubePositions().size / 9, ids.size)
        ids.forEach { assertTrue(it in 0..3) }
    }

    @Test
    fun `spatial kMeans separates spatially distinct point clouds`() {
        // 3 separate single-triangle clusters at (0,0,0), (10,0,0), (20,0,0) — K=3 must separate them.
        val positions = floatArrayOf(
            0f, 0f, 0f,  0.1f, 0f, 0f,  0f, 0.1f, 0f,
            10f, 0f, 0f, 10.1f, 0f, 0f, 10f, 0.1f, 0f,
            20f, 0f, 0f, 20.1f, 0f, 0f, 20f, 0.1f, 0f,
        )
        val (ids, _) = MeshSegmenter.segmentBySpatialKMeans(positions, k = 3)
        assertEquals(3, ids.toSet().size)
    }

    // ---- segmentByTopologyOrSpatial dispatch ----

    @Test
    fun `topologyOrSpatial uses topology for cleanly segmentable cube`() {
        // Cube has 6 face components → no single one dominates → topology path is fine.
        val (_, n) = MeshSegmenter.segmentByTopologyOrSpatial(cubePositions())
        assertTrue("expected topology result (small N), got $n", n in 2..6)
    }

    @Test
    fun `topologyOrSpatial falls back to spatial K-means for a single-shell smooth mesh`() {
        // Build a smooth-walled "pot" approximation: a tessellated cylinder whose dihedral angles
        // are all below the 45° crease threshold → segmentByTopology returns ONE component.
        // segmentByTopologyOrSpatial must spot that and split via spatial K-means instead.
        val sides = 48
        val height = 40f
        val radius = 20f
        val tris = mutableListOf<Float>()
        for (i in 0 until sides) {
            val a0 = (i.toDouble() / sides) * 2 * Math.PI
            val a1 = ((i + 1).toDouble() / sides) * 2 * Math.PI
            val x0 = (radius * Math.cos(a0)).toFloat(); val y0 = (radius * Math.sin(a0)).toFloat()
            val x1 = (radius * Math.cos(a1)).toFloat(); val y1 = (radius * Math.sin(a1)).toFloat()
            tris.addAll(listOf(x0, y0, 0f,  x1, y1, 0f,  x1, y1, height))
            tris.addAll(listOf(x0, y0, 0f,  x1, y1, height,  x0, y0, height))
        }
        val positions = tris.toFloatArray()
        val (topoIds, topoN) = MeshSegmenter.segmentByTopology(positions, maxComponents = 32)
        // Sanity: the smooth-walled cylinder collapses into ≤ 2 topology components.
        // (Two if the dihedral at the side-seam crosses the 45° threshold for that vertex
        // discretisation; one otherwise. Either way the dominant component is huge.)
        val sizes = IntArray(topoN)
        for (id in topoIds) sizes[id]++
        val dom = sizes.maxOrNull()!!.toFloat() / positions.size * 9f
        assertTrue("expected a degenerate topology result, got sizes=${sizes.toList()}",
            dom > 0.7f)

        val (_, n) = MeshSegmenter.segmentByTopologyOrSpatial(positions, targetSpatial = 6)
        assertEquals("expected spatial fallback to produce K components", 6, n)
    }

    // ---- segmentByTopology tests ----

    @Test
    fun `segmentByTopology flat quad produces single component`() {
        val (ids, numComps) = MeshSegmenter.segmentByTopology(flatQuadPositions())
        assertEquals(1, numComps)
        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun `segmentByTopology output size matches triangle count`() {
        val positions = cubePositions()
        val (ids, _) = MeshSegmenter.segmentByTopology(positions)
        assertEquals(positions.size / 9, ids.size)
    }

    @Test
    fun `segmentByTopology cube produces 6 components`() {
        val (_, numComps) = MeshSegmenter.segmentByTopology(cubePositions())
        assertEquals("each face of a unit cube is a separate component", 6, numComps)
    }

    @Test
    fun `segmentByTopology all component ids are in valid range`() {
        val (ids, numComps) = MeshSegmenter.segmentByTopology(cubePositions())
        ids.forEach { assertTrue("id $it out of range 0..$numComps", it in 0 until numComps) }
    }

    @Test
    fun `segmentByTopology merges to maxComponents when specified`() {
        val (ids, numComps) = MeshSegmenter.segmentByTopology(cubePositions(), maxComponents = 2)
        assertEquals(2, numComps)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `segmentByTopology merged result covers all triangles`() {
        val positions = cubePositions()
        val (ids, numComps) = MeshSegmenter.segmentByTopology(positions, maxComponents = 3)
        assertEquals(positions.size / 9, ids.size)
        assertEquals(3, numComps)
        ids.forEach { assertTrue(it in 0 until numComps) }
    }

    /**
     * Body: 2 coplanar triangles sharing an edge → one connected mesh component.
     * Eye:  2 free-floating triangles far away, NOT touching the body → second mesh component.
     * Even with maxComponents = 1, the merge step must preserve the island as its own
     * component. Pre-fix behaviour fell back to "merge into the largest other component"
     * because adjCount was empty, which collapsed the eye into the body. Regression guard for
     * free-floating features like eyes / loose detail meshes.
     */
    @Test
    fun `disconnected mesh island stays its own component even when cap forces merge`() {
        val body = floatArrayOf(
            0f,0f,0f,  1f,0f,0f,  0f,1f,0f,
            1f,0f,0f,  1f,1f,0f,  0f,1f,0f,
        )
        val eye = floatArrayOf(
            10f,10f,5f,  11f,10f,5f,  10f,11f,5f,
            11f,10f,5f,  11f,11f,5f,  10f,11f,5f,
        )
        val positions = body + eye
        val (ids, n) = MeshSegmenter.segmentByTopology(positions, maxComponents = 1)
        assertEquals("two mesh components must survive", 2, n)
        assertEquals("both body triangles share one id", 1, ids.toList().subList(0, 2).toSet().size)
        assertEquals("both eye triangles share one id", 1, ids.toList().subList(2, 4).toSet().size)
        assertNotEquals("body and eye must have different component ids", ids[0], ids[2])
    }

    @Test
    fun `multiple islands all survive even with very low cap`() {
        // 3 separate single-triangle islands far apart → 3 components even with cap = 1.
        val positions = floatArrayOf(
            0f,  0f, 0f,   1f,  0f, 0f,   0f,  1f, 0f,
            10f, 0f, 0f,  11f,  0f, 0f,  10f,  1f, 0f,
            20f, 0f, 0f,  21f,  0f, 0f,  20f,  1f, 0f,
        )
        val (_, n) = MeshSegmenter.segmentByTopology(positions, maxComponents = 1)
        assertEquals(3, n)
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
