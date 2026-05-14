package com.u1.slicer.aipaint

import kotlin.math.sqrt

object MeshSegmenter {

    // fix38.3: was 32 — the cap merged small components (e.g. a goat's nose patch) into
    // larger edge-shared neighbours (e.g. a leg), causing tap-on-nose-highlights-leg. Raised
    // to 1024 so the merge step almost never fires; the user can always see the natural
    // dihedral-flood components and recombine via brush/lasso if there are too many.
    private const val MAX_INTERMEDIATE_COMPONENTS = 1024
    // fix38.4: was cos(45°) — that flood-filled goat's nose, ear, leg etc. into one giant
    // component because organic models have continuous smooth transitions across visual
    // features. Tightened to cos(20°) so only very smooth surfaces (within a 20° dihedral)
    // count as the same component. Sculpted features now generally land in separate regions.
    private const val CREASE_DOT = 0.94f // cos(~20°) — dihedral angles sharper than 20° are treated as creases

    /**
     * Segments a mesh by surface topology using dihedral-angle flood fill.
     * Triangles separated by crease edges (angle > 45°) form separate components.
     * Merges small components until at most [maxComponents] remain.
     *
     * Returns (componentIdPerTriangle, numComponents).
     */
    fun segmentByTopology(
        positions: FloatArray,
        maxComponents: Int = MAX_INTERMEDIATE_COMPONENTS
    ): Pair<IntArray, Int> {
        val nTri = positions.size / 9
        if (nTri == 0) return Pair(IntArray(0), 0)

        val normals = Array(nTri) { i -> triNormal(positions, i * 9) }

        // Build edge → triangle list (all manifold edges only)
        val edgeMap = HashMap<Long, MutableList<Int>>(nTri * 2)
        for (i in 0 until nTri) {
            val b = i * 9
            for (e in 0..2) {
                val va = b + e * 3
                val vb = b + (e + 1) % 3 * 3
                edgeMap.getOrPut(edgeKey(positions, va, vb)) { mutableListOf() }.add(i)
            }
        }

        // Smooth adjacency for flood fill; all-edge adjacency for component merging
        val smoothAdj = Array(nTri) { mutableListOf<Int>() }
        val allAdj    = Array(nTri) { mutableListOf<Int>() }
        for ((_, tris) in edgeMap) {
            if (tris.size != 2) continue
            val a = tris[0]; val b = tris[1]
            allAdj[a].add(b); allAdj[b].add(a)
            val na = normals[a]; val nb = normals[b]
            if (na[0]*nb[0] + na[1]*nb[1] + na[2]*nb[2] >= CREASE_DOT) {
                smoothAdj[a].add(b); smoothAdj[b].add(a)
            }
        }

        // BFS flood fill → connected components
        val compId = IntArray(nTri) { -1 }
        var numComps = 0
        for (start in 0 until nTri) {
            if (compId[start] != -1) continue
            val q = ArrayDeque<Int>()
            q.add(start); compId[start] = numComps
            while (q.isNotEmpty()) {
                val tri = q.removeFirst()
                for (nb in smoothAdj[tri]) {
                    if (compId[nb] == -1) { compId[nb] = numComps; q.add(nb) }
                }
            }
            numComps++
        }

        return mergeComponents(compId, numComps, allAdj, maxComponents)
    }

    private fun mergeComponents(
        compId: IntArray,
        numComps: Int,
        allAdj: Array<MutableList<Int>>,
        maxComps: Int
    ): Pair<IntArray, Int> {
        val ids = compId.copyOf()
        var n = numComps

        // Components that are geometrically disconnected mesh islands (zero shared edges with any
        // other component) are preserved untouched. Otherwise the merge step would collapse e.g.
        // a goat's free-floating eye disc into the body just because it's small — and the user
        // would no longer be able to tap the eye to assign it its own colour. We always keep
        // true islands as their own component, even at the cost of exceeding [maxComps].
        while (n > maxComps) {
            val size = IntArray(n)
            for (id in ids) size[id]++

            val skip = BooleanArray(n) // components with no edge-shared neighbour
            val candidates = (0 until n).sortedBy { size[it] }
            var smallest = -1
            var mergeInto = -1
            for (cand in candidates) {
                if (skip[cand]) continue
                val adjCount = HashMap<Int, Int>()
                for (i in ids.indices) {
                    if (ids[i] != cand) continue
                    for (nb in allAdj[i]) {
                        val nc = ids[nb]
                        if (nc != cand) adjCount[nc] = (adjCount[nc] ?: 0) + 1
                    }
                }
                val pick = adjCount.maxByOrNull { it.value }?.key
                if (pick == null) {
                    // No edge-shared neighbour — true mesh island. Never merge it.
                    skip[cand] = true
                    continue
                }
                smallest = cand
                mergeInto = pick
                break
            }
            if (smallest < 0) break  // all remaining components are islands

            // Merge smallest → mergeInto; keep id space dense by renaming n-1 → smallest
            for (i in ids.indices) if (ids[i] == smallest) ids[i] = mergeInto
            if (smallest != n - 1) {
                for (i in ids.indices) if (ids[i] == n - 1) ids[i] = smallest
            }
            n--
        }

        return Pair(ids, n)
    }

    private fun triNormal(positions: FloatArray, base: Int): FloatArray {
        val ax = positions[base + 3] - positions[base]
        val ay = positions[base + 4] - positions[base + 1]
        val az = positions[base + 5] - positions[base + 2]
        val bx = positions[base + 6] - positions[base]
        val by = positions[base + 7] - positions[base + 1]
        val bz = positions[base + 8] - positions[base + 2]
        val nx = ay * bz - az * by
        val ny = az * bx - ax * bz
        val nz = ax * by - ay * bx
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
        return floatArrayOf(nx / len, ny / len, nz / len)
    }

    private fun vertexKey(positions: FloatArray, base: Int): Long {
        // 20-bit per axis (±524mm at 1mm/1000 precision): 3×20 = 60 bits, fits in positive Long
        val x = Math.round(positions[base]     * 1000f).toLong().coerceIn(-524_288, 524_287)
        val y = Math.round(positions[base + 1] * 1000f).toLong().coerceIn(-524_288, 524_287)
        val z = Math.round(positions[base + 2] * 1000f).toLong().coerceIn(-524_288, 524_287)
        return ((x + 524_288L) shl 40) or ((y + 524_288L) shl 20) or (z + 524_288L)
    }

    private fun edgeKey(positions: FloatArray, va: Int, vb: Int): Long {
        val k1 = vertexKey(positions, va)
        val k2 = vertexKey(positions, vb)
        // Canonical: min * PRIME + max — same for either edge direction
        // 6364136223846793005L is Knuth's multiplicative hash constant (fits in positive Long)
        return if (k1 <= k2) k1 * 6364136223846793005L + k2
        else k2 * 6364136223846793005L + k1
    }


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

    /**
     * Spatial K-means clustering on per-triangle centroids. Produces exactly [k] components
     * that partition the model into 3D spatial regions, regardless of mesh topology. Used as
     * the fallback path when [segmentByTopology] returns a degenerate result (one component
     * covers almost everything because the model is a single smooth-connected surface — cat
     * pots, vases, organic blobs).
     *
     * Deterministic via farthest-point seeding from the first triangle. Runs [iterations]
     * Lloyd updates; 15 is usually enough for spatial convergence on decimated previews
     * (≤50k triangles).
     */
    fun segmentBySpatialKMeans(positions: FloatArray, k: Int, iterations: Int = 15): Pair<IntArray, Int> {
        val triCount = positions.size / 9
        if (triCount == 0 || k <= 0) return Pair(IntArray(triCount) { 0 }, 0)
        if (triCount <= k) return Pair(IntArray(triCount) { it.coerceAtMost(k - 1) }, minOf(triCount, k))

        val cents = Array(triCount) { i ->
            val b = i * 9
            floatArrayOf(
                (positions[b]     + positions[b + 3] + positions[b + 6]) / 3f,
                (positions[b + 1] + positions[b + 4] + positions[b + 7]) / 3f,
                (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            )
        }

        val seeds = Array(k) { FloatArray(3) }
        seeds[0] = cents[0].copyOf()
        for (s in 1 until k) {
            var bestDist = -1f
            var bestIdx = 0
            for (i in 0 until triCount) {
                var minD = Float.MAX_VALUE
                for (j in 0 until s) {
                    val dx = cents[i][0] - seeds[j][0]
                    val dy = cents[i][1] - seeds[j][1]
                    val dz = cents[i][2] - seeds[j][2]
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < minD) minD = d
                }
                if (minD > bestDist) { bestDist = minD; bestIdx = i }
            }
            seeds[s] = cents[bestIdx].copyOf()
        }

        val assignments = IntArray(triCount)
        repeat(iterations) {
            for (i in 0 until triCount) {
                var bestD = Float.MAX_VALUE
                var best = 0
                for (j in 0 until k) {
                    val dx = cents[i][0] - seeds[j][0]
                    val dy = cents[i][1] - seeds[j][1]
                    val dz = cents[i][2] - seeds[j][2]
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < bestD) { bestD = d; best = j }
                }
                assignments[i] = best
            }
            val sumX = FloatArray(k); val sumY = FloatArray(k); val sumZ = FloatArray(k); val cnt = IntArray(k)
            for (i in 0 until triCount) {
                val a = assignments[i]
                sumX[a] += cents[i][0]; sumY[a] += cents[i][1]; sumZ[a] += cents[i][2]; cnt[a]++
            }
            for (j in 0 until k) {
                if (cnt[j] > 0) {
                    seeds[j][0] = sumX[j] / cnt[j]
                    seeds[j][1] = sumY[j] / cnt[j]
                    seeds[j][2] = sumZ[j] / cnt[j]
                }
            }
        }
        return Pair(assignments, k)
    }

    /**
     * Adaptive segmentation entry point used by AI Paint. Tries [segmentByTopology] first;
     * if the result is "degenerate" — one component covers more than [dominantThreshold] of
     * the mesh — replaces it with a spatial K-means clustering of [targetSpatial] components
     * so the AI has visibly distinct regions to label.
     *
     * Catches the common case of single-shell organic prints (cat pots, vases, character
     * busts) where topology can't find creases to split on.
     */
    fun segmentByTopologyOrSpatial(
        positions: FloatArray,
        topologyCap: Int = 32,
        // 32 spatial clusters gives the user a usable granularity for the manual paint mode
        // — each cluster is roughly one "tap-target" on a smooth shell like the cat pot.
        targetSpatial: Int = 32,
        dominantThreshold: Float = 0.7f
    ): Pair<IntArray, Int> {
        val triCount = positions.size / 9
        if (triCount == 0) return Pair(IntArray(0), 0)

        val (topoIds, topoN) = segmentByTopology(positions, maxComponents = topologyCap)
        if (topoN <= 1) return segmentBySpatialKMeans(positions, targetSpatial)

        val sizes = IntArray(topoN)
        for (id in topoIds) sizes[id]++
        val dominantId = sizes.indices.maxByOrNull { sizes[it] } ?: return Pair(topoIds, topoN)
        val dominantPct = sizes[dominantId].toFloat() / triCount
        return if (dominantPct < dominantThreshold) Pair(topoIds, topoN)
        else segmentBySpatialKMeans(positions, targetSpatial)
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
