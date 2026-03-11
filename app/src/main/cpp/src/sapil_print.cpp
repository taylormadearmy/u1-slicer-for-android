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
#include "libslic3r/Exception.hpp"

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

    // Temperature (OrcaSlicer per-extruder keys)
    dpc.set_key_value("nozzle_temperature", new Slic3r::ConfigOptionInts(temps));
    dpc.set_key_value("nozzle_temperature_initial_layer", new Slic3r::ConfigOptionInts(first_temps));
    // Bed temperature — OrcaSlicer resolves bed temp from the active plate type (curr_bed_type).
    // We set btPEI (textured PEI plate), so hot_plate_temp is the one that matters.
    // Also set the initial layer variant (+5°C for better first layer adhesion, matching pla.json).
    dpc.set_key_value("hot_plate_temp", new Slic3r::ConfigOptionInts(bed_temps));
    std::vector<int> bed_temps_initial(n_ext, config.bed_temp + 5);
    dpc.set_key_value("hot_plate_temp_initial_layer", new Slic3r::ConfigOptionInts(bed_temps_initial));

    // Retraction (OrcaSlicer keys)
    dpc.set_key_value("retraction_length", new Slic3r::ConfigOptionFloats(retract_len));
    dpc.set_key_value("retraction_speed", new Slic3r::ConfigOptionFloats(retract_spd));
    // Toolchange retraction — OrcaSlicer defaults to 10mm (bowden).  For the Snapmaker U1's
    // direct-drive extruders this pulls filament past the heat break, causing heat-creep clogs
    // during the standby period between tool changes.  Use the same length as normal retraction.
    dpc.set_key_value("retract_length_toolchange", new Slic3r::ConfigOptionFloats(retract_len));

    // Multi-extruder machine type — the Snapmaker U1 has 4 independent extruders, NOT a
    // single-extruder multi-material (SEMM) setup like Bambu/Prusa MMU.  OrcaSlicer defaults
    // single_extruder_multi_material to true, which makes WipeTower2 perform bowden-style
    // 94mm filament unload/reload sequences during tool changes — completely wrong for the U1
    // and causes filament jams.  Must be set to false.
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

    // Filament max volumetric speed — OrcaSlicer defaults to 2 mm³/s which throttles all
    // print speeds to ~22 mm/s.  Set to 21 mm³/s (matching Snapmaker PLA profile) as fallback;
    // the embedded config can override via the profile_keys[] list.
    dpc.set_key_value("filament_max_volumetric_speed", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 21.0)));
    // Filament density for weight estimation (PLA = 1.24 g/cm³)
    dpc.set_key_value("filament_density", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 1.24)));

    // Fan / cooling — OrcaSlicer defaults fan_min_speed to 20%, but PLA needs 100%.
    // These fallbacks match pla.json; embedded config overrides via profile_keys[].
    // fan_min_speed and fan_max_speed are coFloats in OrcaSlicer (per-extruder arrays).
    dpc.set_key_value("fan_min_speed", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 100.0)));
    dpc.set_key_value("fan_max_speed", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 100.0)));
    // overhang_fan_speed is coInts (per-extruder)
    dpc.set_key_value("overhang_fan_speed", new Slic3r::ConfigOptionInts(std::vector<int>(n_ext, 100)));
    // Slow-down cooling — OrcaSlicer default is 5s, Snapmaker profile uses 4s
    dpc.set_key_value("slow_down_layer_time", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 4.0)));
    dpc.set_key_value("slow_down_min_speed", new Slic3r::ConfigOptionFloats(std::vector<double>(n_ext, 20.0)));

    // G-code dialect — NOT set as a fallback here because gcode_flavor=klipper suppresses
    // M104/M109 temp commands from the slicer body, relying on the start G-code template.
    // For raw STL files (no embedded profile), the Marlin default is correct.
    // When the Snapmaker profile IS embedded, gcode_flavor=klipper is applied from the
    // profile_keys[] whitelist in the is_snapmaker_profile block above.

    // Deretraction speed — OrcaSlicer defaults to 0 (= same as retraction speed).
    // Snapmaker profile uses 35 mm/s for smoother prime-after-retract.
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
            // Only apply G-code templates that were embedded by our ProfileEmbedder
            // (Snapmaker profile).  Raw Bambu 3MFs contain Bambu-specific template
            // variables (flush_volumetric_speeds, M620, etc.) that cause parse errors.
            // Snapmaker's machine_start_gcode always begins with "PRINT_START".
            bool is_snapmaker_profile = false;
            auto* start_opt = model_config.option<Slic3r::ConfigOptionString>("machine_start_gcode");
            if (start_opt && start_opt->value.find("PRINT_START") != std::string::npos) {
                is_snapmaker_profile = true;
            }

            if (is_snapmaker_profile) {
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
                    "wipe_tower_x",
                    "wipe_tower_y",
                    // Tool change / wipe tower filament handling (from printer+filament profile)
                    "single_extruder_multi_material",
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
                    nullptr
                };
                int applied = 0;
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
        applyConfigToPrusa(dpc, config);

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
        {
            const double BED_X = 270.0, BED_Y = 270.0;
            Slic3r::BoundingBoxf3 worldBB;
            for (auto* obj : model.objects) {
                worldBB.merge(obj->bounding_box_exact());
            }
            if (worldBB.defined) {
                double xMin = worldBB.min.x(), xMax = worldBB.max.x();
                double yMin = worldBB.min.y(), yMax = worldBB.max.y();
                bool outOfBounds = xMin < -0.5 || xMax > BED_X + 0.5 ||
                                   yMin < -0.5 || yMax > BED_Y + 0.5;
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

        // TODO(SEMM): Enable paint-based multi-color slicing for Bambu SEMM files.
        //
        // Currently DISABLED: OrcaSlicer's multi_material_segmentation_by_painting()
        // crashes with SIGSEGV in MultiPoint::bounding_box() when processing Bambu
        // paint_color= triangle data on Android ARM64.  The crash occurs in TBB
        // parallel slice_volumes() → get_extents(vector<ExPolygon>) with corrupt
        // polygon data (null _begin_ pointers).
        //
        // Workaround: ProfileEmbedder strips paint_color= attributes before loading
        // and sets single_extruder_multi_material=0 so the algorithm never runs.
        // Painted Bambu files (e.g. colored 3DBenchy) therefore slice as single-color.
        //
        // To fix: investigate TBB thread-safety on Android ARM64 in slice_volumes(),
        // possibly reduce parallelism or add synchronisation around ExPolygon vectors.
        // Requires native .so rebuild.  See SemmSlicingTest for regression guard.

        if (progress) progress(10, "Applying configuration to model");

        // Apply the config to the print
        print.apply(model, dpc);

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

    } catch (const Slic3r::SlicingErrors& e) {
        result.success = false;
        std::string msg = "Slicing errors:";
        for (const auto& err : e.errors_) {
            msg += "\n  [obj " + std::to_string(err.objectId()) + "] " + err.what();
            SAPIL_LOGE("Slicing error [obj %zu]: %s", err.objectId(), err.what());
        }
        result.error_message = msg;
        SAPIL_LOGE("Total slicing errors: %d", (int)e.errors_.size());
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
