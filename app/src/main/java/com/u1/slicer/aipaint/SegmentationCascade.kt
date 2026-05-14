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

    /** fix39.3 — restored from fix30. Labels biased toward upright figurines (the common
     *  Smart Paint use case): bottom-to-top anatomy. Used by topology Z-banded grouping,
     *  which folds N flood-fill components into 12 height bands so bilateral pairs (left/
     *  right horns, eyes, legs, ears) share a band and thus a colour without needing AI. */
    internal val HEIGHT_BAND_LABELS: List<String> = listOf(
        "Base", "Hooves", "Lower legs", "Upper legs", "Belly",
        "Lower body", "Upper body", "Neck", "Head", "Crown",
        "Top", "Tip",
    )
    internal val HEIGHT_BAND_COLOURS: List<String> = listOf(
        "#37474F", "#5D4037", "#795548", "#1E88E5", "#43A047",
        "#00ACC1", "#FB8C00", "#8E24AA", "#E53935", "#EC407A",
        "#FFEB3B", "#FFFFFF",
    )
    private const val HEIGHT_BAND_COUNT = 12
    /** Minimum Z-span (post-coerce) for height-banding to make sense. Below this the components
     *  are effectively coplanar (a sheet, a coin) and per-component leaves remain the better
     *  choice — height grouping would collapse everything into one band. */
    private const val HEIGHT_SPAN_EPSILON = 1e-3f

    private val PALETTE = listOf(
        "#E53935", "#1E88E5", "#43A047", "#FB8C00",
        "#8E24AA", "#00ACC1", "#F4511E", "#6D4C41",
        "#EC407A", "#FFEB3B", "#FFFFFF", "#37474F",
    )

    internal fun paletteFor(i: Int): String = PALETTE[i % PALETTE.size]

    // (DOMINANT_THRESHOLD / RECURSION_ID_BASE removed in fix39.3 — topology now folds
    // components into height bands instead of recursively K-means-splitting the dominant
    // shell; the same end result without depending on K-means convergence quirks.)

    /** One per-plate object, as fed into the cascade. The triangleIds list MUST be exhaustive
     *  and disjoint across all objects on the plate; the cascade does no deduplication. */
    data class ObjectInfo(
        val objectId: Long,
        val name: String,
        /** 1-based extruder index from `model_settings.config`; null when undeclared. */
        val extruder: Int?,
        val triangleIds: IntArray,
    )

    /** Branch C — one tree leaf per object on the selected plate. */
    fun objectBranch(totalTriangles: Int, objects: List<ObjectInfo>): CascadeResult {
        if (objects.size < 2) {
            return CascadeResult(emptyList(), ByteArray(totalTriangles), SegmentationSource.OBJECT)
        }
        val triangleSegments = ByteArray(totalTriangles)
        val children = objects.mapIndexed { i, obj ->
            val slot = obj.extruder?.let { (it - 1).coerceIn(0, TARGET_SLOTS - 1) }
                ?: (i % TARGET_SLOTS)
            obj.triangleIds.forEach { t ->
                if (t in 0 until totalTriangles) triangleSegments[t] = i.toByte()
            }
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = obj.name,
                    suggestedColour = paletteFor(i),
                    coverageFraction = obj.triangleIds.size.toFloat() / totalTriangles,
                    slot = slot,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.OBJECT,
                triangleIds = obj.triangleIds,
            )
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -1, label = "Model", suggestedColour = "#888888", coverageFraction = 1f,
            ),
            children = children,
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = IntArray(totalTriangles) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.OBJECT)
    }

    data class VolumeInfo(
        val volumeIndex: Int,
        val extruder: Int?,
        val triangleIds: IntArray,
    )
    data class ObjectVolumes(
        val objectId: Long,
        val objectName: String,
        val volumes: List<VolumeInfo>,
    )

    /** Branch B — per-volume extruder. Nests volumes under their object when > 1 volume. */
    fun volumeBranch(totalTriangles: Int, objects: List<ObjectVolumes>): CascadeResult {
        val totalVolumes = objects.sumOf { it.volumes.size }
        if (totalVolumes < 2) {
            return CascadeResult(emptyList(), ByteArray(totalTriangles), SegmentationSource.VOLUME)
        }
        val triangleSegments = ByteArray(totalTriangles)
        var nextLeafId = 0
        // fix37: parents need unique ids too — previously every multi-volume object's parent
        // got id=-1, colliding with the cascade root (-2) and other parents. Multiple parents
        // with the same id broke findNodeById on tap-to-highlight.
        var nextParentId = -100

        val rootChildren = mutableListOf<AiRegionNode>()
        for (obj in objects) {
            if (obj.volumes.size == 1) {
                val v = obj.volumes.first()
                val slot = v.extruder?.let { (it - 1).coerceIn(0, TARGET_SLOTS - 1) }
                    ?: (nextLeafId % TARGET_SLOTS)
                val id = nextLeafId++
                v.triangleIds.forEach { t ->
                    if (t in 0 until totalTriangles) triangleSegments[t] = id.toByte()
                }
                rootChildren += AiRegionNode(
                    region = AiRegion(
                        id = id,
                        label = obj.objectName,
                        suggestedColour = paletteFor(id),
                        coverageFraction = v.triangleIds.size.toFloat() / totalTriangles,
                        slot = slot,
                    ),
                    children = emptyList(),
                    nodeSource = SegmentationSource.VOLUME,
                    triangleIds = v.triangleIds,
                )
            } else {
                val volChildren = obj.volumes.map { v ->
                    val slot = v.extruder?.let { (it - 1).coerceIn(0, TARGET_SLOTS - 1) }
                        ?: (nextLeafId % TARGET_SLOTS)
                    val id = nextLeafId++
                    v.triangleIds.forEach { t ->
                        if (t in 0 until totalTriangles) triangleSegments[t] = id.toByte()
                    }
                    AiRegionNode(
                        region = AiRegion(
                            id = id,
                            label = "${obj.objectName} · volume ${v.volumeIndex + 1}",
                            suggestedColour = paletteFor(id),
                            coverageFraction = v.triangleIds.size.toFloat() / totalTriangles,
                            slot = slot,
                        ),
                        children = emptyList(),
                        nodeSource = SegmentationSource.VOLUME,
                        triangleIds = v.triangleIds,
                    )
                }
                val objTris = obj.volumes.flatMap { it.triangleIds.toList() }.toIntArray()
                val parentId = nextParentId--
                rootChildren += AiRegionNode(
                    region = AiRegion(
                        id = parentId,
                        label = obj.objectName,
                        suggestedColour = paletteFor(0),
                        coverageFraction = objTris.size.toFloat() / totalTriangles,
                        slot = volChildren.first().region.slot,
                    ),
                    children = volChildren,
                    nodeSource = SegmentationSource.VOLUME,
                    triangleIds = objTris,
                )
            }
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -2, label = "Model", suggestedColour = "#888888", coverageFraction = 1f,
            ),
            children = rootChildren,
            nodeSource = SegmentationSource.VOLUME,
            triangleIds = IntArray(totalTriangles) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.VOLUME)
    }

    /** Branch A — pre-painted (MMU / H2C / SEMM). perTriangleState[t] = paint state 0..16
     *  (0 = unpainted; we ignore those when building leaves but include them as "state 0" if
     *  the user has unpainted triangles). */
    fun paintStateBranch(perTriangleState: ByteArray): CascadeResult {
        val triCount = perTriangleState.size
        val grouped = mutableMapOf<Int, MutableList<Int>>()
        for (t in 0 until triCount) {
            val s = perTriangleState[t].toInt() and 0xFF
            grouped.getOrPut(s) { mutableListOf() }.add(t)
        }
        if (grouped.size < 2) {
            return CascadeResult(emptyList(), perTriangleState.copyOf(), SegmentationSource.PAINT_STATE)
        }
        val sortedStates = grouped.keys.sorted()
        val triangleSegments = perTriangleState.copyOf()
        val children = sortedStates.mapIndexed { i, state ->
            val tris = grouped[state]!!.toIntArray()
            val slot = if (state == 0) i % TARGET_SLOTS else ((state - 1) % TARGET_SLOTS)
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = if (state == 0) "Unpainted" else "Paint state $state",
                    suggestedColour = paletteFor(i),
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = slot,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.PAINT_STATE,
                triangleIds = tris,
            )
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -1, label = "Model", suggestedColour = "#888888", coverageFraction = 1f,
            ),
            children = children,
            nodeSource = SegmentationSource.PAINT_STATE,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.PAINT_STATE)
    }

    /** Branch D — distinct preview-mesh extruder indices. Safety net. */
    fun triangleIndexBranch(perTriangleIndex: ByteArray): CascadeResult {
        val triCount = perTriangleIndex.size
        val grouped = mutableMapOf<Int, MutableList<Int>>()
        for (t in 0 until triCount) {
            val s = perTriangleIndex[t].toInt() and 0xFF
            grouped.getOrPut(s) { mutableListOf() }.add(t)
        }
        if (grouped.size < 2) {
            return CascadeResult(emptyList(), perTriangleIndex.copyOf(), SegmentationSource.TRIANGLE_INDEX)
        }
        val sortedKeys = grouped.keys.sorted()
        val triangleSegments = perTriangleIndex.copyOf()
        val children = sortedKeys.mapIndexed { i, idx ->
            val tris = grouped[idx]!!.toIntArray()
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = "Region ${i + 1}",
                    suggestedColour = paletteFor(i),
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = idx % TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.TRIANGLE_INDEX,
                triangleIds = tris,
            )
        }
        val root = AiRegionNode(
            region = AiRegion(
                id = -1, label = "Model", suggestedColour = "#888888", coverageFraction = 1f,
            ),
            children = children,
            nodeSource = SegmentationSource.TRIANGLE_INDEX,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.TRIANGLE_INDEX)
    }

    /** Branch E — topology flood-fill, then height-band components by centroid Z so that
     *  bilaterally symmetric features (left + right horns at the same Z) land in the same
     *  band and thus the same physical slot. Restores fix30's deterministic symmetric
     *  grouping without depending on a vision-capable AI provider. */
    fun topologyBranch(positions: FloatArray): CascadeResult {
        val (componentIds, numComponents) =
            MeshSegmenter.segmentByTopologyOrSpatial(positions)
        val triCount = positions.size / 9
        if (numComponents < 2) {
            return CascadeResult(emptyList(), ByteArray(triCount), SegmentationSource.TOPOLOGY)
        }

        // Per-component centroid Z and total Z span.
        val sumZ = FloatArray(numComponents)
        val cnt = IntArray(numComponents)
        for (t in 0 until triCount) {
            val b = t * 9
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            val c = componentIds[t]
            sumZ[c] += cz
            cnt[c]++
        }
        val meanZ = FloatArray(numComponents) { if (cnt[it] > 0) sumZ[it] / cnt[it] else 0f }
        val minZ = meanZ.min()
        val maxZ = meanZ.max()
        val span = maxZ - minZ

        // Triangles grouped by their component (used by both the height-banded and the
        // per-component fallback paths).
        val triByComp = Array(numComponents) { mutableListOf<Int>() }
        for (t in 0 until triCount) triByComp[componentIds[t]].add(t)

        // Degenerate Z-span (coplanar components — sheets, coins, very flat models): fall
        // back to per-component leaves so the user can still tap individual shells. Height
        // banding here would collapse everything into a single band.
        if (span < HEIGHT_SPAN_EPSILON) {
            return perComponentTopology(numComponents, triCount, triByComp)
        }

        // Assign each component to one of HEIGHT_BAND_COUNT bands by its centroid Z. Left/
        // right symmetric features share Z → share band → share slot → symmetric colouring.
        val bandCount = minOf(HEIGHT_BAND_COUNT, numComponents)
        val compToBand = IntArray(numComponents) { i ->
            val pct = (meanZ[i] - minZ) / span
            (pct * bandCount).toInt().coerceIn(0, bandCount - 1)
        }

        val trisByBand = Array(bandCount) { mutableListOf<Int>() }
        val partsByBand = IntArray(bandCount)
        for (c in 0 until numComponents) {
            val b = compToBand[c]
            trisByBand[b].addAll(triByComp[c])
            partsByBand[b]++
        }

        // Drop empty bands — they show up when components cluster heavily in one Z range
        // and several bands receive no components. Renumber so leaf ids stay dense 0..M-1.
        val nonEmptyBands = (0 until bandCount).filter { trisByBand[it].isNotEmpty() }
        val newIdFor = IntArray(bandCount) { -1 }
        nonEmptyBands.forEachIndexed { i, oldId -> newIdFor[oldId] = i }

        // If banding collapsed everything to a single band (e.g. clustered Z components),
        // fall back to per-component so the user can still address individual shells.
        if (nonEmptyBands.size < 2) {
            return perComponentTopology(numComponents, triCount, triByComp)
        }

        val triangleSegments = ByteArray(triCount) { compToBand[componentIds[it]].let(newIdFor::get).toByte() }

        val children = nonEmptyBands.mapIndexed { newId, oldId ->
            val tris = trisByBand[oldId].toIntArray()
            AiRegionNode(
                region = AiRegion(
                    id = newId,
                    label = HEIGHT_BAND_LABELS.getOrElse(oldId) { "Band ${oldId + 1}" },
                    suggestedColour = HEIGHT_BAND_COLOURS.getOrElse(oldId) { "#888888" },
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = newId % TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.TOPOLOGY,
                triangleIds = tris,
            )
        }
        val root = AiRegionNode(
            region = AiRegion(id = -1, label = "Model", suggestedColour = "#888888", coverageFraction = 1f),
            children = children,
            nodeSource = SegmentationSource.TOPOLOGY,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.TOPOLOGY)
    }

    /** Per-component fallback used when height-banding can't produce >= 2 distinct bands —
     *  matches the pre-fix39.3 raw topology behaviour so disjoint-but-coplanar inputs still
     *  resolve to separate leaves. */
    private fun perComponentTopology(
        numComponents: Int,
        triCount: Int,
        triByComp: Array<MutableList<Int>>,
    ): CascadeResult {
        val sortedComps = (0 until numComponents).sortedByDescending { triByComp[it].size }
        val triangleSegments = ByteArray(triCount)
        val children = sortedComps.mapIndexed { i, comp ->
            val tris = triByComp[comp].toIntArray()
            tris.forEach { t -> triangleSegments[t] = i.toByte() }
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
        val root = AiRegionNode(
            region = AiRegion(id = -1, label = "Model", suggestedColour = "#888888", coverageFraction = 1f),
            children = children,
            nodeSource = SegmentationSource.TOPOLOGY,
            triangleIds = IntArray(triCount) { it },
        )
        return CascadeResult(listOf(root), triangleSegments, SegmentationSource.TOPOLOGY)
    }

    /** Bundle every input the cascade needs from the loaded native snapshot. The ViewModel
     *  assembles this from JNI calls; tests construct it directly. */
    data class Input(
        val positions: FloatArray,
        val perTrianglePaintState: ByteArray,
        val volumes: List<ObjectVolumes>,
        val objects: List<ObjectInfo>,
        val perTriangleIndex: ByteArray,
    )

    /** Walk branches A → F top-down; return the first non-trivial result. */
    fun run(input: Input): CascadeResult {
        val triCount = input.positions.size / 9

        // A — paint state
        if (input.perTrianglePaintState.size == triCount && triCount > 0) {
            val r = paintStateBranch(input.perTrianglePaintState)
            if (r.tree.isNotEmpty()) return r
        }
        // B — per-volume
        if (input.volumes.sumOf { it.volumes.size } >= 2) {
            val r = volumeBranch(triCount, input.volumes)
            if (r.tree.isNotEmpty()) return r
        }
        // C — per-object
        if (input.objects.size >= 2) {
            val r = objectBranch(triCount, input.objects)
            if (r.tree.isNotEmpty()) return r
        }
        // D — triangle indices (safety net)
        if (input.perTriangleIndex.size == triCount && triCount > 0) {
            val r = triangleIndexBranch(input.perTriangleIndex)
            if (r.tree.isNotEmpty()) return r
        }
        // E — topology + recursion
        val topo = topologyBranch(input.positions)
        if (topo.tree.isNotEmpty()) return topo
        // F — Z-bands (last resort, always succeeds)
        return zBandBranch(input.positions)
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
