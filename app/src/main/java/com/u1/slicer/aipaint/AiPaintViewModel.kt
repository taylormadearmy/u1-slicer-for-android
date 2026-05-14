package com.u1.slicer.aipaint

import android.app.Application
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.NativeLibrary
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.viewer.NativePreviewMesh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * AI Paint pipeline (F54 redesign — post-fix33):
 *   1. Read the loaded native snapshot — paint state, volumes, objects.
 *   2. Run [SegmentationCascade] (branches A → F) to produce a deterministic tree.
 *   3. If `aiNamingEnabled`: ask AI to name the tree leaves (text-only — no spatial grounding).
 *   4. The user refines on the result screen via paint, lasso, slot reassignment, brush strokes.
 *
 * AI is opt-in and decorative. Segmentation always succeeds (Z-bands as last resort).
 */
class AiPaintViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val TARGET_SLOTS = SegmentationCascade.TARGET_SLOTS

        /** fix39 — topology cascades get folded into this many AI-named groups (matches fix30).
         *  Capped at the actual component count so small models stay sane. */
        const val TARGET_GROUP_COUNT = 12

        /**
         * Painted 3MFs are written by [PaintedMeshWriter] as `ai_paint_<timestamp>.3mf`.
         * Strip pipeline prefixes (`embedded_`, `sanitized_`) so cache-hit lookup works.
         */
        fun isSamePainting(cachedPaintedPath: String?, currentModelPath: String?): Boolean {
            if (cachedPaintedPath.isNullOrBlank() || currentModelPath.isNullOrBlank()) return false
            val cachedName = stripPipelinePrefixes(java.io.File(cachedPaintedPath).name)
            val currentName = stripPipelinePrefixes(java.io.File(currentModelPath).name)
            return cachedName.isNotEmpty() && cachedName == currentName
        }

        private val PIPELINE_PREFIXES = listOf("embedded_", "sanitized_")
        private fun stripPipelinePrefixes(name: String): String {
            var out = name
            var changed = true
            while (changed) {
                changed = false
                for (p in PIPELINE_PREFIXES) {
                    if (out.startsWith(p)) {
                        out = out.removePrefix(p)
                        changed = true
                    }
                }
            }
            return out
        }
    }

    private val app get() = getApplication<U1SlicerApplication>()
    private val settings get() = app.container.settingsRepository

    private val _uiState = MutableStateFlow<AiPaintUiState>(AiPaintUiState.Idle)
    val uiState: StateFlow<AiPaintUiState> = _uiState.asStateFlow()

    private var lastPrinterColours: List<String>? = null

    private val HEX_REGEX = Regex("^#[0-9A-Fa-f]{6}$")
    private fun isValidHex(s: String): Boolean = HEX_REGEX.matches(s)

    private val undoStack = ArrayDeque<ByteArray>()
    private fun pushUndo(snapshot: ByteArray) {
        undoStack.addLast(snapshot.copyOf())
        while (undoStack.size > 50) undoStack.removeFirst()
    }

    fun beginUndoCheckpoint() {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        pushUndo(current.state.triangleRegions)
        if (!current.state.canUndo) {
            _uiState.value = AiPaintUiState.Result(current.state.copy(canUndo = true))
        }
    }

    fun undo() {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        val prev = undoStack.removeLastOrNull() ?: return
        if (prev.size != state.triangleRegions.size) return
        _uiState.value = AiPaintUiState.Result(state.copy(
            triangleRegions = prev,
            canUndo = undoStack.isNotEmpty(),
        ))
    }

    fun runPipeline(sourceModelPath: String, native: NativeLibrary, printerColours: List<String>? = null) {
        lastPrinterColours = printerColours
        runPipelineInternal(sourceModelPath, native, printerColours)
    }

    private fun runPipelineInternal(
        sourceModelPath: String,
        native: NativeLibrary,
        printerColours: List<String>?,
    ) {
        viewModelScope.launch {
            _uiState.value = AiPaintUiState.Running(1, "Reading model geometry…")
            try {
                val providerName = settings.aiPaintProvider.first()
                val apiKey = settings.aiPaintApiKeyFor(providerName).first()
                val provider = AiPaintProvider.fromId(providerName)
                val aiEnabled = settings.aiNamingEnabled.first()

                val mesh = native.getPreparePreviewMesh(
                    maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES
                ) ?: run {
                    _uiState.value = AiPaintUiState.Error("Could not read model geometry.")
                    return@launch
                }
                val positions = mesh.trianglePositions
                val triCount = positions.size / 9

                _uiState.value = AiPaintUiState.Running(2, "Finding parts of the model…")
                val input = withContext(Dispatchers.Default) {
                    buildCascadeInput(native, mesh, triCount)
                }
                val cascadeResult = withContext(Dispatchers.Default) {
                    SegmentationCascade.run(input)
                }
                Log.i("AiPaint", "Cascade fired: ${cascadeResult.source.name} → " +
                    "${cascadeResult.tree.firstOrNull()?.leafCount() ?: 0} leaves")

                // fix38: compute a topology alternate so the user can toggle between Parts
                // (Branch A/B/C/D) and Regions (Branch E topology) views on models that
                // support both. Skip when the primary IS already a topology view.
                val alternateResult: CascadeResult? = if (
                    cascadeResult.source != SegmentationSource.TOPOLOGY &&
                    cascadeResult.source != SegmentationSource.TOPOLOGY_RECURSIVE &&
                    cascadeResult.source != SegmentationSource.Z_BAND
                ) {
                    withContext(Dispatchers.Default) {
                        SegmentationCascade.topologyBranch(input.positions)
                            .takeIf { it.tree.isNotEmpty() }
                    }
                } else null
                if (alternateResult != null) {
                    Log.i("AiPaint", "Alternate available: ${alternateResult.source.name} → " +
                        "${alternateResult.tree.firstOrNull()?.leafCount() ?: 0} leaves")
                }

                // fix39: when the cascade picked topology (organic models — goat, dragon,
                // figurines) AND AI is enabled, do the AI semantic-grouping pass that fix30
                // used. AI sees the topology-coloured render and groups the N components into
                // ~12 semantic regions with explicit symmetry rules (left/right eyes share a
                // group → same colour). Without this, raw topology gives 50-200 tiny leaves
                // that are individually selectable but visually noisy and asymmetric.
                val isTopologyPrimary = cascadeResult.source == SegmentationSource.TOPOLOGY ||
                    cascadeResult.source == SegmentationSource.TOPOLOGY_RECURSIVE
                val canCallAi = aiEnabled && (!provider.requiresKey || apiKey.isNotBlank())
                val groupedCascade: CascadeResult = if (isTopologyPrimary && canCallAi) {
                    _uiState.value = AiPaintUiState.Running(3, "Asking AI to group the parts…")
                    applyAiTopologyGrouping(provider, apiKey, positions, cascadeResult)
                        ?: cascadeResult
                } else cascadeResult

                val (treeAfterAi, aiFailed, modelTried) = if (canCallAi && !isTopologyPrimary) {
                    _uiState.value = AiPaintUiState.Running(3, "Asking AI to name the parts…")
                    applyAiNaming(provider, apiKey, positions, groupedCascade.tree)
                } else {
                    Triple(groupedCascade.tree, false, null)
                }

                _uiState.value = AiPaintUiState.Running(4, "Writing painted model…")

                // Apply printer-slot colour overrides on leaf regions matching slot index.
                val tree = treeAfterAi.map { root -> applyPrinterColours(root, printerColours) }

                // Per-triangle slot from tree leaves' slot assignment.
                val triangleRegions = ByteArray(triCount)
                val leafByTri = IntArray(triCount) { -1 }
                tree.forEach { root ->
                    root.flatten().forEach { (node, _) ->
                        if (node.isLeaf) {
                            node.triangleIds.forEach { t ->
                                if (t in 0 until triCount) {
                                    triangleRegions[t] = node.region.slot.toByte()
                                    leafByTri[t] = node.region.id
                                }
                            }
                        }
                    }
                }
                val triangleSegments = ByteArray(triCount) {
                    leafByTri[it].coerceIn(0, 255).toByte()
                }

                val slotsView = (0 until TARGET_SLOTS).map { s ->
                    AiRegion(
                        id = s,
                        label = "Slot ${s + 1}",
                        suggestedColour = printerColours?.getOrNull(s) ?: "#888888",
                        userColour = printerColours?.getOrNull(s)?.takeIf(::isValidHex),
                        slot = s,
                    )
                }
                val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
                val slotIdsForFile = IntArray(triCount) { triangleRegions[it].toInt() and 0xFF }
                PaintedMeshWriter.write(
                    positions, slotIdsForFile, slotsView, outFile,
                    printerColours = printerColours,
                )

                // fix38: bake the alternate tree (topology) with printer colours so that when
                // the user switches, the alternate already has the right slot colours.
                val alternateTreeWithColours = alternateResult?.tree?.map { root ->
                    applyPrinterColours(root, printerColours)
                }

                _uiState.value = AiPaintUiState.Result(
                    AiPaintResultState(
                        tree = tree,
                        source = cascadeResult.source,
                        paintedModelPath = outFile.absolutePath,
                        sourceModelPath = sourceModelPath,
                        trianglePositions = positions,
                        triangleSegments = triangleSegments,
                        triangleRegions = triangleRegions,
                        aiNamingFailed = aiFailed,
                        aiModelTried = modelTried,
                        customSelections = emptyList(),
                        alternateTree = alternateTreeWithColours,
                        alternateSource = alternateResult?.source,
                        alternateTriangleSegments = alternateResult?.triangleSegments,
                    )
                )
            } catch (e: Exception) {
                Log.e("AiPaint", "Pipeline failed", e)
                _uiState.value = AiPaintUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun buildCascadeInput(
        native: NativeLibrary,
        mesh: NativePreviewMesh,
        triCount: Int,
    ): SegmentationCascade.Input {
        val perTriPaint = mesh.extruderIndices.takeIf { it.size == triCount } ?: ByteArray(triCount)

        // fix36: populate mesh.volumeRanges from the per-volume triangle counts captured
        // during the native preview build. Once populated the cascade's Branch B (per-volume)
        // and Branch C (per-object) become usable on multi-volume Bambu 3MFs.
        // fix37: always emit a range entry per count (even when count is 0) so the index
        // alignment with nativeGetAllVolumeExtruders's volume order stays correct. Empty
        // ranges encode "this volume exists but contributed no preview triangles".
        if (mesh.volumeRanges == null) {
            val counts = runCatching { native.nativeGetPreviewVolumeTriangleCounts() }.getOrNull()
            if (counts != null && counts.isNotEmpty()) {
                var cursor = 0
                val ranges = mutableListOf<IntRange>()
                for (c in counts) {
                    val safeC = c.coerceAtLeast(0)
                    if (safeC == 0) {
                        // empty range — preserves index alignment with the JSON volumes
                        ranges += IntRange(0, -1)
                        continue
                    }
                    val end = cursor + safeC - 1
                    if (end >= triCount) break // defensive — desync between counts + mesh
                    ranges += cursor..end
                    cursor += safeC
                }
                if (ranges.isNotEmpty()) {
                    mesh.volumeRanges = ranges
                    Log.i("AiPaint",
                        "fix37 volumeRanges populated: ${ranges.size} ranges, " +
                        "total tris ${ranges.sumOf { (it.last - it.first + 1).coerceAtLeast(0) }} " +
                        "/ ${triCount}")
                }
            }
        }

        val volumeJson = runCatching { native.nativeGetAllVolumeExtruders() }.getOrNull()
        val objectJson = runCatching { native.nativeGetObjectExtruderMap() }.getOrNull()
        val ranges = mesh.volumeRanges ?: emptyList()

        val volumes = parseObjectVolumes(volumeJson, ranges)
        val objects = parseObjectInfos(objectJson, ranges)

        return SegmentationCascade.Input(
            positions = mesh.trianglePositions,
            perTrianglePaintState = perTriPaint,
            volumes = volumes,
            objects = objects,
            perTriangleIndex = perTriPaint,
        )
    }

    /** Parse the JSON returned by NativeLibrary.nativeGetAllVolumeExtruders() into the cascade
     *  input shape. Returns empty list when JSON is missing or volumeRanges aren't supplied. */
    private fun parseObjectVolumes(
        json: String?,
        volumeRanges: List<IntRange>,
    ): List<SegmentationCascade.ObjectVolumes> {
        if (json.isNullOrBlank() || volumeRanges.isEmpty()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SegmentationCascade.ObjectVolumes>()
        var volumeCursor = 0
        for (o in 0 until arr.length()) {
            val obj = arr.getJSONObject(o)
            val objId = obj.optLong("objectIndex", o.toLong())
            val objName = obj.optString("objectName", "Object ${o + 1}").ifBlank { "Object ${o + 1}" }
            val volsArr = obj.optJSONArray("volumes") ?: continue
            val vols = mutableListOf<SegmentationCascade.VolumeInfo>()
            for (v in 0 until volsArr.length()) {
                val vobj = volsArr.getJSONObject(v)
                val ext = vobj.optInt("extruder", -1).takeIf { it > 0 }
                val range = volumeRanges.getOrNull(volumeCursor) ?: continue
                vols += SegmentationCascade.VolumeInfo(
                    volumeIndex = v,
                    extruder = ext,
                    triangleIds = (range.first..range.last).toList().toIntArray(),
                )
                volumeCursor++
            }
            if (vols.isNotEmpty()) out += SegmentationCascade.ObjectVolumes(objId, objName, vols)
        }
        return out
    }

    /** Parse the JSON returned by NativeLibrary.nativeGetObjectExtruderMap() into the cascade
     *  input shape. Pairs each object with its triangle range using volumeRanges as a side-table. */
    private fun parseObjectInfos(
        json: String?,
        volumeRanges: List<IntRange>,
    ): List<SegmentationCascade.ObjectInfo> {
        if (json.isNullOrBlank() || volumeRanges.isEmpty()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        var rangeCursor = 0
        val out = mutableListOf<SegmentationCascade.ObjectInfo>()
        for (o in 0 until arr.length()) {
            val obj = arr.getJSONObject(o)
            val id = obj.optLong("objectId", o.toLong())
            val name = obj.optString("name", "Object ${o + 1}").ifBlank { "Object ${o + 1}" }
            val extruder = obj.optInt("extruder", -1).takeIf { it > 0 }
            val volumeCount = obj.optInt("volumeCount", 1).coerceAtLeast(1)
            val objRanges = (0 until volumeCount).mapNotNull { volumeRanges.getOrNull(rangeCursor + it) }
            rangeCursor += volumeCount
            if (objRanges.isEmpty()) continue
            val tris = objRanges.flatMap { (it.first..it.last).toList() }.toIntArray()
            out += SegmentationCascade.ObjectInfo(
                objectId = id,
                name = name,
                extruder = extruder,
                triangleIds = tris,
            )
        }
        return out
    }

    /** Optional AI naming post-pass. Returns (renamed tree, aiFailed, modelTried). */
    private suspend fun applyAiNaming(
        provider: AiPaintProvider,
        apiKey: String,
        positions: FloatArray,
        tree: List<AiRegionNode>,
    ): Triple<List<AiRegionNode>, Boolean, String?> {
        val leaves = tree.flatMap { it.flatten().filter { (n, _) -> n.isLeaf }.map { it.first } }
        if (leaves.isEmpty()) return Triple(tree, false, null)

        val shaded = withContext(Dispatchers.Default) {
            AiPaintRenderer.renderShaded(positions, 512, 512, CameraAngle.RIGHT_ISO)
        }
        val perTriRegion = IntArray(positions.size / 9)
        leaves.forEachIndexed { idx, leaf ->
            leaf.triangleIds.forEach { t -> if (t in perTriRegion.indices) perTriRegion[t] = idx }
        }
        val palette = leaves.mapIndexed { i, _ ->
            runCatching { Color.parseColor(SegmentationCascade.paletteFor(i)) }
                .getOrDefault(Color.GRAY)
        }.toIntArray()
        val banded = withContext(Dispatchers.Default) {
            AiPaintRenderer.renderRegions(positions, perTriRegion, palette, 512, 512, CameraAngle.RIGHT_ISO)
        }
        val names = AiLabelClient.labelSegments(provider, apiKey, listOf(shaded, banded), leaves.size)
            ?: return Triple(tree, true, AiLabelClient.lastModel)

        if (names.size != leaves.size) return Triple(tree, true, AiLabelClient.lastModel)
        val nameById: Map<Int, NamedColour> = leaves.mapIndexed { i, leaf -> leaf.region.id to names[i] }.toMap()
        val renamed = tree.map { root -> applyNames(root, nameById) }
        return Triple(renamed, false, AiLabelClient.lastModel)
    }

    /**
     * fix39 — semantic-grouping post-pass for topology cascades. Renders the N topology
     * components in distinct hues, sends `(shaded, components)` to the AI with an explicit
     * symmetry rule, and folds the result into a new cascade tree with [TARGET_GROUP_COUNT]
     * leaves. On any failure (AI declined, parse mismatch, model error) returns null and the
     * caller keeps the raw topology cascade.
     */
    private suspend fun applyAiTopologyGrouping(
        provider: AiPaintProvider,
        apiKey: String,
        positions: FloatArray,
        cascade: CascadeResult,
    ): CascadeResult? {
        val triCount = positions.size / 9
        val leaves = cascade.tree.flatMap { it.flatten().filter { (n, _) -> n.isLeaf }.map { it.first } }
        if (leaves.size < 2) return null

        // Render shaded + component-coloured views from the same camera angle.
        val perTriComp = IntArray(triCount)
        leaves.forEachIndexed { idx, leaf ->
            leaf.triangleIds.forEach { t -> if (t in perTriComp.indices) perTriComp[t] = idx }
        }
        val palette = AiLabelClient.componentDisplayColors(leaves.size)
        val shaded = withContext(Dispatchers.Default) {
            AiPaintRenderer.renderShaded(positions, 512, 512, CameraAngle.RIGHT_ISO)
        }
        val banded = withContext(Dispatchers.Default) {
            AiPaintRenderer.renderRegions(positions, perTriComp, palette, 512, 512, CameraAngle.RIGHT_ISO)
        }

        val targetCount = minOf(TARGET_GROUP_COUNT, leaves.size)
        val groups = AiLabelClient.labelGroups(
            provider, apiKey, shaded, banded, leaves.size, targetCount,
        ) ?: return null

        // Build new leaves: one per AI group, triangle ids = union of member components'
        // triangles. Slot rotates so adjacent groups land on different physical slots; if the
        // AI's suggested colour parses, it stays as suggestedColour.
        val newLeaves = groups.mapIndexed { i, g ->
            val tris = g.componentIds.flatMap { ci ->
                leaves.getOrNull(ci)?.triangleIds?.toList() ?: emptyList()
            }.toIntArray()
            AiRegionNode(
                region = AiRegion(
                    id = i,
                    label = g.label,
                    suggestedColour = g.suggestedColour.takeIf(::isValidHex)
                        ?: SegmentationCascade.paletteFor(i),
                    coverageFraction = tris.size.toFloat() / triCount,
                    slot = i % TARGET_SLOTS,
                ),
                children = emptyList(),
                nodeSource = cascade.source,
                triangleIds = tris,
            )
        }

        val triangleSegments = ByteArray(triCount)
        newLeaves.forEach { leaf ->
            leaf.triangleIds.forEach { t ->
                if (t in 0 until triCount) triangleSegments[t] = leaf.region.id.toByte()
            }
        }
        val newRoot = AiRegionNode(
            region = AiRegion(
                id = -1, label = "Model", suggestedColour = "#888888", coverageFraction = 1f,
            ),
            children = newLeaves,
            nodeSource = cascade.source,
            triangleIds = IntArray(triCount) { it },
        )
        Log.i("AiPaint", "fix39 AI grouping: ${leaves.size} components → ${newLeaves.size} groups " +
            "(model=${AiLabelClient.lastModel})")
        return CascadeResult(listOf(newRoot), triangleSegments, cascade.source)
    }

    private fun applyNames(node: AiRegionNode, names: Map<Int, NamedColour>): AiRegionNode {
        val named = names[node.region.id]
        val updated = if (named != null) {
            node.region.copy(label = named.label, suggestedColour = named.colour)
        } else node.region
        return node.copy(
            region = updated,
            children = node.children.map { applyNames(it, names) },
        )
    }

    private fun applyPrinterColours(node: AiRegionNode, printerColours: List<String>?): AiRegionNode {
        val printerHex = printerColours?.getOrNull(node.region.slot)?.takeIf(::isValidHex)
        val updated = if (printerHex != null && node.isLeaf) {
            node.region.copy(userColour = printerHex)
        } else node.region
        return node.copy(
            region = updated,
            children = node.children.map { applyPrinterColours(it, printerColours) },
        )
    }

    fun updateRegionColour(regionId: Int, hexColour: String) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val newTree = current.state.tree.map { root -> recolorNode(root, regionId, hexColour) }
        _uiState.value = AiPaintUiState.Result(current.state.copy(tree = newTree))
    }

    private fun recolorNode(node: AiRegionNode, targetId: Int, hex: String): AiRegionNode {
        if (node.region.id == targetId) return node.copy(region = node.region.copy(userColour = hex))
        return node.copy(children = node.children.map { recolorNode(it, targetId, hex) })
    }

    /** Brush stroke: paint a set of triangles to a target slot. Appends a CustomSelection so
     *  the screen renders the stroke as a child under the "Custom selections" group. */
    fun paintTriangles(triangleIndices: List<Int>, toSlot: Int) {
        if (triangleIndices.isEmpty()) return
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (toSlot !in 0 until TARGET_SLOTS) return
        val newTriRegions = state.triangleRegions.copyOf()
        val slot = toSlot.toByte()
        for (t in triangleIndices) if (t in newTriRegions.indices) newTriRegions[t] = slot
        val nextId = (state.customSelections.maxOfOrNull { it.id } ?: -1) + 1
        val newSelections = state.customSelections + CustomSelection(
            id = nextId,
            slot = toSlot,
            triangleIds = triangleIndices.toIntArray(),
        )
        _uiState.value = AiPaintUiState.Result(state.copy(
            triangleRegions = newTriRegions,
            customSelections = newSelections,
            canUndo = true,
        ))
    }

    fun commitSelection(triangleIndices: List<Int>, toSlot: Int) = paintTriangles(triangleIndices, toSlot)

    /** Reassign a single SEGMENT (or any node by id) to a different physical slot. */
    fun setSegmentSlot(segmentId: Int, newSlot: Int) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (newSlot !in 0 until TARGET_SLOTS) return
        pushUndo(state.triangleRegions)
        val newTriRegions = state.triangleRegions.copyOf()
        val newTree = state.tree.map { root -> reassignSlot(root, segmentId, newSlot, newTriRegions) }
        _uiState.value = AiPaintUiState.Result(state.copy(
            tree = newTree,
            triangleRegions = newTriRegions,
            canUndo = true,
        ))
    }

    /** Cascade-reassign: reassign EVERY cascade-tree leaf under the node addressed by [pathIds]
     *  to the new slot. Custom-selection rows are NOT swept up — they live under a separate
     *  root-level group (see CustomSelections.buildGroup). */
    fun cascadeReassign(pathIds: List<Int>, newSlot: Int) {
        if (pathIds.isEmpty()) return
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (newSlot !in 0 until TARGET_SLOTS) return
        pushUndo(state.triangleRegions)
        val newTriRegions = state.triangleRegions.copyOf()
        val newTree = state.tree.map { root -> reassignSubtree(root, pathIds, newSlot, newTriRegions) }
        _uiState.value = AiPaintUiState.Result(state.copy(
            tree = newTree,
            triangleRegions = newTriRegions,
            canUndo = true,
        ))
    }

    /** Reassign a single node by its region.id to a slot, including all its leaf triangles. */
    private fun reassignSlot(node: AiRegionNode, targetId: Int, newSlot: Int, out: ByteArray): AiRegionNode {
        if (node.region.id == targetId) {
            fun visit(n: AiRegionNode): AiRegionNode {
                if (n.isLeaf) {
                    n.triangleIds.forEach { t -> if (t in out.indices) out[t] = newSlot.toByte() }
                    return n.copy(region = n.region.copy(slot = newSlot))
                }
                return n.copy(children = n.children.map(::visit))
            }
            return visit(node).copy(region = node.region.copy(slot = newSlot))
        }
        return node.copy(children = node.children.map { reassignSlot(it, targetId, newSlot, out) })
    }

    /** Cascade subtree reassign — finds the node at path[last] and reassigns every leaf under it. */
    private fun reassignSubtree(node: AiRegionNode, path: List<Int>, newSlot: Int, out: ByteArray): AiRegionNode {
        if (path.isEmpty()) return node
        if (node.region.id == path.last()) {
            fun visit(n: AiRegionNode): AiRegionNode {
                if (n.isLeaf) {
                    n.triangleIds.forEach { t -> if (t in out.indices) out[t] = newSlot.toByte() }
                    return n.copy(region = n.region.copy(slot = newSlot))
                }
                return n.copy(children = n.children.map(::visit))
            }
            return visit(node).copy(region = node.region.copy(slot = newSlot))
        }
        return node.copy(children = node.children.map { reassignSubtree(it, path, newSlot, out) })
    }

    /** Backwards-compat: callers that did "move part to region N" funnel through setSegmentSlot. */
    fun moveComponent(componentId: Int, toRegion: Int) = setSegmentSlot(componentId, toRegion)

    fun highlightComponent(componentId: Int?) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        if (current.state.highlightComponentId == componentId) return
        _uiState.value = AiPaintUiState.Result(current.state.copy(highlightComponentId = componentId))
    }

    fun finalizePainting(): String? {
        val current = _uiState.value as? AiPaintUiState.Result ?: return null
        val state = current.state
        val regionIds = IntArray(state.triangleRegions.size) { state.triangleRegions[it].toInt() }
        val slotsView = (0 until TARGET_SLOTS).map { s ->
            AiRegion(
                id = s,
                label = "Slot ${s + 1}",
                suggestedColour = lastPrinterColours?.getOrNull(s) ?: "#888888",
                userColour = lastPrinterColours?.getOrNull(s)?.takeIf(::isValidHex),
                slot = s,
            )
        }
        val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
        PaintedMeshWriter.write(
            state.trianglePositions, regionIds, slotsView, outFile,
            printerColours = lastPrinterColours,
        )
        _uiState.value = AiPaintUiState.Result(state.copy(paintedModelPath = outFile.absolutePath))
        return outFile.absolutePath
    }

    fun redo(sourceModelPath: String, native: NativeLibrary) {
        _uiState.value = AiPaintUiState.Idle
        runPipeline(sourceModelPath, native, lastPrinterColours)
    }

    /** fix38: swap the active tree with the stored alternate (typically topology). User edits
     *  (triangleRegions overrides + customSelections + undo stack) are reset on switch — the
     *  alternate is a fresh start with the cascade-derived slot defaults, not a paint overlay
     *  carried across views. */
    fun switchToAlternate() {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        val altTree = state.alternateTree ?: return
        val altSource = state.alternateSource ?: return
        val altSegments = state.alternateTriangleSegments ?: return

        val triCount = state.trianglePositions.size / 9
        val newTriRegions = ByteArray(triCount)
        altTree.forEach { root ->
            root.flatten().forEach { (node, _) ->
                if (node.isLeaf) {
                    node.triangleIds.forEach { t ->
                        if (t in 0 until triCount) newTriRegions[t] = node.region.slot.toByte()
                    }
                }
            }
        }

        undoStack.clear()
        _uiState.value = AiPaintUiState.Result(
            state.copy(
                tree = altTree,
                source = altSource,
                triangleSegments = altSegments,
                triangleRegions = newTriRegions,
                // Swap the alternate pointer to the previous active view so the user can flip
                // back and forth.
                alternateTree = state.tree,
                alternateSource = state.source,
                alternateTriangleSegments = state.triangleSegments,
                customSelections = emptyList(),
                canUndo = false,
                highlightComponentId = null,
            )
        )
    }

    fun reset() { _uiState.value = AiPaintUiState.Idle }
}
