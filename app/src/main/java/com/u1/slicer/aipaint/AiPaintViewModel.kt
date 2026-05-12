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
    }

    private val app get() = getApplication<U1SlicerApplication>()
    private val settings get() = app.container.settingsRepository

    private val _uiState = MutableStateFlow<AiPaintUiState>(AiPaintUiState.Idle)
    val uiState: StateFlow<AiPaintUiState> = _uiState.asStateFlow()

    fun runPipeline(sourceModelPath: String, native: NativeLibrary) {
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
                PaintedMeshWriter.write(mesh.trianglePositions, regionIds, regionsWithCoverage, outFile)

                // Render final preview with AI-assigned colours
                val regionColors = regionsWithCoverage.map { r ->
                    runCatching { android.graphics.Color.parseColor(r.effectiveColour) }
                        .getOrDefault(android.graphics.Color.GRAY)
                }.toIntArray()
                val previewBitmap = withContext(Dispatchers.Default) {
                    AiPaintRenderer.renderRegions(
                        mesh.trianglePositions, regionIds, regionColors, 512, 512, CameraAngle.RIGHT_ISO
                    )
                }

                _uiState.value = AiPaintUiState.Result(
                    AiPaintResultState(
                        regions = regionsWithCoverage,
                        paintedModelPath = outFile.absolutePath,
                        sourceModelPath = sourceModelPath,
                        previewBitmap = previewBitmap
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

    fun redo(sourceModelPath: String, native: NativeLibrary) {
        _uiState.value = AiPaintUiState.Idle
        runPipeline(sourceModelPath, native)
    }

    fun reset() { _uiState.value = AiPaintUiState.Idle }
}
