#pragma once

// =============================================================================
// SAPIL — Slicer API Layer
// JNI bridge between Android/Kotlin and PrusaSlicer C++ core
// =============================================================================

#include <jni.h>
#include <string>
#include <vector>
#include <functional>
#include <optional>
#include <array>
#include <android/log.h>

#define SAPIL_TAG "SAPIL"
#define SAPIL_LOGI(...) __android_log_print(ANDROID_LOG_INFO, SAPIL_TAG, __VA_ARGS__)
#define SAPIL_LOGW(...) __android_log_print(ANDROID_LOG_WARN, SAPIL_TAG, __VA_ARGS__)
#define SAPIL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SAPIL_TAG, __VA_ARGS__)

namespace sapil {

// ---- Diagnostics ----
void diagnostics_set_output_path(const std::string& path);
std::string diagnostics_get_state_json();
void diagnostics_record_native_event(const std::string& event, const std::string& payload_json = "{}");
void diagnostics_trace_native_event(const std::string& event, const std::string& payload_json = "{}");
void diagnostics_clear_trace_buffer();
void diagnostics_flush_trace_buffer(const std::string& reason);
void diagnostics_note_clipper_point(long long x, long long y, const char* source);

// ---- Progress Callback ----
using ProgressCallback = std::function<void(int percentage, const std::string& stage)>;

// ---- Slicing Configuration ----
struct SliceConfig {
    // Print settings
    float layer_height = 0.2f;
    float first_layer_height = 0.3f;
    int perimeters = 2;
    int top_solid_layers = 5;
    int bottom_solid_layers = 4;
    float fill_density = 0.15f;  // 0.0 - 1.0
    std::string fill_pattern = "gyroid";

    // Speed settings (mm/s)
    float print_speed = 60.0f;
    float travel_speed = 150.0f;
    float first_layer_speed = 20.0f;

    // Temperature
    int nozzle_temp = 210;
    int bed_temp = 60;

    // Retraction
    float retract_length = 0.8f;
    float retract_speed = 45.0f;

    // Support
    bool support_enabled = false;
    std::string support_type = "normal"; // "normal", "tree"
    float support_angle = 45.0f;
    int support_filament = 0;            // 1-based Orca filament index; 0 = default
    int support_interface_filament = 0;  // 1-based Orca filament index; 0 = default

    // Skirt/Brim
    int skirt_loops = 0;
    float skirt_distance = 6.0f;
    float brim_width = 0.0f;

    // Printer bed (Snapmaker U1: 270x270x270mm)
    float bed_size_x = 270.0f;
    float bed_size_y = 270.0f;
    float max_print_height = 270.0f;

    // Nozzle
    float nozzle_diameter = 0.4f;

    // Filament
    float filament_diameter = 1.75f;
    std::string filament_type = "PLA";
    std::vector<std::string> filament_types; // per-extruder material types; empty = use filament_type

    // Multi-extruder (up to 4 for Snapmaker U1)
    int extruder_count = 1;
    std::vector<int> extruder_temps;           // per-extruder nozzle temps
    std::vector<float> extruder_retract_length; // per-extruder retraction
    std::vector<float> extruder_retract_speed;  // per-extruder retraction speed

    // Wipe tower (for multi-extruder)
    bool wipe_tower_enabled = false;
    float wipe_tower_x = 170.0f;
    float wipe_tower_y = 140.0f;
    float wipe_tower_width = 60.0f;

    // B106: machine G-code templates for STL files (no embedded Snapmaker profile).
    // Empty = use OrcaSlicer's built-in default. OrcaSlicer resolves {variable}
    // template expressions at G-code generation time.
    std::string machine_start_gcode;
    std::string machine_end_gcode;

    // F91 (2026-05-25): per-extruder filament tuning sourced from the user's filament
    // library on the Kotlin side at slice time. Empty vector = "user has not set this
    // — fall back to the embed value (via profile_keys[]) or the U1 hardware default".
    // When non-empty, applyConfigToPrusa uses these AS the final value, overriding
    // both the embed value and the hardware default. This makes the library reach STL
    // slices too (which bypass ProfileEmbedder entirely on the Kotlin side).
    std::vector<float> filament_flow_ratios;
    std::vector<float> filament_max_volumetric_speeds;
    std::vector<int>   filament_fan_min_speeds;
    std::vector<int>   filament_fan_max_speeds;
    std::vector<int>   filament_overhang_fan_speeds;
    std::vector<int>   filament_additional_cooling_fan_speeds;
    std::vector<float> filament_slow_down_layer_times;
    std::vector<float> filament_slow_down_min_speeds;
    std::vector<int>   filament_close_fan_first_layers;
    std::vector<int>   filament_full_fan_speed_layers;
    std::vector<int>   filament_enable_pressure_advance;   // 0 = unset/off, 1 = on
    std::vector<float> filament_pressure_advances;
    std::vector<float> filament_minimal_purge_on_wipe_tower;
    std::vector<int>   filament_nozzle_temp_initial_layers;
    std::vector<int>   filament_bed_temp_initial_layers;
    std::vector<float> filament_costs;

    // Full-spectrum mixed-filament recipe (stage 2; serialized MixedFilamentManager output)
    std::string mixed_filament_definitions = "";
};

// ---- Model Info ----
struct ModelInfo {
    std::string filename;
    std::string format;       // "stl", "3mf", "step", "obj"
    float size_x = 0, size_y = 0, size_z = 0;  // bounding box mm
    int triangle_count = 0;
    int volume_count = 0;
    bool is_manifold = true;
};

// ---- Native Prepare Preview ----
struct PreviewMesh {
    std::vector<float> triangle_positions;   // world-space xyz triplets, 9 floats per triangle
    std::vector<uint8_t> extruder_indices;   // 0-based per-triangle preview color/extruder index
};

// ---- Slice Result ----
struct SliceResult {
    bool success = false;
    bool cancelled = false;
    std::string error_message;
    std::string gcode_path;
    int total_layers = 0;
    float estimated_time_seconds = 0;
    float estimated_filament_mm = 0;
    float estimated_filament_grams = 0;
};

// ---- Core API ----
class SlicerEngine {
public:
    SlicerEngine();
    ~SlicerEngine();

    // Version info
    std::string getCoreVersion() const;

    // Model operations
    bool loadModel(const std::string& filepath);

    /**
     * Load a 3MF, optionally filtered to one BBS plate.
     *
     * @param plate_id 0 = load all plates (default, same as loadModel(path));
     *                 >0 = 1-based plate_id passed to Model::read_from_file, which
     *                 causes the BBS importer to only instantiate objects in
     *                 m_plater_data[plate_id]. Used by Phase 1 sub-plan #2b to
     *                 retire the Kotlin BambuSanitizer.extractPlate disk rewrite.
     */
    bool loadModel(const std::string& filepath, int plate_id);

    ModelInfo getModelInfo() const;
    // Pass 0 (default) to auto-select budget: flat models get 500K, others 100K.
    PreviewMesh getPreparePreviewMesh(int max_triangles = 0) const;
    void clearModel();

    // Cancel an in-progress getPreparePreviewMesh() QEM decimation.
    // Safe to call from any thread. The QEM loop checks this flag every iteration.
    static void cancelPreviewMesh();

    // Cancel an in-progress slice(). Calls Print::cancel() which triggers
    // CanceledException at the next throw_if_canceled() checkpoint.
    // Safe to call from any thread.
    static void cancelSlice();

    // Slicing
    SliceResult slice(const SliceConfig& config, ProgressCallback progress = nullptr);

    // Profile management
    bool loadProfile(const std::string& ini_path);
    SliceConfig getConfigFromProfile() const;

    // G-code
    std::string getGcodePreview(int max_lines = 100) const;

    // Multiple copies — set instance positions (x,y pairs in mm, bed-space)
    // positions: flat array [x0, y0, x1, y1, ...], clears existing instances first
    bool setModelInstances(const std::vector<std::pair<float, float>>& positions);

    // Scale the loaded model (applied per instance, before setModelInstances)
    bool setModelScale(float x, float y, float z);

    // Rotate the loaded model (Euler angles in degrees, applied per instance).
    // Call after setModelScale and before setModelInstances.
    bool setModelRotation(float rx_deg, float ry_deg, float rz_deg);

    // Returns flat [x0, y0, x1, y1, ...] world-space XY offsets for all instances
    // (in object/instance enumeration order). Used by instrumented tests.
    std::vector<float> getInstanceOffsets() const;

    // Returns per-instance world-space minimum Z (after full instance + volume
    // transform), in object/instance enumeration order. Used by the B108
    // multi-object regression test to verify that each instance's bottom sits
    // on the bed after setModelScale.
    std::vector<float> getInstanceWorldZMins() const;

    // Append objects from an additional file into the already-loaded g_model.
    // The primary file's embedded config (g_model_config) is preserved unchanged.
    // Caller must call setObjectPositions() afterwards to lay out all objects.
    // Returns false if no primary model is loaded or the file fails to parse.
    bool addModel(const std::string& filepath);

    /**
     * Append objects from a specific plate of a 3MF file into the already-loaded g_model.
     *
     * @param plate_id 0 = load all plates (same as addModel(path));
     *                 >0 = 1-based plate_id — only objects from that plate are appended.
     * Used by F85 to add a user-selected plate from a multi-plate 3MF without replacing
     * the primary model.
     */
    bool addModel(const std::string& filepath, int plate_id);

    // Returns flat [sizeX0, sizeY0, sizeZ0, sizeX1, sizeY1, sizeZ1, ...] for each
    // object in g_model, computed from instance[0]'s scale+rotation without
    // translation offset. Empty if no model is loaded.
    std::vector<float> getObjectBoundingBoxes() const;

    // Set XY bed-space lower-left position for each object independently.
    // positions[i*2], positions[i*2+1] is the lower-left corner for g_model.objects[i].
    // positions.size() / 2 must equal g_model.objects.size().
    bool setObjectPositions(const std::vector<std::pair<float, float>>& positions);

    // F66 — returns flat [minX0, minY0, minX1, minY1, ...] world-space AABB
    // min for each object, computed from instance[0]'s full transform applied
    // to each model-part volume's mesh bounding box. The values are exactly
    // what setObjectPositions consumes — feeding them back is idempotent.
    //
    // Used to populate `customObjectPositions` after a multi-object load
    // (e.g. Button-for-S-trousers, multi-extruder 3MF) so the per-object
    // renderer dispatch + drag + tap-to-select all work from load without
    // imposing a forced grid layout on intentionally-positioned files.
    std::vector<float> getObjectWorldAABBMins() const;

    // ---- F66: Split + Auto-Orient + per-object pose ----

    // True iff g_model.objects[objIdx] has more than one connected component
    // (cheap probe used by Kotlin to enable/disable the Split-to-Objects button).
    bool isObjectSplittable(int objIdx) const;

    // True iff g_model.objects[objIdx]->volumes[volIdx]->is_splittable() is true.
    bool isVolumeSplittable(int objIdx, int volIdx) const;

    // Split the object at objIdx into its connected components, replacing the
    // original at the same index. On success returns {removedIdx, addedCount}.
    // On a one-island input returns std::nullopt without mutating the model.
    struct SplitResult { int removedIdx; int addedCount; };
    std::optional<SplitResult> splitObject(int objIdx);

    // Split one volume's mesh into multiple volumes within the same object.
    // Returns the new volume count for the object on success, -1 on failure.
    int splitVolume(int objIdx, int volIdx);

    // B132c follow-up (v2.10.4): deep-copy an existing object via
    // Model::add_object(*src). The new object is appended at the end of the
    // model.objects vector. Returns the new object's index on success, or
    // std::nullopt if objIdx is invalid. Used by per-object "Copy" UX so
    // the user can duplicate individual split pieces without re-loading.
    std::optional<int> duplicateObject(int objIdx);

    // Run Slic3r::orientation::orient on one object and apply the result to
    // its instances[0] rotation. Returns the new euler [x, y, z] in radians,
    // or std::nullopt on failure (model not loaded, objIdx OOR, orient bailout).
    std::optional<std::array<double, 3>> autoOrientObject(int objIdx);

    // Iterate every object on the bed and call autoOrientObject. Returns the
    // number of objects successfully oriented.
    int autoOrientAll();

    // Set/get instances[0] rotation (degrees, Euler XYZ) for one object.
    bool setObjectRotation(int objIdx, float rxDeg, float ryDeg, float rzDeg);
    std::array<float, 3> getObjectRotation(int objIdx) const;

    // Set/get instances[0] scaling factor (per-axis) for one object.
    bool setObjectScale(int objIdx, float sx, float sy, float sz);
    std::array<float, 3> getObjectScale(int objIdx) const;

    // Display name. Returns empty string on OOR or no-model.
    std::string getObjectName(int objIdx) const;

    // Per-volume metadata + extruder. Slot is 1-indexed per Orca convention.
    std::string getVolumeName(int objIdx, int volIdx) const;
    int  getVolumeExtruder(int objIdx, int volIdx) const;
    bool setVolumeExtruder(int objIdx, int volIdx, int slot);

private:
    struct Impl;
    Impl* pImpl;
};

// ---- Bambu Snapshot (Phase 0 diff harness) ----
// Walks the global Slic3r::Model after Model::read_from_file and emits
// a BambuFileSnapshot-shaped JSON. Returns "" if g_model has no objects.
std::string bambu_snapshot_json();

// ---- JNI Helpers ----
SliceConfig configFromJava(JNIEnv* env, jobject jconfig);
jobject configToJava(JNIEnv* env, const SliceConfig& config);
jobject modelInfoToJava(JNIEnv* env, const ModelInfo& info);
jobject previewMeshToJava(JNIEnv* env, const PreviewMesh& mesh);
jobject sliceResultToJava(JNIEnv* env, const SliceResult& result);

} // namespace sapil
