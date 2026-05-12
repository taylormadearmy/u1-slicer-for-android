package com.u1.slicer.aipaint

object MeshSegmenter {

    // boundaryPcts: N+1 boundary values for N regions, e.g. [0, 25, 50, 75, 100].
    // Returns per-triangle region index 0..N-1 based on Z height (Z=up in 3D printing).
    // Triangles are assigned to whichever band their Z centroid falls into.
    fun segmentByBounds(positions: FloatArray, boundaryPcts: FloatArray): IntArray {
        val nTri = positions.size / 9
        val nRegions = boundaryPcts.size - 1
        if (nTri == 0 || nRegions <= 0) return IntArray(nTri) { 0 }

        val zCentroids = FloatArray(nTri) { i ->
            val b = i * 9
            (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
        }
        val minZ = zCentroids.minOrNull() ?: return IntArray(nTri) { 0 }
        val maxZ = zCentroids.maxOrNull() ?: return IntArray(nTri) { 0 }
        val zRange = maxZ - minZ

        return IntArray(nTri) { i ->
            val pct = if (zRange > 0f) (zCentroids[i] - minZ) / zRange * 100f else 50f
            var region = nRegions - 1
            for (r in 0 until nRegions) {
                if (pct < boundaryPcts[r + 1]) { region = r; break }
            }
            region
        }
    }

    fun coverageFractions(regionIds: IntArray, targetRegions: Int): FloatArray {
        val counts = IntArray(targetRegions)
        regionIds.forEach { counts[it]++ }
        val total = regionIds.size.toFloat()
        return FloatArray(targetRegions) { counts[it] / total }
    }
}
