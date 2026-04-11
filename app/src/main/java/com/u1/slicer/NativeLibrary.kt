package com.u1.slicer

import android.util.Log
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceResult
import com.u1.slicer.viewer.NativePreviewMesh
import kotlinx.coroutines.sync.Mutex

/**
 * JNI bridge to the SAPIL (Slicer API Layer) native library.
 * All native methods correspond to functions in slicer_wrapper.cpp.
 */
class NativeLibrary {
    companion object {
        private const val TAG = "NativeLibrary"

        /**
         * Serializes all operations that mutate + read the global native model state
         * (setModelRotation, getPreparePreviewMesh). Without this, concurrent coroutines
         * on Dispatchers.IO race: setModelRotation mutates instance transforms while
         * getPreparePreviewMesh reads them, producing garbled/rotated preview geometry.
         */
        val previewMutex = Mutex()

        val isLoaded: Boolean = try {
            System.loadLibrary("prusaslicer-jni")
            Log.i(TAG, "Native library loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not available: ${e.message}")
            false
        }
    }

    // ---- Core ----
    external fun getCoreVersion(): String
    external fun configureDiagnostics(path: String)
    external fun getDiagnosticsState(): String

    // ---- Model ----
    external fun loadModel(path: String): Boolean
    external fun clearModel()
    // Cancel an in-progress QEM preview decimation. Called from clearModel() before
    // acquiring previewMutex so QEM bails out immediately.
    external fun cancelPreviewMesh()
    // Cancel an in-progress native slice. Triggers CanceledException at the next
    // OrcaSlicer checkpoint. Called from cancelSlicing().
    external fun cancelSlice()
    external fun getModelInfo(): ModelInfo?
    // Pass 0 to let native auto-select budget (flat models get 500K, others get 100K).
    external fun getPreparePreviewMesh(maxTriangles: Int = 0): NativePreviewMesh?

    // ---- Slicing ----
    external fun slice(config: SliceConfig): SliceResult?

    // ---- Profile ----
    external fun loadProfile(path: String): Boolean

    // ---- G-code ----
    external fun getGcodePreview(maxLines: Int = 100): String

    // ---- Multiple copies ----
    // positions: flat array [x0, y0, x1, y1, ...] in mm (bed-space)
    external fun setModelInstances(positions: FloatArray): Boolean

    // ---- Scale ----
    // Apply uniform or per-axis scale to the loaded model. Call before setModelInstances.
    external fun setModelScale(x: Float, y: Float, z: Float): Boolean

    // ---- Rotation ----
    // Apply Euler rotation (degrees) to the loaded model. Call after setModelScale and before setModelInstances.
    external fun setModelRotation(x: Float, y: Float, z: Float): Boolean

    // Returns flat [x0, y0, x1, y1, ...] world-space XY offsets for all instances.
    // Used by instrumented tests only.
    external fun getInstanceOffsets(): FloatArray

    // ---- Progress Callback (called from native code) ----
    fun onSliceProgress(percentage: Int, stage: String) {
        progressListener?.invoke(percentage, stage)
    }

    // ---- Kotlin-side listener ----
    var progressListener: ((Int, String) -> Unit)? = null
}
