package com.u1.slicer.aipaint

object MeshSegmenter {

    fun segment(positions: FloatArray, targetRegions: Int = 4): IntArray {
        val nTri = positions.size / 9
        if (nTri == 0) return IntArray(0)
        if (nTri < targetRegions) return IntArray(nTri) { 0 }

        // Divide by Z-height (Z=up in 3D printing coordinate system) using
        // rank-percentile banding so each region gets an equal share of triangles
        // regardless of how geometry is distributed vertically.
        // This produces semantically useful regions (head/body/legs/feet) for
        // typical upright models.
        val zCentroids = FloatArray(nTri) { i ->
            val b = i * 9
            (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
        }
        val sortedIndices = (0 until nTri).sortedBy { zCentroids[it] }.toIntArray()
        val ids = IntArray(nTri)
        sortedIndices.forEachIndexed { rank, triIdx ->
            ids[triIdx] = rank * targetRegions / nTri
        }
        return ids
    }

    fun coverageFractions(regionIds: IntArray, targetRegions: Int): FloatArray {
        val counts = IntArray(targetRegions)
        regionIds.forEach { counts[it]++ }
        val total = regionIds.size.toFloat()
        return FloatArray(targetRegions) { counts[it] / total }
    }
}
