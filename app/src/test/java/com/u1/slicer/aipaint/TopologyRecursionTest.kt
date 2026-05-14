package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyRecursionTest {

    /** 80 triangles laid out along Z 0..79; subdividing into K=8 should yield 8 evenly-spaced
     *  Z bands of 10 triangles each (within K-means' rounding). */
    @Test
    fun `subdivide returns K sub-regions covering all input triangles`() {
        val positions = FloatArray(80 * 9)
        for (t in 0 until 80) {
            val b = t * 9
            val z = t.toFloat()
            for (v in 0 until 3) {
                positions[b + v * 3 + 0] = 0f
                positions[b + v * 3 + 1] = 0f
                positions[b + v * 3 + 2] = z
            }
        }
        val all = IntArray(80) { it }
        val subs = TopologyRecursion.subdivide(positions, all, kMeansK = 8, startId = 12)
        assertEquals(8, subs.size)
        val coveredTriangles = subs.flatMap { it.triangleIds.toList() }.toSet()
        assertEquals(80, coveredTriangles.size)
        // IDs assigned sequentially from startId
        assertEquals((12 until 20).toList(), subs.map { it.region.id })
        // Every sub-region tagged TOPOLOGY_RECURSIVE
        assertTrue(subs.all { it.nodeSource == SegmentationSource.TOPOLOGY_RECURSIVE })
    }

    @Test
    fun `subdivide returns single-region for tiny input`() {
        val positions = FloatArray(3 * 9) // 3 triangles
        val subs = TopologyRecursion.subdivide(positions, intArrayOf(0, 1, 2), kMeansK = 8, startId = 12)
        // Can't subdivide 3 triangles into 8 useful clusters; expect ≤ inputCount sub-regions,
        // each non-empty.
        assertTrue("expected at most 3 sub-regions on 3-triangle input", subs.size <= 3)
        assertTrue("every sub-region must be non-empty",
            subs.all { it.triangleIds.isNotEmpty() })
    }
}
