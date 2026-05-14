package com.u1.slicer.aipaint

/** Output of one cascade branch (or the cascade as a whole). */
data class CascadeResult(
    val tree: List<AiRegionNode>,         // typically one cascade root; may include the
                                          // "Custom selections" group as a sibling later
    val triangleSegments: ByteArray,      // per-triangle leaf-id mapping; ByteArray so it
                                          // round-trips with state.triangleSegments
    val source: SegmentationSource,
)

object SegmentationCascade {

    const val TARGET_SLOTS = 4

    /** Default labels for Z-bands. Generic so a Benchy in fallback mode doesn't show
     *  "Hooves" — AI naming (if enabled) overrides these. */
    internal val Z_BAND_LABELS: List<String> = List(12) { "Band ${it + 1}" }
    internal val Z_BAND_COLOURS: List<String> = listOf(
        "#37474F", "#5D4037", "#795548", "#1E88E5", "#43A047",
        "#00ACC1", "#FB8C00", "#8E24AA", "#E53935", "#EC407A",
        "#FFEB3B", "#FFFFFF",
    )

    private val PALETTE = listOf(
        "#E53935", "#1E88E5", "#43A047", "#FB8C00",
        "#8E24AA", "#00ACC1", "#F4511E", "#6D4C41",
        "#EC407A", "#FFEB3B", "#FFFFFF", "#37474F",
    )

    internal fun paletteFor(i: Int): String = PALETTE[i % PALETTE.size]

    /** Triangle-share threshold for recursive sub-division: when ONE component owns more than
     *  this fraction of total triangles, we K-means-split it into sub-regions. */
    private const val DOMINANT_THRESHOLD = 0.60f

    /** Maximum leaves the cascade emits. The original UI cap; tree depth caps independently. */
    private const val TARGET_LEAVES = 12

    /** Branch E — topology flood-fill, with recursion on the dominant component. */
    fun topologyBranch(positions: FloatArray): CascadeResult {
        val (componentIds, numComponents) =
            MeshSegmenter.segmentByTopologyOrSpatial(positions)
        val triCount = positions.size / 9
        if (numComponents < 2) {
            return CascadeResult(emptyList(), ByteArray(triCount), SegmentationSource.TOPOLOGY)
        }

        // Triangle counts per component → descending order; keep top TARGET_LEAVES.
        val triByComp = Array(numComponents) { mutableListOf<Int>() }
        for (t in 0 until triCount) triByComp[componentIds[t]].add(t)
        val sortedComps = (0 until numComponents).sortedByDescending { triByComp[it].size }

        // Detect dominant component for recursion.
        val largestSize = triByComp[sortedComps.first()].size
        val largestFraction = largestSize.toFloat() / triCount
        val shouldRecurse = largestFraction > DOMINANT_THRESHOLD

        val baseLeaves = sortedComps.take(TARGET_LEAVES).mapIndexed { i, comp ->
            val tris = triByComp[comp].toIntArray()
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = "Region ${i + 1}",
                    suggestedColour = paletteFor(i),
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = i % TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.TOPOLOGY,
                triangleIds = tris,
            )
        }

        val children: List<AiRegionNode> = if (shouldRecurse) {
            // Replace the dominant leaf with one whose children are K-means sub-regions.
            val dominant = baseLeaves.first()
            val subRegions = TopologyRecursion.subdivide(
                positions = positions,
                triangleIds = dominant.triangleIds,
                kMeansK = 8,
                startId = TARGET_LEAVES,
            )
            listOf(dominant.copy(
                children = subRegions,
                nodeSource = SegmentationSource.TOPOLOGY_RECURSIVE,
            )) + baseLeaves.drop(1)
        } else {
            baseLeaves
        }

        // Triangle → segment id map. For recursive children, the SUB-region id wins.
        val triangleSegments = ByteArray(triCount)
        children.forEach { topChild ->
            if (topChild.children.isEmpty()) {
                topChild.triangleIds.forEach { t -> triangleSegments[t] = topChild.region.id.toByte() }
            } else {
                topChild.children.forEach { sub ->
                    sub.triangleIds.forEach { t -> triangleSegments[t] = sub.region.id.toByte() }
                }
            }
        }

        val root = AiRegionNode(
            region = AiRegion(
                id = -1,
                label = "Model",
                suggestedColour = "#888888",
                coverageFraction = 1f,
            ),
            children = children,
            nodeSource = if (shouldRecurse) SegmentationSource.TOPOLOGY_RECURSIVE else SegmentationSource.TOPOLOGY,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(
            tree = listOf(root),
            triangleSegments = triangleSegments,
            source = root.nodeSource,
        )
    }

    /** Branch F — equal-width Z-band segmentation. Always succeeds. */
    fun zBandBranch(positions: FloatArray, bandCount: Int = 12): CascadeResult {
        val triCount = positions.size / 9
        val bands = ByteArray(triCount)

        if (triCount == 0 || bandCount <= 0) {
            return CascadeResult(emptyList(), bands, SegmentationSource.Z_BAND)
        }
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (t in 0 until triCount) {
            val b = t * 9
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            if (cz < minZ) minZ = cz
            if (cz > maxZ) maxZ = cz
        }
        val span = (maxZ - minZ).coerceAtLeast(1e-3f)
        for (t in 0 until triCount) {
            val b = t * 9
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            val band = ((cz - minZ) / span * bandCount).toInt().coerceIn(0, bandCount - 1)
            bands[t] = band.toByte()
        }

        // Group triangle indices by band so the tree carries explicit membership.
        val perBand = Array(bandCount) { mutableListOf<Int>() }
        for (t in 0 until triCount) perBand[bands[t].toInt() and 0xFF].add(t)

        val children = (0 until bandCount).map { i ->
            val tris = perBand[i].toIntArray()
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = Z_BAND_LABELS.getOrElse(i) { "Band ${i + 1}" },
                    suggestedColour = Z_BAND_COLOURS.getOrElse(i) { "#888888" },
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = i % TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.Z_BAND,
                triangleIds = tris,
            )
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -1,
                label = "Model",
                suggestedColour = "#888888",
                coverageFraction = 1f,
            ),
            children = children,
            nodeSource = SegmentationSource.Z_BAND,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(listOf(root), bands, SegmentationSource.Z_BAND)
    }
}
