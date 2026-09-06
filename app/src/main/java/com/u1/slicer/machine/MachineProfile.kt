package com.u1.slicer.machine

import kotlin.math.round
import org.json.JSONArray
import org.json.JSONObject

enum class MachineFamily {
    SNAPMAKER,
    BAMBU,
    GENERIC
}

enum class GcodeFlavor {
    KLIPPER,
    MARLIN,
    OTHER;

    companion object {
        fun fromOrca(value: String?): GcodeFlavor = when (value?.trim()?.lowercase()) {
            "klipper" -> KLIPPER
            "marlin", "marlin2" -> MARLIN
            else -> OTHER
        }
    }
}

data class MachineProfile(
    val id: String,
    val displayName: String,
    val vendor: String,
    val printerModel: String,
    val printerVariant: String,
    val family: MachineFamily,
    val bedSizeX: Float,
    val bedSizeY: Float,
    val maxPrintHeight: Float,
    val extruderCount: Int,
    val nozzleDiameter: Float,
    val gcodeFlavor: GcodeFlavor,
    val singleExtruderMultiMaterial: Boolean,
    val machineStartGcode: String,
    val machineEndGcode: String,
    val machineChangeFilamentGcode: String,
    val machinePauseGcode: String,
    val machineJson: String,
    /** Maximum targets accepted by the app's manual Moonraker heater controls. */
    val maxNozzleTemperatureC: Int = DEFAULT_MAX_NOZZLE_TEMPERATURE_C,
    val maxBedTemperatureC: Int = DEFAULT_MAX_BED_TEMPERATURE_C,
    /** Optional app asset used to render this machine's physical bed surface. */
    val bedStlAsset: String? = null,
) {
    companion object {
        /**
         * Parses a fully resolved Orca machine preset. Presets with malformed geometry or
         * a non-machine type are not printable and therefore return null.
         */
        fun fromResolvedOrcaJson(json: String, family: MachineFamily): MachineProfile? {
            return try {
                val root = parseJson(json)
                if (root.optString("type") != "machine") return null

                val bounds = bounds(root.optJSONArray("printable_area") ?: return null) ?: return null
                val printerModel = root.optString("printer_model")
                val printerVariant = root.optString("printer_variant")
                val displayName = root.optString("name")
                val vendor = root.optString("vendor").ifBlank {
                    when (family) {
                        MachineFamily.SNAPMAKER -> "Snapmaker"
                        MachineFamily.BAMBU -> "Bambu Lab"
                        MachineFamily.GENERIC -> "Generic"
                    }
                }
                val nozzleDiameters = root.optJSONArray("nozzle_diameter")
                val extruderCount = nozzleDiameters?.length()?.takeIf { it > 0 } ?: 1
                val nozzleDiameter = nozzleDiameters?.optString(0)?.toFloatOrNull() ?: 0.4f

                MachineProfile(
                    id = stableId(vendor, printerModel, printerVariant, displayName),
                    displayName = displayName,
                    vendor = vendor,
                    printerModel = printerModel,
                    printerVariant = printerVariant,
                    family = family,
                    bedSizeX = bedSize(bounds.minX, bounds.maxX),
                    bedSizeY = bedSize(bounds.minY, bounds.maxY),
                    maxPrintHeight = root.optString("printable_height").toFloatOrNull() ?: 270f,
                    extruderCount = extruderCount,
                    nozzleDiameter = nozzleDiameter,
                    gcodeFlavor = GcodeFlavor.fromOrca(root.optString("gcode_flavor")),
                    singleExtruderMultiMaterial = root.optString("single_extruder_multi_material") in
                        setOf("1", "true", "yes"),
                    machineStartGcode = root.optString("machine_start_gcode"),
                    machineEndGcode = root.optString("machine_end_gcode"),
                    machineChangeFilamentGcode = root.optString(
                        "machine_change_filament_gcode",
                        root.optString("change_filament_gcode")
                    ),
                    machinePauseGcode = root.optString("machine_pause_gcode"),
                    machineJson = json,
                    maxNozzleTemperatureC = temperatureLimit(
                        root,
                        "nozzle_max_temperature",
                        "max_nozzle_temperature",
                        fallback = DEFAULT_MAX_NOZZLE_TEMPERATURE_C,
                    ),
                    maxBedTemperatureC = temperatureLimit(
                        root,
                        "bed_temperature_limit",
                        "max_bed_temperature",
                        fallback = DEFAULT_MAX_BED_TEMPERATURE_C,
                    ),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun parseJson(json: String): JSONObject {
            return try {
                JSONObject(json)
            } catch (_: Exception) {
                // Some Orca machine presets contain a repeated scalar property. Android's
                // JSONObject rejects duplicate keys, while Orca accepts the last value.
                val seen = HashSet<String>()
                val deduplicated = json.lineSequence().filter { line ->
                    val match = Regex("^\\s*\\\"([^\\\"]+)\\\"\\s*:").find(line)
                    if (match == null) {
                        true
                    } else {
                        seen.add(match.groupValues[1])
                    }
                }.joinToString("\n")
                JSONObject(deduplicated)
            }
        }

        private data class Bounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

        private fun bounds(area: JSONArray): Bounds? {
            if (area.length() == 0) return null
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (index in 0 until area.length()) {
                val point = area.optString(index)
                val separator = point.indexOf('x')
                if (separator <= 0 || separator == point.lastIndex) return null
                val x = point.substring(0, separator).toFloatOrNull() ?: return null
                val y = point.substring(separator + 1).toFloatOrNull() ?: return null
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
            return Bounds(minX, maxX, minY, maxY)
        }

        private fun bedSize(min: Float, max: Float): Float {
            val roundedMax = round(max)
            return if (min < 0f && max > roundedMax - 0.6f) {
                round(max + min).toFloat()
            } else {
                round(max - min).toFloat()
            }
        }

        private fun temperatureLimit(
            root: JSONObject,
            vararg keys: String,
            fallback: Int,
        ): Int = keys.firstNotNullOfOrNull { key ->
            root.optString(key).toIntOrNull()?.takeIf { it > 0 }
        } ?: fallback

        const val DEFAULT_MAX_NOZZLE_TEMPERATURE_C = 300
        const val DEFAULT_MAX_BED_TEMPERATURE_C = 120

        private fun stableId(vendor: String, model: String, variant: String, name: String): String {
            val modelWithoutVendor = model.trim().removePrefix(vendor.trim()).trim()
            val source = listOf(vendor, modelWithoutVendor, variant).filter { it.isNotBlank() }.joinToString("-")
                .ifBlank { name }
            return source.lowercase()
                .replace(Regex("[^a-z0-9.]+"), "-")
                .trim('-')
        }
    }
}
