package com.u1.slicer.slice

import com.u1.slicer.data.SliceConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** An immutable, resolved Orca machine snapshot. Transport settings are not part of it. */
class GenericMachineProfile private constructor(
    val name: String,
    val resolvedJson: String,
    val fingerprint: String,
    val bedSizeX: Float,
    val bedSizeY: Float,
    val maxPrintHeight: Float,
    val nozzleDiameter: Float,
    val extruderCount: Int,
    val gcodeFlavor: String,
) {
    val settings: Map<String, Any> get() = readSettings(JSONObject(resolvedJson))

    override fun equals(other: Any?): Boolean = other is GenericMachineProfile && fingerprint == other.fingerprint
    override fun hashCode(): Int = fingerprint.hashCode()

    fun applyTo(base: SliceConfig, target: SlicerTarget): SliceConfig = base.copy(
        bedSizeX = bedSizeX,
        bedSizeY = bedSizeY,
        maxPrintHeight = maxPrintHeight,
        nozzleDiameter = nozzleDiameter,
        extruderCount = extruderCount,
        supportFilament = base.supportFilament.coerceIn(0, extruderCount),
        supportInterfaceFilament = base.supportInterfaceFilament.coerceIn(0, extruderCount),
        wipeTowerEnabled = false,
        mixedFilamentDefinitions = "",
        machineStartGcode = template(settings["machine_start_gcode"]),
        machineEndGcode = template(settings["machine_end_gcode"]),
        machineChangeFilamentGcode = template(settings["machine_change_filament_gcode"] ?: settings["change_filament_gcode"]),
        machineTarget = when (target) {
            SlicerTarget.ElegooCentauriCarbon -> "ELEGOO_CC1"
            SlicerTarget.GenericMarlin -> "GENERIC_MARLIN"
            else -> "GENERIC_KLIPPER"
        },
    )

    /** Source projects contribute process/filament tuning, never machine commands or geometry. */
    fun compose(source: Map<String, Any>? = null, overrides: Map<String, Any> = emptyMap()): Map<String, Any> {
        val config = DEFAULTS.toMutableMap()
        settings.filterKeys { it in MACHINE_KEYS || it in PROCESS_KEYS || it.startsWith("machine_max_") || it.startsWith("machine_min_") }.forEach { (key, value) ->
            config[key] = if (key in TEMPLATE_KEYS) template(value)
                else if (key.startsWith("machine_max_") || key.startsWith("machine_min_")) value else normalized(key, value)
        }
        source.orEmpty().filterKeys { it in PROCESS_KEYS }.forEach { (key, value) -> config[key] = normalized(key, value) }
        overrides.filterKeys { it in PROCESS_KEYS }.forEach { (key, value) -> config[key] = normalized(key, value) }
        config.entries.toList().forEach { (key, value) -> config[key] = normalized(key, value) }
        config["nozzle_diameter"] = strings(settings["nozzle_diameter"])
        config["extruder_offset"] = strings(settings["extruder_offset"]).ifEmpty { List(extruderCount) { "0x0" } }
        config["single_extruder_multi_material"] = "0"
        config["enable_prime_tower"] = "0"
        config["support_filament"] = "0"
        config["support_interface_filament"] = "0"
        config["wall_filament"] = "1"
        config["sparse_infill_filament"] = "1"
        config["solid_infill_filament"] = "1"
        config["change_filament_gcode"] = template(settings["machine_change_filament_gcode"] ?: settings["change_filament_gcode"])
        config["filament_start_gcode"] = List(extruderCount) { "" }
        config["filament_end_gcode"] = List(extruderCount) { "" }
        config["exclude_object"] = "0"
        // Orca's relative-E Marlin output requires a reset at each layer. This
        // app-owned command supplements selected machine hooks, never source hooks.
        val relativeExtrusion = config["use_relative_e_distances"]?.toString()?.lowercase() !in setOf("0", "false", "no")
        if (gcodeFlavor in setOf("marlin", "marlin2") && relativeExtrusion) {
            val reset = Regex("(?im)^[ \\t]*G92[ \\t]*E(?:0(?:\\.0*)?|\\.0+)[ \\t]*(?:;.*)?$")
            val before = template(config["before_layer_change_gcode"])
            val after = template(config["layer_change_gcode"])
            if (!reset.containsMatchIn(before) && !reset.containsMatchIn(after)) {
                config["before_layer_change_gcode"] = if (before.isBlank()) "G92 E0" else "${before.trimEnd()}\nG92 E0"
            }
        }
        return config
    }

    private fun normalized(key: String, value: Any): Any = if (value is List<*> &&
        key !in setOf("printable_area", "bed_exclude_area") &&
        !key.startsWith("machine_max_") && !key.startsWith("machine_min_")) {
        require(value.isNotEmpty() || key == "extruder_offset") { "Empty array for '$key'" }
        if (value.isEmpty()) List(extruderCount) { "0x0" }
        else List(extruderCount) { value.getOrElse(it) { value.last() }.toString() }
    } else value

    companion object {
        fun fromJson(json: String): GenericMachineProfile = import(json)

        /** Released 405 snapshots disabled SEMM at composition time, even when their metadata set it. */
        internal fun fromPersistedJson(json: String): GenericMachineProfile = importResolved(json, emptyList(), true)

        /** Import one profile plus parent documents, or a JSON array of inheritance documents. */
        fun import(json: String, parents: List<String> = emptyList()): GenericMachineProfile = importResolved(json, parents, false)

        private fun importResolved(json: String, parents: List<String>, bundledCc1: Boolean): GenericMachineProfile {
            require(json.length <= 2_000_000) { "Machine profile is too large" }
            val documents = if (json.trimStart().startsWith("[")) {
                val array = JSONArray(json)
                require(array.length() in 1..256) { "Profile bundle must contain 1 to 256 documents" }
                (0 until array.length()).map { array.getJSONObject(it) }
            } else listOf(JSONObject(json))
            val all = documents + parents.map(::JSONObject)
            val byName = all.associateBy { it.optString("name") }
            require(byName.size == all.size) { "Profile bundle contains duplicate names" }
            val parentNames = all.map { it.optString("inherits") }.filter { it.isNotBlank() }.toSet()
            val leaves = documents.filter { it.optString("name") !in parentNames && it.optString("instantiation") != "false" }
            require(leaves.size == 1) { "Import a bundle with exactly one selectable machine profile" }
            fun resolve(doc: JSONObject, visiting: Set<String>): Map<String, Any> {
                val name = doc.optString("name")
                require(name.isNotBlank() && name.length <= 200) { "Machine profile requires a name" }
                require(name !in visiting) { "Machine profile inheritance contains a cycle at $name" }
                require(doc.optString("type", "machine") == "machine") { "Select an Orca machine profile, not a process or filament" }
                val parent = doc.optString("inherits")
                val result = if (parent.isBlank()) linkedMapOf() else {
                    val parentDoc = byName[parent] ?: throw IllegalArgumentException("Missing inherited profile '$parent'. Export a flattened profile or import its parents in one JSON array.")
                    resolve(parentDoc, visiting + name).toMutableMap()
                }
                result.putAll(readSettings(doc).filterKeys { it !in METADATA_KEYS })
                return result
            }
            val values = resolve(leaves.single(), emptySet()).toMutableMap()
            TEMPLATE_KEYS.forEach { key ->
                val value = values[key]
                if (value is List<*>) {
                    require(value.size == 1) { "$key requires one machine template" }
                }
            }
            val name = leaves.single().getString("name")
            values["name"] = name
            values["type"] = "machine"
            val flavor = values["gcode_flavor"]?.toString()
            require(flavor in setOf("klipper", "marlin", "marlin2")) { "Only Klipper or Marlin machine profiles are supported" }
            require(values["printer_technology"]?.toString()?.let { it == "FFF" } != false) { "Only FFF machine profiles are supported" }
            val nozzles = strings(values["nozzle_diameter"])
            require(nozzles.size in 1..4) { "One to four physical nozzles are supported" }
            val diameters = nozzles.map { positive(it, "nozzle diameter", 0.1f, 2f) }
            require(diameters.distinct().size == 1) { "Physical nozzles must have the same diameter" }
            val nozzle = diameters.first()
            val manualSingleTool = nozzles.size == 1 && values["manual_filament_change"]?.toString()?.lowercase() in setOf("1", "true", "yes")
            require(bundledCc1 && nozzles.size == 1 || manualSingleTool || values["single_extruder_multi_material"]?.toString()?.lowercase() !in setOf("1", "true", "yes")) { "Automatic single-extruder multi-material machines are not supported" }
            val offsets = strings(values["extruder_offset"])
            require(offsets.isEmpty() || offsets.size == nozzles.size) { "Extruder offsets must match physical nozzles" }
            offsets.forEach { point ->
                val xy = point.split('x')
                require(xy.size == 2 && xy.all { it.toFloatOrNull()?.let { n -> n.isFinite() && n in -2000f..2000f } == true }) { "Invalid extruder offset" }
            }
            require(strings(values["printer_extruder_id"]).size <= nozzles.size && strings(values["physical_extruder_map"]).size <= nozzles.size) { "Invalid physical extruder map" }
            require(values["printer_structure"]?.toString()?.lowercase() != "delta") { "Delta printers are not supported" }
            val polygon = strings(values["printable_area"])
            require(polygon.size == 4) { "Only rectangular origin-based beds are supported" }
            val points = polygon.map { point ->
                val xy = point.split('x')
                require(xy.size == 2) { "Invalid printable area point '$point'" }
                xy[0].toFloatOrNull() to xy[1].toFloatOrNull()
            }
            require(points.all { (x, y) -> x != null && y != null && x.isFinite() && y.isFinite() }) { "Printable area must have finite coordinates" }
            val width = positive(points.maxOf { it.first!! }.toString(), "bed width", 1f, 2000f)
            val depth = positive(points.maxOf { it.second!! }.toString(), "bed depth", 1f, 2000f)
            require(points.toSet() == setOf(0f to 0f, width to 0f, width to depth, 0f to depth)) { "Only rectangular beds starting at 0,0 are supported" }
            values["printable_area"] = listOf("0x0", "${number(width)}x0", "${number(width)}x${number(depth)}", "0x${number(depth)}")
            val exclusion = strings(values["bed_exclude_area"])
            require(exclusion.isEmpty() || exclusion == listOf("0x0") || exclusion.size == 4) { "Only rectangular bed exclusion polygons are supported" }
            exclusion.forEach { point ->
                val xy = point.split('x')
                require(xy.size == 2 && xy[0].toFloatOrNull()?.let { it.isFinite() && it in 0f..width } == true &&
                    xy[1].toFloatOrNull()?.let { it.isFinite() && it in 0f..depth } == true) { "Bed exclusion lies outside the printable area" }
            }
            if (exclusion.size == 4) {
                val excludePoints = exclusion.map { point -> point.split('x').let { it[0].toFloat() to it[1].toFloat() } }
                val minX = excludePoints.minOf { it.first }
                val minY = excludePoints.minOf { it.second }
                val maxX = excludePoints.maxOf { it.first }
                val maxY = excludePoints.maxOf { it.second }
                require(maxX > minX && maxY > minY && excludePoints.toSet() == setOf(minX to minY, maxX to minY, maxX to maxY, minX to maxY)) { "Only rectangular bed exclusion polygons are supported" }
                values["bed_exclude_area"] = listOf("${number(minX)}x${number(minY)}", "${number(maxX)}x${number(minY)}", "${number(maxX)}x${number(maxY)}", "${number(minX)}x${number(maxY)}")
            }
            if (exclusion == listOf("0x0")) values["bed_exclude_area"] = emptyList<String>()
            val height = positive(values["printable_height"]?.toString(), "printable height", 1f, 2000f)
            require(template(values["machine_start_gcode"]).isNotBlank()) { "A machine start G-code template is required" }
            require(template(values["machine_end_gcode"]).isNotBlank()) { "A machine end G-code template is required" }
            listOf("machine_start_gcode", "machine_end_gcode").forEach { key -> require(values[key].toString().length <= 100_000) { "$key is too large" } }
            values.forEach { (key, value) ->
                if (key.startsWith("machine_max_") || key.startsWith("machine_min_")) {
                    require(strings(value).size in 1..2 && strings(value).all { it.toDoubleOrNull()?.let { n -> n.isFinite() && n >= 0 } == true }) { "Invalid machine limit '$key'" }
                }
            }
            val canonical = JSONObject().apply {
                values.toSortedMap().forEach { (key, value) -> put(key, if (value is List<*>) JSONArray(value) else value) }
            }.toString()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return GenericMachineProfile(name, canonical, fingerprint, width, depth, height, nozzle, nozzles.size, requireNotNull(flavor))
        }

        fun centauriCarbon(): GenericMachineProfile = CC1
        private val CC1 by lazy { importResolved(CentauriCarbonProfileJson.value, emptyList(), true) }

        private fun positive(value: String?, label: String, min: Float, max: Float): Float {
            val number = value?.toFloatOrNull()
            require(number != null && number.isFinite() && number in min..max) { "Invalid $label" }
            return number
        }

        private fun number(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else value.toString()

        private fun strings(value: Any?): List<String> = when (value) {
            null -> emptyList()
            is List<*> -> value.map { it.toString() }
            else -> value.toString().split(',').map { it.trim() }
        }

        private fun readSettings(json: JSONObject): Map<String, Any> = json.keys().asSequence().associateWith { key ->
            when (val value = json.get(key)) {
                is JSONArray -> (0 until value.length()).map { index ->
                    val entry = value.get(index)
                    require(entry !is JSONObject && entry !is JSONArray && entry != JSONObject.NULL) { "Invalid list value in '$key'" }
                    entry.toString()
                }
                else -> {
                    require(value !is JSONObject && value != JSONObject.NULL) { "Invalid machine value '$key'" }
                    value.toString()
                }
            }
        }

        private val METADATA_KEYS = setOf("inherits", "from", "setting_id", "instantiation", "version")
        private val TEMPLATE_KEYS = setOf("machine_start_gcode", "machine_end_gcode", "machine_change_filament_gcode", "change_filament_gcode", "machine_pause_gcode", "before_layer_change_gcode", "layer_change_gcode")
        private fun template(value: Any?): String = if (value is List<*>) value.single().toString() else value?.toString().orEmpty()

        private val MACHINE_KEYS = setOf(
            "printer_model", "printer_settings_id", "printer_variant", "printer_structure", "gcode_flavor",
            "printable_area", "printable_height", "bed_exclude_area", "nozzle_diameter", "extruder_offset",
            "machine_start_gcode", "machine_end_gcode", "machine_change_filament_gcode", "change_filament_gcode", "machine_pause_gcode", "before_layer_change_gcode", "layer_change_gcode",
            "retraction_length", "retraction_speed", "deretraction_speed", "retraction_minimum_travel", "retract_before_wipe",
            "retract_when_changing_layer", "retract_restart_extra", "z_hop", "z_hop_types", "retract_lift_above", "retract_lift_below", "wipe", "wipe_distance",
            "min_layer_height", "max_layer_height", "extruder_clearance_radius", "extruder_clearance_height_to_rod", "extruder_clearance_height_to_lid",
            "use_relative_e_distances", "disable_m73", "silent_mode", "auxiliary_fan", "nozzle_type", "nozzle_volume",
        )

        private val PROCESS_KEYS = setOf(
            "layer_height", "initial_layer_print_height", "wall_loops", "top_shell_layers", "bottom_shell_layers", "sparse_infill_density", "sparse_infill_pattern",
            "line_width", "outer_wall_line_width", "inner_wall_line_width", "top_surface_line_width", "sparse_infill_line_width", "initial_layer_line_width", "support_line_width",
            "outer_wall_speed", "inner_wall_speed", "sparse_infill_speed", "internal_solid_infill_speed", "top_surface_speed", "initial_layer_speed", "initial_layer_infill_speed",
            "bridge_speed", "gap_infill_speed", "travel_speed", "small_perimeter_speed", "default_acceleration", "outer_wall_acceleration", "inner_wall_acceleration",
            "top_surface_acceleration", "travel_acceleration", "initial_layer_acceleration", "initial_layer_travel_acceleration", "enable_overhang_speed",
            "overhang_1_4_speed", "overhang_2_4_speed", "overhang_3_4_speed", "overhang_4_4_speed", "wall_generator", "reduce_infill_retraction",
            "enable_support", "support_type", "support_threshold_angle", "support_on_build_plate_only", "support_object_xy_distance", "support_interface_top_layers",
            "support_interface_bottom_layers", "support_base_pattern", "support_base_pattern_spacing", "support_interface_pattern", "support_interface_spacing",
            "support_speed", "support_interface_speed", "tree_support_branch_angle", "tree_support_branch_distance", "tree_support_branch_diameter", "tree_support_wall_count",
            "brim_type", "brim_width", "brim_object_gap", "skirt_loops", "skirt_distance", "skirt_height", "seam_position", "elefant_foot_compensation",
            "top_surface_pattern", "bottom_surface_pattern", "ironing_type", "ironing_speed", "ironing_flow", "ironing_spacing", "curr_bed_type",
            "filament_type", "filament_colour", "extruder_colour", "filament_diameter", "filament_flow_ratio", "filament_density", "filament_cost", "filament_max_volumetric_speed",
            "nozzle_temperature", "nozzle_temperature_initial_layer", "hot_plate_temp", "hot_plate_temp_initial_layer", "textured_plate_temp", "textured_plate_temp_initial_layer",
            "cool_plate_temp", "cool_plate_temp_initial_layer", "fan_min_speed", "fan_max_speed", "overhang_fan_speed", "additional_cooling_fan_speed", "fan_cooling_layer_time",
            "reduce_fan_stop_start_freq", "close_fan_the_first_x_layers", "full_fan_speed_layer", "slow_down_layer_time", "slow_down_min_speed", "enable_pressure_advance", "pressure_advance",
        )

        private val DEFAULTS: Map<String, Any> = mapOf(
            "layer_height" to "0.2", "initial_layer_print_height" to "0.2", "wall_loops" to "2", "sparse_infill_density" to "15%", "sparse_infill_pattern" to "gyroid",
            "brim_type" to "no_brim", "brim_width" to "0", "skirt_loops" to "0", "enable_support" to "0",
            "outer_wall_speed" to "60", "inner_wall_speed" to "80", "sparse_infill_speed" to "80", "internal_solid_infill_speed" to "80", "top_surface_speed" to "50",
            "initial_layer_speed" to "20", "initial_layer_infill_speed" to "20", "travel_speed" to "150", "default_acceleration" to "1000", "outer_wall_acceleration" to "1000",
            "filament_type" to listOf("PLA"), "filament_colour" to listOf("#FFFFFF"), "filament_diameter" to listOf("1.75"), "filament_flow_ratio" to listOf("1"),
            "filament_max_volumetric_speed" to listOf("12"), "nozzle_temperature" to listOf("210"), "nozzle_temperature_initial_layer" to listOf("210"),
            "hot_plate_temp" to listOf("60"), "hot_plate_temp_initial_layer" to listOf("60"), "textured_plate_temp" to listOf("60"), "textured_plate_temp_initial_layer" to listOf("60"),
            "curr_bed_type" to "Textured PEI Plate", "enable_pressure_advance" to listOf("0"), "pressure_advance" to listOf("0"),
        )
    }
}
