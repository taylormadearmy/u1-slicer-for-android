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
    // Shell / surface (F31)
    val topShellLayers: OverrideValue<Int> = OverrideValue(),
    val bottomShellLayers: OverrideValue<Int> = OverrideValue(),
    val topSurfacePattern: OverrideValue<String> = OverrideValue(),
    val bottomSurfacePattern: OverrideValue<String> = OverrideValue(),
    val sparseInfillSpeed: OverrideValue<Int> = OverrideValue(),
    // F41: Reduce infill retraction - default off for Snapmaker U1
    val reduceInfillRetraction: OverrideValue<Boolean> = OverrideValue(),
    // F42: Wall generator and seam position
    val wallGenerator: OverrideValue<String> = OverrideValue(),
    val seamPosition: OverrideValue<String> = OverrideValue(),
    val supports: OverrideValue<Boolean> = OverrideValue(),
    val supportType: OverrideValue<String> = OverrideValue(),
    val supportAngle: OverrideValue<Int> = OverrideValue(),
    val supportBuildPlateOnly: OverrideValue<Boolean> = OverrideValue(),
    val supportPattern: OverrideValue<String> = OverrideValue(),
    val supportPatternSpacing: OverrideValue<Float> = OverrideValue(),
    val supportInterfaceTopLayers: OverrideValue<Int> = OverrideValue(),
    val supportInterfaceBottomLayers: OverrideValue<Int> = OverrideValue(),
    val supportFilament: OverrideValue<Int> = OverrideValue(),
    val supportInterfaceFilament: OverrideValue<Int> = OverrideValue(),
    // Extended support settings (F30)
    val supportXyDistance: OverrideValue<Float> = OverrideValue(),
    val supportInterfacePattern: OverrideValue<String> = OverrideValue(),
    val supportInterfaceSpacing: OverrideValue<Float> = OverrideValue(),
    val supportSpeed: OverrideValue<Int> = OverrideValue(),
    // Tree support parameters (shown only when support type is tree)
    val treeSupportBranchAngle: OverrideValue<Int> = OverrideValue(),
    val treeSupportBranchDistance: OverrideValue<Float> = OverrideValue(),
    val treeSupportBranchDiameter: OverrideValue<Float> = OverrideValue(),
    val brimWidth: OverrideValue<Float> = OverrideValue(),
    val skirtLoops: OverrideValue<Int> = OverrideValue(),
    val bedTemp: OverrideValue<Int> = OverrideValue(),
    val primeTower: OverrideValue<Boolean> = OverrideValue(),
    // Prime tower detail overrides (go through ProfileEmbedder JSON, not JNI)
    val primeVolume: OverrideValue<Int> = OverrideValue(),
    val primeTowerBrimWidth: OverrideValue<Float> = OverrideValue(),
    val primeTowerBrimChamfer: OverrideValue<Boolean> = OverrideValue(),
    val primeTowerChamferMaxWidth: OverrideValue<Float> = OverrideValue(),
    val primeTowerWidth: OverrideValue<Float> = OverrideValue(),
    val wipeTowerRotationAngle: OverrideValue<Float> = OverrideValue(),
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
            layerHeight      = res(layerHeight,       base.layerHeight,       "layerHeight"),
            fillDensity      = res(infillDensity,     base.fillDensity,       "infillDensity"),
            perimeters       = res(wallCount,         base.perimeters,        "wallCount"),
            fillPattern      = res(infillPattern,     base.fillPattern,       "infillPattern"),
            topSolidLayers   = res(topShellLayers,    base.topSolidLayers,    "topShellLayers"),
            bottomSolidLayers= res(bottomShellLayers, base.bottomSolidLayers, "bottomShellLayers"),
            supportEnabled   = res(supports,          base.supportEnabled,    "supports"),
            supportType      = res(supportType,       base.supportType,       "supportType"),
            supportAngle     = res(supportAngle,      base.supportAngle.toInt(), "supportAngle").toFloat(),
            brimWidth        = res(brimWidth,          base.brimWidth,         "brimWidth"),
            skirtLoops       = res(skirtLoops,        base.skirtLoops,        "skirtLoops"),
            bedTemp          = res(bedTemp,           base.bedTemp,           "bedTemp"),
            // Multi-extruder slicing requires a wipe tower to produce T1 tool changes.
            // Only bypass if the user has explicitly set OVERRIDE mode to false.
            wipeTowerEnabled = if (base.extruderCount > 1 && primeTower.mode != OverrideMode.OVERRIDE)
                true
            else
                res(primeTower, base.wipeTowerEnabled, "primeTower"),
            wipeTowerWidth = res(primeTowerWidth, base.wipeTowerWidth, "primeTowerWidth"),
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
        putOverride("topShellLayers", topShellLayers)
        putOverride("bottomShellLayers", bottomShellLayers)
        putOverride("topSurfacePattern", topSurfacePattern)
        putOverride("bottomSurfacePattern", bottomSurfacePattern)
        putOverride("sparseInfillSpeed", sparseInfillSpeed)
        putOverride("reduceInfillRetraction", reduceInfillRetraction)
        putOverride("wallGenerator", wallGenerator)
        putOverride("seamPosition", seamPosition)
        putOverride("supports", supports)
        putOverride("supportType", supportType)
        putOverride("supportAngle", supportAngle)
        putOverride("supportBuildPlateOnly", supportBuildPlateOnly)
        putOverride("supportPattern", supportPattern)
        putOverride("supportPatternSpacing", supportPatternSpacing)
        putOverride("supportInterfaceTopLayers", supportInterfaceTopLayers)
        putOverride("supportInterfaceBottomLayers", supportInterfaceBottomLayers)
        putOverride("supportFilament", supportFilament)
        putOverride("supportInterfaceFilament", supportInterfaceFilament)
        putOverride("supportXyDistance", supportXyDistance)
        putOverride("supportInterfacePattern", supportInterfacePattern)
        putOverride("supportInterfaceSpacing", supportInterfaceSpacing)
        putOverride("supportSpeed", supportSpeed)
        putOverride("treeSupportBranchAngle", treeSupportBranchAngle)
        putOverride("treeSupportBranchDistance", treeSupportBranchDistance)
        putOverride("treeSupportBranchDiameter", treeSupportBranchDiameter)
        putOverride("brimWidth", brimWidth)
        putOverride("skirtLoops", skirtLoops)
        putOverride("bedTemp", bedTemp)
        putOverride("primeTower", primeTower)
        putOverride("primeVolume", primeVolume)
        putOverride("primeTowerBrimWidth", primeTowerBrimWidth)
        putOverride("primeTowerBrimChamfer", primeTowerBrimChamfer)
        putOverride("primeTowerChamferMaxWidth", primeTowerChamferMaxWidth)
        putOverride("primeTowerWidth", primeTowerWidth)
        putOverride("wipeTowerRotationAngle", wipeTowerRotationAngle)
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
            "topShellLayers" to 5,
            "bottomShellLayers" to 4,
            "topSurfacePattern" to "monotonic",
            "bottomSurfacePattern" to "monotonic",
            "sparseInfillSpeed" to 0,
            "reduceInfillRetraction" to false,
            "wallGenerator" to "arachne",
            "seamPosition" to "aligned",
            "supports" to false,
            "supportType" to "normal(auto)",
            "supportAngle" to 30,
            "supportBuildPlateOnly" to false,
            "supportPattern" to "default",
            "supportPatternSpacing" to 2.5f,
            "supportInterfaceTopLayers" to 3,
            "supportInterfaceBottomLayers" to 0,
            "supportFilament" to 0,
            "supportInterfaceFilament" to 0,
            "supportXyDistance" to 0.35f,
            "supportInterfacePattern" to "auto",
            "supportInterfaceSpacing" to 0.5f,
            "supportSpeed" to 0,
            "treeSupportBranchAngle" to 40,
            "treeSupportBranchDistance" to 5.0f,
            "treeSupportBranchDiameter" to 5.0f,
            "brimWidth" to 0f,
            "skirtLoops" to 0,
            "bedTemp" to 60,
            "primeTower" to false,
            "primeVolume" to 45,
            "primeTowerBrimWidth" to 3f,
            "primeTowerBrimChamfer" to true,
            "primeTowerChamferMaxWidth" to 5f,
            "primeTowerWidth" to 35f,
            "wipeTowerRotationAngle" to 0f
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
                    topShellLayers = parseOverride("topShellLayers") { (it as Number).toInt() },
                    bottomShellLayers = parseOverride("bottomShellLayers") { (it as Number).toInt() },
                    topSurfacePattern = parseOverride("topSurfacePattern") { it.toString() },
                    bottomSurfacePattern = parseOverride("bottomSurfacePattern") { it.toString() },
                    sparseInfillSpeed = parseOverride("sparseInfillSpeed") { (it as Number).toInt() },
                    reduceInfillRetraction = parseOverride("reduceInfillRetraction") { it as Boolean },
                    wallGenerator = parseOverride("wallGenerator") { it.toString() },
                    seamPosition = parseOverride("seamPosition") { it.toString() },
                    supports = parseOverride("supports") { it as Boolean },
                    supportType = parseOverride("supportType") { it.toString() },
                    supportAngle = parseOverride("supportAngle") { (it as Number).toInt() },
                    supportBuildPlateOnly = parseOverride("supportBuildPlateOnly") { it as Boolean },
                    supportPattern = parseOverride("supportPattern") { it.toString() },
                    supportPatternSpacing = parseOverride("supportPatternSpacing") { (it as Number).toFloat() },
                    supportInterfaceTopLayers = parseOverride("supportInterfaceTopLayers") { (it as Number).toInt() },
                    supportInterfaceBottomLayers = parseOverride("supportInterfaceBottomLayers") { (it as Number).toInt() },
                    supportFilament = parseOverride("supportFilament") { (it as Number).toInt() },
                    supportInterfaceFilament = parseOverride("supportInterfaceFilament") { (it as Number).toInt() },
                    supportXyDistance = parseOverride("supportXyDistance") { (it as Number).toFloat() },
                    supportInterfacePattern = parseOverride("supportInterfacePattern") { it.toString() },
                    supportInterfaceSpacing = parseOverride("supportInterfaceSpacing") { (it as Number).toFloat() },
                    supportSpeed = parseOverride("supportSpeed") { (it as Number).toInt() },
                    treeSupportBranchAngle = parseOverride("treeSupportBranchAngle") { (it as Number).toInt() },
                    treeSupportBranchDistance = parseOverride("treeSupportBranchDistance") { (it as Number).toFloat() },
                    treeSupportBranchDiameter = parseOverride("treeSupportBranchDiameter") { (it as Number).toFloat() },
                    brimWidth = parseOverride("brimWidth") { (it as Number).toFloat() },
                    skirtLoops = parseOverride("skirtLoops") { (it as Number).toInt() },
                    bedTemp = parseOverride("bedTemp") { (it as Number).toInt() },
                    primeTower = parseOverride("primeTower") { it as Boolean },
                    primeVolume = parseOverride("primeVolume") { (it as Number).toInt() },
                    primeTowerBrimWidth = parseOverride("primeTowerBrimWidth") { (it as Number).toFloat() },
                    primeTowerBrimChamfer = parseOverride("primeTowerBrimChamfer") { it as Boolean },
                    primeTowerChamferMaxWidth = parseOverride("primeTowerChamferMaxWidth") { (it as Number).toFloat() },
                    primeTowerWidth = parseOverride("primeTowerWidth") { (it as Number).toFloat() },
                    wipeTowerRotationAngle = parseOverride("wipeTowerRotationAngle") { (it as Number).toFloat() },
                    flowCalibration = obj.optBoolean("flowCalibration", true)
                )
            } catch (_: Exception) {
                SlicingOverrides()
            }
        }
    }
}
