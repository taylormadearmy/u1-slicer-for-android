package com.u1.slicer.aipaint

import android.app.Application
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

class AiPaintViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Hardwired at 4 for Snapmaker U1's 4 extruders; change to 8 when fullspectrum lands
        const val TARGET_COLOURS = 4

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
            // Strip repeatedly to handle either ordering and any nesting.
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
        val newCompMap = computeMajorityRegions(state, prev)
        val nextState = applyTriangleRegions(state, prev, newCompMap).copy(
            canUndo = undoStack.isNotEmpty()
        )
        _uiState.value = AiPaintUiState.Result(nextState)
    }

    private fun runPipelineInternal(
        sourceModelPath: String,
        native: NativeLibrary,
        printerColours: List<String>?,
    ) {
        viewModelScope.launch {
            _uiState.value = AiPaintUiState.Running(1, "Analysing model topology…")
            try {
                // Pre-flight: check API key when required. Per-provider key store — switching
                // providers no longer drags one provider's key into another.
                val providerName = settings.aiPaintProvider.first()
                val apiKey = settings.aiPaintApiKeyFor(providerName).first()
                val provider = AiPaintProvider.fromId(providerName)
                if (provider.requiresKey && apiKey.isBlank()) {
                    _uiState.value = AiPaintUiState.Error(
                        "No API key set for ${provider.displayName}. Open Settings > AI Paint to add one, then try again."
                    )
                    return@launch
                }

                val mesh = native.getPreparePreviewMesh(
                    maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES
                ) ?: run {
                    _uiState.value = AiPaintUiState.Error("Could not read model geometry.")
                    return@launch
                }

                // Phase 1 — geometric segmentation to produce stable tap-targets. Topology
                // (dihedral flood fill) gives semantic components for hard-edged models; the
                // dispatcher falls back to spatial K-means for smooth single-shell prints
                // (vases / cat pots) where topology would return one giant blob.
                val (componentIds, numComponents) = withContext(Dispatchers.Default) {
                    MeshSegmenter.segmentByTopologyOrSpatial(mesh.trianglePositions)
                }

                Log.i("AiPaint", "Phase 1 → $numComponents components from ${mesh.trianglePositions.size / 9} triangles")

                val componentToRegion: IntArray
                val regionLabels: List<String>
                val regionColours: List<String>
                var find3DFellBack = false
                // When non-null, Find3D returned a per-triangle label and we use it directly as
                // the per-triangle region map — no need to flatten through componentToRegion,
                // so triangles within the same topology component can land in different regions.
                var find3DTriangleLabels: IntArray? = null

                if (provider.isFind3D) {
                    // Phase 2/3 — Find3D path. Skip the 2D render step; talk directly to the
                    // Find3D HuggingFace Space with our mesh as a point cloud and text queries
                    // for the 4 part names.
                    _uiState.value = AiPaintUiState.Running(2, "Sending mesh to Find3D…")
                    val queries = listOf("head", "body", "legs", "base")  // TODO: editable on result screen
                    val find3DLabels = Find3DClient.segmentByCentroid(
                        trianglePositions = mesh.trianglePositions,
                        queries = queries,
                        hfToken = apiKey.takeIf { it.isNotBlank() },
                    )
                    if (find3DLabels != null) {
                        _uiState.value = AiPaintUiState.Running(4, "Writing painted model…")
                        find3DTriangleLabels = find3DLabels
                        // Aggregate to per-component via majority for tap-to-move compatibility.
                        componentToRegion = aggregateLabelsToComponents(
                            find3DLabels, componentIds, numComponents, queries.size
                        )
                        regionLabels = queries.map { it.replaceFirstChar(Char::titlecase) }
                        regionColours = listOf("#E53935", "#1E88E5", "#43A047", "#FB8C00")
                        Log.i("AiPaint", "Find3D returned ${find3DLabels.size} per-triangle labels")
                    } else {
                        Log.w("AiPaint", "Find3D failed (${Find3DClient.lastError}) — Z-band fallback")
                        find3DFellBack = true
                        _uiState.value = AiPaintUiState.Running(4, "Find3D unavailable — falling back…")
                        componentToRegion = withContext(Dispatchers.Default) {
                            assignByZBands(mesh.trianglePositions, componentIds, numComponents, TARGET_COLOURS)
                        }
                        regionLabels = listOf("Base", "Lower", "Upper", "Top").take(TARGET_COLOURS)
                        regionColours = listOf("#37474F", "#1E88E5", "#43A047", "#FB8C00").take(TARGET_COLOURS)
                    }
                } else {
                    // Phase 2 — render 4 plain shaded views for the 2D vision AI to look at.
                    _uiState.value = AiPaintUiState.Running(2, "Rendering model views…")
                    val shadedBitmaps = withContext(Dispatchers.Default) {
                        CameraAngle.entries.map { angle ->
                            AiPaintRenderer.renderShaded(mesh.trianglePositions, 512, 512, angle)
                        }
                    }

                    // Phase 3 — AI returns a list of regions, each with a label, a suggested colour,
                    // and a bounding box in each view (normalised [0..1] screen coordinates).
                    _uiState.value = AiPaintUiState.Running(3, "Asking AI to label regions…")
                    val regionBoxes = AiLabelClient.labelByBoxes(provider, apiKey, shadedBitmaps, TARGET_COLOURS)
                    Log.i("AiPaint", "Phase 3 raw AI text: ${AiLabelClient.lastBoxesRaw?.replace('\n', ' ')?.take(1500)}")
                    regionBoxes.forEachIndexed { i, r ->
                        val areas = r.boxes.entries.joinToString(", ") { (a, b) ->
                            val w = (b[2] - b[0]).coerceAtLeast(0f)
                            val h = (b[3] - b[1]).coerceAtLeast(0f)
                            "${a.name.lowercase()}=${"%.2f".format(w * h)}"
                        }
                        Log.i("AiPaint", "  region $i \"${r.label}\" colour=${r.suggestedColour} areas: $areas")
                    }

                    // Phase 4 — back-project each component's triangles into all 4 views and vote
                    // by box membership.
                    _uiState.value = AiPaintUiState.Running(4, "Writing painted model…")
                    val projectors = CameraAngle.entries.associateWith { angle ->
                        AiPaintProjector.build(mesh.trianglePositions, angle, 512, 512)
                    }
                    if (AiLabelClient.lastBoxesFellBack) {
                        Log.w("AiPaint", "AI fell back — using Z-band 3D fallback. Raw: ${AiLabelClient.lastBoxesRaw?.take(200)}")
                        componentToRegion = withContext(Dispatchers.Default) {
                            assignByZBands(mesh.trianglePositions, componentIds, numComponents, TARGET_COLOURS)
                        }
                        regionLabels = listOf("Base", "Lower", "Upper", "Top").take(TARGET_COLOURS)
                        regionColours = listOf("#37474F", "#1E88E5", "#43A047", "#FB8C00").take(TARGET_COLOURS)
                    } else {
                        // Per-triangle assignment — preserves Gemini's small-region intent even
                        // when most of a topology component sits outside the box. We aggregate
                        // to componentToRegion via majority afterwards so the tap-to-move sheet
                        // still has a coherent per-component map.
                        val perTri = withContext(Dispatchers.Default) {
                            AiPaintRegionAssigner.assign(mesh.trianglePositions, regionBoxes, projectors)
                        }
                        find3DTriangleLabels = perTri
                        componentToRegion = aggregateLabelsToComponents(
                            perTri, componentIds, numComponents, TARGET_COLOURS
                        )
                        regionLabels = regionBoxes.map { it.label }
                        regionColours = regionBoxes.map { it.suggestedColour }
                    }
                }
                // Diagnostic: how did the assignment distribute components across regions?
                val regionCounts = IntArray(TARGET_COLOURS)
                for (c in 0 until numComponents) {
                    val r = componentToRegion[c]
                    if (r in regionCounts.indices) regionCounts[r]++
                }
                Log.i("AiPaint", "Phase 4 component→region distribution: ${regionCounts.toList()} (sum ${regionCounts.sum()}/$numComponents)")

                // Convert region labels/colours into AiRegion + per-region component lists.
                val regions = (0 until TARGET_COLOURS).map { regionIdx ->
                    val members = (0 until numComponents).filter { componentToRegion[it] == regionIdx }
                    AiRegion(
                        id = regionIdx,
                        label = regionLabels.getOrElse(regionIdx) { "Region ${regionIdx + 1}" },
                        suggestedColour = regionColours.getOrElse(regionIdx) { "#888888" },
                        componentIds = members,
                    )
                }
                // Per-triangle region indices. Find3D's per-triangle labels override the
                // component-level flatten when present (preserving its finer granularity).
                val regionIds = if (find3DTriangleLabels != null) {
                    IntArray(find3DTriangleLabels!!.size) { i ->
                        find3DTriangleLabels!![i].coerceIn(0, TARGET_COLOURS - 1)
                    }
                } else {
                    IntArray(componentIds.size) { componentToRegion[componentIds[it]] }
                }

                val fractions = MeshSegmenter.coverageFractions(regionIds, regions.size)
                val regionsWithCoverage = regions.mapIndexed { i, r ->
                    // Default each region's userColour to the matching printer slot so the result
                    // screen and the painted 3MF render in the user's loaded filament palette from
                    // the first frame, not in the AI's suggested palette. The AI's suggestion
                    // remains visible as the small caption inside the colour-swap sheet.
                    val printerHex = printerColours?.getOrNull(i)?.takeIf(::isValidHex)
                    r.copy(
                        coverageFraction = fractions.getOrElse(i) { 0f },
                        userColour = printerHex
                    )
                }
                val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
                PaintedMeshWriter.write(
                    mesh.trianglePositions, regionIds, regionsWithCoverage, outFile,
                    printerColours = printerColours
                )

                // Per-triangle region map — Find3D's labels feed this directly; otherwise we
                // derive it from the component-level map (uniform within each component).
                val triangleRegions = ByteArray(regionIds.size) { regionIds[it].toByte() }

                // Also compute the Z-band variant — even when the AI succeeded — so the user
                // can flip between "🤖 AI" and "📏 Height-based" on the result screen for a
                // direct visual comparison. Cheap (one pass over centroids) and the toggle is
                // load-bearing for diagnosing AI mistakes.
                val zBandCompToRegion = withContext(Dispatchers.Default) {
                    assignByZBands(mesh.trianglePositions, componentIds, numComponents, TARGET_COLOURS)
                }
                val zBandTriRegions = ByteArray(componentIds.size) {
                    zBandCompToRegion[componentIds[it]].toByte()
                }
                val zBandLabels = listOf("Base", "Lower", "Upper", "Top").take(TARGET_COLOURS)
                val zBandColours = listOf("#37474F", "#1E88E5", "#43A047", "#FB8C00").take(TARGET_COLOURS)
                val zBandFractions = MeshSegmenter.coverageFractions(
                    IntArray(zBandTriRegions.size) { zBandTriRegions[it].toInt() },
                    TARGET_COLOURS,
                )
                val zBandRegions = (0 until TARGET_COLOURS).map { i ->
                    val members = (0 until numComponents).filter { zBandCompToRegion[it] == i }
                    AiRegion(
                        id = i,
                        label = zBandLabels.getOrElse(i) { "Region ${i + 1}" },
                        suggestedColour = zBandColours.getOrElse(i) { "#888888" },
                        userColour = printerColours?.getOrNull(i)?.takeIf(::isValidHex),
                        coverageFraction = zBandFractions.getOrElse(i) { 0f },
                        componentIds = members,
                    )
                }
                val fallbackReason = when {
                    find3DFellBack -> "Find3D is currently unavailable — its HuggingFace Space is failing for all inputs right now (including the project's own example files). Split by height instead. Try Gemini or Claude in Settings while Find3D recovers."
                    AiLabelClient.lastBoxesFellBack && provider == AiPaintProvider.POLLINATIONS ->
                        "Pollinations couldn't process this model (free tier returns a refusal for vision tasks). Split by height instead. Set a Pollinations key in Settings to lift rate limits, or try Gemini / Claude."
                    AiLabelClient.lastBoxesFellBack ->
                        "${provider.displayName} couldn't process this model. Split by height instead — try a different provider in Settings → AI Paint."
                    else -> ""
                }
                val fellBack = AiLabelClient.lastBoxesFellBack || find3DFellBack
                _uiState.value = AiPaintUiState.Result(
                    AiPaintResultState(
                        regions = regionsWithCoverage,
                        usedAiFallback = fellBack,
                        fallbackReason = fallbackReason,
                        // Only snapshot an AI variant when the AI actually succeeded — when it
                        // fell back, the working copy is already the Z-band and there's no
                        // separate "AI result" to toggle back to.
                        aiTriangleRegions = if (fellBack) null else triangleRegions.copyOf(),
                        aiRegions = if (fellBack) emptyList() else regionsWithCoverage,
                        zBandTriangleRegions = zBandTriRegions,
                        zBandRegions = zBandRegions,
                        showingZBands = fellBack,
                        paintedModelPath = outFile.absolutePath,
                        sourceModelPath = sourceModelPath,
                        previewBitmap = null,
                        trianglePositions = mesh.trianglePositions,
                        componentIds = componentIds,
                        numComponents = numComponents,
                        componentToRegion = componentToRegion,
                        triangleRegions = triangleRegions,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = AiPaintUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun updateRegionColour(regionId: Int, hexColour: String) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val updated = current.state.regions.map { r ->
            if (r.id == regionId) r.copy(userColour = hexColour) else r
        }
        _uiState.value = AiPaintUiState.Result(current.state.copy(regions = updated))
    }

    /**
     * Non-AI fallback used when the AI refuses or returns malformed JSON: split the mesh by Z
     * height into equal-percentile bands and assign each component to whichever band contains
     * its centroid. For upright prints this yields "base / lower body / upper body / top".
     */
    private fun assignByZBands(
        positions: FloatArray,
        componentIds: IntArray,
        numComponents: Int,
        bandCount: Int
    ): IntArray {
        if (numComponents == 0 || bandCount <= 0) return IntArray(numComponents)
        // Per-component mean Z.
        val sumZ = FloatArray(numComponents)
        val cnt = IntArray(numComponents)
        val triCount = componentIds.size
        for (t in 0 until triCount) {
            val b = t * 9
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            val c = componentIds[t]
            sumZ[c] += cz
            cnt[c]++
        }
        val meanZ = FloatArray(numComponents) { if (cnt[it] > 0) sumZ[it] / cnt[it] else 0f }
        val minZ = meanZ.min()
        val maxZ = meanZ.max().coerceAtLeast(minZ + 1e-3f)
        val span = maxZ - minZ
        return IntArray(numComponents) { i ->
            val pct = (meanZ[i] - minZ) / span        // 0..1
            (pct * bandCount).toInt().coerceIn(0, bandCount - 1)
        }
    }

    /** Move a single topology component from its current region to [toRegion]. */
    fun moveComponent(componentId: Int, toRegion: Int) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (componentId !in 0 until state.numComponents) return
        if (toRegion !in state.regions.indices) return

        pushUndo(state.triangleRegions)
        val newTriRegions = state.triangleRegions.copyOf()
        for (t in state.componentIds.indices) {
            if (state.componentIds[t] == componentId) newTriRegions[t] = toRegion.toByte()
        }
        val newCompMap = state.componentToRegion.copyOf()
        newCompMap[componentId] = toRegion
        _uiState.value = AiPaintUiState.Result(
            applyTriangleRegions(state, newTriRegions, newCompMap).copy(canUndo = true)
        )
    }

    /**
     * Paint mode: set every triangle in [triangleIndices] to [toRegion]. Updates triangleRegions
     * directly, recomputes per-component majority for componentToRegion (so the move-component
     * sheet still shows the user's intent when paint mode is off), and refreshes per-region
     * coverage fractions. No disk I/O — fast enough for a tight tap-loop on the result screen.
     */
    fun paintTriangles(triangleIndices: List<Int>, toRegion: Int) {
        if (triangleIndices.isEmpty()) return
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (toRegion !in state.regions.indices) return

        val newTriRegions = state.triangleRegions.copyOf()
        val target = toRegion.toByte()
        for (t in triangleIndices) {
            if (t in newTriRegions.indices) newTriRegions[t] = target
        }
        val newCompMap = computeMajorityRegions(state, newTriRegions)
        _uiState.value = AiPaintUiState.Result(applyTriangleRegions(state, newTriRegions, newCompMap))
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
        val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
        PaintedMeshWriter.write(
            state.trianglePositions, regionIds, state.regions, outFile,
            printerColours = lastPrinterColours
        )
        _uiState.value = AiPaintUiState.Result(state.copy(paintedModelPath = outFile.absolutePath))
        return outFile.absolutePath
    }

    private fun applyTriangleRegions(
        state: AiPaintResultState,
        newTriRegions: ByteArray,
        newCompMap: IntArray,
    ): AiPaintResultState {
        val regionIds = IntArray(newTriRegions.size) { newTriRegions[it].toInt() }
        val fractions = MeshSegmenter.coverageFractions(regionIds, state.regions.size)
        val updatedRegions = state.regions.mapIndexed { i, r ->
            val members = (0 until state.numComponents).filter { newCompMap[it] == i }
            r.copy(
                coverageFraction = fractions.getOrElse(i) { 0f },
                componentIds = members,
            )
        }
        return state.copy(
            regions = updatedRegions,
            triangleRegions = newTriRegions,
            componentToRegion = newCompMap,
        )
    }

    /** Per-component majority vote across an IntArray of per-triangle labels (used by the
     *  Find3D path during the initial pipeline, before the result state exists). */
    private fun aggregateLabelsToComponents(
        triangleLabels: IntArray,
        componentIds: IntArray,
        numComponents: Int,
        numRegions: Int,
    ): IntArray {
        val tally = Array(numComponents) { IntArray(numRegions) }
        for (t in triangleLabels.indices) {
            val c = if (t < componentIds.size) componentIds[t] else continue
            val r = triangleLabels[t]
            if (c in tally.indices && r in 0 until numRegions) tally[c][r]++
        }
        return IntArray(numComponents) { c ->
            var best = 0; var bestCount = -1
            for (r in 0 until numRegions) if (tally[c][r] > bestCount) { bestCount = tally[c][r]; best = r }
            best
        }
    }

    /** Per-component winning region via majority vote across its triangles. */
    private fun computeMajorityRegions(state: AiPaintResultState, triRegions: ByteArray): IntArray {
        val numRegions = state.regions.size
        val tallies = Array(state.numComponents) { IntArray(numRegions) }
        for (t in state.componentIds.indices) {
            val c = state.componentIds[t]
            val r = triRegions[t].toInt() and 0xFF
            if (c in tallies.indices && r in 0 until numRegions) tallies[c][r]++
        }
        return IntArray(state.numComponents) { c ->
            var best = 0; var bestCount = -1
            for (r in 0 until numRegions) if (tallies[c][r] > bestCount) { bestCount = tallies[c][r]; best = r }
            best
        }
    }

    /** Toggle between the AI segmentation and the always-computed Z-band variant so the user
     *  can compare and pick whichever looks better. No-op if either snapshot is missing.
     *  Swaps `regions` + `triangleRegions` to the other snapshot; the previous variant's brush
     *  edits are lost (toggle is a comparison tool, not a workflow merge). Undo stack reset. */
    fun toggleZBands() {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        val next = if (state.showingZBands) {
            // → AI variant. Requires the AI snapshot to exist.
            val ai = state.aiTriangleRegions ?: return
            if (state.aiRegions.isEmpty()) return
            state.copy(
                regions = state.aiRegions,
                triangleRegions = ai.copyOf(),
                showingZBands = false,
                canUndo = false,
            )
        } else {
            // → Z-band variant.
            val zb = state.zBandTriangleRegions ?: return
            if (state.zBandRegions.isEmpty()) return
            state.copy(
                regions = state.zBandRegions,
                triangleRegions = zb.copyOf(),
                showingZBands = true,
                canUndo = false,
            )
        }
        _uiState.value = AiPaintUiState.Result(next)
        undoStack.clear()
    }

    /** Highlight a single component on the 3D view (others dimmed). Pass null to clear. */
    fun highlightComponent(componentId: Int?) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        if (current.state.highlightComponentId == componentId) return
        _uiState.value = AiPaintUiState.Result(current.state.copy(highlightComponentId = componentId))
    }

    fun redo(sourceModelPath: String, native: NativeLibrary) {
        _uiState.value = AiPaintUiState.Idle
        // Reuse the printer colours captured from the most recent runPipeline call so a Redo
        // doesn't silently switch back to the AI's suggestion palette.
        runPipeline(sourceModelPath, native, lastPrinterColours)
    }

    fun reset() { _uiState.value = AiPaintUiState.Idle }
}
