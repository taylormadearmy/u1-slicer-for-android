package com.u1.slicer.bambu

internal object BambuFilamentConfigCompactor {
    fun compact(
        key: String,
        value: String,
        sourceFilamentIndices: List<Int>,
    ): List<String>? {
        if (sourceFilamentIndices.isEmpty()) return null
        val delimiter = if (key != "flush_volumes_matrix" && ';' in value) ';' else ','
        val values = value.split(delimiter).map(String::trim)
        if (key == "flush_volumes_matrix") {
            val width = kotlin.math.sqrt(values.size.toDouble()).toInt()
            if (width * width != values.size || sourceFilamentIndices.any { it !in 0 until width }) return null
            return sourceFilamentIndices.flatMap { row ->
                sourceFilamentIndices.map { column -> values[row * width + column] }
            }
        }
        if (key !in PER_FILAMENT_CONFIG_KEYS || sourceFilamentIndices.any { it !in values.indices }) return null
        return sourceFilamentIndices.map(values::get)
    }

    private val PER_FILAMENT_CONFIG_KEYS = setOf(
        "activate_air_filtration",
        "additional_cooling_fan_speed",
        "bed_temperature_difference",
        "chamber_temperatures",
        "close_fan_the_first_x_layers",
        "complete_print_exhaust_fan_speed",
        "default_filament_colour",
        "deretraction_speed",
        "during_print_exhaust_fan_speed",
        "enable_overhang_bridge_fan",
        "enable_pressure_advance",
        "end_print_exhaust_fan_speed",
        "end_print_exhaust_fan_time",
        "fan_cooling_layer_time",
        "filament_colour",
        "extruder_colour",
        "filament_type",
        "filament_density",
        "filament_diameter",
        "filament_cost",
        "filament_flow_ratio",
        "filament_max_volumetric_speed",
        "filament_shrink",
        "filament_deretraction_speed",
        "filament_is_support",
        "filament_minimal_purge_on_wipe_tower",
        "filament_retract_before_wipe",
        "filament_retract_restart_extra",
        "filament_retract_when_changing_layer",
        "filament_retraction_length",
        "filament_retraction_minimum_travel",
        "filament_retraction_speed",
        "filament_soluble",
        "filament_vendor",
        "filament_wipe",
        "filament_wipe_distance",
        "filament_z_hop",
        "filament_z_hop_types",
        "filament_settings_id",
        "filament_ids",
        "default_filament_profile",
        "filament_map",
        "filament_nozzle_map",
        "nozzle_temperature",
        "nozzle_temperature_initial_layer",
        "nozzle_temperature_range_low",
        "nozzle_temperature_range_high",
        "hot_plate_temp",
        "hot_plate_temp_initial_layer",
        "textured_plate_temp",
        "textured_plate_temp_initial_layer",
        "cool_plate_temp",
        "cool_plate_temp_initial_layer",
        "eng_plate_temp",
        "eng_plate_temp_initial_layer",
        "retraction_length",
        "retraction_speed",
        "retract_length_toolchange",
        "fan_min_speed",
        "fan_max_speed",
        "overhang_fan_speed",
        "overhang_fan_threshold",
        "pressure_advance",
        "reduce_fan_stop_start_freq",
        "required_nozzle_HRC",
        "retract_before_wipe",
        "retract_restart_extra",
        "retract_restart_extra_toolchange",
        "retract_when_changing_layer",
        "retraction_minimum_travel",
        "slow_down_layer_time",
        "slow_down_min_speed",
        "slow_down_for_layer_cooling",
        "temperature_vitrification",
        "wipe",
        "wipe_distance",
        "z_hop",
        "z_hop_types",
    )
}
