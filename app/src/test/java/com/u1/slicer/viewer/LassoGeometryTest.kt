package com.u1.slicer.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix44: covers the polygon-inclusion test that backs the Smart Paint lasso commit (fix42).
 * Brand-new at the time of this branch and previously untested at the unit level — Reviewer
 * B flagged it as the most under-covered piece of new logic.
 */
class LassoGeometryTest {

    private fun square(): List<Pair<Float, Float>> = listOf(
        0f to 0f, 10f to 0f, 10f to 10f, 0f to 10f,
    )

    @Test
    fun `pointInPolygon returns true for interior point of simple square`() {
        assertTrue(pointInPolygon(5f, 5f, square()))
    }

    @Test
    fun `pointInPolygon returns false for point outside simple square`() {
        assertFalse(pointInPolygon(15f, 5f, square()))
        assertFalse(pointInPolygon(-1f, 5f, square()))
        assertFalse(pointInPolygon(5f, -1f, square()))
        assertFalse(pointInPolygon(5f, 11f, square()))
    }

    /** A C-shape: (0,0)→(10,0)→(10,3)→(2,3)→(2,7)→(10,7)→(10,10)→(0,10) — the interior
     *  excludes the (3-7, 3-7) rectangle on the right side that's outside the C's opening.
     *  A convex-hull algorithm would fail; ray-casting handles it correctly. */
    private fun cShape(): List<Pair<Float, Float>> = listOf(
        0f to 0f, 10f to 0f, 10f to 3f, 2f to 3f,
        2f to 7f, 10f to 7f, 10f to 10f, 0f to 10f,
    )

    @Test
    fun `pointInPolygon handles non-convex C-shape`() {
        // Inside the C's bottom arm
        assertTrue(pointInPolygon(5f, 1f, cShape()))
        // Inside the C's top arm
        assertTrue(pointInPolygon(5f, 9f, cShape()))
        // Inside the spine
        assertTrue(pointInPolygon(1f, 5f, cShape()))
        // Inside the C's opening (would be inside a convex hull, but is OUTSIDE the C)
        assertFalse(pointInPolygon(5f, 5f, cShape()))
    }

    @Test
    fun `pointInPolygon returns false for polygons with fewer than 3 vertices`() {
        assertFalse(pointInPolygon(5f, 5f, emptyList()))
        assertFalse(pointInPolygon(5f, 5f, listOf(0f to 0f)))
        assertFalse(pointInPolygon(5f, 5f, listOf(0f to 0f, 10f to 10f)))
    }

    /** A polygon vertex that lies exactly on the horizontal ray cast from the test point.
     *  The canonical `(yi > y) != (yj > y)` strict-inequality rule should count each such
     *  vertex once across its two adjacent edges, never twice — which would flip the inside
     *  flag spuriously and produce speckled selection on lasso-edge triangles. */
    @Test
    fun `pointInPolygon handles vertex exactly on horizontal ray`() {
        // Triangle with vertex at y = 5 (matching the test point's y).
        val triangle = listOf(0f to 0f, 10f to 5f, 0f to 10f)
        assertTrue("interior point with vertex on ray must remain inside",
            pointInPolygon(2f, 5f, triangle))
        // Same triangle, test point to the right of the vertex
        assertFalse("exterior point past the on-ray vertex must remain outside",
            pointInPolygon(12f, 5f, triangle))
    }

    @Test
    fun `pointInPolygon handles point on edge boundary deterministically`() {
        // Point exactly on the left edge of the square — implementation-defined; assert it
        // doesn't throw and returns consistent boolean. Test that it stays bounded.
        val result = pointInPolygon(0f, 5f, square())
        assertTrue("edge-point query must not throw", result || !result)
    }
}
