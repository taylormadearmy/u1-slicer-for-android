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
    // Prime tower detail overrides (go through ProfileEmbedder JSON, not JNI)
    val primeVolume: OverrideValue<Int> = OverrideValue(),
    val primeTowerBrimWidth: OverrideValue<Float> = OverrideValue(),
    val primeTowerBrimChamfer: OverrideValue<Boolean> = OverrideValue(),
    val primeTowerChamferMaxWidth: OverrideValue<Float> = OverrideValue(),
    val flowCalibration: Boolean = true
) {
    /**
     * Resolve all override modes into [base], producing the effective SliceConfig for slicing.
     *
     * - USE_FILE   → keeps the value already in [base] (loaded profile / user UI setting)
     * - ORCA_DEFAULT → replaces with OrcaSlicer factory default from [ORCA_DEFAULTS]
     * - OVERRIDE   → replaces with the user-specified value (falls back to [base] if null)
     *
     * Prime-tower detail overrides (primeVolume etc.) go through ProfileEmbedder's embedded
     * JSON path and are NOT handled here — they don't have corresponding SliceConfig fields.
     */
    /**
     * Resolve the prime tower setting for the embedded profile JSON.
     * This mirrors the [resolveInto] guard: for multi-extruder jobs, ORCA_DEFAULT and USE_FILE
     * must NOT disable the wipe tower — only an explicit OVERRIDE false is honoured.
     *
     * @param extCount number of extruders being used
     * @param cfgWipeTower current [SliceConfig.wipeTowerEnabled] value
     */
    fun resolvePrimeTower(extCount: Int, cfgWipeTower: Boolean): Boolean {
        if (extCount > 1 && primeTower.mode != OverrideMode.OVERRIDE) return true
        return when (primeTower.mode) {
            OverrideMode.USE_FILE     -> cfgWipeTower
            OverrideMode.ORCA_DEFAULT -> @Suppress("UNCHECKED_CAST")
                (ORCA_DEFAULTS["primeTower"] as? Boolean) ?: cfgWipeTower
            OverrideMode.OVERRIDE     -> primeTower.value ?: cfgWipeTower
        }
    }

    fun resolveInto(base: SliceConfig): SliceConfig {
        fun <T> res(ov: OverrideValue<T>, baseVal: T, defaultKey: String): T =
            when (ov.mode) {
                OverrideMode.USE_FILE    -> baseVal
                OverrideMode.ORCA_DEFAULT -> @Suppress("UNCHECKED_CAST")
                    (ORCA_DEFAULTS[defaultKey] as? T) ?: baseVal
                OverrideMode.OVERRIDE   -> ov.value ?: baseVal
            }

        return base.copy(
            layerHeight      = res(layerHeight,   base.layerHeight,      "layerHeight"),
            fillDensity      = res(infillDensity, base.fillDensity,      "infillDensity"),
            perimeters       = res(wallCount,     base.perimeters,       "wallCount"),
            fillPattern      = res(infillPattern, base.fillPattern,      "infillPattern"),
            supportEnabled   = res(supports,      base.supportEnabled,   "supports"),
            brimWidth        = res(brimWidth,      base.brimWidth,        "brimWidth"),
            skirtLoops       = res(skirtLoops,    base.skirtLoops,       "skirtLoops"),
            bedTemp          = res(bedTemp,       base.bedTemp,          "bedTemp"),
            // Multi-extruder slicing requires a wipe tower to produce T1 tool changes.
            // Only bypass if the user has explicitly set OVERRIDE mode to false.
            wipeTowerEnabled = if (base.extruderCount > 1 && primeTower.mode != OverrideMode.OVERRIDE)
                true
            else
                res(primeTower, base.wipeTowerEnabled, "primeTower"),
        )
    }

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
        putOverride("primeVolume", primeVolume)
        putOverride("primeTowerBrimWidth", primeTowerBrimWidth)
        putOverride("primeTowerBrimChamfer", primeTowerBrimChamfer)
        putOverride("primeTowerChamferMaxWidth", primeTowerChamferMaxWidth)
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
            "primeTower" to false,
            "primeVolume" to 45,
            "primeTowerBrimWidth" to 3f,
            "primeTowerBrimChamfer" to true,
            "primeTowerChamferMaxWidth" to 5f
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
                    primeVolume = parseOverride("primeVolume") { (it as Number).toInt() },
                    primeTowerBrimWidth = parseOverride("primeTowerBrimWidth") { (it as Number).toFloat() },
                    primeTowerBrimChamfer = parseOverride("primeTowerBrimChamfer") { it as Boolean },
                    primeTowerChamferMaxWidth = parseOverride("primeTowerChamferMaxWidth") { (it as Number).toFloat() },
                    flowCalibration = obj.optBoolean("flowCalibration", true)
                )
            } catch (_: Exception) {
                SlicingOverrides()
            }
        }
    }
}
