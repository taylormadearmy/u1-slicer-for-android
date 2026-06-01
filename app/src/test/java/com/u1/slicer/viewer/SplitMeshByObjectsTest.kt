package com.u1.slicer.viewer

import org.junit.Assert.*
import org.junit.Test

class SplitMeshByObjectsTest {

    private val fpp = MeshData.FLOATS_PER_VERTEX

    /**
     * Build a MeshData from flat triangle data [x1,y1,z1, x2,y2,z2, x3,y3,z3, ...].
     * Each group of 9 floats is one triangle.
     */
    private fun buildMesh(vararg triangles: FloatArray): MeshData {
        val triCount = triangles.size
        val vertexCount = triCount * 3
        val buf = MeshData.allocateBuffer(triCount)
        for (tri in triangles) {
            for (v in 0 until 3) {
                buf.put(tri[v * 3])     // x
                buf.put(tri[v * 3 + 1]) // y
                buf.put(tri[v * 3 + 2]) // z
                buf.put(0f); buf.put(0f); buf.put(1f)          // normal
                buf.put(1f); buf.put(0f); buf.put(0f); buf.put(1f) // rgba
            }
        }
        buf.rewind()
        return MeshData(
            vertices = buf, vertexCount = vertexCount,
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 200f, maxY = 100f, maxZ = 100f
        )
    }

    /**
     * Regression: edge triangle of a large object A was misclassified to adjacent small
     * object B because B's centre was closer than A's centre.
     *
     * Layout:
     *   Object A: pos=(0,0)   size=(100,50,50) — centre=(50,25)
     *   Object B: pos=(100,0) size=(20,50,50)  — centre=(110,25)
     *
     * Triangle "triAEdge" has centroid ≈(98,25) — inside A's AABB [0..100]×[0..50]
     * but dist² to B centre (144) < dist² to A centre (2304).
     * Correct assignment: A. Old nearest-centre code: B (the visual bug).
     */
    @Test
    fun `edge triangle of large object assigned to correct object not closer small neighbour`() {
        val triA1    = floatArrayOf(10f,10f,0f, 20f,10f,0f, 15f,20f,0f)  // centroid (15,13)
        val triA2    = floatArrayOf(30f,10f,0f, 50f,10f,0f, 40f,20f,0f)  // centroid (40,13)
        val triAEdge = floatArrayOf(98f,20f,0f, 99f,30f,0f, 97f,25f,0f)  // centroid (98,25) — the bug case
        val triB1    = floatArrayOf(105f,10f,0f, 115f,10f,0f, 110f,20f,0f) // centroid (110,13)
        val triB2    = floatArrayOf(108f,30f,0f, 118f,30f,0f, 113f,40f,0f) // centroid (113,33)

        val mesh = buildMesh(triA1, triA2, triAEdge, triB1, triB2)
        val positions = floatArrayOf(0f, 0f, 100f, 0f)          // A.minXY, B.minXY
        val sizes     = floatArrayOf(100f, 50f, 50f, 20f, 50f, 50f) // A.xyz, B.xyz

        val (_, ranges) = ModelRenderer.splitMeshByObjects(mesh, positions, sizes)!!

        assertEquals(2, ranges.size)
        assertEquals("Object A should own 3 triangles including the edge one", 3, ranges[0].vertexCount / 3)
        assertEquals("Object B should own 2 triangles", 2, ranges[1].vertexCount / 3)
    }

    @Test
    fun `well-separated objects classified correctly`() {
        // Two objects far apart — both nearest-centre and AABB agree
        val triA = floatArrayOf(10f,10f,0f, 20f,10f,0f, 15f,20f,0f)
        val triB = floatArrayOf(200f,10f,0f, 210f,10f,0f, 205f,20f,0f)

        val mesh = buildMesh(triA, triB)
        val positions = floatArrayOf(0f, 0f, 190f, 0f)
        val sizes     = floatArrayOf(50f, 50f, 50f, 50f, 50f, 50f)

        val (_, ranges) = ModelRenderer.splitMeshByObjects(mesh, positions, sizes)!!

        assertEquals(1, ranges[0].vertexCount / 3)
        assertEquals(1, ranges[1].vertexCount / 3)
    }

    @Test
    fun `triangle outside all AABBs falls back to nearest centre`() {
        // A triangle in the gap between two objects — outside both AABBs, nearest centre wins
        val triA   = floatArrayOf(10f,10f,0f, 20f,10f,0f, 15f,20f,0f)  // clearly in A
        val triGap = floatArrayOf(52f,10f,0f, 58f,10f,0f, 55f,20f,0f)  // centroid (55,13) — gap; closer to A centre (25,25)? No...
        val triB   = floatArrayOf(90f,10f,0f, 100f,10f,0f, 95f,20f,0f) // clearly in B

        // A: pos=(0,0) size=(50,50,50) → x=[0..50] — triGap centroid x=55 is OUTSIDE
        // B: pos=(60,0) size=(50,50,50) → x=[60..110] — triGap centroid x=55 is OUTSIDE
        // Nearest centre: A centre=(25,25), B centre=(85,25); dist to A=(55-25)²=900, dist to B=(55-85)²=900 — tie → A (first)
        val mesh = buildMesh(triA, triGap, triB)
        val positions = floatArrayOf(0f, 0f, 60f, 0f)
        val sizes     = floatArrayOf(50f, 50f, 50f, 50f, 50f, 50f)

        val (_, ranges) = ModelRenderer.splitMeshByObjects(mesh, positions, sizes)!!

        // Total triangles preserved
        assertEquals(3, ranges[0].vertexCount / 3 + ranges[1].vertexCount / 3)
    }

    /**
     * B132c guard — when `sizes` is too short for the implied object count
     * from `positions`, return null instead of indexing OOB. Mirrors the
     * defensive check added in v2.10.3 after the Pixel 8a Oreo crash:
     *
     *   ArrayIndexOutOfBoundsException: length=9; index=9
     *   at ModelRenderer$Companion.splitMeshByObjects(ModelRenderer.kt:1058)
     */
    @Test
    fun `returns null when sizes shorter than required by positions count`() {
        val tri = floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,1f,0f)
        val mesh = buildMesh(tri)
        // 5-object positions (10 floats) but only 3-object sizes (9 floats) —
        // mirrors the device repro state.
        val positions = FloatArray(10) { it.toFloat() }
        val sizes = FloatArray(9) { 50f }

        val result = ModelRenderer.splitMeshByObjects(mesh, positions, sizes)
        assertNull(
            "B132c: mismatched positions/sizes must return null instead of crashing",
            result,
        )
    }
}
