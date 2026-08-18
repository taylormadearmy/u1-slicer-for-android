#include "../include/sapil.h"
#include "sapil_internal.h"
#include "sapil_diagnostics.h"
#include <atomic>
#include <thread>
#include <chrono>
#include <fstream>
#include <sstream>

// Slicer includes
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/GCode.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Config.hpp"
#include "libslic3r/Exception.hpp"
#include "libslic3r/clipper.hpp"

// =============================================================================
// sapil_print.cpp — Slicing using PrusaSlicer's Slic3r::Print
// =============================================================================

// Forward declarations from sapil_model.cpp
namespace sapil {
    extern Slic3r::Model& getGlobalModel();
    extern bool isModelLoaded();
    extern Slic3r::DynamicPrintConfig& getModelConfig();
}

namespace sapil {

// B55: pointer to the in-flight Print object so cancelSlice() can reach it.
// Only non-null while slice() is running. Cleared on all exit paths.
static std::atomic<Slic3r::Print*> g_active_print{nullptr};
// B55: independent cancel flag checked during config setup (before g_active_print is set).
static std::atomic<bool> g_slice_cancel{false};

void SlicerEngine::cancelSlice() {
    g_slice_cancel.store(true, std::memory_order_release);
    auto* print = g_active_print.load(std::memory_order_acquire);
    if (print) {
        print->cancel();
        SAPIL_LOGI("cancelSlice: signalled cancellation to Print + flag");
    } else {
        SAPIL_LOGI("cancelSlice: signalled cancel flag (print not yet started)");
    }
}

static std::string jsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 16);
    for (char c : input) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}

static const char* supportTypeName(Slic3r::SupportType type) {
    switch (type) {
        case Slic3r::stNormalAuto: return "normal(auto)";
        case Slic3r::stTreeAuto: return "tree(auto)";
        case Slic3r::stNormal: return "normal";
        case Slic3r::stTree: return "tree";
        default: return "unknown";
    }
}

static std::string buildNativeModelSnapshot(const Slic3r::Model& model)
{
    std::ostringstream out;
    out << "{";
    out << "\"objectCount\":" << model.objects.size() << ",";
    size_t printable_objects = 0;
    size_t printable_instances = 0;
    out << "\"objects\":[";
    bool first_object = true;
    for (size_t object_index = 0; object_index < model.objects.size(); ++object_index) {
        const auto* obj = model.objects[object_index];
        if (obj == nullptr) continue;
        if (obj->printable) printable_objects++;
        size_t object_printable_instances = 0;
        for (const auto* inst : obj->instances) {
            if (inst != nullptr && inst->printable) {
                ++object_printable_instances;
                ++printable_instances;
            }
        }
        if (!first_object) out << ",";
        first_object = false;
        out << "{"
            << "\"index\":" << object_index << ","
            << "\"printable\":" << (obj->printable ? "true" : "false") << ","
            << "\"volumeCount\":" << obj->volumes.size() << ","
            << "\"instanceCount\":" << obj->instances.size() << ","
            << "\"printableInstanceCount\":" << object_printable_instances;

        auto raw_bb = obj->raw_bounding_box();
        if (raw_bb.defined) {
            out << ",\"rawBounds\":{"
                << "\"xMin\":" << raw_bb.min.x() << ","
                << "\"xMax\":" << raw_bb.max.x() << ","
                << "\"yMin\":" << raw_bb.min.y() << ","
                << "\"yMax\":" << raw_bb.max.y() << ","
                << "\"zMin\":" << raw_bb.min.z() << ","
                << "\"zMax\":" << raw_bb.max.z()
                << "}";
        }

        out << ",\"instances\":[";
        bool first_instance = true;
        for (size_t instance_index = 0; instance_index < obj->instances.size(); ++instance_index) {
            const auto* inst = obj->instances[instance_index];
            if (inst == nullptr) continue;
            if (!first_instance) out << ",";
            first_instance = false;
            const auto offset = inst->get_offset();
            const auto scaling = inst->get_scaling_factor();
            out << "{"
                << "\"index\":" << instance_index << ","
                << "\"printable\":" << (inst->printable ? "true" : "false") << ","
                << "\"offset\":{"
                << "\"x\":" << offset.x() << ","
                << "\"y\":" << offset.y() << ","
                << "\"z\":" << offset.z()
                << "},"
                << "\"scale\":{"
                << "\"x\":" << scaling.x() << ","
                << "\"y\":" << scaling.y() << ","
                << "\"z\":" << scaling.z()
                << "}"
                << "}";
        }
        out << "]";
        out << "}";
    }
    out << "],";
    out << "\"printableObjectCount\":" << printable_objects << ",";
    out << "\"printableInstanceCount\":" << printable_instances;
    out << "}";
    return out.str();
}

SlicerEngine::SlicerEngine() : pImpl(new Impl()) {
    SAPIL_LOGI("SlicerEngine created");

    // Initialize default configs
    pImpl->print_config.apply(Slic3r::FullPrintConfig::defaults());
}

SlicerEngine::~SlicerEngine() {
    delete pImpl;
    SAPIL_LOGI("SlicerEngine destroyed");
}

std::string SlicerEngine::getCoreVersion() const {
    return "Snapmaker Orca 2.2.4 (Android ARM64)";
}

// Map SliceConfig to OrcaSlicer DynamicPrintConfig
// Key names differ from PrusaSlicer 2.8 — use OrcaSlicer names throughout.
// Bambu jobs deliberately have a separate configuration boundary.  Do not move
// settings from applyConfigToPrusa() into this routine: that function is the
// released U1 path, and its hardware defaults are unsafe ownership for a Bambu
// target.  The caller has already copied only safe process/filament keys from
// the imported project before this runs.
static void applyBambuConfigToPrusa(Slic3r::DynamicPrintConfig& dpc, const SliceConfig& config,
                                    bool has_embedded_profile) {
    const std::string& target = config.machine_target;
    double bed_x = 256.0, bed_y = 256.0, bed_z = 250.0;
    std::string model = "Bambu Lab X1 Carbon";
    std::string settings = "Bambu Lab X1 Carbon 0.4 nozzle";
    bool front_left_exclusion = true;
    bool bed_slinger = false;
    bool h2d = false;
    bool p2s = false;
    if (target == "BAMBU_X1E") { model = "Bambu Lab X1E"; settings = "Bambu Lab X1E 0.4 nozzle"; }
    else if (target == "BAMBU_P1S") { model = "Bambu Lab P1S"; settings = "Bambu Lab P1S 0.4 nozzle"; }
    else if (target == "BAMBU_P1P") { model = "Bambu Lab P1P"; settings = "Bambu Lab P1P 0.4 nozzle"; }
    else if (target == "BAMBU_P2S") { model = "Bambu Lab P2S"; settings = "Bambu Lab P2S 0.4 nozzle"; bed_z = 256.0; front_left_exclusion = false; p2s = true; }
    else if (target == "BAMBU_A1") { model = "Bambu Lab A1"; settings = "Bambu Lab A1 0.4 nozzle"; bed_z = 256.0; front_left_exclusion = false; bed_slinger = true; }
    else if (target == "BAMBU_A1_MINI") { model = "Bambu Lab A1 mini"; settings = "Bambu Lab A1 mini 0.4 nozzle"; bed_x = bed_y = bed_z = 180.0; front_left_exclusion = false; bed_slinger = true; }
    else if (target == "BAMBU_H2D") { model = "Bambu Lab H2D"; settings = "Bambu Lab H2D 0.4 nozzle"; bed_x = 350.0; bed_y = 320.0; bed_z = 325.0; front_left_exclusion = false; h2d = true; }

    // Target-owned machine identity, geometry and kinematics.  These always
    // replace a source project so foreign printer geometry/macros cannot leak.
    dpc.set_key_value("printable_area", new Slic3r::ConfigOptionPoints({{0, 0}, {bed_x, 0}, {bed_x, bed_y}, {0, bed_y}}));
    dpc.set_key_value("printable_height", new Slic3r::ConfigOptionFloat(bed_z));
    dpc.set_key_value("printer_model", new Slic3r::ConfigOptionString(model));
    dpc.set_key_value("printer_settings_id", new Slic3r::ConfigOptionString(settings));
    dpc.set_key_value("printer_variant", new Slic3r::ConfigOptionString("0.4"));
    dpc.set_key_value("gcode_flavor", new Slic3r::ConfigOptionEnum<Slic3r::GCodeFlavor>(Slic3r::gcfMarlinLegacy));
    if (front_left_exclusion) {
        dpc.set_key_value("bed_exclude_area", new Slic3r::ConfigOptionPoints({{0, 0}, {18, 0}, {18, 28}, {0, 28}}));
    }

    const int n_ext = std::max(h2d ? 2 : 1, config.extruder_count);

    // Plain geometry has no project_settings.config. Seed it from the Bambu
    // 0.20 mm / PLA baseline instead of falling through to Orca's generic or
    // the app's U1 defaults. Imported projects skip this block and retain the
    // whitelisted source process/filament settings applied by the caller.
    if (!has_embedded_profile) {
        dpc.set_key_value("layer_height", new Slic3r::ConfigOptionFloat(0.20));
        dpc.set_key_value("initial_layer_print_height", new Slic3r::ConfigOptionFloat(0.20));
        dpc.set_key_value("elefant_foot_compensation", new Slic3r::ConfigOptionFloat(0.15));
        dpc.set_key_value("wall_loops", new Slic3r::ConfigOptionInt(2));
        dpc.set_key_value("sparse_infill_density", new Slic3r::ConfigOptionPercent(15.0));
        dpc.set_key_value("sparse_infill_pattern",
            new Slic3r::ConfigOptionEnum<Slic3r::InfillPattern>(Slic3r::ipGrid));
        dpc.set_key_value("curr_bed_type",
            new Slic3r::ConfigOptionEnum<Slic3r::BedType>(Slic3r::btPTE));
        dpc.set_key_value("nozzle_temperature", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 220)));
        dpc.set_key_value("nozzle_temperature_initial_layer", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 220)));
        dpc.set_key_value("hot_plate_temp", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 55)));
        dpc.set_key_value("hot_plate_temp_initial_layer", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 55)));
        dpc.set_key_value("textured_plate_temp", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 55)));
        dpc.set_key_value("textured_plate_temp_initial_layer", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 55)));
        dpc.set_key_value("filament_flow_ratio", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 0.98)));
        dpc.set_key_value("fan_min_speed", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 100.0)));
        dpc.set_key_value("fan_max_speed", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 100.0)));
        dpc.set_key_value("reduce_fan_stop_start_freq", new Slic3r::ConfigOptionBools(std::vector<unsigned char>(n_ext, 1)));
        dpc.set_key_value("fan_cooling_layer_time", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 100.0)));
        dpc.set_key_value("close_fan_the_first_x_layers", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 1)));
        dpc.set_key_value("full_fan_speed_layer", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 0)));
    }

    dpc.set_key_value("nozzle_diameter", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, config.nozzle_diameter)));
    dpc.set_key_value("filament_diameter", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, config.filament_diameter)));
    dpc.set_key_value("extruder_offset", new Slic3r::ConfigOptionPoints(std::vector<Slic3r::Vec2d>(n_ext, Slic3r::Vec2d(0, 0))));
    dpc.set_key_value("single_extruder_multi_material", new Slic3r::ConfigOptionBool(!h2d));
    // H2D's two physical nozzles must remain addressable even for a one-colour
    // plate.  Make the per-filament safety arrays valid before Print applies it.
    if (n_ext > 1) {
        auto* colours = dpc.option<Slic3r::ConfigOptionStrings>("filament_colour");
        if (!colours) {
            dpc.set_key_value("filament_colour", new Slic3r::ConfigOptionStrings(std::vector<std::string>(n_ext, "#FFFFFF")));
        } else if ((int)colours->values.size() < n_ext) {
            auto values = colours->values;
            values.resize(n_ext, values.empty() ? "#FFFFFF" : values.back());
            dpc.set_key_value("filament_colour", new Slic3r::ConfigOptionStrings(std::move(values)));
        }
        auto* matrix = dpc.option<Slic3r::ConfigOptionFloats>("flush_volumes_matrix");
        if (!matrix) {
            dpc.set_key_value("flush_volumes_matrix", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext * n_ext, 140.0)));
        } else if ((int)matrix->values.size() < n_ext * n_ext) {
            auto values = matrix->values;
            values.resize(n_ext * n_ext, values.empty() ? 140.0 : values.back());
            dpc.set_key_value("flush_volumes_matrix", new Slic3r::ConfigOptionFloats(std::move(values)));
        }
    }
    dpc.set_key_value("machine_max_speed_x", new Slic3r::ConfigOptionFloats({500.0, 500.0}));
    dpc.set_key_value("machine_max_speed_y", new Slic3r::ConfigOptionFloats({500.0, 500.0}));
    dpc.set_key_value("machine_max_speed_z", new Slic3r::ConfigOptionFloats(
        bed_slinger ? std::vector<double>{30.0, 30.0} : p2s ? std::vector<double>{20.0, 20.0, 20.0} : std::vector<double>{20.0, 20.0}));
    dpc.set_key_value("machine_max_speed_e", new Slic3r::ConfigOptionFloats(std::vector<double>{30.0, 30.0}));
    dpc.set_key_value("machine_max_acceleration_x", new Slic3r::ConfigOptionFloats({20000.0, 20000.0}));
    dpc.set_key_value("machine_max_acceleration_y", new Slic3r::ConfigOptionFloats({20000.0, 20000.0}));
    dpc.set_key_value("machine_max_acceleration_z", new Slic3r::ConfigOptionFloats(
        bed_slinger ? std::vector<double>{1500.0, 1500.0} : p2s ? std::vector<double>{500.0, 500.0, 500.0} : std::vector<double>{500.0, 500.0}));
    dpc.set_key_value("machine_max_acceleration_e", new Slic3r::ConfigOptionFloats({5000.0, 5000.0}));
    dpc.set_key_value("machine_max_acceleration_extruding", new Slic3r::ConfigOptionFloats({20000.0, 20000.0}));
    dpc.set_key_value("machine_max_acceleration_retracting", new Slic3r::ConfigOptionFloats({5000.0, 5000.0}));
    dpc.set_key_value("machine_max_acceleration_travel", new Slic3r::ConfigOptionFloats({9000.0, 9000.0}));

    // Bambu templates only arrive through the target resolver in SliceConfig;
    // source project macros are never copied into dpc by the Bambu whitelist.
    if (!config.machine_start_gcode.empty()) dpc.set_key_value("machine_start_gcode", new Slic3r::ConfigOptionString(config.machine_start_gcode));
    if (!config.machine_end_gcode.empty()) dpc.set_key_value("machine_end_gcode", new Slic3r::ConfigOptionString(config.machine_end_gcode));
    if (!config.machine_change_filament_gcode.empty()) dpc.set_key_value("change_filament_gcode", new Slic3r::ConfigOptionString(config.machine_change_filament_gcode));

    // Empty JNI vectors mean no explicit override; imported Bambu filament
    // values survive.  Non-empty vectors are the existing explicit user path.
    auto apply_floats = [&](const char* key, const std::vector<float>& values) {
        if (values.empty()) return;
        std::vector<double> v(values.begin(), values.end());
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value(key, new Slic3r::ConfigOptionFloats(std::move(v)));
    };
    auto apply_ints = [&](const char* key, const std::vector<int>& values) {
        if (values.empty()) return;
        std::vector<int> v(values);
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value(key, new Slic3r::ConfigOptionInts(std::move(v)));
    };
    auto apply_ints_as_floats = [&](const char* key, const std::vector<int>& values) {
        if (values.empty()) return;
        std::vector<double> v(values.begin(), values.end());
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value(key, new Slic3r::ConfigOptionFloats(std::move(v)));
    };
    auto apply_bools = [&](const char* key, const std::vector<int>& values) {
        if (values.empty()) return;
        std::vector<unsigned char> v;
        v.reserve(std::max(n_ext, (int)values.size()));
        for (int value : values) v.push_back(value != 0 ? 1 : 0);
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value(key, new Slic3r::ConfigOptionBools(std::move(v)));
    };
    apply_floats("filament_flow_ratio", config.filament_flow_ratios);
    apply_floats("filament_max_volumetric_speed", config.filament_max_volumetric_speeds);
    apply_ints_as_floats("fan_min_speed", config.filament_fan_min_speeds);
    apply_ints_as_floats("fan_max_speed", config.filament_fan_max_speeds);
    apply_ints("overhang_fan_speed", config.filament_overhang_fan_speeds);
    apply_ints("additional_cooling_fan_speed", config.filament_additional_cooling_fan_speeds);
    apply_floats("slow_down_layer_time", config.filament_slow_down_layer_times);
    apply_floats("slow_down_min_speed", config.filament_slow_down_min_speeds);
    apply_ints("close_fan_the_first_x_layers", config.filament_close_fan_first_layers);
    apply_ints("full_fan_speed_layer", config.filament_full_fan_speed_layers);
    apply_bools("enable_pressure_advance", config.filament_enable_pressure_advance);
    apply_floats("pressure_advance", config.filament_pressure_advances);
    apply_floats("filament_minimal_purge_on_wipe_tower", config.filament_minimal_purge_on_wipe_tower);
    apply_ints("nozzle_temperature_initial_layer", config.filament_nozzle_temp_initial_layers);
    apply_ints("hot_plate_temp_initial_layer", config.filament_bed_temp_initial_layers);
    apply_floats("filament_cost", config.filament_costs);

    const std::string explicit_keys = "|" + config.bambu_explicit_overrides + "|";
    auto explicitly_overrides = [&](const char* key) {
        return explicit_keys.find("|" + std::string(key) + "|") != std::string::npos;
    };
    Slic3r::ConfigSubstitutionContext bambu_substitutions{
        Slic3r::ForwardCompatibilitySubstitutionRule::Disable};
    if (explicitly_overrides("layer_height") && config.layer_height > 0.0f)
        dpc.set_key_value("layer_height", new Slic3r::ConfigOptionFloat(config.layer_height));
    if (explicitly_overrides("wall_loops"))
        dpc.set_key_value("wall_loops", new Slic3r::ConfigOptionInt(config.perimeters));
    if (explicitly_overrides("top_shell_layers"))
        dpc.set_key_value("top_shell_layers", new Slic3r::ConfigOptionInt(config.top_solid_layers));
    if (explicitly_overrides("bottom_shell_layers"))
        dpc.set_key_value("bottom_shell_layers", new Slic3r::ConfigOptionInt(config.bottom_solid_layers));
    if (explicitly_overrides("sparse_infill_density"))
        dpc.set_key_value("sparse_infill_density", new Slic3r::ConfigOptionPercent(config.fill_density * 100.0));
    if (explicitly_overrides("sparse_infill_pattern"))
        dpc.set_deserialize("sparse_infill_pattern", config.fill_pattern, bambu_substitutions);
    if (explicitly_overrides("enable_support"))
        dpc.set_key_value("enable_support", new Slic3r::ConfigOptionBool(config.support_enabled));
    if (explicitly_overrides("support_type"))
        dpc.set_deserialize("support_type", config.support_type, bambu_substitutions);
    if (explicitly_overrides("support_threshold_angle"))
        dpc.set_key_value("support_threshold_angle", new Slic3r::ConfigOptionFloat(config.support_angle));
    if (explicitly_overrides("support_filament"))
        dpc.set_key_value("support_filament", new Slic3r::ConfigOptionInt(config.support_filament));
    if (explicitly_overrides("support_interface_filament"))
        dpc.set_key_value("support_interface_filament", new Slic3r::ConfigOptionInt(config.support_interface_filament));
    if (explicitly_overrides("brim_width"))
        dpc.set_key_value("brim_width", new Slic3r::ConfigOptionFloat(config.brim_width));
    if (explicitly_overrides("skirt_loops"))
        dpc.set_key_value("skirt_loops", new Slic3r::ConfigOptionInt(config.skirt_loops));
    if (explicitly_overrides("hot_plate_temp") || explicitly_overrides("textured_plate_temp")) {
        dpc.set_key_value("hot_plate_temp", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, config.bed_temp)));
        dpc.set_key_value("hot_plate_temp_initial_layer", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, config.bed_temp)));
        dpc.set_key_value("textured_plate_temp", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, config.bed_temp)));
        dpc.set_key_value("textured_plate_temp_initial_layer", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, config.bed_temp)));
    }
    if (explicitly_overrides("nozzle_temperature") && !config.extruder_temps.empty()) {
        std::vector<int> values = config.extruder_temps;
        while ((int)values.size() < n_ext) values.push_back(values.back());
        dpc.set_key_value("nozzle_temperature", new Slic3r::ConfigOptionInts(values));
        dpc.set_key_value("nozzle_temperature_initial_layer", new Slic3r::ConfigOptionInts(std::move(values)));
    }

    // The target may supply a corrected tower location through SliceConfig.
    // Do not overwrite imported enable/shape settings unless the user enabled it.
    if (explicitly_overrides("enable_prime_tower")) {
        dpc.set_key_value("enable_prime_tower", new Slic3r::ConfigOptionBool(config.wipe_tower_enabled));
    }
    if (config.wipe_tower_enabled) {
        dpc.set_key_value("wipe_tower_x", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, config.wipe_tower_x)));
        dpc.set_key_value("wipe_tower_y", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, config.wipe_tower_y)));
        dpc.set_key_value("prime_tower_width", new Slic3r::ConfigOptionFloat(config.wipe_tower_width));
    }
}

static void applyConfigToPrusa(Slic3r::DynamicPrintConfig& dpc, const SliceConfig& config, bool has_embedded_profile = false) {
    const bool is_bambu_target = config.machine_target.rfind("BAMBU_", 0) == 0;
    if (is_bambu_target) {
        applyBambuConfigToPrusa(dpc, config, has_embedded_profile);
        return;
    }
    // Layer settings (OrcaSlicer keys)
    // layer_height: 0.0f sentinel means "let profile_keys[] value (or OrcaSlicer default) stand".
    // USE_FILE and ORCA_DEFAULT modes pass 0.0f from Kotlin; OVERRIDE passes the explicit value.
    if (config.layer_height > 0.0f) {
        dpc.set_key_value("layer_height", new Slic3r::ConfigOptionFloat(config.layer_height));
    }
    // initial_layer_print_height: profile_keys[] applies the embedded profile's value for
    // Snapmaker profiles (sourced from either the file's config or our process profile).
    // Only fall back to the JNI value for non-Snapmaker files (plain STL without profile).
    // has_embedded_profile is true when called with is_snapmaker_profile=true (see call site).
    if (!has_embedded_profile) {
        dpc.set_key_value("initial_layer_print_height", new Slic3r::ConfigOptionFloat(config.first_layer_height));
    }
    dpc.set_key_value("wall_loops", new Slic3r::ConfigOptionInt(config.perimeters));
    dpc.set_key_value("top_shell_layers", new Slic3r::ConfigOptionInt(config.top_solid_layers));
    dpc.set_key_value("bottom_shell_layers", new Slic3r::ConfigOptionInt(config.bottom_solid_layers));

    // Infill (OrcaSlicer keys)
    Slic3r::InfillPattern pattern = Slic3r::InfillPattern::ipGyroid;
    if (config.fill_pattern == "grid") pattern = Slic3r::InfillPattern::ipGrid;
    else if (config.fill_pattern == "honeycomb") pattern = Slic3r::InfillPattern::ipHoneycomb;
    else if (config.fill_pattern == "line") pattern = Slic3r::InfillPattern::ipLine;
    else if (config.fill_pattern == "rectilinear") pattern = Slic3r::InfillPattern::ipRectilinear;

    // F87/F91 (2026-05-25): gate fallbacks on !has_embedded_profile so an imported
    // OrcaSlicer process profile / user-tuned library filament can win. When the Kotlin
    // ProfileEmbedder has authored the embed (is_snapmaker_profile=true) the embed's
    // values came from the layered: printer profile + bundled process + library +
    // SlicingOverrides, so trust it. Raw STL (no embed) still gets these SliceConfig-
    // derived defaults.
    if (!has_embedded_profile) {
        dpc.set_key_value("sparse_infill_pattern", new Slic3r::ConfigOptionEnum<Slic3r::InfillPattern>(pattern));
        dpc.set_key_value("sparse_infill_density", new Slic3r::ConfigOptionPercent(config.fill_density * 100.0));
    }

    // Speed (OrcaSlicer keys).
    // outer_wall_speed is the primary speed control — set from the user's "print speed" setting.
    // All other speeds use profile values (applied from embedded config above) rather than
    // deriving them all from print_speed.  This gives the profile's per-feature tuning
    // (inner walls faster, infill faster, bridge slower) while still honouring the user's
    // overall speed preference via outer_wall_speed.
    // Fallback values here match standard_0.20mm.json and are used when no profile is embedded.
    dpc.set_key_value("outer_wall_speed",          new Slic3r::ConfigOptionFloat(config.print_speed));
    dpc.set_key_value("inner_wall_speed",          new Slic3r::ConfigOptionFloat(config.print_speed * 1.5));
    dpc.set_key_value("sparse_infill_speed",       new Slic3r::ConfigOptionFloat(config.print_speed * 1.35));
    dpc.set_key_value("internal_solid_infill_speed", new Slic3r::ConfigOptionFloat(config.print_speed * 1.25));
    dpc.set_key_value("top_surface_speed",         new Slic3r::ConfigOptionFloat(config.print_speed));
    dpc.set_key_value("bridge_speed",              new Slic3r::ConfigOptionFloat(50.0));
    dpc.set_key_value("gap_infill_speed",          new Slic3r::ConfigOptionFloat(config.print_speed * 1.25));
    dpc.set_key_value("travel_speed",              new Slic3r::ConfigOptionFloat(config.travel_speed));
    dpc.set_key_value("initial_layer_speed",       new Slic3r::ConfigOptionFloat(config.first_layer_speed));
    dpc.set_key_value("initial_layer_infill_speed",new Slic3r::ConfigOptionFloat(config.first_layer_speed * 2.1));

    // Snapmaker U1 bed geometry — OrcaSlicer defaults to 200×200mm (Bambu A1).
    // Must always be set so the slicer knows the actual printable area.
    dpc.set_key_value("printable_area", new Slic3r::ConfigOptionPoints({
        {0, 0}, {270, 0}, {270, 270}, {0, 270}
    }));

    // Bed plate type — OrcaSlicer defaults to btPC (Engineering Plate) whose temp keys
    // are cool_plate_temp (35°C for PLA).  The Snapmaker U1 uses a textured PEI plate,
    // so set btPEI so OrcaSlicer resolves bed temp from hot_plate_temp (60°C for PLA).
    dpc.set_key_value("curr_bed_type", new Slic3r::ConfigOptionEnum<Slic3r::BedType>(Slic3r::btPEI));

    // Snapmaker U1 machine kinematic limits — fallback values used when no Snapmaker
    // profile is embedded (e.g. plain STL files).  For 3MF files these are overridden
    // by the values from the embedded profile in the is_snapmaker_profile block above.
    // Values match snapmaker_u1.json: {normal, silent} mode pairs.
    dpc.set_key_value("machine_max_speed_x",          new Slic3r::ConfigOptionFloats({500.0, 200.0}));
    dpc.set_key_value("machine_max_speed_y",          new Slic3r::ConfigOptionFloats({500.0, 200.0}));
    dpc.set_key_value("machine_max_speed_z",          new Slic3r::ConfigOptionFloats({20.0, 12.0}));
    dpc.set_key_value("machine_max_speed_e",          new Slic3r::ConfigOptionFloats({30.0, 25.0}));
    dpc.set_key_value("machine_max_acceleration_x",   new Slic3r::ConfigOptionFloats({20000.0, 20000.0}));
    dpc.set_key_value("machine_max_acceleration_y",   new Slic3r::ConfigOptionFloats({20000.0, 20000.0}));
    dpc.set_key_value("machine_max_acceleration_z",   new Slic3r::ConfigOptionFloats({500.0, 200.0}));
    dpc.set_key_value("machine_max_acceleration_e",   new Slic3r::ConfigOptionFloats({5000.0, 5000.0}));
    dpc.set_key_value("machine_max_jerk_x",           new Slic3r::ConfigOptionFloats({9.0, 9.0}));
    dpc.set_key_value("machine_max_jerk_y",           new Slic3r::ConfigOptionFloats({9.0, 9.0}));
    dpc.set_key_value("machine_max_jerk_z",           new Slic3r::ConfigOptionFloats({3.0, 0.4}));
    dpc.set_key_value("machine_max_jerk_e",           new Slic3r::ConfigOptionFloats({2.5, 2.5}));
    // M204 acceleration values — must match U1 profile for correct G-code and time estimation.
    // OrcaSlicer defaults (1500/1250 mm/s²) are 13× too low and inflate print time estimates.
    dpc.set_key_value("machine_max_acceleration_extruding",  new Slic3r::ConfigOptionFloats({20000.0, 20000.0}));
    dpc.set_key_value("machine_max_acceleration_retracting", new Slic3r::ConfigOptionFloats({5000.0, 5000.0}));
    dpc.set_key_value("machine_max_acceleration_travel",     new Slic3r::ConfigOptionFloats({20000.0, 20000.0}));
    // Per-feature accelerations from the standard process profile.
    // Without these OrcaSlicer uses FullPrintConfig::defaults() which are very low,
    // making every short move appear to be in the acceleration ramp → 5× time inflation.
    dpc.set_key_value("default_acceleration",          new Slic3r::ConfigOptionFloat(10000.0));
    dpc.set_key_value("outer_wall_acceleration",       new Slic3r::ConfigOptionFloat(5000.0));
    dpc.set_key_value("inner_wall_acceleration",       new Slic3r::ConfigOptionFloat(0.0));
    dpc.set_key_value("top_surface_acceleration",      new Slic3r::ConfigOptionFloat(2000.0));
    dpc.set_key_value("travel_acceleration",           new Slic3r::ConfigOptionFloat(10000.0));
    dpc.set_key_value("initial_layer_acceleration",    new Slic3r::ConfigOptionFloat(500.0));

    // Multi-extruder setup
    int n_ext = std::max(1, config.extruder_count);
    SAPIL_LOGI("Configuring %d extruder(s)", n_ext);

    // Build per-extruder arrays
    std::vector<double> nozzle_diameters(n_ext, config.nozzle_diameter);
    std::vector<double> filament_diameters(n_ext, config.filament_diameter);
    std::vector<int> temps(n_ext, config.nozzle_temp);
    std::vector<int> first_temps(n_ext, config.nozzle_temp);
    std::vector<int> bed_temps(n_ext, config.bed_temp);
    std::vector<double> retract_len(n_ext, config.retract_length);
    std::vector<double> retract_spd(n_ext, config.retract_speed);

    // Override with per-extruder values if provided.
    // When the array covers all extruders, use ALL values (including 0 for unused slots)
    // so OrcaSlicer correctly marks only the active extruders as "used".
    // When the array is shorter, only override slots that have non-zero values.
    bool temps_full = (int)config.extruder_temps.size() >= n_ext;
    bool retract_len_full = (int)config.extruder_retract_length.size() >= n_ext;
    bool retract_spd_full = (int)config.extruder_retract_speed.size() >= n_ext;
    for (int i = 0; i < n_ext; i++) {
        if (i < (int)config.extruder_temps.size()) {
            if (temps_full || config.extruder_temps[i] > 0) {
                temps[i] = config.extruder_temps[i];
                first_temps[i] = config.extruder_temps[i];
            }
        }
        if (i < (int)config.extruder_retract_length.size()) {
            if (retract_len_full || config.extruder_retract_length[i] > 0)
                retract_len[i] = config.extruder_retract_length[i];
        }
        if (i < (int)config.extruder_retract_speed.size()) {
            if (retract_spd_full || config.extruder_retract_speed[i] > 0)
                retract_spd[i] = config.extruder_retract_speed[i];
        }
    }

    // Phase 2 (2026-04-28) — `nozzle_temperature` gate ONLY. The
    // canonical-aware embed pipeline writes this array with per-file-
    // filament material overrides applied. Skipping the write here lets
    // those values survive (the embed reached `dpc` via profile_keys[]
    // above). Other per-filament keys (bed temp, retraction, fan,
    // volumetric speed, slow_down, flush) are NOT gated — they stay
    // clamped to U1 hardware defaults regardless of embed because the
    // gcode differential vs v1.6.13 (see
    // `docs/superpowers/exploration/2026-04-28-gcode-baseline-diff.md`)
    // showed that respecting embed values for them causes print
    // regressions on Bambu/Prusa-prepared files (cool-plate 45°C bed
    // temps, conservative fan curves, 3× higher Prusa-calibrated purge
    // volumes). The user's only override surface today is material type,
    // which only drives nozzle temp.
    if (!has_embedded_profile) {
        dpc.set_key_value("nozzle_temperature", new Slic3r::ConfigOptionInts(temps));
        dpc.set_key_value("nozzle_temperature_initial_layer", new Slic3r::ConfigOptionInts(first_temps));
    }
    // Bed temperature — OrcaSlicer resolves bed temp from the active plate type (curr_bed_type).
    // We set btPEI (textured PEI plate), so hot_plate_temp is the one that matters.
    // B107: use the user's explicit bedTemp for BOTH layers. The previous +5 first-layer
    // bump caused the printer to run at bedTemp+5 even when the user set a specific value.
    // For embedded-profile 3MF files, profile_keys[] overrides these with the profile's own
    // hot_plate_temp_initial_layer, which may include its own offset. For STL files the user's
    // setting must be respected exactly.
    // Always written: U1 hardware default takes precedence over embed.
    dpc.set_key_value("hot_plate_temp", new Slic3r::ConfigOptionInts(bed_temps));
    dpc.set_key_value("hot_plate_temp_initial_layer", new Slic3r::ConfigOptionInts(bed_temps));

    // Retraction (OrcaSlicer keys) — hardware-tuned, always written. The U1
    // direct-drive extruder requires ≤5mm retraction; Bambu Studio profiles
    // ship 10mm bowden defaults that cause heat-creep clogs.
    dpc.set_key_value("retraction_length", new Slic3r::ConfigOptionFloats(retract_len));
    dpc.set_key_value("retraction_speed", new Slic3r::ConfigOptionFloats(retract_spd));
    // Toolchange retraction — OrcaSlicer defaults to 10mm (bowden).  For the Snapmaker U1's
    // direct-drive extruders this pulls filament past the heat break, causing heat-creep clogs
    // during the standby period between tool changes.  Use the same length as normal retraction.
    dpc.set_key_value("retract_length_toolchange", new Slic3r::ConfigOptionFloats(retract_len));

    // Multi-extruder machine type — the Snapmaker U1 has 4 independent extruders, NOT a
    // single-extruder multi-material (SEMM) setup like Bambu/Prusa MMU.  Embedded Snapmaker
    // profiles incorrectly set single_extruder_multi_material=1, which disables ooze_prevention
    // and causes bowden-style unload/reload sequences.  Always override to false.
    dpc.set_key_value("single_extruder_multi_material", new Slic3r::ConfigOptionBool(false));

    // Wipe tower filament handling — OrcaSlicer defaults are for bowden SEMM printers
    // (cooling_tube_retraction=91.5, cooling_tube_length=5, parking_pos=92, ramming=on).
    // Snapmaker fdm_common.json sets all of these to 0 and disables ramming, because the U1
    // tool changer physically parks/unparks extruders — filament stays loaded in each hotend.
    dpc.set_key_value("cooling_tube_retraction", new Slic3r::ConfigOptionFloat(0.0));
    dpc.set_key_value("cooling_tube_length", new Slic3r::ConfigOptionFloat(0.0));
    dpc.set_key_value("parking_pos_retraction", new Slic3r::ConfigOptionFloat(0.0));
    dpc.set_key_value("extra_loading_move", new Slic3r::ConfigOptionFloat(-2.0));
    dpc.set_key_value("enable_filament_ramming", new Slic3r::ConfigOptionBool(false));

    // Filament loading/unloading speeds — Snapmaker profile uses conservative 25mm/s (not
    // OrcaSlicer's 90/100mm/s bowden defaults).  These only matter if ramming were re-enabled,
    // but set them correctly as a safety net.
    dpc.set_key_value("filament_unloading_speed", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 25.0)));
    dpc.set_key_value("filament_unloading_speed_start", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 3.0)));
    dpc.set_key_value("filament_loading_speed", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 25.0)));
    dpc.set_key_value("filament_loading_speed_start", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 3.0)));
    dpc.set_key_value("filament_cooling_moves", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 0)));
    dpc.set_key_value("filament_toolchange_delay", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 0.0)));

    // Nozzle/filament diameter (per-extruder — same key names)
    dpc.set_key_value("nozzle_diameter", new Slic3r::ConfigOptionFloats(nozzle_diameters));
    dpc.set_key_value("filament_diameter", new Slic3r::ConfigOptionFloats(filament_diameters));

    // Filament colour — MUST be sized to at least n_ext.
    // multi_material_segmentation_by_painting() uses filament_colour.size()+1 as
    // num_facets_states to dimension per-layer arrays.  Default is a single entry,
    // so with 2+ extruders the segmentation function creates arrays too small for
    // the paint data's color indices, causing OOB → SIGSEGV.
    // B48: SEMM models embed filament_colour sized to the virtual extruder count
    // (model color count > physical extruder count).  Preserve the larger array
    // when it was already set by the embedded profile.
    {
        auto* fc = dpc.option<Slic3r::ConfigOptionStrings>("filament_colour");
        int current_fc_size = (fc ? (int)fc->values.size() : 0);
        if (current_fc_size < n_ext) {
            dpc.set_key_value("filament_colour", new Slic3r::ConfigOptionStrings(std::vector<std::string>(n_ext, "#FFFFFF")));
        }
    }

    // Flush volumes matrix — NxN where N = effective extruder count.
    // ToolOrdering::reorder_extruders_for_minimum_flush_volume() calls
    // get_extruders_order() which indexes into this matrix.  The
    // FullPrintConfig default is a 1-element vector; with n_ext > 1 that
    // causes a SIGSEGV.  Initialise to a flat 140mm³ default for every
    // combination so multi-extruder slices are safe even when the embedded
    // profile (accessed via profile_keys[]) doesn't provide the matrix
    // (i.e. non-Snapmaker-profile 3MFs such as PaintedMeshWriter output).
    // The B48 path and profile_keys[] may later overwrite this with
    // calibrated values; this floor guarantees a valid matrix is always
    // present before Print::process() starts.
    if (n_ext > 1) {
        auto* fvm = dpc.option<Slic3r::ConfigOptionFloats>("flush_volumes_matrix");
        if (!fvm || (int)fvm->values.size() < n_ext * n_ext) {
            dpc.set_key_value("flush_volumes_matrix",
                new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext * n_ext, 140.0)));
        }
        auto* fvv = dpc.option<Slic3r::ConfigOptionFloats>("flush_volumes_vector");
        if (!fvv || (int)fvv->values.size() < n_ext * 2) {
            dpc.set_key_value("flush_volumes_vector",
                new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext * 2, 140.0)));
        }
    }

    // Extruder offsets — all (0,0) for Snapmaker U1 (firmware handles offsets).
    // MUST be sized to match extruder count; the default is a single entry, and
    // WipeTowerIntegration copies the raw vector then indexes by tool ID — OOB
    // access produces corrupt wipe tower coordinates (2^116 / -inf X values).
    dpc.set_key_value("extruder_offset", new Slic3r::ConfigOptionPoints(std::vector<Slic3r::Vec2d>(n_ext, Slic3r::Vec2d(0, 0))));

    // F91 (2026-05-25): three-way precedence for per-filament tuning keys —
    //   1. SliceConfig.filament_* (user filament-library values)  ← always wins when set
    //   2. embed value via profile_keys[]                          ← preserved when set 1 absent
    //   3. U1 hardware default                                     ← floor for raw STL
    // The first branch reaches STL slices too (which skip ProfileEmbedder); the second
    // protects Snapmaker-authored 3MFs that carry tuned cooling/flow in their embedded
    // profile (e.g. pla.json defaults via ProfileEmbedder's standard path).

    auto applyPerExtFloats = [&](const char* key,
                                  const std::vector<float>& user_override,
                                  double fallback) {
        if (!user_override.empty()) {
            std::vector<double> v(user_override.begin(), user_override.end());
            while ((int)v.size() < n_ext) v.push_back(v.empty() ? fallback : v.back());
            dpc.set_key_value(key, new Slic3r::ConfigOptionFloats(std::move(v)));
        } else if (!has_embedded_profile) {
            dpc.set_key_value(key, new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, fallback)));
        }
    };
    auto applyPerExtInts = [&](const char* key,
                                const std::vector<int>& user_override,
                                int fallback) {
        if (!user_override.empty()) {
            std::vector<int> v = user_override;
            while ((int)v.size() < n_ext) v.push_back(v.empty() ? fallback : v.back());
            dpc.set_key_value(key, new Slic3r::ConfigOptionInts(std::move(v)));
        } else if (!has_embedded_profile) {
            dpc.set_key_value(key, new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, fallback)));
        }
    };

    applyPerExtFloats("filament_max_volumetric_speed", config.filament_max_volumetric_speeds, 21.0);
    // Filament density: not F91-user-controllable yet; always pin to PLA default for raw STL.
    dpc.set_key_value("filament_density", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 1.24)));
    // Fan / cooling — OrcaSlicer defaults fan_min_speed to 20%, U1 PLA needs 100%.
    applyPerExtFloats("fan_min_speed", config.filament_fan_min_speeds.empty() ?
                                          std::vector<float>{} :
                                          std::vector<float>(config.filament_fan_min_speeds.begin(),
                                                             config.filament_fan_min_speeds.end()), 100.0);
    applyPerExtFloats("fan_max_speed", config.filament_fan_max_speeds.empty() ?
                                          std::vector<float>{} :
                                          std::vector<float>(config.filament_fan_max_speeds.begin(),
                                                             config.filament_fan_max_speeds.end()), 100.0);
    applyPerExtInts  ("overhang_fan_speed",      config.filament_overhang_fan_speeds, 100);
    applyPerExtFloats("slow_down_layer_time",    config.filament_slow_down_layer_times, 4.0);
    applyPerExtFloats("slow_down_min_speed",     config.filament_slow_down_min_speeds, 20.0);

    // G-code dialect — NOT set as a fallback here because gcode_flavor=klipper suppresses
    // M104/M109 temp commands from the slicer body, relying on the start G-code template.
    // For raw STL files (no embedded profile), the Marlin default is correct.
    // When the Snapmaker profile IS embedded, gcode_flavor=klipper is applied from the
    // profile_keys[] whitelist in the is_snapmaker_profile block above.

    // Deretraction speed — OrcaSlicer defaults to 0 (= same as retraction speed).
    // Snapmaker profile uses 35 mm/s for smoother prime-after-retract.
    // Hardware-tuned (direct-drive vs bowden); always write the JNI value,
    // never let an embed override it.
    dpc.set_key_value("deretraction_speed", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 35.0)));
    dpc.set_key_value("retraction_minimum_travel", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 1.0)));

    // Line widths — must be set explicitly.
    // When left at 0 (absolute), MultiMaterialSegmentation calls
    // config.get_abs_value("outer_wall_line_width", nozzle_diameter) which returns
    // the literal 0, not the auto-resolved value, causing Flow::rounded_rectangle_extrusion_spacing()
    // to throw FlowErrorNegativeSpacing on multi-color models.
    double nd = config.nozzle_diameter;
    dpc.set_key_value("line_width",              new Slic3r::ConfigOptionFloatOrPercent(nd * 1.125, false));
    dpc.set_key_value("outer_wall_line_width",   new Slic3r::ConfigOptionFloatOrPercent(nd * 1.05,  false));
    dpc.set_key_value("inner_wall_line_width",   new Slic3r::ConfigOptionFloatOrPercent(nd * 1.125, false));
    dpc.set_key_value("top_surface_line_width",  new Slic3r::ConfigOptionFloatOrPercent(nd * 1.05,  false));
    dpc.set_key_value("sparse_infill_line_width",new Slic3r::ConfigOptionFloatOrPercent(nd * 1.125, false));
    dpc.set_key_value("initial_layer_line_width",new Slic3r::ConfigOptionFloatOrPercent(nd * 1.25,  false));

    // Wall generator: keep clamped to Arachne for everyone.
    // OrcaSlicer 2.2.4's classic perimeter generator crashes (SIGSEGV in
    // PerimeterGenerator::process_classic / MultiPoint dtor) on the U1 process config
    // — surfaced 2026-05-25 when F87 first un-gated this key. Pre-existing OrcaSlicer
    // issue; until it's fixed upstream we must not let the user reach the classic path.
    dpc.set_key_value("wall_generator", new Slic3r::ConfigOptionEnum<Slic3r::PerimeterGeneratorType>(Slic3r::PerimeterGeneratorType::Arachne));

    // F87 (2026-05-25): gate seam_position + reduce_infill_retraction on
    // !has_embedded_profile so an imported process profile or user SlicingOverride
    // can win. `buildProfileOverrides` already writes the resolved values into the
    // embed map for Snapmaker-authored embeds.
    if (!has_embedded_profile) {
        // Seam position - aligned is the default; set explicitly for STL files.
        dpc.set_key_value("seam_position", new Slic3r::ConfigOptionEnum<Slic3r::SeamPosition>(Slic3r::spAligned));

        // Reduce infill retraction - off by default for Snapmaker U1.
        dpc.set_key_value("reduce_infill_retraction", new Slic3r::ConfigOptionBool(false));
    }

    // F91 (2026-05-25) — SliceConfig user overrides for the per-filament tuning keys
    // that applyConfigToPrusa doesn't otherwise touch. When the user sets a value via the
    // Filament Library, populate dpc here so the slicer picks it up. When unset, the
    // embedded profile (via profile_keys[]) or OrcaSlicer's compiled default stands.
    if (!config.filament_flow_ratios.empty()) {
        std::vector<double> v(config.filament_flow_ratios.begin(), config.filament_flow_ratios.end());
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value("filament_flow_ratio", new Slic3r::ConfigOptionFloats(std::move(v)));
    }
    if (!config.filament_pressure_advances.empty()) {
        std::vector<double> v(config.filament_pressure_advances.begin(), config.filament_pressure_advances.end());
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value("pressure_advance", new Slic3r::ConfigOptionFloats(std::move(v)));
    }
    if (!config.filament_enable_pressure_advance.empty()) {
        std::vector<unsigned char> v;
        v.reserve(n_ext);
        for (size_t i = 0; i < (size_t)n_ext; ++i) {
            int src = (i < config.filament_enable_pressure_advance.size())
                        ? config.filament_enable_pressure_advance[i]
                        : config.filament_enable_pressure_advance.back();
            v.push_back(src ? 1 : 0);
        }
        dpc.set_key_value("enable_pressure_advance", new Slic3r::ConfigOptionBools(std::move(v)));
    }
    if (!config.filament_additional_cooling_fan_speeds.empty()) {
        std::vector<int> v = config.filament_additional_cooling_fan_speeds;
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value("additional_cooling_fan_speed", new Slic3r::ConfigOptionInts(std::move(v)));
    }
    if (!config.filament_close_fan_first_layers.empty()) {
        std::vector<int> v = config.filament_close_fan_first_layers;
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value("close_fan_the_first_x_layers", new Slic3r::ConfigOptionInts(std::move(v)));
    }
    if (!config.filament_full_fan_speed_layers.empty()) {
        std::vector<int> v = config.filament_full_fan_speed_layers;
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value("full_fan_speed_layer", new Slic3r::ConfigOptionInts(std::move(v)));
    }
    if (!config.filament_minimal_purge_on_wipe_tower.empty()) {
        std::vector<double> v(config.filament_minimal_purge_on_wipe_tower.begin(),
                              config.filament_minimal_purge_on_wipe_tower.end());
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value("filament_minimal_purge_on_wipe_tower", new Slic3r::ConfigOptionFloats(std::move(v)));
    }
    if (!config.filament_nozzle_temp_initial_layers.empty()) {
        std::vector<int> v = config.filament_nozzle_temp_initial_layers;
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value("nozzle_temperature_initial_layer", new Slic3r::ConfigOptionInts(std::move(v)));
    }
    if (!config.filament_bed_temp_initial_layers.empty()) {
        std::vector<int> v = config.filament_bed_temp_initial_layers;
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value("hot_plate_temp_initial_layer", new Slic3r::ConfigOptionInts(std::move(v)));
    }
    if (!config.filament_costs.empty()) {
        std::vector<double> v(config.filament_costs.begin(), config.filament_costs.end());
        while ((int)v.size() < n_ext) v.push_back(v.back());
        dpc.set_key_value("filament_cost", new Slic3r::ConfigOptionFloats(std::move(v)));
    }

    // Support speeds — OrcaSlicer defaults to 80mm/s which is fine today, but pin to
    // Snapmaker profile values so future OrcaSlicer changes don't silently regress.
    dpc.set_key_value("support_speed", new Slic3r::ConfigOptionFloat(100.0));
    dpc.set_key_value("support_interface_speed", new Slic3r::ConfigOptionFloat(80.0));

    // Overhang speed control — OrcaSlicer defaults enable_overhang_speed=true with all
    // overhang speeds at 0 (= use wall speed).  Pin to Snapmaker 0.4mm nozzle profile
    // values so a future default change doesn't break overhang quality.
    dpc.set_key_value("enable_overhang_speed", new Slic3r::ConfigOptionBool(true));
    dpc.set_key_value("overhang_1_4_speed", new Slic3r::ConfigOptionFloatOrPercent(55, false));
    dpc.set_key_value("overhang_2_4_speed", new Slic3r::ConfigOptionFloatOrPercent(30, false));
    dpc.set_key_value("overhang_3_4_speed", new Slic3r::ConfigOptionFloatOrPercent(10, false));
    dpc.set_key_value("overhang_4_4_speed", new Slic3r::ConfigOptionFloatOrPercent(10, false));

    // Idle extruder standby temperature — prevents idle nozzles from staying at full print temp.
    // Snapmaker profiles embed ooze_prevention=0 and standby_temperature_delta=-5, which leaves
    // parked extruders at full print temperature for the entire job.  Always override for
    // multi-extruder prints with a -100°C standby delta (applied after profile_keys[] load,
    // so it wins regardless of what the profile says).
    // This gives ~120°C standby for PLA and ~135°C for PETG — above glass transition,
    // well below print temp, preventing ooze and thermal degradation on idle extruders.
    if (n_ext > 1) {
        dpc.set_key_value("ooze_prevention", new Slic3r::ConfigOptionBool(true));
        dpc.set_key_value("standby_temperature_delta", new Slic3r::ConfigOptionInt(-100));
        // idle_temperature of 0 means "use standby_temperature_delta" (per OrcaSlicer GCode.cpp)
        dpc.set_key_value("idle_temperature", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 0)));
    }

    // Support (OrcaSlicer keys)
    // When an embedded Snapmaker profile is loaded, support settings come from profile_keys[]
    // (the file's own enable_support, support_threshold_angle, etc.).  Only apply JNI fallback
    // defaults when NO embedded profile was loaded (e.g. raw STL files).
    // Without this guard, config.support_enabled (default=false) stomps the file's embedded
    // enable_support=true — causing supports to silently disappear (B10).
    if (!has_embedded_profile) {
        dpc.set_key_value("enable_support", new Slic3r::ConfigOptionBool(config.support_enabled));
        if (config.support_enabled) {
            dpc.set_key_value("support_threshold_angle", new Slic3r::ConfigOptionInt((int)config.support_angle));
        }
        // Map support_type string → OrcaSlicer SupportType enum.  Without this, OrcaSlicer always
        // uses its stNormalAuto default for raw STL files, ignoring the user's tree-support selection.
        {
            Slic3r::SupportType stype = Slic3r::stNormalAuto;
            const std::string& st = config.support_type;
            if (st == "tree(auto)" || st == "tree") {
                stype = Slic3r::stTreeAuto;
            } else if (st == "tree(manual)") {
                stype = Slic3r::stTree;
            } else if (st == "normal(manual)") {
                stype = Slic3r::stNormal;
            }
            dpc.set_key_value("support_type", new Slic3r::ConfigOptionEnum<Slic3r::SupportType>(stype));
        }
        // Filament type — per-extruder array; affects fan curves, flow limits, and G-code metadata.
        // Without this, OrcaSlicer uses its PLA default regardless of the user's material selection.
        {
            std::vector<std::string> ftypes;
            ftypes.reserve(n_ext);
            for (int i = 0; i < n_ext; ++i) {
                std::string material = (i < (int) config.filament_types.size() && !config.filament_types[i].empty())
                    ? config.filament_types[i]
                    : config.filament_type;
                if (material.empty()) material = "PLA";
                ftypes.push_back(material);
            }
            dpc.set_key_value("filament_type", new Slic3r::ConfigOptionStrings(ftypes));
        }
        if (config.support_filament > 0) {
            dpc.set_key_value("support_filament", new Slic3r::ConfigOptionInt(config.support_filament));
        }
        if (config.support_interface_filament > 0) {
            dpc.set_key_value("support_interface_filament", new Slic3r::ConfigOptionInt(config.support_interface_filament));
        }
    }

    // Skirt/Brim (same key names)
    dpc.set_key_value("skirt_loops", new Slic3r::ConfigOptionInt(config.skirt_loops));
    dpc.set_key_value("skirt_distance", new Slic3r::ConfigOptionFloat(config.skirt_distance));
    dpc.set_key_value("brim_width", new Slic3r::ConfigOptionFloat(config.brim_width));
    // Brim type — suppress OrcaSlicer's btAutoBrim default, which adds geometry-based brims
    // even when brim_width=0.  Derive from brim_width: no brim → no_brim, else outer_only.
    {
        Slic3r::BrimType btype = (config.brim_width > 0.0f) ? Slic3r::btOuterOnly : Slic3r::btNoBrim;
        dpc.set_key_value("brim_type", new Slic3r::ConfigOptionEnum<Slic3r::BrimType>(btype));
    }

    // Prime tower / wipe tower (OrcaSlicer keys)
    // wipe_tower_x/y are coFloats (arrays) in OrcaSlicer, one value per extruder
    if (n_ext > 1 && config.wipe_tower_enabled) {
        dpc.set_key_value("enable_prime_tower", new Slic3r::ConfigOptionBool(true));
        dpc.set_key_value("wipe_tower_x", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, config.wipe_tower_x)));
        dpc.set_key_value("wipe_tower_y", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, config.wipe_tower_y)));
        dpc.set_key_value("prime_tower_width", new Slic3r::ConfigOptionFloat(config.wipe_tower_width));
    } else {
        dpc.set_key_value("enable_prime_tower", new Slic3r::ConfigOptionBool(false));
    }

    // B106: for STL files (no embedded Snapmaker profile), inject machine G-code templates
    // passed from Kotlin via the JNI config. OrcaSlicer resolves {variable} template
    // expressions at G-code generation time, so machine_start_gcode emits the correct
    // extruder-conditional SM_ macros (SM_PRINT_AUTO_FEED, SM_PRINT_FLOW_CALIBRATE, etc.).
    // When has_embedded_profile=true (3MF with Snapmaker profile), the templates are already
    // applied via profile_keys[] above — guard prevents double-application here.
    if (!has_embedded_profile) {
        if (!config.machine_start_gcode.empty()) {
            dpc.set_key_value("machine_start_gcode", new Slic3r::ConfigOptionString(config.machine_start_gcode));
        }
        if (!config.machine_end_gcode.empty()) {
            dpc.set_key_value("machine_end_gcode", new Slic3r::ConfigOptionString(config.machine_end_gcode));
        }
    }

    // Exclude object / cancel object support — enables EXCLUDE_OBJECT_DEFINE / EXCLUDE_OBJECT_START /
    // EXCLUDE_OBJECT_END G-code markers so firmware can cancel individual objects mid-print.
    // OrcaSlicer defaults to false; enable unconditionally for all U1 prints.
    dpc.set_key_value("exclude_object", new Slic3r::ConfigOptionBool(true));

    // Full-spectrum mixed-filament (stage 2): when the caller passed a
    // recipe via SliceConfig.mixed_filament_definitions, write it into
    // the engine's config key. Empty (default) is a no-op — engine
    // default produces no mixing, and embedded-profile path's value
    // (allowed via project_settings.config) survives untouched.
    if (!config.mixed_filament_definitions.empty()) {
        dpc.set_key_value("mixed_filament_definitions",
            new Slic3r::ConfigOptionString(config.mixed_filament_definitions));
    }
}

SliceResult SlicerEngine::slice(const SliceConfig& config, ProgressCallback progress) {
    SAPIL_LOGI("Starting slice operation...");
    SliceResult result;
    g_slice_cancel.store(false, std::memory_order_release);

    if (!isModelLoaded()) {
        result.success = false;
        result.error_message = "No model loaded";
        return result;
    }

    try {
        diagnostics_clear_trace_buffer();
        // diagnostics_clear_trace_buffer is defined in sapil.h (not in Clipper namespace for orca backend)
        diagnostics_trace_native_event(
            "native_trace_slice_start",
            std::string("{") +
                "\"wipeTowerEnabled\":" + (config.wipe_tower_enabled ? "true" : "false") + "," +
                "\"wipeTowerX\":" + std::to_string(config.wipe_tower_x) + "," +
                "\"wipeTowerY\":" + std::to_string(config.wipe_tower_y) + "," +
                "\"wipeTowerWidth\":" + std::to_string(config.wipe_tower_width) + "," +
                "\"supportEnabled\":" + (config.support_enabled ? "true" : "false") + "," +
                "\"extruderCount\":" + std::to_string(config.extruder_count) +
            "}"
        );
        // Build config: defaults → embedded profile → user overrides (applyConfigToPrusa).
        // The embedded config (from ProfileEmbedder) contains machine_start_gcode,
        // change_filament_gcode, and all Snapmaker profile settings.  Without this,
        // OrcaSlicer uses its built-in default start G-code which lacks SM_ commands.
        // applyConfigToPrusa runs LAST so that JNI user settings (wipe tower on/off,
        // temperatures, layer height, speeds, etc.) override the embedded profile.
        Slic3r::DynamicPrintConfig dpc;
        dpc.apply(Slic3r::FullPrintConfig::defaults());

        // Detect whether the embedded config is a Snapmaker profile before applying
        // any overrides — both applyConfigToPrusa and profile_keys need this flag.
        //
        // Phase 2 B.3 status (2026-04-28): the Kotlin `ProfileEmbedder`
        // now writes an explicit `snapmaker_authored_profile = "1"` key
        // to the embedded `Metadata/project_settings.config` JSON, so a
        // future native check can replace this PRINT_START heuristic
        // with a tautological true-positive. Implementing that check
        // requires registering `snapmaker_authored_profile` as a
        // ConfigOptionString in OrcaSlicer's `PrintConfig.cpp` schema
        // (otherwise `model_config.option<>()` returns null for
        // unregistered keys), which is invasive enough that it's
        // tracked as a separate native commit. For now the heuristic
        // stays — the Kotlin marker is in place and harmless until
        // the native side reads it.
        //
        // Reviewer concerns (R1, R2): a Bambu/PrusaSlicer file
        // prepared for any Klipper-based printer (Voron, RatRig, etc.)
        // could false-positive here. Mitigation in the meantime:
        // ProfileEmbedder rewrites `machine_start_gcode` to U1's
        // PRINT_START template before the native side ever sees it,
        // so Bambu/Klipper-foreign profiles never reach this check
        // with their original start G-code intact. The only path that
        // bypasses ProfileEmbedder is direct legacy load of pre-marked
        // Snapmaker 3MFs (where the heuristic fires correctly).
        auto& model_config = getModelConfig();
        bool is_snapmaker_profile = false;
        if (!model_config.empty()) {
            auto* start_opt = model_config.option<Slic3r::ConfigOptionString>("machine_start_gcode");
            if (start_opt && start_opt->value.find("PRINT_START") != std::string::npos) {
                is_snapmaker_profile = true;
            }
        }
        // Imported 3MFs currently pass through the U1 profile embedder. The explicit
        // target wins so Bambu slices never inherit U1 templates or machine limits.
        if (config.machine_target.rfind("BAMBU_", 0) == 0) {
            is_snapmaker_profile = false;
        }
        // A non-empty project config is the import payload for a Bambu target.
        // It is intentionally distinct from the U1 marker above: Bambu profile
        // values are admitted through a much narrower, non-machine whitelist.
        const bool is_bambu_embedded_profile =
            config.machine_target.rfind("BAMBU_", 0) == 0 && !model_config.empty();

        // Step 1: Apply whitelisted keys from the embedded profile.
        // Only apply G-code templates that were embedded by our ProfileEmbedder
        // (Snapmaker profile).  Raw Bambu 3MFs contain Bambu-specific template
        // variables (flush_volumetric_speeds, M620, etc.) that cause parse errors.
        // Applying all 391 keys causes SIGSEGV — many have array sizes, feature flags,
        // or template macros that conflict with our minimal Print pipeline.
        int applied = 0;
        if (!model_config.empty()) {
            if (is_bambu_embedded_profile) {
                // Process and filament settings only.  In particular this list
                // must never acquire machine geometry, machine_*, nozzle topology
                // or any G-code/template key: those belong to the selected target.
                static const char* bambu_safe_profile_keys[] = {
                    "curr_bed_type", "layer_height", "initial_layer_print_height",
                    "elefant_foot_compensation", "elephant_foot_compensation",
                    "wall_loops", "top_shell_layers", "bottom_shell_layers",
                    "sparse_infill_density", "sparse_infill_pattern", "wall_generator",
                    "top_surface_pattern", "bottom_surface_pattern", "reduce_infill_retraction",
                    "line_width", "outer_wall_line_width", "inner_wall_line_width",
                    "top_surface_line_width", "sparse_infill_line_width", "initial_layer_line_width",
                    "outer_wall_speed", "inner_wall_speed", "sparse_infill_speed",
                    "internal_solid_infill_speed", "top_surface_speed", "initial_layer_speed",
                    "initial_layer_infill_speed", "bridge_speed", "gap_infill_speed", "travel_speed",
                    "default_acceleration", "outer_wall_acceleration", "inner_wall_acceleration",
                    "top_surface_acceleration", "travel_acceleration", "initial_layer_acceleration",
                    "enable_support", "support_type", "support_threshold_angle",
                    "support_on_build_plate_only", "support_object_xy_distance",
                    "support_interface_top_layers", "support_interface_bottom_layers",
                    "support_base_pattern", "support_base_pattern_spacing", "support_interface_pattern",
                    "support_interface_spacing", "support_speed", "support_interface_speed",
                    "support_filament", "support_interface_filament", "tree_support_branch_angle",
                    "tree_support_branch_distance", "tree_support_branch_diameter", "tree_support_wall_count",
                    "brim_type", "brim_width", "brim_object_gap", "skirt_loops", "skirt_distance", "skirt_height",
                    "seam_position", "ironing_type", "ironing_speed", "ironing_flow", "ironing_spacing",
                    "enable_prime_tower", "prime_tower_width", "prime_volume", "prime_tower_brim_width",
                    "prime_tower_brim_chamfer", "prime_tower_brim_chamfer_max_width",
                    "wipe_tower_x", "wipe_tower_y", "wipe_tower_rotation_angle",
                    "filament_colour", "filament_type", "filament_flow_ratio", "filament_density",
                    "filament_cost", "filament_max_volumetric_speed", "nozzle_temperature",
                    "nozzle_temperature_initial_layer", "hot_plate_temp", "hot_plate_temp_initial_layer",
                    "cool_plate_temp", "cool_plate_temp_initial_layer", "textured_plate_temp",
                    "textured_plate_temp_initial_layer", "fan_min_speed", "fan_max_speed",
                    "overhang_fan_speed", "additional_cooling_fan_speed", "fan_cooling_layer_time",
                    "reduce_fan_stop_start_freq", "close_fan_the_first_x_layers", "full_fan_speed_layer",
                    "slow_down_layer_time", "slow_down_min_speed", "enable_pressure_advance",
                    "pressure_advance", "flush_volumes_matrix", "flush_volumes_vector",
                    "filament_minimal_purge_on_wipe_tower", "purge_in_prime_tower",
                    nullptr
                };
                for (const char** k = bambu_safe_profile_keys; *k; ++k) {
                    if (auto* opt = model_config.option(*k)) {
                        dpc.set_key_value(*k, opt->clone());
                        applied++;
                    }
                }
                SAPIL_LOGI("Applied %d safe keys from imported Bambu config", applied);
            } else if (is_snapmaker_profile) {
                // Keys safe to apply from the embedded Snapmaker profile.
                // G-code templates: must come from our profile (not Bambu defaults).
                // Machine limits + per-feature accelerations: these encode the U1's actual
                // kinematics and are needed for correct G-code M201/M204 commands and accurate
                // print-time estimation.  They are sourced from the merged printer+process
                // profile JSON so we don't need to hardcode U1-specific values here.
                static const char* profile_keys[] = {
                    // Machine geometry + plate type
                    "printable_area",
                    "curr_bed_type",
                    // Bed temperature (per plate type)
                    "hot_plate_temp",
                    "hot_plate_temp_initial_layer",
                    // G-code templates
                    "machine_start_gcode",
                    "machine_end_gcode",
                    "change_filament_gcode",
                    "machine_pause_gcode",
                    "before_layer_change_gcode",
                    "layer_change_gcode",
                    // Machine axis limits (M201 / M203)
                    "machine_max_speed_x",
                    "machine_max_speed_y",
                    "machine_max_speed_z",
                    "machine_max_speed_e",
                    "machine_max_acceleration_x",
                    "machine_max_acceleration_y",
                    "machine_max_acceleration_z",
                    "machine_max_acceleration_e",
                    "machine_max_jerk_x",
                    "machine_max_jerk_y",
                    "machine_max_jerk_z",
                    "machine_max_jerk_e",
                    // M204 acceleration values (used by GCodeProcessor for time estimation
                    // and written as M204 P/T/R commands into the G-code)
                    "machine_max_acceleration_extruding",
                    "machine_max_acceleration_retracting",
                    "machine_max_acceleration_travel",
                    // Per-feature accelerations (from process profile)
                    "default_acceleration",
                    "outer_wall_acceleration",
                    "inner_wall_acceleration",
                    "top_surface_acceleration",
                    "travel_acceleration",
                    "initial_layer_acceleration",
                    "initial_layer_travel_acceleration",
                    // Filament flow limits
                    "filament_max_volumetric_speed",
                    "filament_density",
                    "filament_cost",
                    // Fan / cooling (from filament profile)
                    "fan_min_speed",
                    "fan_max_speed",
                    "overhang_fan_speed",
                    "close_fan_the_first_x_layers",
                    "full_fan_speed_layer",
                    "fan_cooling_layer_time",
                    "additional_cooling_fan_speed",
                    "slow_down_layer_time",
                    "slow_down_min_speed",
                    // G-code dialect (klipper vs marlin — affects SET_VELOCITY_LIMIT)
                    "gcode_flavor",
                    // Retraction details (from printer profile)
                    "deretraction_speed",
                    "retraction_minimum_travel",
                    // Overhang speed limits (from process profile)
                    "enable_overhang_speed",
                    "overhang_1_4_speed",
                    "overhang_2_4_speed",
                    "overhang_3_4_speed",
                    "overhang_4_4_speed",
                    // Support — full settings from embedded profile (critical for
                    // USE_FILE override mode to honour Bambu 3MF support settings)
                    "enable_support",
                    "support_type",
                    "support_threshold_angle",
                    "support_on_build_plate_only",
                    "support_object_xy_distance",
                    "support_interface_top_layers",
                    "support_interface_bottom_layers",
                    "support_base_pattern",
                    "support_base_pattern_spacing",
                    "support_interface_pattern",
                    "support_interface_spacing",
                    "support_speed",
                    "support_interface_speed",
                    // Support material extruder selection (F8: soluble support on dedicated extruder)
                    "support_filament",
                    "support_interface_filament",
                    // Tree support parameters
                    "tree_support_branch_angle",
                    "tree_support_branch_distance",
                    "tree_support_branch_diameter",
                    "tree_support_wall_count",
                    // Brim (brim_type is critical — without it OrcaSlicer defaults
                    // to auto_brim which adds brims even when brim_width=0)
                    "brim_type",
                    "brim_width",
                    "brim_object_gap",
                    // Surface patterns (top/bottom finish)
                    "top_surface_pattern",
                    "bottom_surface_pattern",
                    // Seam
                    "seam_position",
                    // Ironing
                    "ironing_type",
                    "ironing_speed",
                    "ironing_flow",
                    "ironing_spacing",
                    // Print speeds (from embedded profile — overrides applyConfigToPrusa
                    // fallbacks when Snapmaker profile is embedded)
                    "outer_wall_speed",
                    "inner_wall_speed",
                    "sparse_infill_speed",
                    "internal_solid_infill_speed",
                    "top_surface_speed",
                    "initial_layer_speed",
                    "initial_layer_infill_speed",
                    "bridge_speed",
                    "gap_infill_speed",
                    "small_perimeter_speed",
                    "travel_speed",
                    // Layer / wall / infill (from embedded profile)
                    "layer_height",
                    "initial_layer_print_height",
                    "wall_loops",
                    "top_shell_layers",
                    "bottom_shell_layers",
                    "sparse_infill_density",
                    "sparse_infill_pattern",
                    // Wall generator type (classic vs arachne variable-width)
                    "wall_generator",
                    // Retraction reduction for infill moves
                    "reduce_infill_retraction",
                    // Line widths (from embedded profile)
                    "line_width",
                    "outer_wall_line_width",
                    "inner_wall_line_width",
                    "top_surface_line_width",
                    "sparse_infill_line_width",
                    "initial_layer_line_width",
                    "support_line_width",
                    // Skirt
                    "skirt_loops",
                    "skirt_distance",
                    "skirt_height",
                    // Prime tower
                    "enable_prime_tower",
                    "prime_tower_width",
                    "prime_volume",
                    "prime_tower_brim_width",
                    "prime_tower_brim_chamfer",
                    "prime_tower_brim_chamfer_max_width",
                    "wipe_tower_x",
                    "wipe_tower_y",
                    "wipe_tower_rotation_angle",
                    // Extruder offsets (per-extruder, must match extruder count)
                    "extruder_offset",
                    // Tool change / wipe tower filament handling (from printer+filament profile)
                    "single_extruder_multi_material",
                    "single_extruder_multi_material_priming",
                    "manual_filament_change",
                    "cooling_tube_retraction",
                    "cooling_tube_length",
                    "parking_pos_retraction",
                    "extra_loading_move",
                    "enable_filament_ramming",
                    "filament_unloading_speed",
                    "filament_unloading_speed_start",
                    "filament_loading_speed",
                    "filament_loading_speed_start",
                    "filament_cooling_moves",
                    "filament_toolchange_delay",
                    // Filament colour (per-extruder — used by multi_material_segmentation_by_painting
                    // to dimension per-layer arrays; must be sized to extruder count)
                    "filament_colour",
                    // Idle/standby temperature management — controls what temperature idle extruders
                    // are held at while not printing.  Without these keys, inactive extruders stay
                    // at full print temperature, causing unnecessary thermal degradation and ooze.
                    // The native Snapmaker Orca app embeds these in its profiles (ooze_prevention=1,
                    // idle_temperature=[105,...]).  idle_temperature is per-extruder (coInts).
                    "ooze_prevention",
                    "idle_temperature",
                    "standby_temperature_delta",
                    // Filament flow calibration — per-filament extrusion multiplier.
                    // Native Snapmaker profiles use 0.95; without this, flow defaults
                    // to 1.0 and calibrated profiles over-extrude by ~5%.
                    "filament_flow_ratio",
                    // Filament type string (per-filament).  Used in change_filament_gcode
                    // template conditionals, e.g. PVA-specific handling.
                    "filament_type",
                    // Pressure advance (Klipper SET_PRESSURE_ADVANCE).  OrcaSlicer emits
                    // these automatically when enable_pressure_advance is true and
                    // gcode_flavor=klipper.  enable_pressure_advance is ConfigOptionBools.
                    "enable_pressure_advance",
                    "pressure_advance",
                    // Wipe tower purge volumes.  flush_volumes_matrix is an NxN matrix of
                    // mm³ per filament-combo transition; flush_multiplier scales it.
                    // Native Snapmaker profiles calibrate these carefully (0–800mm³).
                    // Without them we use OrcaSlicer's compiled default (flat 140mm³).
                    "flush_volumes_matrix",
                    "flush_multiplier",
                    "filament_minimal_purge_on_wipe_tower",
                    "purge_in_prime_tower",
                    // Ooze prevention re-heat mode: 0 = M104 (non-blocking), 1 = M109 (wait).
                    "tool_change_temprature_wait",
                    // Per-feature jerk limits (from process/printer profile).
                    // Without these the slicer uses OrcaSlicer defaults instead of the
                    // Snapmaker-tuned values.
                    "default_jerk",
                    "outer_wall_jerk",
                    "inner_wall_jerk",
                    "infill_jerk",
                    "initial_layer_jerk",
                    "top_surface_jerk",
                    "travel_jerk",
                    // Phase 2 (2026-04-28) — nozzle_temperature only. The
                    // canonical-aware embed pipeline writes this array sized
                    // to the canonical filament count with per-file-filament
                    // material overrides applied (e.g. PETG override on
                    // Filament 1 produces nozzle_temperature=[235,220,...]).
                    // Without it in profile_keys[] the applyConfigToPrusa
                    // fallback overwrote with slot-space uniform defaults —
                    // see docs/superpowers/exploration/2026-04-27-applyConfigToPrusa-cascade-audit.md
                    //
                    // GCode differential against v1.6.13 (2026-04-28) revealed
                    // that whitelisting other per-filament tuning keys
                    // (`hot_plate_temp`, `fan_*`, `filament_max_volumetric_speed`,
                    // `slow_down_*`, `flush_volumes_matrix`, etc.) caused U1
                    // print-quality regressions on Bambu/PrusaSlicer files
                    // because their embedded profiles target Bambu/Prusa
                    // hardware (lower bed temps, conservative fan curves,
                    // 3× higher purge volumes for non-Snapmaker calibration).
                    // U1 users would silently lose adhesion (cool-plate 45°C
                    // vs hot-plate 65°C), gain stringing (low fan), or burn
                    // 3× the purge filament (Korok mask flush_multiplier
                    // 0.3 → 1 from PrusaSlicer embed). Phase 2's user-
                    // facing override surface today is JUST material type,
                    // which only drives nozzle temp. Other material-tuned
                    // keys stay clamped to U1 hardware defaults via
                    // applyConfigToPrusa until the corresponding override
                    // UI lands.
                    "nozzle_temperature",
                    "nozzle_temperature_initial_layer",
                    // Full-spectrum mixed-filament keys (stage 2; PR #375 in v2.3.3).
                    // The recipe string + 18 scalar tuning keys. Lets embedded 3MF
                    // project_settings.config drive mix behavior. Recipe is also
                    // exposed via SliceConfig.mixed_filament_definitions; the other
                    // 18 keys are profile-driven only until M3 exposes them.
                    "mixed_filament_definitions",
                    "mixed_filament_gradient_mode",
                    "mixed_filament_height_lower_bound",
                    "mixed_filament_height_upper_bound",
                    "mixed_filament_advanced_dithering",
                    "mixed_filament_component_bias_enabled",
                    "mixed_filament_surface_indentation",
                    "mixed_filament_region_collapse",
                    "mixed_color_layer_height_a",
                    "mixed_color_layer_height_b",
                    "mixed_filament_pointillism_pixel_size",
                    "mixed_filament_pointillism_line_gap",
                    "dithering_z_step_size",
                    "dithering_local_z_mode",
                    "dithering_local_z_whole_objects",
                    "dithering_local_z_infill",
                    "dithering_local_z_direct_multicolor",
                    "dithering_step_painted_zones_only",
                    "local_z_wipe_tower_purge_lines",
                    nullptr
                };
                for (const char** k = profile_keys; *k; ++k) {
                    auto* opt = model_config.option(*k);
                    if (opt) {
                        dpc.set_key_value(*k, opt->clone());
                        applied++;
                    }
                }
                SAPIL_LOGI("Applied %d profile keys from Snapmaker embedded config", applied);
            } else {
                SAPIL_LOGI("Skipping embedded G-code templates (not a Snapmaker profile, %zu keys)", model_config.keys().size());
            }
        }

        // Step 2: Apply target hardware defaults + JNI user settings.  The
        // Bambu branch is isolated inside applyConfigToPrusa; U1 retains its
        // established ordering and implementation unchanged.
        // Runs AFTER profile_keys so that user-controllable settings (wipe tower,
        // temperatures, layer height, speeds) from the JNI config override the
        // embedded profile's values.
        applyConfigToPrusa(dpc, config, is_snapmaker_profile || is_bambu_embedded_profile);

        if (is_bambu_embedded_profile) {
            // Exported alongside the normal diagnostics trace.  Keep this compact
            // and structured: source keys are the safe whitelist count, target
            // replacement ownership is explicit, and vector presence denotes the
            // existing JNI explicit-override surface.
            std::ostringstream provenance;
            const char* diagnostic_keys[] = {
                "elefant_foot_compensation", "initial_layer_print_height", "curr_bed_type",
                "hot_plate_temp", "filament_flow_ratio", "reduce_fan_stop_start_freq",
                "fan_cooling_layer_time", nullptr
            };
            provenance << "{\"target\":\"" << jsonEscape(config.machine_target) << "\""
                << ",\"safeSourceKeysApplied\":" << applied
                << ",\"targetReplacements\":[\"geometry\",\"identity\",\"gcode_flavor\",\"machine_limits\",\"nozzle_topology\",\"machine_gcode\"]"
                << ",\"explicitOverrides\":{\"filamentFlow\":" << (!config.filament_flow_ratios.empty() ? "true" : "false")
                << ",\"filamentCooling\":" << ((!config.filament_fan_min_speeds.empty() || !config.filament_fan_max_speeds.empty()) ? "true" : "false")
                << ",\"primeTower\":" << (config.wipe_tower_enabled ? "true" : "false") << "}"
                << ",\"final\":{\"bedX\":" << (config.machine_target == "BAMBU_H2D" ? 350 : config.machine_target == "BAMBU_A1_MINI" ? 180 : 256)
                << ",\"bedY\":" << (config.machine_target == "BAMBU_H2D" ? 320 : config.machine_target == "BAMBU_A1_MINI" ? 180 : 256)
                << ",\"singleExtruderMultiMaterial\":" << (config.machine_target == "BAMBU_H2D" ? "false" : "true") << "}"
                << ",\"sourceValues\":{";
            bool first = true;
            for (const char** key = diagnostic_keys; *key; ++key) {
                if (auto* opt = model_config.option(*key)) {
                    if (!first) provenance << ",";
                    provenance << "\"" << *key << "\":\"" << jsonEscape(opt->serialize()) << "\"";
                    first = false;
                }
            }
            provenance << "},\"finalValues\":{";
            first = true;
            for (const char** key = diagnostic_keys; *key; ++key) {
                if (auto* opt = dpc.option(*key)) {
                    if (!first) provenance << ",";
                    provenance << "\"" << *key << "\":\"" << jsonEscape(opt->serialize()) << "\"";
                    first = false;
                }
            }
            provenance << "}}";
            diagnostics_record_native_event("bambu_config_provenance", provenance.str());
        }

        // Step 3: B48 — if the embedded profile set filament_colour to more entries
        // than config.extruder_count (SEMM models with virtual extruder count > physical),
        // pad all per-extruder arrays and flush_volumes_matrix to match.  Without this,
        // ToolOrdering/WipeTower crashes on OOB access when segmentation produces
        // extruder IDs beyond the original n_ext.
        {
            auto* fc = dpc.option<Slic3r::ConfigOptionStrings>("filament_colour");
            int n_ext = std::max(
                config.machine_target == "BAMBU_H2D" ? 2 : 1,
                config.extruder_count);
            if (fc && (int)fc->values.size() > n_ext) {
                int virtual_ext = (int)fc->values.size();
                SAPIL_LOGI("B48: filament_colour has %d entries (n_ext=%d) — padding per-extruder arrays", virtual_ext, n_ext);

                // Helper: pad a ConfigOptionFloats to virtual_ext using its last value
                auto pad_floats = [&](const char* key) {
                    auto* opt = dpc.option<Slic3r::ConfigOptionFloats>(key);
                    if (opt && (int)opt->values.size() < virtual_ext) {
                        double last = opt->values.back();
                        opt->values.resize(virtual_ext, last);
                    }
                };
                // Helper: pad a ConfigOptionInts
                auto pad_ints = [&](const char* key) {
                    auto* opt = dpc.option<Slic3r::ConfigOptionInts>(key);
                    if (opt && (int)opt->values.size() < virtual_ext) {
                        int last = opt->values.back();
                        opt->values.resize(virtual_ext, last);
                    }
                };
                auto pad_strings = [&](const char* key) {
                    auto* opt = dpc.option<Slic3r::ConfigOptionStrings>(key);
                    if (opt && (int)opt->values.size() < virtual_ext) {
                        std::string last = opt->values.back();
                        opt->values.resize(virtual_ext, last);
                    }
                };
                auto pad_bools = [&](const char* key) {
                    auto* opt = dpc.option<Slic3r::ConfigOptionBools>(key);
                    if (opt && (int)opt->values.size() < virtual_ext) {
                        bool last = opt->values.back();
                        opt->values.resize(virtual_ext, last);
                    }
                };

                pad_floats("nozzle_diameter");
                pad_floats("filament_diameter");
                pad_floats("filament_max_volumetric_speed");
                pad_floats("filament_density");
                pad_floats("fan_min_speed");
                pad_floats("fan_max_speed");
                pad_ints("overhang_fan_speed");
                pad_floats("slow_down_layer_time");
                pad_floats("slow_down_min_speed");
                pad_floats("deretraction_speed");
                pad_floats("retraction_minimum_travel");
                pad_floats("filament_unloading_speed");
                pad_floats("filament_unloading_speed_start");
                pad_floats("filament_loading_speed");
                pad_floats("filament_loading_speed_start");
                pad_ints("filament_cooling_moves");
                pad_floats("filament_toolchange_delay");

                // Retraction arrays (set by applyConfigToPrusa from JNI config)
                pad_floats("retract_length");
                pad_floats("retract_speed");
                pad_floats("retract_length_toolchange");

                // Extruder offsets
                auto* eo = dpc.option<Slic3r::ConfigOptionPoints>("extruder_offset");
                if (eo && (int)eo->values.size() < virtual_ext) {
                    eo->values.resize(virtual_ext, Slic3r::Vec2d(0, 0));
                }

                // Wipe tower positions
                pad_floats("wipe_tower_x");
                pad_floats("wipe_tower_y");

                // Nozzle/bed temp arrays
                pad_ints("nozzle_temperature");
                pad_ints("nozzle_temperature_initial_layer");
                pad_ints("bed_temperature");
                pad_ints("bed_temperature_initial_layer");
                // Idle temperature array — must match virtual_ext when ooze_prevention is active
                pad_ints("idle_temperature");

                // New per-filament arrays added to profile_keys
                pad_floats("filament_flow_ratio");
                pad_strings("filament_type");
                pad_bools("enable_pressure_advance");
                pad_floats("pressure_advance");
                pad_floats("filament_minimal_purge_on_wipe_tower");

                // Flush volumes matrix — NxN where N = virtual_ext.
                // ToolOrdering derives extruder count from sqrt(matrix.size()).
                // Prefer the profile's calibrated matrix when it is already sized for
                // virtual_ext.  For SEMM models (virtual_ext > profile extruder count)
                // the sizes won't match and we fall back to the 140mm³ default.
                {
                    auto* fvm = dpc.option<Slic3r::ConfigOptionFloats>("flush_volumes_matrix");
                    if (!fvm || (int)fvm->values.size() != virtual_ext * virtual_ext) {
                        dpc.set_key_value("flush_volumes_matrix",
                            new Slic3r::ConfigOptionFloats(std::vector<double>(virtual_ext * virtual_ext, 140.0)));
                    }
                }
                // Flush volumes vector (per-extruder multipliers, one per extruder)
                {
                    auto* fvv = dpc.option<Slic3r::ConfigOptionFloats>("flush_volumes_vector");
                    if (!fvv || (int)fvv->values.size() < virtual_ext * 2) {
                        dpc.set_key_value("flush_volumes_vector",
                            new Slic3r::ConfigOptionFloats(std::vector<double>(virtual_ext * 2, 140.0)));
                    }
                }
            }
        }

        if (progress) progress(5, "Preparing print configuration");

        // Create a Print object
        Slic3r::Print print;

        // Apply model + config to the print
        Slic3r::Model& model = getGlobalModel();

        // Ensure every object sits on the bed (min z = 0).
        // Per-object ensure_on_bed() is correct: each restructured Bambu build
        // item is an independent printable piece that should touch the bed.
        for (auto* obj : model.objects) {
            obj->ensure_on_bed();
        }

        // Auto-center model on bed if outside 0–270mm bounds.
        // This is a safety net for cases where setModelInstances was not called
        // (e.g. direct native API use, integration tests).  The ViewModel always
        // calls setModelInstances before slicing, so for normal app use this is
        // a no-op.
        //
        // Tolerance: ±2 mm. The previous ±0.5 mm was tight enough that a
        // legitimate user drag to the bed edge could trip it (Review 1 nit) —
        // setModelInstances applied a small wipe-tower or skirt clearance
        // delta that took the world AABB just past 270.5 mm, which then
        // triggered an unwanted full re-centre on top of the user's drag.
        // 2 mm absorbs that without losing the safety property: a model
        // that is genuinely off-bed will be off by tens of millimetres.
        {
            const double BED_X = 270.0, BED_Y = 270.0;
            const double EDGE_TOL = 2.0;
            Slic3r::BoundingBoxf3 worldBB;
            for (auto* obj : model.objects) {
                worldBB.merge(obj->bounding_box_exact());
            }
            if (worldBB.defined) {
                double xMin = worldBB.min.x(), xMax = worldBB.max.x();
                double yMin = worldBB.min.y(), yMax = worldBB.max.y();
                bool outOfBounds = xMin < -EDGE_TOL || xMax > BED_X + EDGE_TOL ||
                                   yMin < -EDGE_TOL || yMax > BED_Y + EDGE_TOL;
                if (outOfBounds) {
                    double sizeX = xMax - xMin, sizeY = yMax - yMin;
                    double deltaX = (BED_X - sizeX) / 2.0 - xMin;
                    double deltaY = (BED_Y - sizeY) / 2.0 - yMin;
                    for (auto* obj : model.objects) {
                        for (auto* inst : obj->instances) {
                            auto offset = inst->get_offset();
                            inst->set_offset(Slic3r::Vec3d(
                                offset.x() + deltaX,
                                offset.y() + deltaY,
                                offset.z()
                            ));
                        }
                    }
                    SAPIL_LOGW("Auto-centered model on bed (was x=%.1f..%.1f y=%.1f..%.1f)",
                        xMin, xMax, yMin, yMax);
                }
            }
        }

        // SEMM (paint-based multi-color): ENABLED via serialized TBB loops.
        // multi_material_segmentation_by_painting() in MultiMaterialSegmentation.cpp
        // uses serial loops on Android (#ifdef __ANDROID__) instead of tbb::parallel_for
        // to prevent SIGSEGV from data races in ExPolygon move semantics on ARM64.
        // Paint data (paint_color= attributes) is preserved through ProfileEmbedder.
        // See SemmSlicingTest for regression guard.

        // B55: check cancel flag before expensive config apply
        if (g_slice_cancel.load(std::memory_order_acquire)) {
            throw Slic3r::CanceledException();
        }
        if (progress) progress(10, "Applying configuration to model");

        // Apply the config to the print
        print.apply(model, dpc);
        diagnostics_record_native_event("slice_native_model_snapshot", buildNativeModelSnapshot(model));

        // Checkpoint 1: validate wipe_tower_y immediately after config apply
        if (config.wipe_tower_enabled) {
            auto& wty = print.config().wipe_tower_y.values;
            std::ostringstream cp1;
            cp1 << "{\"checkpoint\":\"after_apply\",\"vec_size\":" << wty.size();
            for (size_t i = 0; i < wty.size(); ++i)
                cp1 << ",\"y[" << i << "]\":" << wty[i];
            cp1 << ",\"input_y\":" << config.wipe_tower_y << "}";
            diagnostics_record_native_event("wipe_tower_y_checkpoint", cp1.str());
        }

        Slic3r::BoundingBoxf3 finalWorldBB;
        for (auto* obj : model.objects) {
            finalWorldBB.merge(obj->bounding_box_exact());
        }

        std::string support_type_name = "unknown";
        if (auto* support_type_opt = dpc.option<Slic3r::ConfigOptionEnum<Slic3r::SupportType>>("support_type")) {
            support_type_name = supportTypeName(support_type_opt->value);
        }
        bool support_enabled = false;
        if (auto* support_enabled_opt = dpc.option<Slic3r::ConfigOptionBool>("enable_support")) {
            support_enabled = support_enabled_opt->value;
        }
        double support_angle = config.support_angle;
        if (auto* support_angle_opt = dpc.option<Slic3r::ConfigOptionFloat>("support_threshold_angle")) {
            support_angle = support_angle_opt->value;
        }
        std::ostringstream slice_context;
        slice_context << "{"
            << "\"supportEnabled\":" << (support_enabled ? "true" : "false") << ","
            << "\"supportType\":\"" << support_type_name << "\","
            << "\"supportAngle\":" << support_angle << ","
            << "\"worldBounds\":{"
            << "\"xMin\":" << finalWorldBB.min.x() << ","
            << "\"xMax\":" << finalWorldBB.max.x() << ","
            << "\"yMin\":" << finalWorldBB.min.y() << ","
            << "\"yMax\":" << finalWorldBB.max.y() << ","
            << "\"zMin\":" << finalWorldBB.min.z() << ","
            << "\"zMax\":" << finalWorldBB.max.z()
            << "}"
            << ",\"extruderCount\":" << config.extruder_count
            << ",\"wipeTowerEnabled\":" << (config.wipe_tower_enabled ? "true" : "false")
            << ",\"wipeTowerX\":" << config.wipe_tower_x
            << ",\"wipeTowerY\":" << config.wipe_tower_y
            << ",\"isSnapmakerProfile\":" << (is_snapmaker_profile ? "true" : "false")
            << ",\"profileKeysApplied\":" << applied
            << "}";
        diagnostics_record_native_event("slice_pre_process", slice_context.str());

        // Validate the print configuration before processing
        {
            auto validation = print.validate();
            if (!validation.string.empty()) {
                SAPIL_LOGW("Print validation: %s", validation.string.c_str());
            } else {
                SAPIL_LOGI("Print validation: OK");
            }
        }

        // Set up progress callback — monotonic (never goes backwards).
        // PrusaSlicer resets status.percent to 0 at each new step, so we
        // track the high-water mark and only advance forward.
        int max_pct_seen = 15;
        print.set_status_callback([&progress, &max_pct_seen](const Slic3r::PrintBase::SlicingStatus& status) {
            if (progress) {
                int pct = 10 + (int)(status.percent * 0.75); // Map 0-100 to 10-85
                if (pct > max_pct_seen) max_pct_seen = pct;
                progress(max_pct_seen, status.text);
            }
        });

        // B55: check cancel flag before starting heavy processing
        if (g_slice_cancel.load(std::memory_order_acquire)) {
            throw Slic3r::CanceledException();
        }
        if (progress) progress(15, "Starting slicing...");

        // Process (slice + generate toolpaths)
        // Header-level serial shims (extern/tbb_serial/) replace parallel_for,
        // parallel_reduce, etc. with serial implementations, preventing ARM64
        // data races. Compiled TBB library functions (parallel_pipeline in
        // GCode.cpp, task_group in SupportMaterial.cpp) still run with real TBB
        // threads — these are not in the crash path and work correctly.
        // NOTE: Do NOT use tbb::task_arena(1) — it deadlocks because libtbb.so's
        // scheduler cannot schedule child tasks when the arena is limited to 1 thread.
        g_active_print.store(&print, std::memory_order_release);
        print.process();
        g_active_print.store(nullptr, std::memory_order_release);

        // Checkpoint 2: validate wipe_tower_y after print.process()
        if (config.wipe_tower_enabled) {
            auto& wty = print.config().wipe_tower_y.values;
            std::ostringstream cp2;
            cp2 << "{\"checkpoint\":\"after_process\",\"vec_size\":" << wty.size();
            for (size_t i = 0; i < wty.size(); ++i)
                cp2 << ",\"y[" << i << "]\":" << wty[i];
            cp2 << "}";
            diagnostics_record_native_event("wipe_tower_y_checkpoint", cp2.str());
        }

        // B55: check cancel flag before G-code export
        if (g_slice_cancel.load(std::memory_order_acquire)) {
            g_active_print.store(nullptr, std::memory_order_release);
            throw Slic3r::CanceledException();
        }
        if (progress) progress(90, "Generating G-code");

        // Checkpoint 3: validate wipe_tower_y right before G-code export
        if (config.wipe_tower_enabled) {
            auto& wty = print.config().wipe_tower_y.values;
            std::ostringstream cp3;
            cp3 << "{\"checkpoint\":\"before_export\",\"vec_size\":" << wty.size();
            for (size_t i = 0; i < wty.size(); ++i)
                cp3 << ",\"y[" << i << "]\":" << wty[i];
            cp3 << "}";
            diagnostics_record_native_event("wipe_tower_y_checkpoint", cp3.str());
        }

        // Generate G-code — use the same directory as the loaded model
        extern std::string getFilesDir();
        std::string output_path = getFilesDir() + "/output.gcode";
        // OrcaSlicer dereferences result without null check, so provide a real object
        Slic3r::GCodeProcessorResult gcode_result;
        g_active_print.store(&print, std::memory_order_release);
        print.export_gcode(output_path, &gcode_result, nullptr);
        g_active_print.store(nullptr, std::memory_order_release);

        if (progress) progress(95, "Reading results");

        // Read the G-code for preview
        std::ifstream gcode_file(output_path);
        if (gcode_file.good()) {
            std::ostringstream ss;
            ss << gcode_file.rdbuf();
            pImpl->last_gcode_content = ss.str();
            pImpl->last_gcode_path = output_path;
        }

        // Extract print statistics
        const Slic3r::PrintStatistics& stats = print.print_statistics();
        result.success = true;
        result.gcode_path = output_path;
        
        // Count layers across objects
        int max_layers = 0;
        for (const auto* obj : print.objects()) {
            max_layers = std::max(max_layers, (int)obj->total_layer_count());
        }
        result.total_layers = max_layers;

        // Parse time string like "1d 2h 30m 15s" or "2h 30m" into seconds
        {
            const std::string& time_str = stats.estimated_normal_print_time;
            SAPIL_LOGI("Estimated completion time: %s", time_str.c_str());
            float total_seconds = 0.0f;
            int num = 0;
            for (size_t i = 0; i < time_str.size(); ++i) {
                char c = time_str[i];
                if (c >= '0' && c <= '9') {
                    num = num * 10 + (c - '0');
                } else if (c == 'd') {
                    total_seconds += num * 86400.0f;
                    num = 0;
                } else if (c == 'h') {
                    total_seconds += num * 3600.0f;
                    num = 0;
                } else if (c == 'm') {
                    total_seconds += num * 60.0f;
                    num = 0;
                } else if (c == 's') {
                    total_seconds += num;
                    num = 0;
                }
            }
            // Guard against INT_MAX overflow from PrusaSlicer (multi-extruder bug)
            if (total_seconds > 86400.0f * 365.0f) total_seconds = 0.0f;
            result.estimated_time_seconds = total_seconds;
        }

        result.estimated_filament_mm = stats.total_used_filament > 0 ?
            (float)stats.total_used_filament : 0.0f;
        result.estimated_filament_grams = stats.total_weight > 0 ?
            (float)stats.total_weight : result.estimated_filament_mm * 0.003f;

        if (progress) progress(100, "Complete");

        return result;

    } catch (const Slic3r::CanceledException&) {
        g_active_print.store(nullptr, std::memory_order_release);
        result.success = false;
        result.cancelled = true;
        result.error_message = "Cancelled by user";
        SAPIL_LOGI("Slicing cancelled by user");
    } catch (const Slic3r::SlicingErrors& e) {
        g_active_print.store(nullptr, std::memory_order_release);
        result.success = false;
        std::string msg = "Slicing errors:";
        for (const auto& err : e.errors_) {
            msg += "\n  [obj " + std::to_string(err.objectId()) + "] " + err.what();
            SAPIL_LOGE("Slicing error [obj %zu]: %s", err.objectId(), err.what());
        }
        result.error_message = msg;
        SAPIL_LOGE("Total slicing errors: %d", (int)e.errors_.size());
        diagnostics_record_native_event(
            "slice_exception",
            std::string("{\"category\":\"slicing_errors\",\"message\":\"") +
                jsonEscape(msg) + "\"}"
        );
    } catch (const Slic3r::SlicingError& e) {
        g_active_print.store(nullptr, std::memory_order_release);
        result.success = false;
        result.error_message = std::string("Slicing error: ") + e.what();
        SAPIL_LOGE("Slicing error: %s", e.what());
        diagnostics_record_native_event(
            "slice_exception",
            std::string("{\"category\":\"slicing_error\",\"message\":\"") +
                jsonEscape(e.what()) + "\"}"
        );
    } catch (const std::exception& e) {
        g_active_print.store(nullptr, std::memory_order_release);
        result.success = false;
        result.error_message = std::string("Error: ") + e.what();
        SAPIL_LOGE("Error during slicing: %s", e.what());
        diagnostics_record_native_event(
            "slice_exception",
            std::string("{\"category\":\"exception\",\"message\":\"") +
                jsonEscape(e.what()) + "\"}"
        );
    }

    return result;
}

bool SlicerEngine::loadProfile(const std::string& ini_path) {
    try {
        pImpl->print_config.load_from_ini(ini_path, Slic3r::ForwardCompatibilitySubstitutionRule::Enable);
        return true;
    } catch (const std::exception& e) {
        return false;
    }
}

SliceConfig SlicerEngine::getConfigFromProfile() const {
    SliceConfig config;
    const auto& dpc = pImpl->print_config;

    auto optFloat = [&](const char* key, float def) -> float {
        auto opt = dpc.option<Slic3r::ConfigOptionFloat>(key);
        return opt ? (float)opt->value : def;
    };
    auto optInt = [&](const char* key, int def) -> int {
        auto opt = dpc.option<Slic3r::ConfigOptionInt>(key);
        return opt ? opt->value : def;
    };

    config.layer_height = optFloat("layer_height", 0.2f);
    config.perimeters = optInt("wall_loops", 2);
    config.top_solid_layers = optInt("top_shell_layers", 5);
    config.bottom_solid_layers = optInt("bottom_shell_layers", 4);

    return config;
}

} // namespace sapil
