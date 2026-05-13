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

import java.io.File

/**
 * AI Paint pipeline (post-fix32 pivot):
 *   1. Segment the model into N horizontal Z-bands (equal-percentile of mean Z per triangle).
 *   2. Render the banded model in one isometric view and ask the AI to name each band and
 *      suggest a realistic filament colour. AI is text-only — no spatial grounding required.
 *   3. Build N AiRegion entries, round-robin onto the 4 physical extruder slots.
 *   4. The user refines via paint / lasso / per-segment slot swap on the result screen.
 *
 * If AI is unavailable or fails to return parseable JSON, default labels (`Base`, `Hooves`,
 * `Body`, etc.) and colours are used silently — Z-bands are always the segmentation, AI is
 * purely decorative.
 */
class AiPaintViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Snapmaker U1 has 4 physical extruder slots — paint_color in the painted 3MF uses
        // states 1..4 (region indices 0..3). This is the SLOT count.
        const val TARGET_SLOTS = 4
        // The number of SEGMENTS (Z-bands) we split the model into. With N > 4, multiple
        // bands fold onto the 4 physical slots — the slot picker row lets users remap.
        const val TARGET_SEGMENTS = 12

        // Default labels for Z-bands when AI is unavailable. Generic — model-agnostic — so a
        // boat doesn't show up labelled "Hooves". AI fills these with real semantic names when
        // it succeeds. Sized to TARGET_SEGMENTS; "Band N" is fine as a placeholder.
        internal val ZBAND_LABELS = List(TARGET_SEGMENTS) { i -> "Band ${i + 1}" }
        internal val ZBAND_COLOURS = listOf(
            "#37474F", "#5D4037", "#795548", "#1E88E5", "#43A047",
            "#00ACC1", "#FB8C00", "#8E24AA", "#E53935", "#EC407A",
            "#FFEB3B", "#FFFFFF",
        )

        /**
         * Painted 3MFs are written by [PaintedMeshWriter] as `ai_paint_<timestamp>.3mf`. After
         * "Use this painting" calls `loadModelFromFile`, the working copy goes through the Bambu
         * sanitizer (`sanitized_` prefix) and the profile embedder (`embedded_` prefix). We strip
         * both prefixes in any order before comparing basenames so the "Edit AI Paint regions"
         * button can still recognise the in-memory cached result for the currently loaded model.
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

    /**
     * @param printerColours hex colours of the user's loaded extruder slots (E1..E4). When
     *   provided, these are written into the painted 3MF's filament_colour array so the slicer's
     *   Prepare / Preview viewers render the print in the user's physical filament colours
     *   instead of the AI's suggested region colours.
     */
    fun runPipeline(
        sourceModelPath: String,
        native: NativeLibrary,
        printerColours: List<String>? = null
    ) {
        lastPrinterColours = printerColours
        runPipelineInternal(sourceModelPath, native, printerColours)
    }

    private var lastPrinterColours: List<String>? = null

    private val HEX_REGEX = Regex("^#[0-9A-Fa-f]{6}$")
    private fun isValidHex(s: String): Boolean = HEX_REGEX.matches(s)

    // Undo: deque of triangleRegions snapshots, capped to 50 entries. One snapshot per stroke
    // (paint by tap stroke or tap-to-move action), not per intermediate frame, so a single
    // brush sweep is a single undo.
    private val undoStack = ArrayDeque<ByteArray>()
    private fun pushUndo(snapshot: ByteArray) {
        undoStack.addLast(snapshot.copyOf())
        while (undoStack.size > 50) undoStack.removeFirst()
    }

    /** Save the current triangle-region state to the undo stack. Called from the screen at the
     *  start of a brush stroke (ACTION_DOWN) and from moveComponent on each tap-to-move. */
    fun beginUndoCheckpoint() {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        pushUndo(current.state.triangleRegions)
        if (!current.state.canUndo) {
            _uiState.value = AiPaintUiState.Result(current.state.copy(canUndo = true))
        }
    }

    /** Pop the last undo snapshot. No-op if the stack is empty. */
    fun undo() {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        val prev = undoStack.removeLastOrNull() ?: return
        if (prev.size != state.triangleRegions.size) return
        val nextState = state.copy(
            triangleRegions = prev,
            canUndo = undoStack.isNotEmpty(),
        )
        _uiState.value = AiPaintUiState.Result(nextState)
    }

    private fun runPipelineInternal(
        sourceModelPath: String,
        native: NativeLibrary,
        printerColours: List<String>?,
    ) {
        viewModelScope.launch {
            _uiState.value = AiPaintUiState.Running(1, "Analysing model geometry…")
            try {
                val providerName = settings.aiPaintProvider.first()
                val apiKey = settings.aiPaintApiKeyFor(providerName).first()
                val provider = AiPaintProvider.fromId(providerName)

                val mesh = native.getPreparePreviewMesh(
                    maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES
                ) ?: run {
                    _uiState.value = AiPaintUiState.Error("Could not read model geometry.")
                    return@launch
                }

                val canCallAi = !provider.requiresKey || apiKey.isNotBlank()

                // Phase 2 — topology segmentation. Dihedral flood-fill gives N connected
                // surface components for figurines / dragons / goats; the dispatcher falls
                // back to spatial K-means for smooth single-shell models (cat pots, vases).
                _uiState.value = AiPaintUiState.Running(2, "Finding parts of the model…")
                val (componentIds, numComponents) = withContext(Dispatchers.Default) {
                    MeshSegmenter.segmentByTopologyOrSpatial(mesh.trianglePositions)
                }
                Log.i(
                    "AiPaint",
                    "Phase 2 → $numComponents components across ${componentIds.size} triangles"
                )

                // Phase 3 — pick a labelling strategy.
                //   • If we got ENOUGH topology components AND we can call AI: ask AI to group
                //     them into TARGET_SEGMENTS semantic regions (legs, body, head, ...). This
                //     is the path that worked well on figurines pre-fix32 — semantic groups.
                //   • Otherwise fall back to Z-bands (equal-width horizontal slices) with the
                //     AI optionally naming each band based on a shaded reference + banded view.
                val pipelineState = withContext(Dispatchers.Default) {
                    val topologyOk = numComponents >= 4
                    if (topologyOk && canCallAi) {
                        runTopologyGroupingPath(
                            mesh.trianglePositions, componentIds, numComponents, provider, apiKey
                        )
                    } else null
                } ?: withContext(Dispatchers.Default) {
                    runZBandPath(
                        mesh.trianglePositions, provider, apiKey, canCallAi
                    )
                }

                _uiState.value = AiPaintUiState.Running(4, "Writing painted model…")

                // Apply printer colours as userColour overrides on each region (drives the 3D
                // viewer palette + paint flow). Round-robin slot mapping.
                val regions = pipelineState.regions.mapIndexed { idx, r ->
                    val slot = idx % TARGET_SLOTS
                    val printerHex = printerColours?.getOrNull(slot)?.takeIf(::isValidHex)
                    r.copy(slot = slot, userColour = printerHex)
                }

                // Per-triangle segment + slot maps. triangleSegments comes from the chosen
                // strategy (topology group index or Z-band index). triangleRegions = slot.
                val triangleSegments = pipelineState.triangleSegments
                val triangleRegions = ByteArray(triangleSegments.size) { i ->
                    val seg = triangleSegments[i].toInt() and 0xFF
                    regions.getOrNull(seg)?.slot?.toByte() ?: 0
                }
                val slotIdsForFile = IntArray(triangleSegments.size) { i ->
                    triangleRegions[i].toInt() and 0xFF
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
                PaintedMeshWriter.write(
                    mesh.trianglePositions, slotIdsForFile, slotsView, outFile,
                    printerColours = printerColours
                )

                _uiState.value = AiPaintUiState.Result(
                    AiPaintResultState(
                        regions = regions,
                        paintedModelPath = outFile.absolutePath,
                        sourceModelPath = sourceModelPath,
                        previewBitmap = null,
                        trianglePositions = mesh.trianglePositions,
                        componentIds = pipelineState.componentIds,
                        numComponents = pipelineState.numComponents,
                        componentToRegion = pipelineState.componentToRegion,
                        triangleRegions = triangleRegions,
                        triangleSegments = triangleSegments,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = AiPaintUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Per-pipeline shape: enough to drive the result-state construction. */
    private data class PipelineState(
        val regions: List<AiRegion>,
        val triangleSegments: ByteArray,
        val componentIds: IntArray,
        val numComponents: Int,
        val componentToRegion: IntArray,
    )

    /** Topology → AI grouping. Returns null when AI fails so the caller falls back. */
    private suspend fun runTopologyGroupingPath(
        positions: FloatArray,
        componentIds: IntArray,
        numComponents: Int,
        provider: AiPaintProvider,
        apiKey: String,
    ): PipelineState? {
        // Target group count: clamp to numComponents (AI can't make more groups than
        // components since each group needs at least one).
        val targetGroups = TARGET_SEGMENTS.coerceAtMost(numComponents)
        // Render shaded + component-coloured views from the same isometric angle.
        val shaded = AiPaintRenderer.renderShaded(
            positions, 512, 512, CameraAngle.RIGHT_ISO
        )
        val displayColours = AiLabelClient.componentDisplayColors(numComponents)
        val banded = AiPaintRenderer.renderRegions(
            positions, componentIds, displayColours, 512, 512, CameraAngle.RIGHT_ISO
        )
        _uiState.value = AiPaintUiState.Running(3, "Asking AI to name the parts…")
        val grouped = AiLabelClient.labelGroups(
            provider, apiKey, shaded, banded, numComponents, targetGroups
        ) ?: return null

        // componentToRegion: each component to its group index.
        val componentToGroup = IntArray(numComponents) { c ->
            val g = grouped.indexOfFirst { it.componentIds.contains(c) }
            if (g >= 0) g else 0
        }
        // triangleSegments: per-triangle group index.
        val triangleSegments = ByteArray(componentIds.size) { t ->
            componentToGroup[componentIds[t]].toByte()
        }
        val segmentIds = IntArray(triangleSegments.size) { triangleSegments[it].toInt() and 0xFF }
        val fractions = computeCoverageFractions(segmentIds, targetGroups)
        val regions = grouped.mapIndexed { i, g ->
            g.copy(
                coverageFraction = fractions.getOrElse(i) { 0f },
            )
        }
        Log.i("AiPaint", "Topology+AI path: ${regions.size} groups: ${regions.joinToString { it.label }}")
        return PipelineState(
            regions = regions,
            triangleSegments = triangleSegments,
            componentIds = componentIds,
            numComponents = numComponents,
            componentToRegion = componentToGroup,
        )
    }

    /** Z-band fallback. Equal-width horizontal slices. AI optionally names the bands when
     *  available; otherwise generic "Band N" labels are used. Always succeeds. */
    private suspend fun runZBandPath(
        positions: FloatArray,
        provider: AiPaintProvider,
        apiKey: String,
        canCallAi: Boolean,
    ): PipelineState {
        val triangleBands = assignTriangleBands(positions, TARGET_SEGMENTS)
        var bandLabels: List<String> = ZBAND_LABELS.take(TARGET_SEGMENTS)
        var bandColours: List<String> = ZBAND_COLOURS.take(TARGET_SEGMENTS)
        if (canCallAi) {
            _uiState.value = AiPaintUiState.Running(3, "Asking AI to name the bands…")
            val colorInts = bandColours.map {
                runCatching { Color.parseColor(it) }.getOrDefault(Color.GRAY)
            }.toIntArray()
            val banded = AiPaintRenderer.renderRegions(
                positions, triangleBands, colorInts, 512, 512, CameraAngle.RIGHT_ISO,
            )
            val shaded = AiPaintRenderer.renderShaded(
                positions, 512, 512, CameraAngle.RIGHT_ISO,
            )
            val labelled = AiLabelClient.labelSegments(
                provider, apiKey, listOf(shaded, banded), TARGET_SEGMENTS
            )
            if (labelled != null && labelled.size == TARGET_SEGMENTS) {
                bandLabels = labelled.map { it.label }
                bandColours = labelled.map { it.colour }
                Log.i("AiPaint", "Z-band path: AI labels: ${bandLabels.joinToString()}")
            } else {
                Log.w("AiPaint", "Z-band path: AI failed; using default labels.")
            }
        }
        val fractions = computeCoverageFractions(triangleBands, TARGET_SEGMENTS)
        val regions = (0 until TARGET_SEGMENTS).map { i ->
            AiRegion(
                id = i,
                label = bandLabels.getOrElse(i) { "Band ${i + 1}" },
                suggestedColour = bandColours.getOrElse(i) { "#888888" },
                coverageFraction = fractions.getOrElse(i) { 0f },
                componentIds = listOf(i),
            )
        }
        val triangleSegments = ByteArray(triangleBands.size) { triangleBands[it].toByte() }
        val componentToRegion = IntArray(TARGET_SEGMENTS) { it }
        return PipelineState(
            regions = regions,
            triangleSegments = triangleSegments,
            componentIds = triangleBands,
            numComponents = TARGET_SEGMENTS,
            componentToRegion = componentToRegion,
        )
    }

    fun updateRegionColour(regionId: Int, hexColour: String) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val updated = current.state.regions.map { r ->
            if (r.id == regionId) r.copy(userColour = hexColour) else r
        }
        _uiState.value = AiPaintUiState.Result(current.state.copy(regions = updated))
    }

    /** Equal-width Z-band assignment: each triangle is bucketed by its centroid Z relative to
     *  the model's full Z range. Bands are equal-width in world space (not equal-population),
     *  which keeps proportions intuitive on upright models (e.g. tall thin neck → its own band
     *  even when triangle count is low). */
    private fun assignTriangleBands(positions: FloatArray, bandCount: Int): IntArray {
        val triCount = positions.size / 9
        if (triCount == 0 || bandCount <= 0) return IntArray(0)
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (t in 0 until triCount) {
            val b = t * 9
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            if (cz < minZ) minZ = cz
            if (cz > maxZ) maxZ = cz
        }
        val span = (maxZ - minZ).coerceAtLeast(1e-3f)
        return IntArray(triCount) { t ->
            val b = t * 9
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            val pct = (cz - minZ) / span
            (pct * bandCount).toInt().coerceIn(0, bandCount - 1)
        }
    }

    private fun computeCoverageFractions(triangleSegments: IntArray, segmentCount: Int): FloatArray {
        if (triangleSegments.isEmpty() || segmentCount <= 0) return FloatArray(segmentCount)
        val counts = IntArray(segmentCount)
        triangleSegments.forEach { counts[it.coerceIn(0, segmentCount - 1)]++ }
        val total = triangleSegments.size.toFloat()
        return FloatArray(segmentCount) { counts[it] / total }
    }

    /** Move every triangle that currently belongs to [componentId] (a band index) to the slot
     *  of segment [toRegion]. Single undo step. */
    fun moveComponent(componentId: Int, toRegion: Int) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (componentId !in 0 until state.numComponents) return
        if (toRegion !in state.regions.indices) return

        pushUndo(state.triangleRegions)
        val targetSlot = state.regions[toRegion].slot.toByte()
        val targetSegment = toRegion.toByte()
        val newTriRegions = state.triangleRegions.copyOf()
        val newTriSegments = state.triangleSegments.copyOf()
        for (t in state.componentIds.indices) {
            if (state.componentIds[t] == componentId) {
                newTriRegions[t] = targetSlot
                if (t in newTriSegments.indices) newTriSegments[t] = targetSegment
            }
        }
        val newCompMap = state.componentToRegion.copyOf()
        newCompMap[componentId] = toRegion
        val segmentIds = IntArray(newTriSegments.size) { newTriSegments[it].toInt() and 0xFF }
        val fractions = computeCoverageFractions(segmentIds, state.regions.size)
        val updatedRegions = state.regions.mapIndexed { i, r ->
            r.copy(
                coverageFraction = fractions.getOrElse(i) { 0f },
            )
        }
        _uiState.value = AiPaintUiState.Result(
            state.copy(
                regions = updatedRegions,
                triangleRegions = newTriRegions,
                triangleSegments = newTriSegments,
                componentToRegion = newCompMap,
                canUndo = true,
            )
        )
    }

    /** Brush: each triangle is mapped to a target SLOT (0..TARGET_SLOTS-1). triangleSegments
     *  is left alone — a painted triangle keeps its original segment for display purposes
     *  but its physical slot is overridden. */
    fun paintTriangles(triangleIndices: List<Int>, toRegion: Int) {
        if (triangleIndices.isEmpty()) return
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (toRegion !in 0 until TARGET_SLOTS) return

        val newTriRegions = state.triangleRegions.copyOf()
        val targetSlot = toRegion.toByte()
        for (t in triangleIndices) {
            if (t in newTriRegions.indices) newTriRegions[t] = targetSlot
        }
        _uiState.value = AiPaintUiState.Result(state.copy(triangleRegions = newTriRegions))
    }

    /**
     * Write the painted 3MF to disk with the current per-triangle region map and return its
     * path. Called only when the user clicks "Use this painting →" — avoids per-tap disk I/O
     * that previously froze the main thread for 100-300 ms per brush stroke.
     */
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
            printerColours = lastPrinterColours
        )
        _uiState.value = AiPaintUiState.Result(state.copy(paintedModelPath = outFile.absolutePath))
        return outFile.absolutePath
    }

    /** Lasso commit: apply a selected set of triangles to a target slot in one shot. */
    fun commitSelection(triangleIndices: List<Int>, toSlot: Int) {
        if (triangleIndices.isEmpty()) return
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (toSlot !in 0 until TARGET_SLOTS) return

        pushUndo(state.triangleRegions)
        val newTriRegions = state.triangleRegions.copyOf()
        val slotByte = toSlot.toByte()
        for (t in triangleIndices) {
            if (t in newTriRegions.indices) newTriRegions[t] = slotByte
        }
        _uiState.value = AiPaintUiState.Result(
            state.copy(triangleRegions = newTriRegions, canUndo = true)
        )
    }

    /** Reassign a single SEGMENT to a different physical slot. Mass-updates triangleRegions
     *  for every triangle in that segment; overrides any brush edits in those triangles. */
    fun setSegmentSlot(segmentId: Int, newSlot: Int) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (segmentId !in state.regions.indices) return
        if (newSlot !in 0 until TARGET_SLOTS) return
        if (state.regions[segmentId].slot == newSlot) return

        pushUndo(state.triangleRegions)
        val newRegions = state.regions.mapIndexed { i, r ->
            if (i == segmentId) r.copy(
                slot = newSlot,
                userColour = lastPrinterColours?.getOrNull(newSlot)?.takeIf(::isValidHex),
            ) else r
        }
        val newTriRegions = state.triangleRegions.copyOf()
        val segByte = segmentId.toByte()
        for (t in state.triangleSegments.indices) {
            if (state.triangleSegments[t] == segByte) newTriRegions[t] = newSlot.toByte()
        }
        _uiState.value = AiPaintUiState.Result(
            state.copy(
                regions = newRegions,
                triangleRegions = newTriRegions,
                canUndo = true,
            )
        )
    }

    /** Highlight a single component on the 3D view (others dimmed). Pass null to clear. */
    fun highlightComponent(componentId: Int?) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        if (current.state.highlightComponentId == componentId) return
        _uiState.value = AiPaintUiState.Result(current.state.copy(highlightComponentId = componentId))
    }

    fun redo(sourceModelPath: String, native: NativeLibrary) {
        _uiState.value = AiPaintUiState.Idle
        runPipeline(sourceModelPath, native, lastPrinterColours)
    }

    fun reset() { _uiState.value = AiPaintUiState.Idle }
}
