#pragma once

// =============================================================================
// SAPIL — Slicer API Layer
// JNI bridge between Android/Kotlin and PrusaSlicer C++ core
// =============================================================================

#include <jni.h>
#include <string>
#include <vector>
#include <functional>
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

    // Skirt/Brim
    int skirt_loops = 1;
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

// ---- Slice Result ----
struct SliceResult {
    bool success = false;
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
    ModelInfo getModelInfo() const;
    void clearModel();

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

private:
    struct Impl;
    Impl* pImpl;
};

// ---- JNI Helpers ----
SliceConfig configFromJava(JNIEnv* env, jobject jconfig);
jobject configToJava(JNIEnv* env, const SliceConfig& config);
jobject modelInfoToJava(JNIEnv* env, const ModelInfo& info);
jobject sliceResultToJava(JNIEnv* env, const SliceResult& result);

} // namespace sapil
