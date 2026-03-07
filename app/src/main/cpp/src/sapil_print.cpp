#include "../include/sapil.h"
#include "sapil_internal.h"
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
static void applyConfigToPrusa(Slic3r::DynamicPrintConfig& dpc, const SliceConfig& config) {
    // Layer settings (OrcaSlicer keys)
    dpc.set_key_value("layer_height", new Slic3r::ConfigOptionFloat(config.layer_height));
    dpc.set_key_value("initial_layer_print_height", new Slic3r::ConfigOptionFloat(config.first_layer_height));
    dpc.set_key_value("wall_loops", new Slic3r::ConfigOptionInt(config.perimeters));
    dpc.set_key_value("top_shell_layers", new Slic3r::ConfigOptionInt(config.top_solid_layers));
    dpc.set_key_value("bottom_shell_layers", new Slic3r::ConfigOptionInt(config.bottom_solid_layers));

    // Infill (OrcaSlicer keys)
    Slic3r::InfillPattern pattern = Slic3r::InfillPattern::ipGyroid;
    if (config.fill_pattern == "grid") pattern = Slic3r::InfillPattern::ipGrid;
    else if (config.fill_pattern == "honeycomb") pattern = Slic3r::InfillPattern::ipHoneycomb;
    else if (config.fill_pattern == "line") pattern = Slic3r::InfillPattern::ipLine;
    else if (config.fill_pattern == "rectilinear") pattern = Slic3r::InfillPattern::ipRectilinear;

    dpc.set_key_value("sparse_infill_pattern", new Slic3r::ConfigOptionEnum<Slic3r::InfillPattern>(pattern));
    dpc.set_key_value("sparse_infill_density", new Slic3r::ConfigOptionPercent(config.fill_density * 100.0));

    // Speed (OrcaSlicer keys)
    dpc.set_key_value("outer_wall_speed", new Slic3r::ConfigOptionFloat(config.print_speed));
    dpc.set_key_value("inner_wall_speed", new Slic3r::ConfigOptionFloat(config.print_speed));
    dpc.set_key_value("travel_speed", new Slic3r::ConfigOptionFloat(config.travel_speed));
    dpc.set_key_value("initial_layer_speed", new Slic3r::ConfigOptionFloat(config.first_layer_speed));

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

    // Override with per-extruder values if provided
    for (int i = 0; i < n_ext; i++) {
        if (i < (int)config.extruder_temps.size() && config.extruder_temps[i] > 0) {
            temps[i] = config.extruder_temps[i];
            first_temps[i] = config.extruder_temps[i];
        }
        if (i < (int)config.extruder_retract_length.size() && config.extruder_retract_length[i] > 0)
            retract_len[i] = config.extruder_retract_length[i];
        if (i < (int)config.extruder_retract_speed.size() && config.extruder_retract_speed[i] > 0)
            retract_spd[i] = config.extruder_retract_speed[i];
    }

    // Temperature (OrcaSlicer per-extruder keys)
    dpc.set_key_value("nozzle_temperature", new Slic3r::ConfigOptionInts(temps));
    dpc.set_key_value("nozzle_temperature_initial_layer", new Slic3r::ConfigOptionInts(first_temps));
    // hot_plate_temp is one of several bed-temp options in OrcaSlicer
    dpc.set_key_value("hot_plate_temp", new Slic3r::ConfigOptionInts(bed_temps));

    // Retraction (OrcaSlicer keys)
    dpc.set_key_value("retraction_length", new Slic3r::ConfigOptionFloats(retract_len));
    dpc.set_key_value("retraction_speed", new Slic3r::ConfigOptionFloats(retract_spd));
    // Toolchange retraction — OrcaSlicer defaults to 10mm (bowden).  For the Snapmaker U1's
    // direct-drive extruders this pulls filament past the heat break, causing heat-creep clogs
    // during the standby period between tool changes.  Use the same length as normal retraction.
    dpc.set_key_value("retract_length_toolchange", new Slic3r::ConfigOptionFloats(retract_len));

    // Nozzle/filament diameter (per-extruder — same key names)
    dpc.set_key_value("nozzle_diameter", new Slic3r::ConfigOptionFloats(nozzle_diameters));
    dpc.set_key_value("filament_diameter", new Slic3r::ConfigOptionFloats(filament_diameters));

    // Support (OrcaSlicer keys)
    dpc.set_key_value("enable_support", new Slic3r::ConfigOptionBool(config.support_enabled));
    if (config.support_enabled) {
        dpc.set_key_value("support_threshold_angle", new Slic3r::ConfigOptionInt((int)config.support_angle));
    }

    // Skirt/Brim (same key names)
    dpc.set_key_value("skirt_loops", new Slic3r::ConfigOptionInt(config.skirt_loops));
    dpc.set_key_value("skirt_distance", new Slic3r::ConfigOptionFloat(config.skirt_distance));
    dpc.set_key_value("brim_width", new Slic3r::ConfigOptionFloat(config.brim_width));

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
}

SliceResult SlicerEngine::slice(const SliceConfig& config, ProgressCallback progress) {
    SAPIL_LOGI("Starting slice operation...");
    SliceResult result;

    if (!isModelLoaded()) {
        result.success = false;
        result.error_message = "No model loaded";
        return result;
    }

    try {
        // Build config: defaults → 3MF embedded config → user overrides.
        // The embedded config (from ProfileEmbedder) contains machine_start_gcode,
        // change_filament_gcode, and all Snapmaker profile settings.  Without this,
        // OrcaSlicer uses its built-in default start G-code which lacks SM_ commands.
        Slic3r::DynamicPrintConfig dpc;
        dpc.apply(Slic3r::FullPrintConfig::defaults());
        // Only apply G-code template keys from the embedded config.
        // Applying all 391 keys causes SIGSEGV — many have array sizes, feature flags,
        // or template macros that conflict with our minimal Print pipeline.
        auto& model_config = getModelConfig();
        if (!model_config.empty()) {
            static const char* gcode_keys[] = {
                "machine_start_gcode",
                "machine_end_gcode",
                "change_filament_gcode",
                "before_layer_change_gcode",
                "layer_change_gcode",
                nullptr
            };
            int applied = 0;
            for (const char** k = gcode_keys; *k; ++k) {
                auto* opt = model_config.option(*k);
                if (opt) {
                    dpc.set_key_value(*k, opt->clone());
                    applied++;
                }
            }
            SAPIL_LOGI("Applied %d G-code template keys from embedded config (%zu total available)", applied, model_config.keys().size());
        }
        applyConfigToPrusa(dpc, config);

        if (progress) progress(5, "Preparing print configuration");

        // Create a Print object
        Slic3r::Print print;

        // Apply model + config to the print
        Slic3r::Model& model = getGlobalModel();

        // Ensure all objects are positioned on the bed
        for (auto* obj : model.objects) {
            obj->ensure_on_bed();
        }

        if (progress) progress(10, "Applying configuration to model");

        // Apply the config to the print
        print.apply(model, dpc);

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

        if (progress) progress(15, "Starting slicing...");

        // Process (slice + generate toolpaths)
        print.process();

        if (progress) progress(90, "Generating G-code");

        // Generate G-code — use the same directory as the loaded model
        extern std::string getFilesDir();
        std::string output_path = getFilesDir() + "/output.gcode";
        // OrcaSlicer dereferences result without null check, so provide a real object
        Slic3r::GCodeProcessorResult gcode_result;
        print.export_gcode(output_path, &gcode_result, nullptr);

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

    } catch (const Slic3r::SlicingError& e) {
        result.success = false;
        result.error_message = std::string("Slicing error: ") + e.what();
        SAPIL_LOGE("Slicing error: %s", e.what());
    } catch (const std::exception& e) {
        result.success = false;
        result.error_message = std::string("Error: ") + e.what();
        SAPIL_LOGE("Error during slicing: %s", e.what());
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
