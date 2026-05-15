package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B112 regression guards for [MeshSegmenter.mergeComponents]. The pre-fix v2.2.0
 * implementation was O(numComps² × triCount), which on a single-shell connected
 * STL like 3DBenchy (~25k triangles, thousands of dihedral-flood-fill components)
 * effectively hung Smart Paint — the Phase 2/4 "Finding parts of the model…"
 * spinner ran for >5 minutes with no progress signal to the user.
 *
 * These tests synthesise the worst-case input (many small triangle-strip components
 * along a single shared adjacency chain) and assert the merge step both terminates
 * quickly and produces the expected output shape.
 */
class MeshSegmenterMergePerfTest {

    /**
     * Build a triangle fan around a central vertex. Each consecutive pair shares
     * the central-to-neighbour edge AND the neighbour vertex, so the topology graph
     * has dense pairwise adjacency. Tilting alternating triangles' third vertex
     * out of the plane creates crease angles > 10° → each triangle becomes its own
     * flood-fill component, forcing the merge path to actually run.
     */
    private fun buildCreaseFanPositions(triCount: Int): FloatArray {
        val positions = FloatArray(triCount * 9)
        val r = 10f
        for (i in 0 until triCount) {
            val b = i * 9
            val a0 = (i * 2.0 * Math.PI / triCount).toFloat()
            val a1 = ((i + 1) * 2.0 * Math.PI / triCount).toFloat()
            // v0 = origin (shared by all fan triangles)
            positions[b]     = 0f
            positions[b + 1] = 0f
            positions[b + 2] = 0f
            // v1 = ring vertex at angle a0
            positions[b + 3] = (r * Math.cos(a0.toDouble())).toFloat()
            positions[b + 4] = (r * Math.sin(a0.toDouble())).toFloat()
            positions[b + 5] = if (i % 2 == 0) 0f else 0.6f  // tilt every other triangle for crease
            // v2 = ring vertex at angle a1
            positions[b + 6] = (r * Math.cos(a1.toDouble())).toFloat()
            positions[b + 7] = (r * Math.sin(a1.toDouble())).toFloat()
            positions[b + 8] = if ((i + 1) % 2 == 0) 0f else 0.6f
        }
        return positions
    }

    @Test fun mergeOnCreaseFanCompletesQuickly_5000triangles() {
        val positions = buildCreaseFanPositions(5_000)
        val t0 = System.nanoTime()
        val (ids, n) = MeshSegmenter.segmentByTopology(positions, maxComponents = 32)
        val ms = (System.nanoTime() - t0) / 1_000_000
        // Pre-fix v2.2.0: O(n² × triCount) merge took minutes for this shape.
        // Post-fix: should be sub-second on desktop JVM.
        assertTrue("merge took too long: ${ms}ms (budget = 10000ms)", ms < 10_000)
        assertEquals(5_000, ids.size)
        // Sanity — algorithm terminated cleanly, ids are dense.
        val distinct = ids.toSet()
        assertEquals(n, distinct.size)
        assertEquals(0, distinct.min())
        assertEquals(n - 1, distinct.max())
    }

    @Test fun mergeOnCreaseFanCompletesQuickly_25000triangles_benchyShape() {
        // Matches subsampled 3DBenchy.stl triangle count post fix45 (99996 → 24999 at stride 4).
        val positions = buildCreaseFanPositions(25_000)
        val t0 = System.nanoTime()
        val (ids, _) = MeshSegmenter.segmentByTopology(positions, maxComponents = 32)
        val ms = (System.nanoTime() - t0) / 1_000_000
        // Pre-fix: indefinite hang (5+ min observed on Pixel 8a).
        // Post-fix: should complete in seconds on desktop JVM.
        assertTrue("merge took too long: ${ms}ms (budget = 30000ms)", ms < 30_000)
        assertEquals(25_000, ids.size)
    }

    @Test fun mergeOnDisjointIslandsReturnsManyComponents() {
        // Two disconnected triangles, no shared edges → both are mesh islands,
        // merge must NOT collapse them even though both are tiny. Restores fix38.3
        // behaviour (free-floating goat eye stays its own component).
        val positions = floatArrayOf(
            // Triangle A at origin
            0f, 0f, 0f,   1f, 0f, 0f,   0.5f, 1f, 0f,
            // Triangle B far away — no shared vertices, no shared edge
            100f, 100f, 100f,  101f, 100f, 100f,  100.5f, 101f, 100f,
        )
        val (_, n) = MeshSegmenter.segmentByTopology(positions, maxComponents = 1)
        // maxComps = 1 would normally force a merge — but with no shared edges, both
        // are islands and the merge skips them.
        assertEquals("disjoint islands must be preserved even past maxComps", 2, n)
    }

    @Test fun mergeIdsAreDense() {
        // After merging, returned ids must be dense in 0..n-1 (no gaps). Downstream
        // topologyBranch indexes arrays of size n directly, so a sparse id space would
        // be an ArrayIndexOutOfBoundsException waiting to happen.
        val positions = buildCreaseFanPositions(500)
        val (ids, n) = MeshSegmenter.segmentByTopology(positions, maxComponents = 8)
        val distinct = ids.toSet()
        assertEquals(n, distinct.size)
        assertEquals(0, distinct.min())
        assertEquals(n - 1, distinct.max())
    }

    @Test fun mergeBelowCapIsNoOp() {
        // numComps = 1 (one triangle) <= maxComps (32) → return unchanged.
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0.5f, 1f, 0f)
        val (ids, n) = MeshSegmenter.segmentByTopology(positions, maxComponents = 32)
        assertEquals(1, n)
        assertEquals(1, ids.size)
        assertEquals(0, ids[0])
    }
}
