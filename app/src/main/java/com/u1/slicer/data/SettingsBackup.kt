package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup/restore all app settings as a single JSON file.
 *
 * Schema versions:
 *  v1 — single-printer: top-level `printerUrl` + `extruderPresets` array
 *  v2 — multi-printer:  `printers` array + `activePrinterId`; also writes legacy
 *        top-level fields from the active printer so a v1 reader can still load the file.
 *  v3 — F87: adds `processProfiles` array + `activeProcessProfileId`. v3 readers also
 *        accept v1/v2 backups (the new keys are simply absent).
 */
object SettingsBackup {

    private const val VERSION = 3

    data class BackupData(
        val sliceConfig: SliceConfig?,
        val slicingOverrides: SlicingOverrides?,
        val printersConfig: PrintersConfig?,
        val filamentProfiles: List<FilamentProfile>?,
        val processProfilesConfig: ProcessProfilesConfig? = null,
        val makerWorldCookies: String? = null,
    )

    fun import(json: String): BackupData {
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        if (version < 1 || version > VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version (this app supports 1..$VERSION)")
        }

        val printersConfig: PrintersConfig? = when {
            // v2+ with explicit printers array
            root.has("printers") && root.has("activePrinterId") -> {
                val arr = root.getJSONArray("printers")
                val list = (0 until arr.length()).map { Printer.fromJsonObject(arr.getJSONObject(it)) }
                PrintersConfig(printers = list, activeId = root.getString("activePrinterId"))
            }
            // v1 — synthesize from legacy fields
            root.has("printerUrl") || root.has("extruderPresets") -> {
                val legacyUrl = if (root.has("printerUrl")) root.getString("printerUrl") else ""
                // The v1 backup stores extruder presets in slot-based format (slot=1-based).
                // Convert to index-based JSON string that buildMigratedConfig / parseExtruderPresets expects.
                val legacyExtruderPresetsJson: String? = if (root.has("extruderPresets")) {
                    val srcArr = root.getJSONArray("extruderPresets")
                    val converted = JSONArray()
                    for (i in 0 until srcArr.length()) {
                        val obj = srcArr.getJSONObject(i)
                        val slot = obj.optInt("slot", i + 1)
                        converted.put(JSONObject().apply {
                            put("index", slot - 1)
                            put("color", obj.optString("color", "#FFFFFF"))
                            put("materialType", obj.optString("materialType", "PLA"))
                            if (obj.has("filamentProfileId")) put("filamentProfileId", obj.getLong("filamentProfileId"))
                        })
                    }
                    converted.toString()
                } else null
                PrintersRepository.buildMigratedConfig(
                    legacyUrl = legacyUrl,
                    legacyExtruderPresetsJson = legacyExtruderPresetsJson,
                )
            }
            else -> null
        }

        val processProfilesConfig: ProcessProfilesConfig? = root.optJSONArray("processProfiles")?.let { arr ->
            val profiles = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { ProcessProfilesConfig.profileFromJson(it) }
            }
            val activeId = if (root.has("activeProcessProfileId"))
                root.optString("activeProcessProfileId").takeIf { it.isNotBlank() }
            else null
            ProcessProfilesConfig(profiles = profiles, activeId = activeId)
        }

        return BackupData(
            sliceConfig = root.optJSONObject("sliceConfig")?.let { parseSliceConfig(it) },
            slicingOverrides = root.optJSONObject("slicingOverrides")?.let { SlicingOverrides.fromJson(it.toString()) },
            printersConfig = printersConfig,
            filamentProfiles = root.optJSONArray("filamentProfiles")?.let { parseFilamentProfilesArray(it) },
            processProfilesConfig = processProfilesConfig,
            makerWorldCookies = if (root.has("makerWorldCookies")) root.getString("makerWorldCookies") else null,
        )
    }

    /**
     * Export [BackupData] as a VERSION=2 JSON string.
     *
     * v2 schema:
     *  - `printers` array + `activePrinterId`
     *  - Legacy compat: active printer's `printerUrl` + `extruderPresets` also written
     *    at the top level so a v1 reader can still load essential printer settings.
     */
    fun export(data: BackupData): String {
        val root = JSONObject()
        root.put("version", VERSION)
        data.sliceConfig?.let { root.put("sliceConfig", exportSliceConfig(it)) }
        data.slicingOverrides?.let { root.put("slicingOverrides", JSONObject(it.toJson())) }
        data.filamentProfiles?.let { profiles ->
            root.put("filamentProfiles", exportFilamentProfiles(profiles))
        }
        data.makerWorldCookies?.let { if (it.isNotEmpty()) root.put("makerWorldCookies", it) }

        // F87: process profiles. Absent when not in use, so v3 backups are still backwards-compatible.
        data.processProfilesConfig?.let { ppCfg ->
            if (ppCfg.profiles.isNotEmpty()) {
                val arr = JSONArray()
                ppCfg.profiles.forEach { arr.put(ProcessProfilesConfig.profileToJson(it)) }
                root.put("processProfiles", arr)
                ppCfg.activeId?.let { root.put("activeProcessProfileId", it) }
            }
        }

        data.printersConfig?.let { cfg ->
            // v2 schema: full list
            val arr = JSONArray()
            cfg.printers.forEach { arr.put(Printer.toJsonObject(it)) }
            root.put("printers", arr)
            root.put("activePrinterId", cfg.activeId)
            // v1 rollback compat: mirror the active printer's URL + presets into legacy fields
            val active = cfg.active
            root.put("printerUrl", active.moonrakerUrl)
            root.put("extruderPresets", exportExtruderPresets(active.extruderPresets))
        }

        return root.toString(2)
    }

    /**
     * Legacy export signature used by [com.u1.slicer.SlicerViewModel.exportBackupAsync].
     * Builds a [BackupData] and delegates to [export].
     */
    fun export(
        sliceConfig: SliceConfig,
        slicingOverrides: SlicingOverrides,
        printerUrl: String,
        extruderPresets: List<ExtruderPreset>,
        filamentProfiles: List<FilamentProfile>,
        filamentNameResolver: (Long) -> String? = { null },
        makerWorldCookies: String = "",
        printersConfig: PrintersConfig? = null,
        processProfilesConfig: ProcessProfilesConfig? = null,
    ): String {
        // If a full PrintersConfig is provided (v2 path), use it.
        // Otherwise synthesise a single-printer config from the legacy flat fields.
        val cfg = printersConfig ?: run {
            val id = java.util.UUID.randomUUID().toString()
            PrintersConfig(
                printers = listOf(
                    Printer(
                        id = id,
                        nickname = "Printer 1",
                        moonrakerUrl = printerUrl,
                        extruderPresets = extruderPresets,
                    )
                ),
                activeId = id,
            )
        }
        val data = BackupData(
            sliceConfig = sliceConfig,
            slicingOverrides = slicingOverrides,
            printersConfig = cfg,
            filamentProfiles = filamentProfiles.map { p ->
                // Resolve filament profile names for name-linking round-trips.
                // We cannot store names back into the FilamentProfile data class here
                // because that field doesn't exist; the resolver is used in exportExtruderPresets.
                p
            },
            processProfilesConfig = processProfilesConfig,
            makerWorldCookies = makerWorldCookies.takeIf { it.isNotEmpty() },
        )

        // We need to honour filamentNameResolver on extruder presets in the legacy JSON path.
        // Build the JSON via export(data) then patch the extruderPresets array with resolved names.
        val root = JSONObject(export(data))
        val presetsArr = exportExtruderPresetsWithNames(extruderPresets, filamentNameResolver)
        root.put("extruderPresets", presetsArr)
        return root.toString(2)
    }

    private fun exportSliceConfig(cfg: SliceConfig): JSONObject {
        return JSONObject().apply {
            put("layerHeight", cfg.layerHeight.toDouble())
            put("firstLayerHeight", cfg.firstLayerHeight.toDouble())
            put("perimeters", cfg.perimeters)
            put("topSolidLayers", cfg.topSolidLayers)
            put("bottomSolidLayers", cfg.bottomSolidLayers)
            put("fillDensity", cfg.fillDensity.toDouble())
            put("fillPattern", cfg.fillPattern)
            put("printSpeed", cfg.printSpeed.toDouble())
            put("travelSpeed", cfg.travelSpeed.toDouble())
            put("firstLayerSpeed", cfg.firstLayerSpeed.toDouble())
            put("nozzleTemp", cfg.nozzleTemp)
            put("bedTemp", cfg.bedTemp)
            put("retractLength", cfg.retractLength.toDouble())
            put("retractSpeed", cfg.retractSpeed.toDouble())
            put("supportEnabled", cfg.supportEnabled)
            put("supportAngle", cfg.supportAngle.toDouble())
            put("skirtLoops", cfg.skirtLoops)
            put("brimWidth", cfg.brimWidth.toDouble())
            put("nozzleDiameter", cfg.nozzleDiameter.toDouble())
            put("filamentDiameter", cfg.filamentDiameter.toDouble())
            put("filamentType", cfg.filamentType)
            put("wipeTowerEnabled", cfg.wipeTowerEnabled)
            put("wipeTowerX", cfg.wipeTowerX.toDouble())
            put("wipeTowerY", cfg.wipeTowerY.toDouble())
            put("wipeTowerWidth", cfg.wipeTowerWidth.toDouble())
        }
    }

    private fun parseSliceConfig(obj: JSONObject): SliceConfig {
        return SliceConfig(
            layerHeight = obj.optDouble("layerHeight", 0.2).toFloat(),
            firstLayerHeight = obj.optDouble("firstLayerHeight", 0.3).toFloat(),
            perimeters = obj.optInt("perimeters", 2),
            topSolidLayers = obj.optInt("topSolidLayers", 5),
            bottomSolidLayers = obj.optInt("bottomSolidLayers", 4),
            fillDensity = obj.optDouble("fillDensity", 0.15).toFloat(),
            fillPattern = obj.optString("fillPattern", "gyroid"),
            printSpeed = obj.optDouble("printSpeed", 60.0).toFloat(),
            travelSpeed = obj.optDouble("travelSpeed", 150.0).toFloat(),
            firstLayerSpeed = obj.optDouble("firstLayerSpeed", 20.0).toFloat(),
            nozzleTemp = obj.optInt("nozzleTemp", 210),
            bedTemp = obj.optInt("bedTemp", 60),
            retractLength = obj.optDouble("retractLength", 0.8).toFloat(),
            retractSpeed = obj.optDouble("retractSpeed", 45.0).toFloat(),
            supportEnabled = obj.optBoolean("supportEnabled", false),
            supportAngle = obj.optDouble("supportAngle", 45.0).toFloat(),
            skirtLoops = obj.optInt("skirtLoops", 0),
            brimWidth = obj.optDouble("brimWidth", 0.0).toFloat(),
            nozzleDiameter = obj.optDouble("nozzleDiameter", 0.4).toFloat(),
            filamentDiameter = obj.optDouble("filamentDiameter", 1.75).toFloat(),
            filamentType = obj.optString("filamentType", "PLA"),
            wipeTowerEnabled = obj.optBoolean("wipeTowerEnabled", false),
            wipeTowerX = obj.optDouble("wipeTowerX", 170.0).toFloat(),
            wipeTowerY = obj.optDouble("wipeTowerY", 140.0).toFloat(),
            wipeTowerWidth = obj.optDouble("wipeTowerWidth", 60.0).toFloat()
        )
    }

    /**
     * Serialize extruder presets using the v1 slot-based (1-based) format used in top-level
     * `extruderPresets` JSON key — for backward-compat reads by v1 readers.
     */
    private fun exportExtruderPresets(presets: List<ExtruderPreset>): JSONArray {
        return exportExtruderPresetsWithNames(presets) { null }
    }

    private fun exportExtruderPresetsWithNames(
        presets: List<ExtruderPreset>,
        filamentNameResolver: (Long) -> String? = { null }
    ): JSONArray {
        return JSONArray().apply {
            presets.forEach { p ->
                put(JSONObject().apply {
                    put("slot", p.index + 1) // 1-based in backup
                    put("color", p.color)
                    put("materialType", p.materialType)
                    p.filamentProfileId?.let { id ->
                        filamentNameResolver(id)?.let { name ->
                            put("filamentProfileName", name)
                        }
                    }
                })
            }
        }
    }

    data class ParsedExtruderPreset(
        val preset: ExtruderPreset,
        val filamentProfileName: String?
    )

    private fun parseExtruderPresetsArray(arr: JSONArray): List<ExtruderPreset> {
        return parseExtruderPresetsWithNames(arr).map { it.preset }
    }

    fun parseExtruderPresetsWithNames(arr: JSONArray): List<ParsedExtruderPreset> {
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ParsedExtruderPreset(
                preset = ExtruderPreset(
                    index = obj.optInt("slot", i + 1) - 1, // back to 0-based
                    color = obj.optString("color", "#FFFFFF"),
                    materialType = obj.optString("materialType", "PLA")
                ),
                filamentProfileName = if (obj.has("filamentProfileName")) obj.getString("filamentProfileName") else null
            )
        }
    }

    private fun exportFilamentProfiles(profiles: List<FilamentProfile>): JSONArray {
        return JSONArray().apply {
            profiles.forEach { p ->
                put(JSONObject().apply {
                    put("name", p.name)
                    put("material", p.material)
                    put("nozzleTemp", p.nozzleTemp)
                    put("bedTemp", p.bedTemp)
                    put("retractLength", p.retractLength.toDouble())
                    put("retractSpeed", p.retractSpeed.toDouble())
                    put("color", p.color)
                    put("density", p.density.toDouble())
                })
            }
        }
    }

    private fun parseFilamentProfilesArray(arr: JSONArray): List<FilamentProfile> {
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            FilamentProfile(
                name = obj.getString("name"),
                material = obj.optString("material", "PLA"),
                nozzleTemp = obj.optInt("nozzleTemp", 210),
                bedTemp = obj.optInt("bedTemp", 60),
                retractLength = obj.optDouble("retractLength", 0.8).toFloat(),
                retractSpeed = obj.optDouble("retractSpeed", 45.0).toFloat(),
                color = obj.optString("color", "#808080"),
                density = obj.optDouble("density", 1.24).toFloat()
            )
        }
    }
}
