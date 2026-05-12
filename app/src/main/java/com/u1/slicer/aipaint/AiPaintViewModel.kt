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

    private val app get() = getApplication<U1SlicerApplication>()
    private val settings get() = app.container.settingsRepository

    private val _uiState = MutableStateFlow<AiPaintUiState>(AiPaintUiState.Idle)
    val uiState: StateFlow<AiPaintUiState> = _uiState.asStateFlow()

    fun runPipeline(sourceModelPath: String, native: NativeLibrary) {
        viewModelScope.launch {
            _uiState.value = AiPaintUiState.Running(1, "Rendering model views…")
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

                // Phase 1 — render shaded views for AI (no pre-segmentation)
                val shadedBitmaps = withContext(Dispatchers.Default) {
                    CameraAngle.entries.map { angle ->
                        AiPaintRenderer.renderShaded(mesh.trianglePositions, 512, 512, angle)
                    }
                }

                // Phase 2 — AI identifies parts and their vertical boundaries
                _uiState.value = AiPaintUiState.Running(2, "Asking AI to identify parts…")
                val regions = AiLabelClient.label(provider, apiKey, shadedBitmaps)

                // Phase 3 — apply AI boundaries to segment mesh, render colored preview
                _uiState.value = AiPaintUiState.Running(3, "Applying regions to model…")
                val (regionIds, previewBitmap) = withContext(Dispatchers.Default) {
                    val sorted = regions.sortedBy { it.bottomPct }
                    val boundaries = FloatArray(sorted.size + 1)
                    boundaries[0] = 0f
                    sorted.forEachIndexed { i, r -> boundaries[i + 1] = r.topPct }
                    boundaries[sorted.size] = 100f  // clamp last boundary exactly
                    val ids = MeshSegmenter.segmentByBounds(mesh.trianglePositions, boundaries)
                    val preview = AiPaintRenderer.renderRegions(
                        mesh.trianglePositions, ids, 512, 512, CameraAngle.RIGHT_ISO
                    )
                    Pair(ids, preview)
                }
                val fractions = MeshSegmenter.coverageFractions(regionIds, regions.size)
                val regionsWithCoverage = regions.mapIndexed { i, r ->
                    r.copy(coverageFraction = fractions.getOrElse(i) { 0f })
                }

                // Phase 4 — write painted 3MF
                _uiState.value = AiPaintUiState.Running(4, "Writing painted model…")
                val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
                PaintedMeshWriter.write(mesh.trianglePositions, regionIds, regionsWithCoverage, outFile)

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
