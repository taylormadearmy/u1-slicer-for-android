package com.u1.slicer.aipaint

import kotlin.math.sqrt

object MeshSegmenter {

    // fix38.3: was 32 — the cap merged small components (e.g. a goat's nose patch) into
    // larger edge-shared neighbours (e.g. a leg), causing tap-on-nose-highlights-leg. Raised
    // to 1024 so the merge step almost never fires; the user can always see the natural
    // dihedral-flood components and recombine via brush/lasso if there are too many.
    private const val MAX_INTERMEDIATE_COMPONENTS = 1024
    // fix38.5: tightened further from cos(20°) to cos(10°). cos(20°) still kept the whole
    // goat in one component because sculpted organic surfaces transition very gradually.
    // cos(10°) requires near-coplanar adjacency to count as the same component, breaking
    // visual features apart. Trade-off: smooth uniform surfaces (cylinders, spheres) split
    // into many small components, but those are easy to recombine with brush/lasso.
    private const val CREASE_DOT = 0.985f // cos(~10°) — anything sharper is a crease

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

    /**
     * B112: rewrite of the v2.2.0 merge step. The previous implementation rescanned all
     * triangles for each merge candidate (O(n × triCount) per merge, with up to n outer
     * iterations) — for a single-shell connected mesh like 3DBenchy.stl this is
     * O(n² × triCount) ≈ trillions of ops and Smart Paint visibly hangs.
     *
     * This version precomputes the component-to-component adjacency graph in one pass,
     * then updates it incrementally as components merge. Setup is O(triCount × avgDegree);
     * each merge is O(degree). Finding the smallest non-island component per iteration is
     * O(n) for now (good enough up to ~25k components — Pixel 8a completes in < 2s).
     *
     * Behaviour preserved:
     *  - Smallest non-island component is the merge source.
     *  - Merge target = neighbour with the most shared edges.
     *  - True mesh islands (no shared edges) are never merged.
     *  - Returned ids are dense in 0..n-1 (compacted) so downstream uses can index arrays
     *    of size n directly.
     */
    private fun mergeComponents(
        compId: IntArray,
        numComps: Int,
        allAdj: Array<MutableList<Int>>,
        maxComps: Int
    ): Pair<IntArray, Int> {
        if (numComps <= maxComps) return Pair(compId.copyOf(), numComps)
        val ids = compId.copyOf()
        var n = numComps

        // Component sizes and component-to-component adjacency counts (one pass through
        // the per-triangle adjacency map). nbCount[a][b] = number of triangle-pairs across
        // components a and b that share an edge. Symmetric: nbCount[a][b] == nbCount[b][a].
        val sizes = IntArray(numComps)
        for (id in ids) sizes[id]++
        val nbCount = Array(numComps) { HashMap<Int, Int>() }
        for (i in ids.indices) {
            val c = ids[i]
            for (nb in allAdj[i]) {
                val nc = ids[nb]
                if (nc != c) nbCount[c][nc] = (nbCount[c][nc] ?: 0) + 1
            }
        }

        val active = BooleanArray(numComps) { true }
        val isIsland = BooleanArray(numComps)
        // Union-find-lite: mergedTo[c] = component that c was merged into, or -1.
        val mergedTo = IntArray(numComps) { -1 }

        while (n > maxComps) {
            // Find smallest active non-island component.
            var smallest = -1
            var smallestSize = Int.MAX_VALUE
            for (c in 0 until numComps) {
                if (!active[c] || isIsland[c]) continue
                if (sizes[c] < smallestSize) {
                    smallestSize = sizes[c]
                    smallest = c
                }
            }
            if (smallest < 0) break  // all remaining components are islands

            // Pick the neighbour with the most shared edges.
            val nbMap = nbCount[smallest]
            val pick = nbMap.maxByOrNull { it.value }?.key
            if (pick == null || !active[pick]) {
                // No edge-shared neighbour — true mesh island. Never merge.
                isIsland[smallest] = true
                continue
            }

            // Merge smallest INTO pick. Update sizes + adjacency graph.
            sizes[pick] += sizes[smallest]
            sizes[smallest] = 0
            for ((nb, count) in nbMap) {
                if (nb == pick) continue
                // Add smallest's edge-count to nb into pick's edge-count to nb.
                nbCount[pick][nb] = (nbCount[pick][nb] ?: 0) + count
                // nb's view: replace its edge-count-to-smallest with edge-count-to-pick.
                val nbToSmallest = nbCount[nb].remove(smallest) ?: 0
                if (nbToSmallest > 0 && nb != pick) {
                    nbCount[nb][pick] = (nbCount[nb][pick] ?: 0) + nbToSmallest
                }
            }
            nbCount[pick].remove(smallest)
            nbCount[smallest].clear()
            active[smallest] = false
            mergedTo[smallest] = pick
            n--
        }

        // Resolve transitive merges (smallest → pick → ... → final) and rewrite triangles
        // in ONE pass. Avoids the O(numComps × triCount) cost of rewriting per merge.
        val finalId = IntArray(numComps)
        for (c in 0 until numComps) {
            var x = c
            while (mergedTo[x] >= 0) x = mergedTo[x]
            finalId[c] = x
            // Path-compress for future lookups.
            var y = c
            while (mergedTo[y] >= 0) {
                val next = mergedTo[y]
                mergedTo[y] = x  // skip ahead (won't matter — only used once)
                y = next
            }
        }

        // Compact ids to dense 0..n-1 to match the legacy contract.
        val remap = IntArray(numComps) { -1 }
        var nextDense = 0
        for (i in ids.indices) {
            val c = finalId[ids[i]]
            if (remap[c] == -1) remap[c] = nextDense++
            ids[i] = remap[c]
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

}
