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

    // ---- Diagnostics — Phase 0 differential harness ----
    // Returns a JSON dump of g_model after Model::read_from_file.
    // Path must be the same path passed to loadModel(); native re-loads to ensure
    // a clean snapshot independent of any prior mutations (rotation/scale/instances).
    // Returns null if the file fails to load.
    external fun nativeDumpBambuModel(path: String): String?

    // ---- Phase 1 sub-plan #1: g_model volume walkers ----
    // Pure reads of g_model. Callers MUST hold NativeLibrary.previewMutex across a
    // logical sequence of these calls (to prevent races with loadModel / setModelRotation).
    // These five accessors back KotlinBambuSnapshot.volumes population.

    /** Count of ModelObjects in g_model. Returns 0 when no model loaded. */
    external fun nativeGetObjectCount(): Int

    /** Count of ModelVolumes on g_model.objects[objectIndex]. Returns 0 for OOR. */
    external fun nativeGetVolumeCount(objectIndex: Int): Int

    /**
     * Slic3r runtime ObjectID (ObjectBase::id().id, size_t → Long).
     * Matches the VolumeSnapshot.objectId contract from sapil_bambu_snapshot.cpp
     * append_volume(). Returns 0L for out-of-range objectIndex.
     */
    external fun nativeGetObjectModelId(objectIndex: Int): Long

    /**
     * Packed per-volume scalars: [extruder, isMmPaintedBool, isSeamPaintedBool].
     *   - extruder: mv.config.opt_int("extruder") when mv.config.has("extruder"),
     *     else -1 as the null sentinel (decoded into VolumeSnapshot.extruder: Int?).
     *   - isMmPaintedBool / isSeamPaintedBool: 1 or 0.
     * Returns null for out-of-range indices or when no model is loaded.
     */
    external fun nativeGetVolumeScalars(objectIndex: Int, volumeIndex: Int): IntArray?

    /**
     * Triangle counts per painted state on a single FacetsAnnotation.
     *   - kind = 0 -> mv.mmu_segmentation_facets
     *   - kind = 1 -> mv.supported_facets
     * Returns a packed array [state1, count1, state2, count2, ...] sorted by
     * state ascending. Empty array when the annotation has no painted triangles.
     * Null for out-of-range indices, invalid kind, or when no model is loaded.
     *
     * Internally delegates to sapil::count_paint_states — the same helper used
     * by bambu_snapshot_json, so counts are guaranteed to match Phase 0's output.
     */
    external fun nativeGetPaintStateCounts(
        objectIndex: Int,
        volumeIndex: Int,
        kind: Int,
    ): IntArray?

    /**
     * Returns a JSON object with five project-level fields read from g_model after
     * a successful [loadModel]:
     *   {
     *     "isBbl":               bool,                  // g_is_bbl
     *     "fileVersion":         "x.y.z" | "",          // g_file_version.to_string() when valid, else ""
     *     "filamentColours":     ["#RRGGBB", ...],      // project config: filament_colour
     *     "filamentSettingsIds": ["Preset name", ...],  // filament_settings_id > filament_ids (first non-null)
     *     "filamentIds":         ["GFB98", ...]         // project config: filament_ids (raw)
     *   }
     *
     * Returns null when no model is loaded (same contract as the sub-plan #1
     * volume accessors). Callers MUST hold [previewMutex] for the duration of
     * any loadModel + accessor sequence.
     */
    external fun nativeGetProjectConfig(): String?

    // ---- Progress Callback (called from native code) ----
    fun onSliceProgress(percentage: Int, stage: String) {
        progressListener?.invoke(percentage, stage)
    }

    // ---- Kotlin-side listener ----
    var progressListener: ((Int, String) -> Unit)? = null
}
