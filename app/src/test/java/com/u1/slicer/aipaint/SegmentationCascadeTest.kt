package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationCascadeTest {

    /** Synthetic triangle list: N triangles arranged at increasing Z. Used for Z-band tests. */
    internal fun ladderPositions(triCount: Int): FloatArray {
        val out = FloatArray(triCount * 9)
        for (t in 0 until triCount) {
            val z = t.toFloat()
            val b = t * 9
            // three vertices at z, z, z (degenerate-but-fine for centroid computation)
            for (v in 0 until 3) {
                out[b + v * 3 + 0] = 0f
                out[b + v * 3 + 1] = 0f
                out[b + v * 3 + 2] = z
            }
        }
        return out
    }

    @Test
    fun `zBand branch produces TARGET_SEGMENTS leaves with monotonic z`() {
        val tris = 240
        val result = SegmentationCascade.zBandBranch(
            ladderPositions(tris),
            bandCount = 12,
        )
        assertEquals(SegmentationSource.Z_BAND, result.source)
        assertEquals(1, result.tree.size) // single root
        val root = result.tree.first()
        assertEquals(12, root.children.size)
        // Each band gets exactly tris / 12 = 20 triangles.
        root.children.forEachIndexed { i, child ->
            assertEquals("band $i triangle count", 20, child.triangleIds.size)
        }
        // triangleSegments labels triangles 0..bandCount-1 in monotonic order.
        for (t in 0 until tris) {
            val expectedBand = (t / 20).coerceAtMost(11)
            assertEquals("triangle $t belongs to band $expectedBand",
                expectedBand, result.triangleSegments[t].toInt() and 0xFF)
        }
    }

    @Test
    fun `zBand assigns slots round-robin`() {
        val result = SegmentationCascade.zBandBranch(ladderPositions(24), bandCount = 12)
        val slots = result.tree.first().children.map { it.region.slot }
        assertEquals(listOf(0,1,2,3,0,1,2,3,0,1,2,3), slots)
    }
}
