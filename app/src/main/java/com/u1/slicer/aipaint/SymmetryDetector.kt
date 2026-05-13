package com.u1.slicer.aipaint

import kotlin.math.sqrt

/**
 * Detects bilaterally symmetric component pairs across the X-axis. Most printable models
 * (figures, animals, characters) sit with their left/right symmetry plane aligned to YZ —
 * mirroring a component's centroid across X = modelCenterX and finding the nearest other
 * component identifies left/right pairs (eyes, legs, ears, wings, …).
 *
 * Returns an IntArray of size `numComponents` where `rep[i]` is the representative index
 * for component `i`. Paired components share a representative; unpaired components are
 * their own representative. A union-find collapse via repeated rep-lookup is not needed
 * — pairs are 2-cycles only.
 *
 * Used by [AiPaintViewModel] to fuse symmetric components into a single "visual component"
 * before rendering and AI grouping, so the AI sees one blob for both eyes (forced same
 * group) and one blob for each leg pair. After the AI returns groupings, each pair is
 * expanded back to its constituent components.
 */
object SymmetryDetector {

    private const val DEFAULT_TOLERANCE_FRACTION = 0.05f

    fun detectPairsByX(
        trianglePositions: FloatArray,
        componentIds: IntArray,
        numComponents: Int,
        toleranceFraction: Float = DEFAULT_TOLERANCE_FRACTION
    ): IntArray {
        if (numComponents <= 1) return IntArray(numComponents) { it }

        // Per-component centroid + triangle count
        val cx = FloatArray(numComponents)
        val cy = FloatArray(numComponents)
        val cz = FloatArray(numComponents)
        val cnt = IntArray(numComponents)
        for (t in componentIds.indices) {
            val comp = componentIds[t]
            val b = t * 9
            cx[comp] += (trianglePositions[b]     + trianglePositions[b + 3] + trianglePositions[b + 6]) / 3f
            cy[comp] += (trianglePositions[b + 1] + trianglePositions[b + 4] + trianglePositions[b + 7]) / 3f
            cz[comp] += (trianglePositions[b + 2] + trianglePositions[b + 5] + trianglePositions[b + 8]) / 3f
            cnt[comp]++
        }
        for (c in 0 until numComponents) {
            if (cnt[c] > 0) {
                val n = cnt[c].toFloat()
                cx[c] = cx[c] / n
                cy[c] = cy[c] / n
                cz[c] = cz[c] / n
            }
        }

        // Mesh centre on X, weighted by triangle count, plus X span for tolerance scaling.
        var sumX = 0f
        var totalTris = 0
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        for (c in 0 until numComponents) {
            if (cnt[c] == 0) continue
            sumX += cx[c] * cnt[c]
            totalTris += cnt[c]
            if (cx[c] < minX) minX = cx[c]
            if (cx[c] > maxX) maxX = cx[c]
        }
        if (totalTris == 0) return IntArray(numComponents) { it }
        val centerX = sumX / totalTris
        val xSpan = (maxX - minX).coerceAtLeast(1e-3f)

        // For Y/Z tolerance use the full mesh extent on those axes — a left/right pair should
        // have near-identical Y and Z centroids, scaled to model size.
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
        for (c in 0 until numComponents) {
            if (cnt[c] == 0) continue
            if (cy[c] < minY) minY = cy[c]; if (cy[c] > maxY) maxY = cy[c]
            if (cz[c] < minZ) minZ = cz[c]; if (cz[c] > maxZ) maxZ = cz[c]
        }
        val ySpan = (maxY - minY).coerceAtLeast(1e-3f)
        val zSpan = (maxZ - minZ).coerceAtLeast(1e-3f)
        // Use the maximum span as the reference: a single tolerance ratio applied uniformly.
        val refSpan = maxOf(xSpan, ySpan, zSpan)
        val tol = refSpan * toleranceFraction

        val rep = IntArray(numComponents) { it }

        for (a in 0 until numComponents) {
            if (cnt[a] == 0) continue
            if (rep[a] != a) continue  // already taken as a mirror partner

            // Skip components whose centroid sits on the symmetry plane — they're
            // their own mirror image (e.g. a goat's body / torso / head ridge).
            val ax = cx[a]
            val mirroredX = 2f * centerX - ax
            if (kotlin.math.abs(ax - mirroredX) < tol) continue

            var bestB = -1
            var bestDist = Float.POSITIVE_INFINITY
            for (b in (a + 1) until numComponents) {
                if (cnt[b] == 0) continue
                if (rep[b] != b) continue
                val dx = cx[b] - mirroredX
                val dy = cy[b] - cy[a]
                val dz = cz[b] - cz[a]
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist < bestDist && dist < tol) {
                    bestDist = dist
                    bestB = b
                }
            }
            if (bestB >= 0) rep[bestB] = a
        }
        return rep
    }

    /**
     * Given a representative array from [detectPairsByX], produces:
     *  - `aiIdOf`: component → dense AI-component id in `[0, aiCount)`
     *  - `origForAiId`: list, indexed by AI id, of the original component indices folded
     *     into that AI component
     */
    fun buildAiComponentMap(rep: IntArray): Pair<IntArray, List<List<Int>>> {
        val n = rep.size
        val aiIdForRep = IntArray(n) { -1 }
        val origForAi = mutableListOf<MutableList<Int>>()
        var next = 0
        // First pass: assign AI ids to representatives in ascending order.
        for (c in 0 until n) {
            val r = rep[c]
            if (aiIdForRep[r] == -1) {
                aiIdForRep[r] = next
                origForAi.add(mutableListOf())
                next++
            }
        }
        val aiIdOf = IntArray(n) { aiIdForRep[rep[it]] }
        // Second pass: fill the origForAi buckets.
        for (c in 0 until n) origForAi[aiIdOf[c]].add(c)
        return Pair(aiIdOf, origForAi.map { it.toList() })
    }
}
