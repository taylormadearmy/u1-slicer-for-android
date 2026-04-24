package com.u1.slicer.bambu

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
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
            "Metadata/Slic3r_PE.config"
            // NOTE: Slic3r_PE_model.config is NOT dropped here — the embed() when-block
            // converts it to model_settings.config (OrcaSlicer format) with extruder remap applied.
        )

        private val DROP_PREFIXES = listOf(
            "Metadata/plate", "Metadata/top", "Metadata/pick"
        )

        /**
         * Convert BambuSanitizer's Slic3r_PE_model.config XML into OrcaSlicer's
         * model_settings.config format, optionally remapping extruder indices.
         *
         * Per-volume extruder assignments are critical for multi-color models where a single
         * object has different triangle ranges assigned to different extruders (e.g. Dragon Scale).
         */
        internal fun convertToModelSettings(
            slic3rConfigBytes: ByteArray,
            extruderRemap: Map<Int, Int>?
        ): String {
            val text = String(slic3rConfigBytes)
            val sb = StringBuilder()
            sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            sb.appendLine("<config>")

            val objectRe = Regex("""<object id="(\d+)"[^>]*>([\s\S]*?)</object>""")
            val extruderRe = Regex("""key="extruder"\s+value="(\d+)"|value="(\d+)"\s+key="extruder"""")
            val volumeRe = Regex("""<volume\s+firstid="(\d+)"\s+lastid="(\d+)"[^>]*>([\s\S]*?)</volume>""")

            for (objMatch in objectRe.findAll(text)) {
                val objectId = objMatch.groupValues[1]
                val body = objMatch.groupValues[2]

                val objExtMatch = extruderRe.find(body)
                val rawObjExt = (objExtMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() })?.toIntOrNull() ?: 1
                val remappedObj = extruderRemap?.get(rawObjExt) ?: rawObjExt

                sb.appendLine("""  <object id="$objectId">""")
                sb.appendLine("""    <metadata type="object" key="extruder" value="$remappedObj"/>""")

                for (volMatch in volumeRe.findAll(body)) {
                    val firstId = volMatch.groupValues[1]
                    val lastId = volMatch.groupValues[2]
                    val volBody = volMatch.groupValues[3]
                    val volExtMatch = extruderRe.find(volBody)
                    val rawVolExt = (volExtMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() })?.toIntOrNull() ?: rawObjExt
                    val remappedVol = extruderRemap?.get(rawVolExt) ?: rawVolExt
                    sb.appendLine("""    <volume firstid="$firstId" lastid="$lastId">""")
                    sb.appendLine("""      <metadata type="volume" key="extruder" value="$remappedVol"/>""")
                    sb.appendLine("""    </volume>""")
                }

                sb.appendLine("""  </object>""")
            }

            sb.appendLine("</config>")
            return sb.toString()
        }

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
        val sourceHasSupports = sourceConfig?.get("enable_support")?.toString() == "1"
        val needsPreserve = info.isBambu && (
            info.detectedExtruderCount > 1 ||
            info.hasLayerToolChanges ||
            (info.hasPaintData && targetExtruderCount > 1) ||
            info.isMultiPlate ||
            info.hasPaintSupports ||  // B57: single-color with support painting needs embedded config preserved
            sourceHasSupports  // B61: single-color file with supports enabled — preserve so processProfile doesn't stomp
        )

        if (needsPreserve && sourceConfig != null) {
            // Bambu preserve path: start with source config, overlay Snapmaker hardware.
            // Deep-copy list values so normalizePerFilamentArrays() doesn't mutate the
            // cached sourceConfig — the initial embed (targetCount=1) would otherwise
            // truncate filament_colour from 4→1, and the re-embed before slicing would
            // see the truncated list and pad with #FFFFFF defaults (B67 root cause).
            config = sourceConfig.entries.associateTo(mutableMapOf()) { (k, v) ->
                k to if (v is List<*>) v.toMutableList() else v
            }
            config.putAll(printerProfile!!.toMap())
            if (info.hasLayerToolChanges) {
                sourceConfig["machine_pause_gcode"]?.let { config["machine_pause_gcode"] = it }
            }
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

        // Strip Bambu-specific G-code.
        // Hueforge-style models can encode layer-by-layer colour swaps in
        // machine_pause_gcode, so preserve that one when the source file
        // explicitly carries layer tool changes.
        config.remove("time_lapse_gcode")
        if (!info.hasLayerToolChanges) {
            config.remove("machine_pause_gcode")
        }
        if (info.hasLayerToolChanges) {
            // U1 handles these files as manual pause-driven filament swaps, not as
            // AMS-style tool changes. Keep the pause script, but drop the Bambu
            // SEMM/toolchange settings that would otherwise trigger the wrong path.
            config["single_extruder_multi_material"] = "0"
            config.remove("single_extruder_multi_material_priming")
            config.remove("manual_filament_change")
            config.remove("change_filament_gcode")
        }
        val fsg = config["filament_start_gcode"]
        if (fsg is List<*> && fsg.any { it.toString().contains("M142") || it.toString().contains("air_filtration") }) {
            config.remove("filament_start_gcode")
        }

        // Set preset IDs
        config["printer_settings_id"] = "Snapmaker U1 (0.4 nozzle) - multiplate"
        config["print_settings_id"] = "0.20mm Standard @Snapmaker U1"
        config["print_compatible_printers"] = mutableListOf("Snapmaker U1 (0.4 nozzle) - multiplate")

        // Snapmaker U1 has independent extruders (NOT SEMM/MMU), but OrcaSlicer's
        // multi_material_segmentation_by_painting() runs based on filament_diameter.size() > 1,
        // not single_extruder_multi_material. Force SEMM=0 to prevent 94mm retraction sequences.
        // Paint segmentation still runs correctly with SEMM=0 + extruder_count > 1.
        if (info.hasPaintData) {
            config["single_extruder_multi_material"] = "0"
            Log.i(TAG, "Paint data present, SEMM=0 (U1 has independent extruders, not MMU)")
        }

        // OrcaSlicer reads extruder_count from project_settings.config to determine how many
        // extruders are active. Without this key it defaults to 1 and ignores per-volume
        // extruder assignments in model_settings.config — the root cause of the multi-colour
        // regression where Dragon Scale sliced as single-colour despite correct volume entries.
        config["extruder_count"] = targetExtruderCount.toString()

        Log.i(
            TAG,
            "buildConfig final layer-tool keys: " +
                "single_extruder_multi_material=${config["single_extruder_multi_material"]} " +
                "single_extruder_multi_material_priming=${config["single_extruder_multi_material_priming"]} " +
                "change_filament_gcode=${config["change_filament_gcode"] != null} " +
                "machine_pause_gcode=${config["machine_pause_gcode"] != null} " +
                "extruder_count=${config["extruder_count"]}"
        )

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

        // 3. Normalize per-filament arrays to targetExtruderCount.
        // Always normalise even for single-extruder (targetExtruderCount=1) so that
        // multi-plate Bambu configs with many filament_colour entries are truncated to
        // the number of extruders actually used.  Without this, a 7-plate single-colour
        // Bambu file embeds 7 filament_colour entries, which ThreeMfParser then counts
        // as 7 detected extruders even after plate selection.
        normalizePerFilamentArrays(config, targetExtruderCount.coerceAtLeast(1))

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
        if (raw is List<*>) {
            // buildProfileOverrides writes wipe_tower_x/y as per-extruder lists; clamp each element
            @Suppress("UNCHECKED_CAST")
            val list = raw as? MutableList<Any> ?: return
            for (i in list.indices) {
                val v = list[i].toString().toFloatOrNull() ?: continue
                if (v < min || v > max) list[i] = "%.3f".format(v.coerceIn(min, max))
            }
            return
        }
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
     * @param extruderRemap Optional 1-based extruder remap applied to model_settings.config.
     *        E.g. mapOf(1 to 3, 2 to 4) maps compact T0/T1 assignments to physical E3/E4.
     *        When provided, Slic3r_PE_model.config is converted and emitted as
     *        model_settings.config so OrcaSlicer uses the correct is_extruder_used[N] slots.
     * @return Embedded 3MF file
     */
    fun embed(
        inputFile: File,
        config: Map<String, Any>,
        outputDir: File,
        info: ThreeMfInfo,
        extruderRemap: Map<Int, Int>? = null,
        plateId: Int? = null
    ): File {
        val outputFile = File(outputDir, "embedded_${inputFile.name}")
        val configIni = serializeConfig(config)

        ZipFile(inputFile).use { srcZip ->
            ZipOutputStream(FileOutputStream(outputFile)).use { destZip ->
                var wroteModelSettings = false
                for (entry in srcZip.entries()) {
                    val name = entry.name

                    // Drop incompatible files
                    if (name in DROP_FILES) continue
                    if (DROP_PREFIXES.any { name.startsWith(it) }) continue

                    when {
                        // Clean and convert .model files:
                        // - Strip Bambu-specific XML extensions (requiredextensions, p:UUID,
                        //   xmlns:BambuStudio, metadata elements) that cause OrcaSlicer's BBS
                        //   3MF reader to reject the file when requiredextensions="p" is present.
                        // - Preserve p:path and xmlns:p (needed for component file refs).
                        // - Convert PrusaSlicer slic3rpe:mmu_segmentation → paint_color= for paint-data files.
                        name.endsWith(".model") -> {
                            if (name == "3D/3dmodel.model" && entry.size > 50_000_000L) {
                                // Giant main model: stream-clean to avoid buffering hundreds of MB.
                                streamCleanEntry(srcZip, entry, destZip, info.hasPaintData)
                            } else if (name != "3D/3dmodel.model" && entry.method == ZipEntry.STORED && !info.hasPaintData) {
                                // Component .model files already STORED by BambuSanitizer —
                                // raw-copy to avoid expensive regex cleaning on large meshes.
                                // restructurePlateFile() will clean when inlining later.
                                // MUST NOT skip when hasPaintData: cleanModelXmlForOrcaSlicer()
                                // converts slic3rpe:mmu_segmentation → paint_color= for PrusaSlicer SEMM.
                                rawCopyEntry(srcZip, entry, destZip)
                            } else if (name != "3D/3dmodel.model" && entry.size > 50_000_000L) {
                                // Large component (>50MB): stream-clean to avoid OOM.
                                // Uses line-by-line processing like BambuSanitizer.copyZipEntry().
                                streamCleanEntry(srcZip, entry, destZip, info.hasPaintData)
                            } else {
                                val content = srcZip.getInputStream(entry).readBytes()
                                val cleaned = cleanModelXmlForOrcaSlicer(content, info.hasPaintData)
                                // Sub-plan #2c defensive fix: when a plateId is supplied
                                // (from SlicerViewModel.selectPlate), filter the main model's
                                // <build> items to the target plate's objects. Belt-and-braces
                                // against files where the native BBS plate_id filter silently
                                // doesn't take effect (e.g., files lacking proper plate
                                // model_instance entries). Without this, selecting a plate
                                // from a multi-plate file would load all plates' build items
                                // and slice with a 625×625mm+ bounding box.
                                val finalBytes = if (plateId != null && name == "3D/3dmodel.model") {
                                    val plateObjectIds = info.plates
                                        .firstOrNull { it.plateId == plateId }
                                        ?.objectIds
                                        ?.toSet()
                                    val filtered = BambuSanitizer.filterModelToPlate(
                                        String(cleaned),
                                        targetPlateId = plateId,
                                        hasPlateJsons = info.hasPlateJsons,
                                        plateObjectIds = plateObjectIds
                                    )
                                    Log.i(TAG, "Filtered 3D/3dmodel.model <build> to plate $plateId (${plateObjectIds?.size ?: 0} objectIds)")
                                    filtered.toByteArray()
                                } else {
                                    cleaned
                                }
                                writeStored(destZip, name, finalBytes)
                            }
                        }

                        // Convert Slic3r_PE_model.config → model_settings.config (OrcaSlicer format),
                        // applying extruder remap so OrcaSlicer uses correct is_extruder_used[N] slots.
                        name == "Metadata/Slic3r_PE_model.config" -> {
                            val content = srcZip.getInputStream(entry).readBytes()
                            val modelSettings = convertToModelSettings(content, extruderRemap)
                            writeStored(destZip, "Metadata/model_settings.config", modelSettings.toByteArray())
                            wroteModelSettings = true
                            Log.i(TAG, "Converted Slic3r_PE_model.config → model_settings.config" +
                                    if (extruderRemap != null) " (remap=$extruderRemap)" else "")
                        }

                        // Sanitize existing model_settings.config (extractPlate path):
                        // - Clear stale plater_name values (prevents OrcaSlicer segfault)
                        // - Clamp off-bed assemble_item transforms (Bambu multi-plate global coords)
                        // - Apply extruder remap if provided
                        // - Skip if already written from Slic3r_PE_model.config conversion above
                        //   (plate-extracted 3MFs can have both entries)
                        name == "Metadata/model_settings.config" -> {
                            if (wroteModelSettings) {
                                Log.i(TAG, "Skipping duplicate model_settings.config (already converted from Slic3r_PE_model.config)")
                            } else {
                                val content = srcZip.getInputStream(entry).readBytes()
                                var sanitized = BambuSanitizer.sanitizeModelSettings(content)
                                if (extruderRemap != null) {
                                    sanitized = remapModelSettingsExtruders(sanitized, extruderRemap)
                                }
                                writeStored(destZip, name, sanitized)
                                wroteModelSettings = true
                                Log.i(TAG, "Sanitized model_settings.config" +
                                        if (extruderRemap != null) " (remap=$extruderRemap)" else "")
                            }
                        }

                        // Layer-change metadata stays on the source/selected-plate file for
                        // preview parsing and post-slice pause injection, but we intentionally
                        // keep it out of the embedded file sent to native Orca. The selected-plate
                        // native slice path becomes extremely slow when this metadata is present,
                        // while the U1-compatible output is produced by LayerToolPauseInjector.
                        name == "Metadata/custom_gcode_per_layer.xml" && info.hasLayerToolChanges -> {
                            // Sub-plan #2b: when a plateId is supplied (from
                            // SlicerViewModel.selectPlate), retain the XML but filter it
                            // to the target plate so sub-plan #3's XML fallback in
                            // LayerToolPauseInjector has plate-scoped entries at injection
                            // time. Legacy callers that pass plateId=null keep the previous
                            // "drop" behaviour (native slice still runs clean; fallback
                            // simply finds nothing).
                            //
                            // filterCustomGcodePerLayer takes a 1-based plate_info id (same
                            // as Bambu's XML convention); SlicerViewModel passes plateId
                            // from UI (1-based already).
                            if (plateId != null) {
                                val content = srcZip.getInputStream(entry).readBytes()
                                val filtered = BambuSanitizer.filterCustomGcodePerLayer(
                                    String(content), plateId
                                )
                                if (filtered.isNotBlank()) {
                                    writeStored(destZip, name, filtered.toByteArray())
                                    Log.i(TAG, "Filtered custom_gcode_per_layer.xml to plate $plateId")
                                } else {
                                    Log.w(TAG, "filterCustomGcodePerLayer returned blank for plate $plateId; dropping")
                                }
                            } else {
                                Log.i(TAG, "Skipping custom_gcode_per_layer.xml in embedded file; native slice uses pause-injection fallback")
                            }
                        }
                        else -> {
                            rawCopyEntry(srcZip, entry, destZip)
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

    /** Instance delegate to companion for backward compatibility with embed(). */
    internal fun convertToModelSettings(
        slic3rConfigBytes: ByteArray,
        extruderRemap: Map<Int, Int>?
    ): String = Companion.convertToModelSettings(slic3rConfigBytes, extruderRemap)

    /**
     * Strip Bambu-specific XML extensions from a .model file so OrcaSlicer's BBS 3MF reader
     * can load it without failing on `requiredextensions="p"`.
     *
     * Preserves `p:path` and `xmlns:p` (needed for external component file references).
     * Strips: requiredextensions, p:UUID, xmlns:BambuStudio, all <metadata> elements,
     * type="other" → type="model".
     *
     * Mirrors BambuSanitizer.cleanModelXmlPreserveComponentRefs(), which is used by the
     * BambuSanitizer.process() path (instrumented tests). This function is used by the
     * embedProfile path (app ViewModel).
     */
    private fun cleanModelXmlForOrcaSlicer(content: ByteArray, hasPaintData: Boolean): ByteArray {
        var text = String(content)
        // SEMM (paint-based multi-color) is enabled — TBB parallel execution algorithms
        // are replaced with serial shims (extern/tbb_serial/) to prevent ARM64 data races.
        // Bambu paint_color= attributes are preserved as-is for OrcaSlicer's
        // multi_material_segmentation_by_painting() to process.
        // PrusaSlicer slic3rpe:mmu_segmentation uses the same RLE hex encoding as Bambu
        // paint_color — rename it so OrcaSlicer's SEMM algorithm can process it.
        if (hasPaintData && text.contains("slic3rpe:mmu_segmentation")) {
            text = text.replace("slic3rpe:mmu_segmentation=", "paint_color=")
            text = text.replace(Regex("""\s+xmlns:slic3rpe="[^"]*""""), "")
        }
        text = text.replace(Regex("""\s+requiredextensions="[^"]*""""), "")
        text = text.replace(Regex("""\s+p:UUID="[^"]*""""), "")
        text = text.replace(Regex("""\s+xmlns:BambuStudio="[^"]*""""), "")
        text = text.replace(Regex("""[ \t]*<metadata name="[^"]*"(?:>[^<]*</metadata>|[^/]*/>) *\r?\n?"""), "")
        text = text.replace("""type="other"""", """type="model"""")
        // Strip non-printable build items (printable="0") to prevent Clipper errors.
        // These are Bambu scene references that shouldn't be instantiated by OrcaSlicer.
        text = BambuSanitizer.stripNonPrintableBuildItems(text)
        return text.toByteArray()
    }

    /**
     * Apply extruder remap to an existing OrcaSlicer model_settings.config.
     * Rewrites extruder values in `key="extruder" value="N"` metadata elements.
     * Used for the extractPlate path where model_settings.config is preserved from
     * the original Bambu 3MF and needs extruder values updated.
     */
    private fun remapModelSettingsExtruders(
        content: ByteArray,
        extruderRemap: Map<Int, Int>
    ): ByteArray {
        // Match key="extruder" followed by value="N" (any whitespace between, any attr order)
        val re = Regex("""(key="extruder"[^>]*?value="|value="(?=[^"]*"[^>]*key="extruder"))(\d+)""")
        val text = re.replace(String(content)) { mr ->
            val raw = mr.groupValues[2].toIntOrNull() ?: return@replace mr.value
            val remapped = extruderRemap[raw] ?: return@replace mr.value
            mr.groupValues[1] + remapped
        }
        return text.toByteArray()
    }

    private fun writeStored(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        entry.crc = crc.value
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    /**
     * Stream-clean a large component .model ZIP entry to avoid OOM.
     * Uses line-by-line processing with fast-path for mesh data lines
     * (99.9%+ of lines are <vertex>/<triangle> with no Bambu attributes).
     * Writes to temp file → computes CRC → writes STORED to ZIP.
     */
    private fun streamCleanEntry(
        srcZip: ZipFile, srcEntry: ZipEntry, destZip: ZipOutputStream, hasPaintData: Boolean
    ) {
        val tmpFile = File.createTempFile("embed_component_", ".model")
        val pUuidRegex = Regex("""\s+p:UUID="[^"]*"""")
        val reqExtRegex = Regex("""\s+requiredextensions="[^"]*"""")
        val bambuNsRegex = Regex("""\s+xmlns:BambuStudio="[^"]*"""")
        val slic3rNsRegex = Regex("""\s+xmlns:slic3rpe="[^"]*"""")
        val metadataRegex = Regex("""[ \t]*<metadata name="[^"]*"(?:>[^<]*</metadata>|[^/]*/>) *\r?\n?""")
        // SEMM enabled — Bambu paint_color= preserved; PrusaSlicer slic3rpe:mmu_segmentation
        // is renamed to paint_color= (same RLE hex encoding) so OrcaSlicer can process it.
        val renameMmu = hasPaintData
        try {
            tmpFile.bufferedWriter().use { out ->
                srcZip.getInputStream(srcEntry).bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        // Fast path: mesh data lines without any attributes needing removal
                        val hasMmu = renameMmu && line.contains("slic3rpe:mmu_segmentation")
                        if (!line.contains("p:UUID") && !line.contains("requiredextensions") &&
                            !line.contains("xmlns:BambuStudio") && !line.contains("<metadata") &&
                            !line.contains("type=\"other\"") && !line.contains("xmlns:slic3rpe") &&
                            !hasMmu) {
                            if (line.isNotBlank()) {
                                out.write(line)
                                out.newLine()
                            }
                            return@forEachLine
                        }
                        // Slow path: lines needing cleaning or mmu rename
                        var cleaned = line
                        if (hasMmu) cleaned = cleaned.replace("slic3rpe:mmu_segmentation=", "paint_color=")
                        cleaned = cleaned.replace(pUuidRegex, "")
                        cleaned = cleaned.replace(reqExtRegex, "")
                        cleaned = cleaned.replace(bambuNsRegex, "")
                        cleaned = cleaned.replace(slic3rNsRegex, "")
                        cleaned = cleaned.replace(metadataRegex, "")
                        cleaned = cleaned.replace("""type="other"""", """type="model"""")
                        if (cleaned.isNotBlank()) {
                            out.write(cleaned)
                            out.newLine()
                        }
                    }
                }
            }
            // Compute CRC + size from temp file
            val crc = CRC32()
            var totalSize = 0L
            tmpFile.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    crc.update(buf, 0, n)
                    totalSize += n
                }
            }
            // Write STORED entry from temp file
            val entry = ZipEntry(srcEntry.name)
            entry.method = ZipEntry.STORED
            entry.size = totalSize
            entry.compressedSize = totalSize
            entry.crc = crc.value
            destZip.putNextEntry(entry)
            tmpFile.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    destZip.write(buf, 0, n)
                }
            }
            destZip.closeEntry()
            Log.i(TAG, "Stream-cleaned ${srcEntry.name} (${totalSize / 1_000_000}MB)")
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * Raw-copy a ZIP entry without decompressing/recompressing.
     * For STORED entries, copies bytes directly. For DEFLATED entries,
     * decompresses and writes as STORED (3MF spec requires STORED).
     */
    private fun rawCopyEntry(srcZip: ZipFile, srcEntry: ZipEntry, destZip: ZipOutputStream) {
        if (srcEntry.method == ZipEntry.STORED) {
            // Already STORED — copy metadata + bytes directly
            val entry = ZipEntry(srcEntry.name)
            entry.method = ZipEntry.STORED
            entry.size = srcEntry.size
            entry.compressedSize = srcEntry.size
            entry.crc = srcEntry.crc
            destZip.putNextEntry(entry)
            srcZip.getInputStream(srcEntry).use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    destZip.write(buf, 0, n)
                }
            }
            destZip.closeEntry()
        } else {
            // DEFLATED → must decompress and write as STORED
            val data = srcZip.getInputStream(srcEntry).readBytes()
            writeStored(destZip, srcEntry.name, data)
        }
    }

}
