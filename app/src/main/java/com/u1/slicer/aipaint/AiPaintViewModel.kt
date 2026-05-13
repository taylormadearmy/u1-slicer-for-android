package com.u1.slicer.aipaint

import android.app.Application
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

    private fun runPipelineInternal(
        sourceModelPath: String,
        native: NativeLibrary,
        printerColours: List<String>?,
    ) {
        viewModelScope.launch {
            _uiState.value = AiPaintUiState.Running(1, "Analysing model topology…")
            try {
                // Pre-flight: check API key when required
                val providerName = settings.aiPaintProvider.first()
                val apiKey = settings.aiPaintApiKey.first()
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

                // Phase 1 — topology segmentation: dihedral flood fill → ≤16 components
                val (componentIds, numComponents) = withContext(Dispatchers.Default) {
                    MeshSegmenter.segmentByTopology(mesh.trianglePositions)
                }

                // Phase 2 — render 4 shaded + 4 component-coloured views for AI
                _uiState.value = AiPaintUiState.Running(2, "Rendering model views…")
                val componentColors = AiLabelClient.componentDisplayColors(numComponents)
                val (shadedBitmaps, componentBitmaps) = withContext(Dispatchers.Default) {
                    val shaded = CameraAngle.entries.map { angle ->
                        AiPaintRenderer.renderShaded(mesh.trianglePositions, 512, 512, angle)
                    }
                    val comps = CameraAngle.entries.map { angle ->
                        AiPaintRenderer.renderRegions(
                            mesh.trianglePositions, componentIds, componentColors, 512, 512, angle
                        )
                    }
                    Pair(shaded, comps)
                }

                // Phase 3 — AI groups components into TARGET_COLOURS semantic regions
                _uiState.value = AiPaintUiState.Running(3, "Asking AI to label parts…")
                val regions = AiLabelClient.label(
                    provider, apiKey,
                    shadedBitmaps, componentBitmaps,
                    numComponents, TARGET_COLOURS
                )

                // Phase 4 — map components → region ids, write painted 3MF
                _uiState.value = AiPaintUiState.Running(4, "Writing painted model…")
                val componentToRegion = IntArray(numComponents)
                regions.forEachIndexed { regionIdx, region ->
                    region.componentIds.forEach { c ->
                        if (c in 0 until numComponents) componentToRegion[c] = regionIdx
                    }
                }
                val regionIds = IntArray(componentIds.size) { componentToRegion[componentIds[it]] }

                val fractions = MeshSegmenter.coverageFractions(regionIds, regions.size)
                val regionsWithCoverage = regions.mapIndexed { i, r ->
                    r.copy(coverageFraction = fractions.getOrElse(i) { 0f })
                }
                val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
                PaintedMeshWriter.write(
                    mesh.trianglePositions, regionIds, regionsWithCoverage, outFile,
                    printerColours = printerColours
                )

                _uiState.value = AiPaintUiState.Result(
                    AiPaintResultState(
                        regions = regionsWithCoverage,
                        paintedModelPath = outFile.absolutePath,
                        sourceModelPath = sourceModelPath,
                        previewBitmap = null,
                        trianglePositions = mesh.trianglePositions,
                        componentIds = componentIds,
                        numComponents = numComponents,
                        componentToRegion = componentToRegion
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

    /** Move a single topology component from its current region to [toRegion]. Rewrites the painted 3MF. */
    fun moveComponent(componentId: Int, toRegion: Int) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        val state = current.state
        if (componentId !in 0 until state.numComponents) return
        if (toRegion !in state.regions.indices) return
        if (state.componentToRegion[componentId] == toRegion) return

        val newMap = state.componentToRegion.copyOf()
        newMap[componentId] = toRegion
        val newState = applyComponentMap(state, newMap)
        _uiState.value = AiPaintUiState.Result(newState)
    }

    /** Highlight a single component on the 3D view (others dimmed). Pass null to clear. */
    fun highlightComponent(componentId: Int?) {
        val current = _uiState.value as? AiPaintUiState.Result ?: return
        if (current.state.highlightComponentId == componentId) return
        _uiState.value = AiPaintUiState.Result(current.state.copy(highlightComponentId = componentId))
    }

    private fun applyComponentMap(state: AiPaintResultState, componentToRegion: IntArray): AiPaintResultState {
        val regionIds = IntArray(state.componentIds.size) { i ->
            componentToRegion[state.componentIds[i]]
        }
        val fractions = MeshSegmenter.coverageFractions(regionIds, state.regions.size)
        val updatedRegions = state.regions.mapIndexed { i, r ->
            val members = (0 until state.numComponents).filter { componentToRegion[it] == i }
            r.copy(
                coverageFraction = fractions.getOrElse(i) { 0f },
                componentIds = members
            )
        }
        val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
        PaintedMeshWriter.write(
            state.trianglePositions, regionIds, updatedRegions, outFile,
            printerColours = lastPrinterColours
        )
        return state.copy(
            regions = updatedRegions,
            paintedModelPath = outFile.absolutePath,
            componentToRegion = componentToRegion
        )
    }

    fun redo(sourceModelPath: String, native: NativeLibrary) {
        _uiState.value = AiPaintUiState.Idle
        // Reuse the printer colours captured from the most recent runPipeline call so a Redo
        // doesn't silently switch back to the AI's suggestion palette.
        runPipeline(sourceModelPath, native, lastPrinterColours)
    }

    fun reset() { _uiState.value = AiPaintUiState.Idle }
}
