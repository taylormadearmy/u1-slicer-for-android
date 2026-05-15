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

                val rawMesh = native.getPreparePreviewMesh(
                    maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES
                ) ?: run {
                    _uiState.value = AiPaintUiState.Error("Could not read model geometry.")
                    return@launch
                }
                // fix45: native MMU path emits ALL triangles regardless of max_triangles —
                // sapil_model.cpp:533-557 round-robin interleave skips decimation.
                // fix45.1: cap at 30k (not 100k) because the topology *alternate* runs
                // `MeshSegmenter.segmentByTopologyOrSpatial` whose merge step is super-linear
                // in component count — 98k Korok hung >150s. 30k keeps tap precision usable
                // on a phone-sized 3D view while bounding cascade compute.
                val aiPaintTriCap = 30_000
                val subsampled = if (rawMesh.trianglePositions.size / 9 > aiPaintTriCap) {
                    subsampleMeshForAiPaint(rawMesh, aiPaintTriCap)
                } else SubsampledMesh(rawMesh, stride = 1)
                val mesh = subsampled.mesh
                val cascadeStride = subsampled.stride
                val positions = mesh.trianglePositions
                val triCount = positions.size / 9
                val originalTriCount = rawMesh.trianglePositions.size / 9

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

                // fix39.3: deterministic height-banding in topologyBranch produces symmetric
                // grouping without depending on a vision-capable AI provider — the bilateral
                // pairs (left + right horns at same Z) already land in the same band. AI is
                // now only used for optional re-naming (e.g. "Head" instead of "Top") on
                // providers that have working vision; the goat-symmetry case works on every
                // provider, including text-only Pollinations free tier.
                val canCallAi = aiEnabled && (!provider.requiresKey || apiKey.isNotBlank())

                // fix40.5: when the primary cascade is PAINT_STATE but a TOPOLOGY alternate
                // exists, default the user to the topology view. Paint state has only N≤4
                // leaves (one per slot) so tap-to-highlight selects "everything painted this
                // colour" — not useful for editing. Topology gives per-component leaves so
                // tap on the nose actually selects the nose. The user can still switch back
                // to the Painted view via the chip toggle.
                //
                // fix44: decide swap BEFORE running AI naming so the AI-named tree is the
                // one the user actually sees. Previously AI naming was applied to
                // cascadeResult.tree (paint state) but then primaryTree became the topology
                // tree — so the AI names were silently discarded.
                val swapToTopology = cascadeResult.source == SegmentationSource.PAINT_STATE &&
                    (alternateResult?.source == SegmentationSource.TOPOLOGY ||
                     alternateResult?.source == SegmentationSource.TOPOLOGY_RECURSIVE)
                val primaryCascade = if (swapToTopology) alternateResult!! else cascadeResult
                val alternateCascadeForState = if (swapToTopology) cascadeResult else alternateResult
                Log.i("AiPaint", "fix44 gate: aiEnabled=$aiEnabled provider=${provider.name} " +
                    "canCallAi=$canCallAi swapToTopology=$swapToTopology " +
                    "primary=${primaryCascade.source.name} " +
                    "alternate=${alternateCascadeForState?.source?.name}")

                val (primaryTreeAfterAi, aiFailed, modelTried) = if (canCallAi) {
                    _uiState.value = AiPaintUiState.Running(3, "Asking AI to name the parts…")
                    applyAiNaming(provider, apiKey, positions, primaryCascade.tree)
                } else {
                    Triple(primaryCascade.tree, false, null)
                }

                _uiState.value = AiPaintUiState.Running(4, "Writing painted model…")

                val primaryTree = primaryTreeAfterAi
                val primarySource = primaryCascade.source
                val primarySegments = primaryCascade.triangleSegments

                // Apply printer-slot colour overrides on leaf regions matching slot index.
                val tree = primaryTree.map { root -> applyPrinterColours(root, printerColours) }

                // Per-triangle slot from tree leaves' slot assignment.
                val triangleRegions = ByteArray(triCount)
                tree.forEach { root ->
                    root.flatten().forEach { (node, _) ->
                        if (node.isLeaf) {
                            node.triangleIds.forEach { t ->
                                if (t in 0 until triCount) {
                                    triangleRegions[t] = node.region.slot.toByte()
                                }
                            }
                        }
                    }
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
                // B111: export must write the *original* mesh, not the subsampled scaffolding.
                // Broadcast each subsampled triangle's slot id to the `cascadeStride` source
                // triangles it represents. When the model wasn't subsampled (stride == 1)
                // this is identity. Without this, accepting Smart Paint on any model > 30k
                // triangles silently replaces the geometry with ~3% of itself.
                val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
                val slotIdsForFile = broadcastSlotIdsToOriginalMesh(
                    subsampledRegions = triangleRegions,
                    originalTriCount = originalTriCount,
                    stride = cascadeStride,
                )
                PaintedMeshWriter.write(
                    rawMesh.trianglePositions, slotIdsForFile, slotsView, outFile,
                    printerColours = printerColours,
                )

                // fix38: bake the alternate tree with printer colours so that when the user
                // switches, the alternate already has the right slot colours. The alternate
                // is the cascade we DIDN'T pick as primary (see swap logic above). Not run
                // through AI naming today — feels coarse to spend a second AI call on a tree
                // the user may never look at; we re-run on switch only if it ever matters.
                val alternateTreeWithColours = alternateCascadeForState?.tree?.map { root ->
                    applyPrinterColours(root, printerColours)
                }

                _uiState.value = AiPaintUiState.Result(
                    AiPaintResultState(
                        tree = tree,
                        source = primarySource,
                        paintedModelPath = outFile.absolutePath,
                        sourceModelPath = sourceModelPath,
                        trianglePositions = positions,
                        triangleSegments = primarySegments,
                        triangleRegions = triangleRegions,
                        sourceTrianglePositions = if (cascadeStride > 1) rawMesh.trianglePositions else FloatArray(0),
                        cascadeStride = cascadeStride,
                        aiNamingFailed = aiFailed,
                        aiModelTried = modelTried,
                        customSelections = emptyList(),
                        alternateTree = alternateTreeWithColours,
                        alternateSource = alternateCascadeForState?.source,
                        alternateTriangleSegments = alternateCascadeForState?.triangleSegments,
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
        // B111: if the cascade ran on a subsampled mesh (cascadeStride > 1), write the
        // *original* mesh with slot ids broadcast from subsampled space. Otherwise the
        // saved 3MF would contain only ~3% of the geometry (29k of 880k triangles for
        // the axolotl repro). When stride == 1 the source positions are unused and
        // state.trianglePositions is already the original.
        val exportPositions = if (state.cascadeStride > 1 && state.sourceTrianglePositions.isNotEmpty()) {
            state.sourceTrianglePositions
        } else {
            state.trianglePositions
        }
        val exportTriCount = exportPositions.size / 9
        val regionIds = if (state.cascadeStride > 1 && state.sourceTrianglePositions.isNotEmpty()) {
            broadcastSlotIdsToOriginalMesh(
                subsampledRegions = state.triangleRegions,
                originalTriCount = exportTriCount,
                stride = state.cascadeStride,
            )
        } else {
            IntArray(state.triangleRegions.size) { state.triangleRegions[it].toInt() and 0xFF }
        }
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
            exportPositions, regionIds, slotsView, outFile,
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
        Log.i("AiPaint", "switchToAlternate: from ${state.source.name} → ${state.alternateSource?.name}")
        val altTree = state.alternateTree ?: return
        val altSource = state.alternateSource ?: return
        val altSegments = state.alternateTriangleSegments ?: return

        val triCount = state.trianglePositions.size / 9
        val newTriRegions = buildSwitchedTriangleRegions(altTree, state.customSelections, triCount)

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
                // fix43: keep customSelections + canUndo so manual edits + undo history
                // travel with the user across tab switches.
                highlightComponentId = null,
            )
        )
    }

    fun reset() { _uiState.value = AiPaintUiState.Idle }
}

/**
 * fix45 — Kotlin-side subsample for AI Paint pipeline.
 *
 * The native MMU path (painted/SEMM 3MFs) in `sapil_model.cpp` skips decimation —
 * the round-robin interleave at line ~533 emits every triangle from every paint
 * state regardless of the `max_triangles` argument. As a result colored_3DBenchy
 * returned 603k triangles to the cascade, and Korok 788k → topology pass either
 * hangs or OOMs.
 *
 * This helper applies a global stride to the position + extruderIndices arrays,
 * and remaps `volumeRanges` to the subsampled index space. Output triangle count
 * is approximately `targetCount` (within stride rounding).
 *
 * The stride pattern preserves per-state proportion globally (we keep every Nth
 * triangle), but doesn't try to balance across paint states — that's the native
 * path's job. For Smart Paint's segmentation cascade this is good enough: topology
 * flood-fill and Z-banding work on a strided subset just as well as the full
 * mesh, and tap-to-highlight lookups still resolve via `triangleIds in leaf`.
 */
/** Result of [subsampleMeshForAiPaint]: the strided mesh plus the stride that produced
 *  it. B111: callers need the stride to broadcast per-subsampled-triangle paint state
 *  back to the original mesh at export time — without the stride, the export collapses
 *  to the subsampled scaffolding (~3% of geometry on an 880k-tri model). */
internal data class SubsampledMesh(
    val mesh: com.u1.slicer.viewer.NativePreviewMesh,
    val stride: Int,
)

internal fun subsampleMeshForAiPaint(
    src: com.u1.slicer.viewer.NativePreviewMesh,
    targetCount: Int,
): SubsampledMesh {
    val triCount = src.trianglePositions.size / 9
    if (triCount <= targetCount) return SubsampledMesh(src, stride = 1)
    val stride = (triCount + targetCount - 1) / targetCount
    val newTriCount = (triCount + stride - 1) / stride
    val newPositions = FloatArray(newTriCount * 9)
    val newIndices = ByteArray(newTriCount)
    var writeT = 0
    var readT = 0
    while (readT < triCount && writeT < newTriCount) {
        val readBase = readT * 9
        val writeBase = writeT * 9
        for (k in 0 until 9) newPositions[writeBase + k] = src.trianglePositions[readBase + k]
        newIndices[writeT] = src.extruderIndices[readT]
        readT += stride
        writeT++
    }

    // Re-map volume ranges from the source's triangle index space into the subsampled
    // one. Each source range [a, b] becomes [ceil(a/stride), b/stride].
    val newRanges = src.volumeRanges?.map { range ->
        val newFirst = (range.first + stride - 1) / stride
        val newLast = range.last / stride
        if (newLast < newFirst) IntRange(0, -1) else IntRange(newFirst, newLast)
    }

    val out = com.u1.slicer.viewer.NativePreviewMesh(
        trianglePositions = newPositions.copyOf(writeT * 9),
        extruderIndices = newIndices.copyOf(writeT),
    )
    out.volumeRanges = newRanges
    android.util.Log.i("AiPaint", "fix45 subsample: ${triCount} → ${writeT} tris (stride=${stride})")
    return SubsampledMesh(out, stride)
}

/** B111: broadcast a subsampled per-triangle slot id array back to the original mesh's
 *  triangle count. Each subsampled triangle covers `stride` source triangles (every
 *  Nth was kept). The export uses this to write a 3MF with the full original geometry
 *  whose paint state is derived from the subsampled cascade output.
 *
 *  When `stride == 1` (no subsampling) this is the identity-with-widen-to-Int copy. */
internal fun broadcastSlotIdsToOriginalMesh(
    subsampledRegions: ByteArray,
    originalTriCount: Int,
    stride: Int,
): IntArray {
    require(stride >= 1) { "stride must be >= 1, got $stride" }
    val subsampledCount = subsampledRegions.size
    return IntArray(originalTriCount) { t ->
        val s = (t / stride).coerceAtMost(subsampledCount - 1).coerceAtLeast(0)
        if (subsampledCount == 0) 0 else (subsampledRegions[s].toInt() and 0xFF)
    }
}

/**
 * fix43 / fix44 — pure logic for `switchToAlternate`. Builds the per-triangle slot byte
 * array when the user toggles to a tree's alternate view. Two passes:
 *   1. Base colouring from the alternate tree's leaf slot assignments
 *   2. Replay every CustomSelection in order so manual paint/lasso commits override the
 *      base (last-write-wins on overlapping triangles, matching commit-time semantics)
 *
 * Extracted to a top-level function so it's unit-testable without an AndroidViewModel
 * harness.
 */
internal fun buildSwitchedTriangleRegions(
    altTree: List<AiRegionNode>,
    customSelections: List<CustomSelection>,
    triCount: Int,
): ByteArray {
    val out = ByteArray(triCount)
    altTree.forEach { root ->
        root.flatten().forEach { (node, _) ->
            if (node.isLeaf) {
                node.triangleIds.forEach { t ->
                    if (t in 0 until triCount) out[t] = node.region.slot.toByte()
                }
            }
        }
    }
    customSelections.forEach { sel ->
        val slotByte = sel.slot.toByte()
        sel.triangleIds.forEach { t ->
            if (t in 0 until triCount) out[t] = slotByte
        }
    }
    return out
}
