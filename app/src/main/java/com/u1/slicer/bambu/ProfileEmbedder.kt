package com.u1.slicer.bambu

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Embeds Snapmaker U1 OrcaSlicer profiles into 3MF files before slicing.
 *
 * Ported from u1-slicer-bridge: profile_embedder.py
 *
 * The profile embedding pipeline:
 * 1. Load base Snapmaker profiles (printer + process + filament)
 * 2. Merge with user overrides (layer height, infill, temps, etc.)
 * 3. Sanitize parameters (clamp, normalize arrays, fix nil values)
 * 4. Inject into 3MF as Metadata/project_settings.config
 * 5. Strip incompatible Bambu metadata
 */
class ProfileEmbedder(private val context: Context) {
    companion object {
        private const val TAG = "ProfileEmbedder"

        // Keys that are NOT per-filament arrays
        private val NON_FILAMENT_KEYS = setOf(
            "compatible_printers", "compatible_prints"
        )

        // Keys with special list semantics
        private val SPECIAL_LIST_KEYS = setOf(
            "flush_volumes_matrix", "flush_volumes_vector",
            "different_settings_to_system", "inherits_group",
            "upward_compatible_machine", "printable_area",
            "bed_exclude_area", "thumbnails", "head_wrap_detect_zone",
            "extruder_offset", "wipe_tower_x", "wipe_tower_y"
        )

        // Files to strip from Bambu 3MF
        private val DROP_FILES = setOf(
            "Metadata/project_settings.config",
            "Metadata/slice_info.config",
            "Metadata/cut_information.xml",
            "Metadata/filament_sequence.json",
            "Metadata/Slic3r_PE.config",
            "Metadata/Slic3r_PE_model.config"
        )

        private val DROP_PREFIXES = listOf(
            "Metadata/plate", "Metadata/top", "Metadata/pick"
        )

        // Parameters to clamp
        private val CLAMP_INT_RULES = mapOf(
            "raft_first_layer_expansion" to 0,
            "tree_support_wall_count" to 0,
            "prime_volume" to 0,
            "prime_tower_brim_width" to 0,
            "prime_tower_brim_chamfer" to 0,
            "prime_tower_brim_chamfer_max_width" to 0,
            "solid_infill_filament" to 1,
            "sparse_infill_filament" to 1,
            "wall_filament" to 1
        )

        // Default values for per-filament array padding
        private val LIST_DEFAULTS = mapOf(
            "filament_type" to "PLA",
            "filament_colour" to "#FFFFFF",
            "extruder_colour" to "#FFFFFF",
            "default_filament_profile" to "Snapmaker PLA",
            "filament_settings_id" to "Snapmaker PLA",
            "nozzle_temperature" to "210",
            "nozzle_temperature_initial_layer" to "210",
            "bed_temperature" to "60",
            "bed_temperature_initial_layer" to "60",
            "cool_plate_temp" to "60",
            "cool_plate_temp_initial_layer" to "60",
            "textured_plate_temp" to "60",
            "textured_plate_temp_initial_layer" to "60"
        )
    }

    // Cached profiles
    private var printerProfile: MutableMap<String, Any>? = null
    private var processProfile: MutableMap<String, Any>? = null
    private var filamentProfile: MutableMap<String, Any>? = null

    /**
     * Load Snapmaker U1 profiles from assets.
     */
    private fun loadProfiles() {
        if (printerProfile != null) return
        printerProfile = loadJsonAsMap("orca_profiles/printer/snapmaker_u1.json")
        processProfile = loadJsonAsMap("orca_profiles/process/standard_0.20mm.json")
        filamentProfile = loadJsonAsMap("orca_profiles/filament/pla.json")
        Log.i(TAG, "Loaded profiles: printer=${printerProfile!!.size} keys, " +
                "process=${processProfile!!.size} keys, filament=${filamentProfile!!.size} keys")
    }

    private fun loadJsonAsMap(assetPath: String): MutableMap<String, Any> {
        val json = context.assets.open(assetPath).bufferedReader().readText()
        return jsonToMap(JSONObject(json))
    }

    private fun jsonToMap(json: JSONObject): MutableMap<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in json.keys()) {
            val value = json.get(key)
            map[key] = when (value) {
                is org.json.JSONArray -> {
                    val list = mutableListOf<String>()
                    for (i in 0 until value.length()) {
                        list.add(value.get(i).toString())
                    }
                    list
                }
                else -> value.toString()
            }
        }
        return map
    }

    /**
     * Build the complete slicer config by merging profiles with user settings.
     *
     * @param info Parsed 3MF info (for Bambu detection and needs_preserve)
     * @param sourceConfig Existing config from source 3MF (if Bambu file)
     * @param filamentSettings Per-extruder filament settings (temps, colors, materials)
     * @param overrides User overrides (layer height, infill, supports, etc.)
     * @param targetExtruderCount Number of active extruders
     */
    fun buildConfig(
        info: ThreeMfInfo,
        sourceConfig: Map<String, Any>? = null,
        filamentSettings: Map<String, Any> = emptyMap(),
        overrides: Map<String, Any> = emptyMap(),
        targetExtruderCount: Int = 1
    ): MutableMap<String, Any> {
        loadProfiles()

        val config: MutableMap<String, Any>

        // Determine merge strategy based on file type
        val needsPreserve = info.isBambu && (
            info.detectedExtruderCount > 1 ||
            info.hasLayerToolChanges ||
            (info.hasPaintData && targetExtruderCount > 1) ||
            info.isMultiPlate
        )

        if (needsPreserve && sourceConfig != null) {
            // Bambu preserve path: start with source config, overlay Snapmaker hardware
            config = sourceConfig.toMutableMap()
            config.putAll(printerProfile!!.toMap())
            Log.i(TAG, "Using preserve path (Bambu with assignments/tool-changes/paint)")
        } else {
            // Standard path: full Snapmaker profile stack
            config = mutableMapOf<String, Any>()
            config.putAll(printerProfile!!)
            config.putAll(processProfile!!)
            config.putAll(filamentProfile!!)
            Log.i(TAG, "Using standard profile stack")
        }

        // Layer on user filament settings
        for ((key, value) in filamentSettings) {
            config[key] = value
        }

        // Layer on user overrides (highest priority)
        for ((key, value) in overrides) {
            config[key] = value
        }

        // Sanitize
        sanitizeConfig(config, targetExtruderCount)

        // Set G-code quality defaults
        config["layer_gcode"] = "G92 E0"
        config["enable_arc_fitting"] = "1"

        // Clear inherits to avoid lookup failures
        config.remove("inherits")
        config.remove("inherits_group")

        // Strip Bambu-specific G-code
        config.remove("time_lapse_gcode")
        config.remove("machine_pause_gcode")
        val fsg = config["filament_start_gcode"]
        if (fsg is List<*> && fsg.any { it.toString().contains("M142") || it.toString().contains("air_filtration") }) {
            config.remove("filament_start_gcode")
        }

        // Set preset IDs
        config["printer_settings_id"] = "Snapmaker U1 (0.4 nozzle) - multiplate"
        config["print_settings_id"] = "0.20mm Standard @Snapmaker U1"
        config["print_compatible_printers"] = mutableListOf("Snapmaker U1 (0.4 nozzle) - multiplate")

        // Handle SEMM mode for paint data
        if (info.hasPaintData && targetExtruderCount > 1) {
            config["single_extruder_multi_material"] = "1"
            Log.i(TAG, "Enabled SEMM mode for painted multi-color file")
        }

        Log.i(TAG, "Built config with ${config.size} keys, $targetExtruderCount extruders")
        return config
    }

    /**
     * Sanitize the merged config: clamp values, normalize arrays, fix nil.
     */
    private fun sanitizeConfig(config: MutableMap<String, Any>, targetExtruderCount: Int) {
        // 1. Clamp integer parameters
        for ((key, minimum) in CLAMP_INT_RULES) {
            clampIntField(config, key, minimum)
        }

        // 2. Replace nil values in arrays
        sanitizeNilValues(config)

        // 3. Normalize per-filament arrays
        if (targetExtruderCount > 1) {
            normalizePerFilamentArrays(config, targetExtruderCount)
        }

        // 4. Clamp wipe tower position
        sanitizeWipeTowerPosition(config)
    }

    private fun clampIntField(config: MutableMap<String, Any>, key: String, minimum: Int) {
        val raw = config[key] ?: return
        val str = when (raw) {
            is List<*> -> (raw.firstOrNull() ?: return).toString()
            else -> raw.toString()
        }
        val value = str.trim().toIntOrNull() ?: return
        if (value < minimum) {
            config[key] = minimum.toString()
        }
    }

    private fun sanitizeNilValues(config: MutableMap<String, Any>) {
        val toRemove = mutableListOf<String>()
        for ((key, value) in config) {
            if (value !is MutableList<*>) continue
            @Suppress("UNCHECKED_CAST")
            val list = value as MutableList<String>
            if (!list.any { it == "nil" }) continue

            val default = list.firstOrNull { it != "nil" }
            if (default == null) {
                toRemove.add(key)
                continue
            }
            for (i in list.indices) {
                if (list[i] == "nil") list[i] = default
            }
        }
        toRemove.forEach { config.remove(it) }
    }

    private fun normalizePerFilamentArrays(config: MutableMap<String, Any>, targetCount: Int) {
        for ((key, value) in config.entries.toList()) {
            if (key in NON_FILAMENT_KEYS || key in SPECIAL_LIST_KEYS) continue
            if (value !is MutableList<*>) continue
            @Suppress("UNCHECKED_CAST")
            val list = value as MutableList<Any>
            if (list.isEmpty()) continue

            val defaultVal = LIST_DEFAULTS[key]
            when {
                list.size < targetCount -> {
                    val pad = defaultVal ?: list.last().toString()
                    while (list.size < targetCount) list.add(pad)
                }
                list.size > targetCount -> {
                    while (list.size > targetCount) list.removeAt(list.lastIndex)
                }
            }
        }

        // flush_volumes_matrix = NxN
        normalizeMatrix(config, "flush_volumes_matrix", targetCount * targetCount)
        // flush_volumes_vector = 2xN
        normalizeMatrix(config, "flush_volumes_vector", targetCount * 2)
    }

    private fun normalizeMatrix(config: MutableMap<String, Any>, key: String, targetSize: Int) {
        val fvm = config[key]
        if (fvm is MutableList<*>) {
            @Suppress("UNCHECKED_CAST")
            val list = fvm as MutableList<Any>
            val pad = list.lastOrNull() ?: "0"
            while (list.size < targetSize) list.add(pad)
            while (list.size > targetSize) list.removeAt(list.lastIndex)
        }
    }

    private fun sanitizeWipeTowerPosition(config: MutableMap<String, Any>, bedSize: Float = 270f) {
        val towerWidth = getNumeric(config, "prime_tower_width", 35f)
        val towerBrim = maxOf(0f, getNumeric(config, "prime_tower_brim_width", 3f))
        val halfSpan = maxOf(12f, towerWidth / 2f + towerBrim + 6f)
        val minPos = halfSpan
        val maxPos = maxOf(minPos, bedSize - halfSpan)

        clampScalarPosition(config, "wipe_tower_x", minPos, maxPos)
        clampScalarPosition(config, "wipe_tower_y", minPos, maxPos)
    }

    private fun clampScalarPosition(config: MutableMap<String, Any>, key: String, min: Float, max: Float) {
        val raw = config[key] ?: return
        if (raw is List<*>) return // Don't clamp per-plate arrays
        val value = raw.toString().toFloatOrNull() ?: return
        if (value < min || value > max) {
            config[key] = "%.3f".format(value.coerceIn(min, max))
        }
    }

    private fun getNumeric(config: Map<String, Any>, key: String, default: Float): Float {
        val raw = config[key] ?: return default
        return when (raw) {
            is List<*> -> raw.firstOrNull()?.toString()?.toFloatOrNull() ?: default
            else -> raw.toString().toFloatOrNull() ?: default
        }
    }

    /**
     * Serialize config to OrcaSlicer JSON format (required by load_from_json in bbs_3mf.cpp).
     */
    fun serializeConfig(config: Map<String, Any>): String {
        val json = org.json.JSONObject()
        for ((key, value) in config) {
            when (value) {
                is List<*> -> {
                    val arr = org.json.JSONArray()
                    value.forEach { arr.put(it.toString()) }
                    json.put(key, arr)
                }
                else -> json.put(key, value.toString())
            }
        }
        return json.toString(2)
    }

    /**
     * Parse an existing project_settings.config from a 3MF file.
     * Bambu/OrcaSlicer 3MF config is JSON format.
     */
    fun parseSourceConfig(zipFile: ZipFile): Map<String, Any>? {
        val entry = zipFile.getEntry("Metadata/project_settings.config") ?: return null
        val content = zipFile.getInputStream(entry).bufferedReader().readText()
        return try {
            jsonToMap(org.json.JSONObject(content))
        } catch (e: Exception) {
            Log.w(TAG, "Source config is not valid JSON, skipping: ${e.message}")
            null
        }
    }

    /**
     * Embed config into a 3MF file, stripping incompatible metadata.
     *
     * @param inputFile Source 3MF
     * @param config Merged config to inject
     * @param outputDir Directory for output file
     * @param info 3MF analysis info
     * @return Embedded 3MF file
     */
    fun embed(
        inputFile: File,
        config: Map<String, Any>,
        outputDir: File,
        info: ThreeMfInfo
    ): File {
        val outputFile = File(outputDir, "embedded_${inputFile.name}")
        val configIni = serializeConfig(config)

        ZipFile(inputFile).use { srcZip ->
            ZipOutputStream(FileOutputStream(outputFile)).use { destZip ->
                for (entry in srcZip.entries()) {
                    val name = entry.name

                    // Drop incompatible files
                    if (name in DROP_FILES) continue
                    if (DROP_PREFIXES.any { name.startsWith(it) }) continue

                    val content = srcZip.getInputStream(entry).readBytes()

                    when {
                        // Convert mmu_segmentation in .model files (only when paint data present)
                        name.endsWith(".model") && info.hasPaintData -> {
                            var text = String(content)
                            text = text.replace("slic3rpe:mmu_segmentation=", "paint_color=")
                            text = text.replace(Regex("""\s+xmlns:slic3rpe="[^"]*""""), "")
                            destZip.putNextEntry(ZipEntry(name))
                            destZip.write(text.toByteArray())
                            destZip.closeEntry()
                        }

                        // Pass through
                        else -> {
                            destZip.putNextEntry(ZipEntry(name))
                            destZip.write(content)
                            destZip.closeEntry()
                        }
                    }
                }

                // Inject our config as project_settings.config
                destZip.putNextEntry(ZipEntry("Metadata/project_settings.config"))
                destZip.write(configIni.toByteArray())
                destZip.closeEntry()
            }
        }

        Log.i(TAG, "Embedded ${config.size} config keys into ${outputFile.name}")
        return outputFile
    }

}
