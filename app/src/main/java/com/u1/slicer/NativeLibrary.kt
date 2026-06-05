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

    /**
     * Load a Bambu multi-plate 3MF but restrict `g_model.objects` to the target plate.
     *
     * Forwards to `Model::read_from_file(..., plate_id = plateIdx + 1)` when `plateIdx >= 0`
     * (the BBS importer convention is 1-based, 0 meaning "all plates"). `plateIdx = -1`
     * is the Kotlin-side alias for "load all plates" — forwards with `plate_id = 0`.
     *
     * Callers MUST hold [previewMutex] for the load + any subsequent accessor sequence
     * (same contract as [loadModel]). Phase 1 sub-plan #2b retires the Kotlin
     * `BambuSanitizer.extractPlate` disk-rewrite pass in favour of this entry point.
     */
    external fun loadModelForPlate(path: String, plateIdx: Int): Boolean

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

    // ---- Additive model loading ----
    // Append objects from an additional file into the already-loaded model.
    // Primary file's embedded config is preserved. Call setObjectPositions() after.
    external fun addModel(path: String): Boolean

    // F85: append objects from a specific plate of a 3MF. plateIdx is 0-based;
    // -1 means all plates (same as addModel). Primary config is preserved.
    external fun addModelForPlate(path: String, plateIdx: Int): Boolean

    // Returns flat [sizeX0, sizeY0, sizeZ0, sizeX1, ...] per object (no offset applied).
    external fun getObjectBoundingBoxes(): FloatArray

    // Set per-object XY lower-left positions: [x0, y0, x1, y1, ...], one pair per object.
    // positions.size / 2 must equal object count.
    external fun setObjectPositions(positions: FloatArray): Boolean

    /**
     * F66 — per-object world-space AABB min, returned as flat `[minX0, minY0, minX1, minY1, ...]`.
     * Each `(minX, minY)` is one object's current bed-space lower-left corner, computed from
     * its instance transform applied to its model-part volumes. Format matches
     * [setObjectPositions] input — feeding the result back into setObjectPositions is
     * idempotent (the engine already has these positions internally).
     *
     * Used by promoteToMultiObjectIfApplicable to populate `customObjectPositions` after a
     * multi-object load without imposing a forced grid layout on intentionally-positioned
     * files (Button-for-S-trousers' tessellated buttons, painted multi-extruder 3MFs).
     */
    external fun nativeGetObjectWorldAABBMins(): FloatArray

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

    // Returns per-instance world-space minimum Z (after full instance + volume
    // transform), in object/instance enumeration order. Used by the B108
    // multi-object regression test only.
    external fun getInstanceWorldZMins(): FloatArray

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

    /**
     * Number of plates in g_plate_data_list. Returns 0 when no model is loaded.
     * Callers MUST hold [previewMutex] for any loadModel + accessor sequence.
     */
    external fun nativeGetPlateCount(): Int

    /**
     * Returns the Phase 0 append_plate JSON for the plate at the given 0-based
     * index — a JSON object with plateIndex, filamentColours,
     * filamentSettingsIds, objectInstanceMap [{objectId,instanceId}],
     * customGcode, and a stringified plateConfig key/value map.
     *
     * Returns null when no model is loaded, plateIndex is out of range, or the
     * plate slot is null. Callers MUST hold [previewMutex].
     */
    external fun nativeGetPlateData(plateIndex: Int): String?

    /**
     * Returns a JSON array of every ModelObject in g_model:
     *   [{"objectId": <runtime-size_t>, "name": "...", "extruder": <int>, "sourcePath": "..."}, ...]
     *
     * `objectId` is Slic3r's process-local runtime ObjectID — NOT the XML
     * object id. `extruder` 0 means inherit/unset. Returns null when no
     * model is loaded. Callers MUST hold [previewMutex].
     *
     * Production code that needs XML-id-keyed maps should continue to read
     * `ThreeMfInfo.objectExtruderMap`; this accessor is snapshot-scoped.
     */
    external fun nativeGetObjectExtruderMap(): String?

    /**
     * Returns JSON array of all objects with per-volume extruder + paint data:
     *   [{"objectIndex": 0, "objectExtruder": 1, "volumes": [
     *     {"volumeIndex": 0, "extruder": 1, "isMmPainted": true, "isSeamPainted": false}, ...
     *   ]}, ...]
     *
     * `extruder` at volume level: -1 = inherit from object, 0 = unset, 1-4 = explicit.
     * Returns null when no model is loaded.
     * Callers MUST hold [previewMutex].
     */
    external fun nativeGetAllVolumeExtruders(): String?

    /**
     * F54 fix36: per-volume triangle counts captured during the most recent
     * getPreparePreviewMesh build, in the same mesh-build order as
     * nativeGetAllVolumeExtruders enumerates them. Sum equals the total triangle count of
     * the cached preview mesh. Returns null when no model is loaded or the preview cache
     * has been invalidated.
     *
     * Used by AiPaintViewModel to populate NativePreviewMesh.volumeRanges so the cascade's
     * per-volume branch (B) can attribute preview triangles back to model volumes.
     *
     * Callers MUST hold [previewMutex].
     */
    external fun nativeGetPreviewVolumeTriangleCounts(): IntArray?

    /**
     * F95: triangle index at which the trailing negative/modifier-volume block begins in the
     * most recent getPreparePreviewMesh build, or -1 when the model has no negative/modifier
     * volumes. Triangles at or after this index are non-model-part helper geometry that the
     * renderer draws translucent. Returned value is in the same mesh-build order as the
     * preview's trianglePositions/extruderIndices. Callers set it on
     * [com.u1.slicer.viewer.NativePreviewMesh.modifierBlockStartTriangle] right after
     * getPreparePreviewMesh returns, mirroring the volumeRanges population pattern.
     *
     * Callers MUST hold [previewMutex].
     */
    external fun nativeGetPreviewModifierBlockStart(): Int

    // ---- F66: Split + Auto-Orient + per-object pose ----

    /** True iff `g_model.objects[objIdx]->parts_count() > 1` — cheap probe used
     *  to enable/disable the Split-to-Objects button. */
    external fun nativeIsObjectSplittable(objIdx: Int): Boolean

    /** True iff `volume.is_splittable()` — cheap probe for the Split-to-Parts button. */
    external fun nativeIsVolumeSplittable(objIdx: Int, volIdx: Int): Boolean

    /** Split the object at `objIdx` into its connected components. Returns
     *  `[removedIdx, addedCount]` on success, `null` if the object had only one
     *  connected component (no mutation). The replacement objects occupy indices
     *  `[removedIdx, removedIdx + addedCount)`; objects above shift up by `addedCount - 1`. */
    external fun nativeSplitObject(objIdx: Int): IntArray?

    /** Split one volume into multiple volumes within the same object. Returns the
     *  new volume count, or -1 on failure. */
    external fun nativeSplitVolume(objIdx: Int, volIdx: Int): Int

    /** B132c follow-up (v2.10.4): deep-copy an existing object at [objIdx].
     *  Returns the new object's index on success (always `nativeGetObjectCount()-1`),
     *  or -1 on failure. The duplicate keeps the source's volumes, paint state,
     *  per-volume extruder overrides, and instance transform — callers should
     *  call `setObjectPositions` afterwards to place the new object on the bed. */
    external fun nativeDuplicateObject(objIdx: Int): Int

    /** Auto-orient one object so a stable face is on the bed. Returns the new
     *  Euler rotation `[x, y, z]` in radians, or `null` on failure. TBB-parallel
     *  internally — callers wrap this in LongOpService. */
    external fun nativeAutoOrientObject(objIdx: Int): DoubleArray?

    /** Auto-orient every object on the bed. Returns the count of successfully
     *  oriented objects. Same threading discipline as `nativeAutoOrientObject`. */
    external fun nativeAutoOrientAll(): Int

    /** Set `instances[0]` rotation for one object. Angles in degrees, Euler XYZ. */
    external fun nativeSetObjectRotation(objIdx: Int, x: Float, y: Float, z: Float): Boolean

    /** Get `instances[0]` rotation for one object as `[x, y, z]` degrees (length 3). */
    external fun nativeGetObjectRotation(objIdx: Int): FloatArray

    /** Set `instances[0]` per-axis scaling factor for one object. */
    external fun nativeSetObjectScale(objIdx: Int, sx: Float, sy: Float, sz: Float): Boolean

    /** Get `instances[0]` per-axis scaling factor as `[sx, sy, sz]` (length 3). */
    external fun nativeGetObjectScale(objIdx: Int): FloatArray

    /** Display name for the object. Empty string if no model loaded or OOR. */
    external fun nativeGetObjectName(objIdx: Int): String?

    /** Display name for one volume within an object. */
    external fun nativeGetVolumeName(objIdx: Int, volIdx: Int): String?

    /** 1-indexed extruder slot the volume is assigned to (Orca convention). */
    external fun nativeGetVolumeExtruder(objIdx: Int, volIdx: Int): Int

    /** Assign a 1-indexed extruder slot to one volume — used by the Parts panel. */
    external fun nativeSetVolumeExtruder(objIdx: Int, volIdx: Int, slot: Int): Boolean

    // ---- Progress Callback (called from native code) ----
    fun onSliceProgress(percentage: Int, stage: String) {
        progressListener?.invoke(percentage, stage)
    }

    // ---- Kotlin-side listener ----
    var progressListener: ((Int, String) -> Unit)? = null
}
