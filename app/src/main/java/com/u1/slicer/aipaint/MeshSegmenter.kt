package com.u1.slicer.aipaint

object MeshSegmenter {

    /**
     * Segments a mesh into [targetRegions] regions using BVH-based thickness detection
     * combined with vertical (Z) position. K-means clusters triangles in the 2D feature
     * space (Z%, normalisedThickness), so thin appendages (legs, horns, ears) at the
     * same Z level as thick parts (torso, head) land in different regions.
     *
     * Regions are re-labelled 0..targetRegions-1 in ascending mean-Z order so that
     * region 0 is always the lowest part of the model.
     */
    fun segmentByThickness(positions: FloatArray, targetRegions: Int = 4): IntArray {
        val nTri = positions.size / 9
        if (nTri == 0) return IntArray(0)
        if (nTri < targetRegions) return IntArray(nTri) { 0 }

        // Feature 1: normalised Z centroid (0 = bottom, 1 = top)
        val zCents = FloatArray(nTri) { i ->
            val b = i * 9
            (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
        }
        val minZ = zCents.minOrNull()!!
        val maxZ = zCents.maxOrNull()!!
        val zRange = maxZ - minZ
        val zPct = FloatArray(nTri) { i ->
            if (zRange > 0f) (zCents[i] - minZ) / zRange else 0.5f
        }

        // Feature 2: normalised local thickness (0 = infinitely thin, 1 = thickest triangle)
        val rawThick  = MeshThicknessComputer.compute(positions)
        val maxThick  = rawThick.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1f
        val thickNorm = FloatArray(nTri) { rawThick[it] / maxThick }

        // Seeds: spread via farthest-point initialisation (deterministic, no random)
        val means = Array(targetRegions) { FloatArray(2) }
        var bestDist = Float.MAX_VALUE; var seedIdx = 0
        for (i in 0 until nTri) {
            val d = zPct[i] * zPct[i] + thickNorm[i] * thickNorm[i]
            if (d < bestDist) { bestDist = d; seedIdx = i }
        }
        means[0][0] = zPct[seedIdx]; means[0][1] = thickNorm[seedIdx]

        for (k in 1 until targetRegions) {
            var maxMinDist = -1f; var nextIdx = 0
            for (i in 0 until nTri) {
                var minD = Float.MAX_VALUE
                for (j in 0 until k) {
                    val dz = zPct[i] - means[j][0]; val dt = thickNorm[i] - means[j][1]
                    val d = dz * dz + dt * dt
                    if (d < minD) minD = d
                }
                if (minD > maxMinDist) { maxMinDist = minD; nextIdx = i }
            }
            means[k][0] = zPct[nextIdx]; means[k][1] = thickNorm[nextIdx]
        }

        // K-means iterations
        val assignments = IntArray(nTri)
        repeat(20) {
            for (i in 0 until nTri) {
                var minD = Float.MAX_VALUE; var best = 0
                for (k in 0 until targetRegions) {
                    val dz = zPct[i] - means[k][0]; val dt = thickNorm[i] - means[k][1]
                    val d = dz * dz + dt * dt
                    if (d < minD) { minD = d; best = k }
                }
                assignments[i] = best
            }
            val sumZ = FloatArray(targetRegions); val sumT = FloatArray(targetRegions); val cnt = IntArray(targetRegions)
            for (i in 0 until nTri) {
                val k = assignments[i]; sumZ[k] += zPct[i]; sumT[k] += thickNorm[i]; cnt[k]++
            }
            for (k in 0 until targetRegions) {
                if (cnt[k] > 0) { means[k][0] = sumZ[k] / cnt[k]; means[k][1] = sumT[k] / cnt[k] }
            }
        }

        // Re-label by ascending mean-Z so region 0 is always the lowest part
        val order = (0 until targetRegions).sortedBy { means[it][0] }.toIntArray()
        val remap = IntArray(targetRegions)
        order.forEachIndexed { newId, oldId -> remap[oldId] = newId }
        return IntArray(nTri) { remap[assignments[it]] }
    }

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
