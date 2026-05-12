package com.u1.slicer.aipaint

object MeshSegmenter {

    fun segment(positions: FloatArray, targetRegions: Int = 4): IntArray {
        val nTri = positions.size / 9
        if (nTri == 0) return IntArray(0)
        if (nTri < targetRegions) return IntArray(nTri) { 0 }

        // Compute triangle centroids.
        val centroids = Array(nTri) { i ->
            val b = i * 9
            floatArrayOf(
                (positions[b]     + positions[b + 3] + positions[b + 6]) / 3f,
                (positions[b + 1] + positions[b + 4] + positions[b + 7]) / 3f,
                (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            )
        }

        // Initialise k seeds evenly spread across the centroid array.
        val step = nTri / targetRegions
        val means = Array(targetRegions) { r -> centroids[r * step].copyOf() }

        val ids = IntArray(nTri)
        repeat(15) {
            // Assignment: each triangle goes to the nearest mean.
            for (i in 0 until nTri) {
                var best = 0
                var bestDist = dist2(centroids[i], means[0])
                for (r in 1 until targetRegions) {
                    val d = dist2(centroids[i], means[r])
                    if (d < bestDist) { bestDist = d; best = r }
                }
                ids[i] = best
            }
            // Update: recompute each mean from its assigned triangles.
            val sums = Array(targetRegions) { FloatArray(3) }
            val counts = IntArray(targetRegions)
            for (i in 0 until nTri) {
                val r = ids[i]
                sums[r][0] += centroids[i][0]
                sums[r][1] += centroids[i][1]
                sums[r][2] += centroids[i][2]
                counts[r]++
            }
            for (r in 0 until targetRegions) {
                if (counts[r] > 0) {
                    means[r][0] = sums[r][0] / counts[r]
                    means[r][1] = sums[r][1] / counts[r]
                    means[r][2] = sums[r][2] / counts[r]
                }
            }
        }

        return remap(ids)
    }

    fun coverageFractions(regionIds: IntArray, targetRegions: Int): FloatArray {
        val counts = IntArray(targetRegions)
        regionIds.forEach { counts[it]++ }
        val total = regionIds.size.toFloat()
        return FloatArray(targetRegions) { counts[it] / total }
    }

    private fun dist2(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return dx * dx + dy * dy + dz * dz
    }

    private fun remap(ids: IntArray): IntArray {
        val seen = HashMap<Int, Int>()
        var next = 0
        return IntArray(ids.size) { seen.getOrPut(ids[it]) { next++ } }
    }
}
