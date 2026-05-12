package com.u1.slicer.aipaint

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.NativeLibrary
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.viewer.NativePreviewMesh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class AiPaintViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<U1SlicerApplication>()
    private val settings get() = app.container.settingsRepository

    private val _uiState = MutableStateFlow<AiPaintUiState>(AiPaintUiState.Idle)
    val uiState: StateFlow<AiPaintUiState> = _uiState.asStateFlow()

    fun runPipeline(sourceModelPath: String, native: NativeLibrary) {
        viewModelScope.launch {
            _uiState.value = AiPaintUiState.Running(1, "Analysing model shape…")
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

                // Phase 1 — geometry segmentation
                val mesh = native.getPreparePreviewMesh(
                    maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES
                ) ?: run {
                    _uiState.value = AiPaintUiState.Error("Could not read model geometry.")
                    return@launch
                }
                val regionIds = MeshSegmenter.segment(mesh.trianglePositions, targetRegions = 4)
                val fractions = MeshSegmenter.coverageFractions(regionIds, targetRegions = 4)

                // Phase 2 — render thumbnails
                _uiState.value = AiPaintUiState.Running(2, "Rendering views…")
                val bitmaps = mutableListOf<android.graphics.Bitmap>()
                CameraAngle.entries.forEach { angle ->
                    bitmaps += AiPaintRenderer.renderShaded(mesh.trianglePositions, 512, 512, angle)
                    bitmaps += AiPaintRenderer.renderRegions(mesh.trianglePositions, regionIds, 512, 512, angle)
                }

                // Phase 3 — AI labeling
                _uiState.value = AiPaintUiState.Running(3, "Asking AI to identify regions…")
                val regions = AiLabelClient.label(provider, apiKey, bitmaps).mapIndexed { i, r ->
                    r.copy(coverageFraction = fractions.getOrElse(i) { 0f })
                }

                // Phase 4 — write painted 3MF
                _uiState.value = AiPaintUiState.Running(4, "Writing painted model…")
                val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
                PaintedMeshWriter.write(mesh.trianglePositions, regionIds, regions, outFile)

                _uiState.value = AiPaintUiState.Result(
                    AiPaintResultState(
                        regions = regions,
                        paintedModelPath = outFile.absolutePath,
                        sourceModelPath = sourceModelPath,
                        previewBitmap = bitmaps[0]
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
