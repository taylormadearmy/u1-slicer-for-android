package com.u1.slicer.aipaint

import kotlin.math.min

/**
 * Spatial K-means clustering for the recursive sub-division of a dominant topology component.
 * Operates on per-triangle centroids; K bounded by the input size. Sub-regions are emitted as
 * AiRegionNode leaves tagged TOPOLOGY_RECURSIVE.
 */
object TopologyRecursion {

    fun subdivide(
        positions: FloatArray,
        triangleIds: IntArray,
        kMeansK: Int,
        startId: Int,
    ): List<AiRegionNode> {
        val n = triangleIds.size
        if (n == 0) return emptyList()
        val k = min(kMeansK, n)

        val centroids = Array(n) { i ->
            val t = triangleIds[i]
            val b = t * 9
            floatArrayOf(
                (positions[b + 0] + positions[b + 3] + positions[b + 6]) / 3f,
                (positions[b + 1] + positions[b + 4] + positions[b + 7]) / 3f,
                (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f,
            )
        }

        // Farthest-point seeding for determinism (no random).
        val means = Array(k) { FloatArray(3) }
        means[0] = centroids[0].copyOf()
        for (s in 1 until k) {
            var bestIdx = 0; var bestDist = -1f
            for (i in 0 until n) {
                var minD = Float.POSITIVE_INFINITY
                for (j in 0 until s) {
                    val dx = centroids[i][0] - means[j][0]
                    val dy = centroids[i][1] - means[j][1]
                    val dz = centroids[i][2] - means[j][2]
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < minD) minD = d
                }
                if (minD > bestDist) { bestDist = minD; bestIdx = i }
            }
            means[s] = centroids[bestIdx].copyOf()
        }

        // Lloyd's iteration — bounded (8 rounds is plenty for k ≤ 12 on small inputs).
        val labels = IntArray(n)
        for (iter in 0 until 8) {
            var changed = false
            for (i in 0 until n) {
                var bestK = 0; var bestD = Float.POSITIVE_INFINITY
                for (j in 0 until k) {
                    val dx = centroids[i][0] - means[j][0]
                    val dy = centroids[i][1] - means[j][1]
                    val dz = centroids[i][2] - means[j][2]
                    val d = dx * dx + dy * dy + dz * dz
                    if (d < bestD) { bestD = d; bestK = j }
                }
                if (labels[i] != bestK) { labels[i] = bestK; changed = true }
            }
            if (!changed) break
            // Update means.
            val sums = Array(k) { FloatArray(3) }
            val counts = IntArray(k)
            for (i in 0 until n) {
                val l = labels[i]
                sums[l][0] += centroids[i][0]
                sums[l][1] += centroids[i][1]
                sums[l][2] += centroids[i][2]
                counts[l]++
            }
            for (j in 0 until k) {
                if (counts[j] > 0) {
                    means[j][0] = sums[j][0] / counts[j]
                    means[j][1] = sums[j][1] / counts[j]
                    means[j][2] = sums[j][2] / counts[j]
                }
            }
        }

        // Group by label; drop empty clusters.
        val grouped = Array(k) { mutableListOf<Int>() }
        for (i in 0 until n) grouped[labels[i]].add(triangleIds[i])
        val nonEmpty = grouped.filter { it.isNotEmpty() }

        return nonEmpty.mapIndexed { i, tris ->
            AiRegionNode(
                region = AiRegion(
                    id = startId + i,
                    label = "Sub-region ${i + 1}",
                    suggestedColour = SegmentationCascade.paletteFor(startId + i),
                    coverageFraction = tris.size.toFloat() / (positions.size / 9),
                    slot = (startId + i) % SegmentationCascade.TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.TOPOLOGY_RECURSIVE,
                triangleIds = tris.toIntArray(),
            )
        }
    }
}
