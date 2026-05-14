package com.u1.slicer.viewer

/**
 * fix44: extracted from `ModelViewerView` so the polygon-inclusion test is unit-testable
 * without spinning up a GLSurfaceView. Used by the lasso pick path (fix42) — every triangle
 * centroid projected into screen space is tested against the lasso loop here.
 *
 * Standard horizontal-ray crossing count: a point is inside the polygon when a ray cast to
 * the right crosses an odd number of edges. Handles arbitrary non-convex loops. Returns
 * false for any polygon with fewer than 3 vertices (a meaningful enclosure isn't possible).
 *
 * The `(yi > y) != (yj > y)` strict inequality is the canonical Sunday/Franklin formulation
 * — it avoids double-counting when a polygon vertex lies exactly on the horizontal ray.
 */
internal fun pointInPolygon(x: Float, y: Float, polygon: List<Pair<Float, Float>>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val (xi, yi) = polygon[i]
        val (xj, yj) = polygon[j]
        if ((yi > y) != (yj > y)) {
            val xIntersect = xi + (y - yi) * (xj - xi) / (yj - yi + 1e-9f)
            if (x < xIntersect) inside = !inside
        }
        j = i
    }
    return inside
}
