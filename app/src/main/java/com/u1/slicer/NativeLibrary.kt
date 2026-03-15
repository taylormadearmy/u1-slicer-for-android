package com.u1.slicer

import android.util.Log
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceResult

/**
 * JNI bridge to the SAPIL (Slicer API Layer) native library.
 * All native methods correspond to functions in slicer_wrapper.cpp.
 */
class NativeLibrary {
    companion object {
        private const val TAG = "NativeLibrary"

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
    external fun getModelInfo(): ModelInfo?

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

    // ---- Progress Callback (called from native code) ----
    fun onSliceProgress(percentage: Int, stage: String) {
        progressListener?.invoke(percentage, stage)
    }

    // ---- Kotlin-side listener ----
    var progressListener: ((Int, String) -> Unit)? = null
}
