package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup/restore all app settings as a single JSON file.
 * Schema version 1.
 */
object SettingsBackup {

    private const val VERSION = 1

    fun export(
        sliceConfig: SliceConfig,
        slicingOverrides: SlicingOverrides,
        printerUrl: String,
        extruderPresets: List<ExtruderPreset>,
        filamentProfiles: List<FilamentProfile>
    ): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("sliceConfig", exportSliceConfig(sliceConfig))
        root.put("slicingOverrides", JSONObject(slicingOverrides.toJson()))
        root.put("printerUrl", printerUrl)
        root.put("extruderPresets", exportExtruderPresets(extruderPresets))
        root.put("filamentProfiles", exportFilamentProfiles(filamentProfiles))
        return root.toString(2)
    }

    data class BackupData(
        val sliceConfig: SliceConfig?,
        val slicingOverrides: SlicingOverrides?,
        val printerUrl: String?,
        val extruderPresets: List<ExtruderPreset>?,
        val filamentProfiles: List<FilamentProfile>?
    )

    fun import(json: String): BackupData {
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        if (version < 1) throw IllegalArgumentException("Unknown backup version: $version")

        return BackupData(
            sliceConfig = root.optJSONObject("sliceConfig")?.let { parseSliceConfig(it) },
            slicingOverrides = root.optJSONObject("slicingOverrides")?.let { SlicingOverrides.fromJson(it.toString()) },
            printerUrl = if (root.has("printerUrl")) root.getString("printerUrl") else null,
            extruderPresets = root.optJSONArray("extruderPresets")?.let { parseExtruderPresetsArray(it) },
            filamentProfiles = root.optJSONArray("filamentProfiles")?.let { parseFilamentProfilesArray(it) }
        )
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
            skirtLoops = obj.optInt("skirtLoops", 1),
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

    private fun exportExtruderPresets(presets: List<ExtruderPreset>): JSONArray {
        return JSONArray().apply {
            presets.forEach { p ->
                put(JSONObject().apply {
                    put("slot", p.index + 1) // 1-based in backup
                    put("color", p.color)
                    put("materialType", p.materialType)
                })
            }
        }
    }

    private fun parseExtruderPresetsArray(arr: JSONArray): List<ExtruderPreset> {
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ExtruderPreset(
                index = obj.optInt("slot", i + 1) - 1, // back to 0-based
                color = obj.optString("color", "#FFFFFF"),
                materialType = obj.optString("materialType", "PLA")
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
                    put("printSpeed", p.printSpeed.toDouble())
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
                printSpeed = obj.optDouble("printSpeed", 60.0).toFloat(),
                retractLength = obj.optDouble("retractLength", 0.8).toFloat(),
                retractSpeed = obj.optDouble("retractSpeed", 45.0).toFloat(),
                color = obj.optString("color", "#808080"),
                density = obj.optDouble("density", 1.24).toFloat()
            )
        }
    }
}
