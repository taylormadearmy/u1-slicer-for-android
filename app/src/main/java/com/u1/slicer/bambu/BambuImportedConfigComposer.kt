package com.u1.slicer.bambu

import com.u1.slicer.slice.SlicerTarget

/**
 * Resolves a Bambu slice configuration without borrowing any U1 profile state.
 *
 * Imported projects are intentionally treated as a source of process and filament
 * tuning, never as a source of machine identity, motion limits, or executable
 * firmware commands.  Keeping this policy in a small pure class makes it possible
 * to audit (and export) every value which participated in retargeting.
 */
internal object BambuImportedConfigComposer {

    data class Provenance(
        val sourceValue: Any? = null,
        val targetReplacement: Any? = null,
        val explicitOverride: Any? = null,
        val finalValue: Any?,
        val disposition: Disposition,
    )

    enum class Disposition {
        TARGET_DEFAULT,
        TARGET_ADAPTATION,
        SOURCE_SAFE,
        SOURCE_REJECTED_TARGET_REPLACED,
        SOURCE_REJECTED,
        EXPLICIT_OVERRIDE,
        FIRMWARE_SAFETY,
    }

    data class Result(
        val config: Map<String, Any>,
        val provenance: Map<String, Provenance>,
    )

    /**
     * Precedence: target defaults -> safe imported values -> target adaptation ->
     * explicit safe overrides -> firmware-safety metadata.
     */
    fun compose(
        target: SlicerTarget,
        sourceConfig: Map<String, Any>? = null,
        targetAdaptationOverrides: Map<String, Any> = emptyMap(),
        explicitOverrides: Map<String, Any> = emptyMap(),
    ): Result {
        require(target in BAMBU_MACHINE_PROFILES) { "Unsupported Bambu target: $target" }

        val defaults = targetDefaults(target)
        val values = defaults.toMutableMap()
        val provenance = defaults.mapValuesTo(linkedMapOf()) { (_, value) ->
            Provenance(targetReplacement = value, finalValue = value, disposition = Disposition.TARGET_DEFAULT)
        }

        sourceConfig.orEmpty().forEach { (key, value) ->
            when {
                key in SAFE_IMPORTED_KEYS -> put(values, provenance, key, value, Disposition.SOURCE_SAFE, source = value)
                key in MACHINE_OWNED_KEYS || isMachineOwnedPrefix(key) -> {
                    val replacement = defaults[key]
                    if (replacement != null) {
                        values[key] = replacement
                        provenance[key] = Provenance(
                            sourceValue = value,
                            targetReplacement = replacement,
                            finalValue = replacement,
                            disposition = Disposition.SOURCE_REJECTED_TARGET_REPLACED,
                        )
                    } else {
                        provenance[key] = Provenance(
                            sourceValue = value,
                            finalValue = null,
                            disposition = Disposition.SOURCE_REJECTED,
                        )
                    }
                }
                // Unknown keys are rejected by default.  This prevents a newly-added
                // BambuStudio protocol/macro key from silently crossing machines.
                else -> provenance[key] = Provenance(
                    sourceValue = value,
                    finalValue = null,
                    disposition = Disposition.SOURCE_REJECTED,
                )
            }
        }

        val adaptations = targetAdaptations(target).toMutableMap()
        targetAdaptationOverrides.forEach { (key, value) ->
            if (key in TARGET_ADAPTATION_KEYS) adaptations[key] = value
        }
        adaptations.forEach { (key, value) ->
            values[key] = value
            val old = provenance[key]
            provenance[key] = Provenance(
                sourceValue = old?.sourceValue,
                targetReplacement = value,
                finalValue = value,
                disposition = if (old?.sourceValue != null) {
                    Disposition.SOURCE_REJECTED_TARGET_REPLACED
                } else {
                    Disposition.TARGET_ADAPTATION
                },
            )
        }

        explicitOverrides.forEach { (key, value) ->
            if (key in SAFE_IMPORTED_KEYS) {
                put(values, provenance, key, value, Disposition.EXPLICIT_OVERRIDE, override = value)
            } else {
                // A user choosing a Bambu target must not be able to reintroduce a
                // foreign start macro or alter the target's physical envelope here.
                val prior = provenance[key]
                provenance[key] = Provenance(
                    sourceValue = prior?.sourceValue,
                    targetReplacement = prior?.targetReplacement ?: defaults[key],
                    explicitOverride = value,
                    finalValue = values[key],
                    disposition = prior?.disposition ?: Disposition.SOURCE_REJECTED,
                )
            }
        }

        firmwareSafety(target).forEach { (key, value) ->
            values[key] = value
            val old = provenance[key]
            provenance[key] = Provenance(
                sourceValue = old?.sourceValue,
                targetReplacement = old?.targetReplacement,
                explicitOverride = old?.explicitOverride,
                finalValue = value,
                disposition = Disposition.FIRMWARE_SAFETY,
            )
        }
        return Result(values, provenance)
    }

    private fun put(
        values: MutableMap<String, Any>,
        provenance: MutableMap<String, Provenance>,
        key: String,
        value: Any,
        disposition: Disposition,
        source: Any? = null,
        override: Any? = null,
    ) {
        values[key] = value
        val previous = provenance[key]
        provenance[key] = Provenance(
            sourceValue = source ?: previous?.sourceValue,
            targetReplacement = previous?.targetReplacement,
            explicitOverride = override,
            finalValue = value,
            disposition = disposition,
        )
    }

    private fun targetDefaults(target: SlicerTarget): Map<String, Any> {
        val machine = BAMBU_MACHINE_PROFILES.getValue(target)
        return linkedMapOf(
            "printer_model" to machine.printerModel,
            "printer_model_id" to machine.printerModelId,
            "printer_settings_id" to machine.printerSettingsId,
            "printer_variant" to "0.4",
            "printable_area" to rectangle(machine.bedSizeX, machine.bedSizeY),
            "printable_height" to number(machine.maxPrintHeight),
            "nozzle_diameter" to machine.nozzleDiameters.joinToString(","),
            "default_filament_profile" to machine.defaultPlaFilamentSettingsId,
            "filament_settings_id" to listOf(machine.defaultPlaFilamentSettingsId),
            // Official Bambu 0.20 mm / PLA baseline for geometry-only imports.
            // A source 3MF replaces these only through SAFE_IMPORTED_KEYS.
            "print_settings_id" to defaultProcessSettingsId(target),
            "layer_height" to "0.2",
            "initial_layer_print_height" to "0.2",
            "elefant_foot_compensation" to "0.15",
            "wall_loops" to "2",
            "sparse_infill_density" to "15%",
            "sparse_infill_pattern" to "grid",
            "curr_bed_type" to "Textured PEI Plate",
            "filament_type" to listOf("PLA"),
            "filament_flow_ratio" to listOf("0.98"),
            "nozzle_temperature" to listOf("220"),
            "nozzle_temperature_initial_layer" to listOf("220"),
            "hot_plate_temp" to listOf("55"),
            "hot_plate_temp_initial_layer" to listOf("55"),
            "textured_plate_temp" to listOf("55"),
            "textured_plate_temp_initial_layer" to listOf("55"),
            "fan_min_speed" to listOf("100"),
            "fan_max_speed" to listOf("100"),
            "reduce_fan_stop_start_freq" to listOf("1"),
            "fan_cooling_layer_time" to listOf("100"),
            "close_fan_the_first_x_layers" to listOf("1"),
            "full_fan_speed_layer" to listOf("0"),
            "machine_start_gcode" to targetMachineGcode(target).first,
            "machine_end_gcode" to targetMachineGcode(target).second,
            "change_filament_gcode" to targetMachineGcode(target).third,
        ).apply {
            if (machine.nozzleTypes.isNotEmpty()) put("nozzle_type", machine.nozzleTypes)
            if (machine.nozzleVolumes.isNotEmpty()) put("nozzle_volume", machine.nozzleVolumes)
            if (machine.printerExtruderIds.isNotEmpty()) put("printer_extruder_id", machine.printerExtruderIds)
            if (machine.printerExtruderVariants.isNotEmpty()) put("printer_extruder_variant", machine.printerExtruderVariants)
            if (machine.extruderVariantList.isNotEmpty()) put("extruder_variant", machine.extruderVariantList)
        }
    }

    private fun targetAdaptations(target: SlicerTarget): Map<String, Any> {
        val machine = BAMBU_MACHINE_PROFILES.getValue(target)
        return linkedMapOf<String, Any>().apply {
            put("wipe_tower_width", if (target == SlicerTarget.BambuH2D) "60" else "35")
            if (target == SlicerTarget.BambuH2D) {
                put("master_extruder_id", (machine.masterNozzle + 1).toString())
                put("physical_extruder_map", listOf("1", "0"))
                put("nozzle_printable_area", machine.nozzlePrintableAreas.map { rectangle(it.maxX.toFloat() - it.minX.toFloat(), it.maxY.toFloat() - it.minY.toFloat(), it.minX.toFloat(), it.minY.toFloat()) })
            }
        }
    }

    private fun firmwareSafety(target: SlicerTarget): Map<String, Any> = linkedMapOf(
        "gcode_flavor" to "marlin",
        "single_extruder_multi_material" to if (target == SlicerTarget.BambuH2D) "0" else "1",
        "machine_target" to target.nativeTargetName(),
        "exclude_object" to "0",
    )

    private fun targetMachineGcode(target: SlicerTarget): Triple<String, String, String> = when (target) {
        SlicerTarget.BambuA1Mini -> Triple(BambuA1MiniMachineGcode.start, BambuA1MiniMachineGcode.end, BambuA1MiniMachineGcode.changeFilament)
        SlicerTarget.BambuH2D -> Triple(BambuH2DMachineGcode.start, BambuH2DMachineGcode.end, BambuH2DMachineGcode.changeFilament)
        else -> BambuSingleNozzleMachineGcode.forTarget(target)?.let { Triple(it.start, it.end, it.changeFilament) }
            ?: error("No Bambu machine G-code for $target")
    }

    private fun defaultProcessSettingsId(target: SlicerTarget): String = when (target) {
        SlicerTarget.BambuX1C -> "0.20mm Standard @BBL X1C"
        SlicerTarget.BambuX1E -> "0.20mm Standard @BBL X1E"
        SlicerTarget.BambuP1S -> "0.20mm Standard @BBL P1S"
        SlicerTarget.BambuP1P -> "0.20mm Standard @BBL P1P"
        SlicerTarget.BambuA1 -> "0.20mm Standard @BBL A1"
        SlicerTarget.BambuA1Mini -> "0.20mm Standard @BBL A1M"
        SlicerTarget.BambuH2D -> "0.20mm Standard @BBL H2D"
        SlicerTarget.SnapmakerU1 -> error("Not a Bambu target")
    }

    private fun SlicerTarget.nativeTargetName(): String = when (this) {
        SlicerTarget.BambuX1C -> "BAMBU_X1C"
        SlicerTarget.BambuX1E -> "BAMBU_X1E"
        SlicerTarget.BambuP1S -> "BAMBU_P1S"
        SlicerTarget.BambuP1P -> "BAMBU_P1P"
        SlicerTarget.BambuA1 -> "BAMBU_A1"
        SlicerTarget.BambuA1Mini -> "BAMBU_A1_MINI"
        SlicerTarget.BambuH2D -> "BAMBU_H2D"
        SlicerTarget.SnapmakerU1 -> error("Not a Bambu target")
    }

    private fun rectangle(width: Float, height: Float, x: Float = 0f, y: Float = 0f): String =
        "${number(x)}x${number(y)},${number(x + width)}x${number(y)},${number(x + width)}x${number(y + height)},${number(x)}x${number(y + height)}"

    private fun number(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else value.toString()

    private fun isMachineOwnedPrefix(key: String): Boolean =
        key.startsWith("machine_") || key.startsWith("printer_") || key.startsWith("nozzle_") ||
            key.startsWith("extruder_") || key.startsWith("printable_") || key.startsWith("bed_") ||
            key.endsWith("_gcode") || key.contains("gcode") || key.startsWith("filament_start") ||
            key.startsWith("filament_end") || key.startsWith("filament_change")

    private val MACHINE_OWNED_KEYS = setOf(
        "printer_model", "printer_model_id", "printer_settings_id", "printer_variant",
        "printable_area", "printable_height", "bed_shape", "bed_exclude_area",
        "gcode_flavor", "single_extruder_multi_material", "machine_target",
        "machine_start_gcode", "machine_end_gcode", "change_filament_gcode",
        "time_lapse_gcode", "machine_pause_gcode", "physical_extruder_map", "master_extruder_id",
    )

    /** Explicit allow-list: unknown imported keys cannot become firmware input. */
    private val SAFE_IMPORTED_KEYS = setOf(
        "print_settings_id", "default_print_profile", "filament_settings_id",
        "layer_height", "initial_layer_print_height",
        "wall_loops", "top_shell_layers", "bottom_shell_layers",
        "sparse_infill_density", "sparse_infill_pattern", "elefant_foot_compensation",
        "line_width", "outer_wall_line_width", "inner_wall_line_width", "top_surface_line_width",
        "sparse_infill_line_width", "initial_layer_line_width", "outer_wall_speed", "inner_wall_speed",
        "top_surface_pattern", "bottom_surface_pattern", "reduce_infill_retraction", "wall_generator",
        "sparse_infill_speed", "internal_solid_infill_speed", "top_surface_speed",
        "initial_layer_speed", "initial_layer_infill_speed", "bridge_speed", "gap_infill_speed",
        "travel_speed", "default_acceleration", "outer_wall_acceleration", "inner_wall_acceleration",
        "top_surface_acceleration", "travel_acceleration", "initial_layer_acceleration",
        "enable_support", "support_type", "support_threshold_angle", "support_filament",
        "support_interface_filament", "support_on_build_plate_only", "support_object_xy_distance",
        "support_interface_top_layers", "support_interface_bottom_layers", "support_base_pattern",
        "support_base_pattern_spacing", "support_interface_pattern", "support_interface_spacing",
        "support_speed", "support_interface_speed", "tree_support_branch_angle",
        "tree_support_branch_distance", "tree_support_branch_diameter", "tree_support_wall_count",
        "brim_type", "brim_width", "brim_object_gap", "skirt_loops", "skirt_distance", "skirt_height",
        "seam_position", "ironing_type", "ironing_speed", "ironing_flow", "ironing_spacing",
        "curr_bed_type",
        "filament_type", "filament_colour", "extruder_colour", "filament_diameter",
        "filament_ids", "filament_map", "filament_volume_map", "filament_nozzle_map",
        "filament_flow_ratio", "nozzle_temperature", "nozzle_temperature_initial_layer",
        "hot_plate_temp", "hot_plate_temp_initial_layer", "textured_plate_temp",
        "textured_plate_temp_initial_layer", "cool_plate_temp", "cool_plate_temp_initial_layer",
        "reduce_fan_stop_start_freq",
        "fan_cooling_layer_time", "fan_min_speed", "fan_max_speed", "overhang_fan_speed",
        "additional_cooling_fan_speed",
        "slow_down_layer_time", "slow_down_min_speed", "close_fan_the_first_x_layers",
        "full_fan_speed_layer", "filament_max_volumetric_speed", "retraction_length",
        "retraction_speed", "enable_pressure_advance", "pressure_advance", "filament_density",
        "filament_cost", "filament_minimal_purge_on_wipe_tower", "purge_in_prime_tower",
        "enable_prime_tower", "prime_tower_width", "prime_volume", "prime_tower_brim_width",
        "prime_tower_brim_chamfer", "prime_tower_brim_chamfer_max_width",
        "wipe_tower_x", "wipe_tower_y", "wipe_tower_rotation_angle",
        "flush_volumes_matrix", "flush_volumes_vector",
    )

    private val TARGET_ADAPTATION_KEYS = setOf(
        "wipe_tower_x", "wipe_tower_y", "wipe_tower_width",
        "filament_nozzle_map", "filament_volume_map",
    )
}
