package com.u1.slicer.data

import org.json.JSONObject

/**
 * Per-setting override mode for the slicing pipeline.
 *
 * - USE_FILE: Use the value embedded in the 3MF / profile (no override)
 * - ORCA_DEFAULT: Force OrcaSlicer's factory default
 * - OVERRIDE: Use the user-specified value
 */
enum class OverrideMode { USE_FILE, ORCA_DEFAULT, OVERRIDE }

data class OverrideValue<T>(
    val mode: OverrideMode = OverrideMode.USE_FILE,
    val value: T? = null
)

/**
 * User-configurable overrides for slicing parameters.
 * Each field can be USE_FILE (passthrough), ORCA_DEFAULT, or OVERRIDE with a value.
 * Persisted as JSON in DataStore.
 */
data class SlicingOverrides(
    val layerHeight: OverrideValue<Float> = OverrideValue(),
    val infillDensity: OverrideValue<Float> = OverrideValue(),
    val wallCount: OverrideValue<Int> = OverrideValue(),
    val infillPattern: OverrideValue<String> = OverrideValue(),
    val supports: OverrideValue<Boolean> = OverrideValue(),
    val brimWidth: OverrideValue<Float> = OverrideValue(),
    val skirtLoops: OverrideValue<Int> = OverrideValue(),
    val bedTemp: OverrideValue<Int> = OverrideValue(),
    val primeTower: OverrideValue<Boolean> = OverrideValue(),
    val flowCalibration: Boolean = true
) {
    fun toJson(): String {
        val obj = JSONObject()
        fun <T> putOverride(key: String, ov: OverrideValue<T>) {
            val o = JSONObject()
            o.put("mode", ov.mode.name)
            if (ov.value != null) o.put("value", ov.value)
            obj.put(key, o)
        }
        putOverride("layerHeight", layerHeight)
        putOverride("infillDensity", infillDensity)
        putOverride("wallCount", wallCount)
        putOverride("infillPattern", infillPattern)
        putOverride("supports", supports)
        putOverride("brimWidth", brimWidth)
        putOverride("skirtLoops", skirtLoops)
        putOverride("bedTemp", bedTemp)
        putOverride("primeTower", primeTower)
        obj.put("flowCalibration", flowCalibration)
        return obj.toString()
    }

    companion object {
        // OrcaSlicer factory defaults for ORCA_DEFAULT mode
        val ORCA_DEFAULTS = mapOf(
            "layerHeight" to 0.2f,
            "infillDensity" to 0.15f,
            "wallCount" to 2,
            "infillPattern" to "gyroid",
            "supports" to false,
            "brimWidth" to 0f,
            "skirtLoops" to 0,
            "bedTemp" to 60,
            "primeTower" to false
        )

        fun fromJson(json: String): SlicingOverrides {
            return try {
                val obj = JSONObject(json)
                fun <T> parseOverride(key: String, parse: (Any) -> T): OverrideValue<T> {
                    val o = obj.optJSONObject(key) ?: return OverrideValue()
                    val mode = try { OverrideMode.valueOf(o.getString("mode")) }
                               catch (_: Exception) { OverrideMode.USE_FILE }
                    val value = if (o.has("value")) try { parse(o.get("value")) }
                                catch (_: Exception) { null } else null
                    return OverrideValue(mode, value)
                }
                SlicingOverrides(
                    layerHeight = parseOverride("layerHeight") { (it as Number).toFloat() },
                    infillDensity = parseOverride("infillDensity") { (it as Number).toFloat() },
                    wallCount = parseOverride("wallCount") { (it as Number).toInt() },
                    infillPattern = parseOverride("infillPattern") { it.toString() },
                    supports = parseOverride("supports") { it as Boolean },
                    brimWidth = parseOverride("brimWidth") { (it as Number).toFloat() },
                    skirtLoops = parseOverride("skirtLoops") { (it as Number).toInt() },
                    bedTemp = parseOverride("bedTemp") { (it as Number).toInt() },
                    primeTower = parseOverride("primeTower") { it as Boolean },
                    flowCalibration = obj.optBoolean("flowCalibration", true)
                )
            } catch (_: Exception) {
                SlicingOverrides()
            }
        }
    }
}
