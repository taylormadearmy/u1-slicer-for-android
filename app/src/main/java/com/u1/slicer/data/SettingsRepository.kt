package com.u1.slicer.data

import android.content.Context
import androidx.datastore.preferences.core.*
import com.u1.slicer.aipaint.AiPaintProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LAYER_HEIGHT = floatPreferencesKey("layer_height")
        val FIRST_LAYER_HEIGHT = floatPreferencesKey("first_layer_height")
        val PERIMETERS = intPreferencesKey("perimeters")
        val TOP_SOLID_LAYERS = intPreferencesKey("top_solid_layers")
        val BOTTOM_SOLID_LAYERS = intPreferencesKey("bottom_solid_layers")
        val FILL_DENSITY = floatPreferencesKey("fill_density")
        val FILL_PATTERN = stringPreferencesKey("fill_pattern")
        val PRINT_SPEED = floatPreferencesKey("print_speed")
        val TRAVEL_SPEED = floatPreferencesKey("travel_speed")
        val FIRST_LAYER_SPEED = floatPreferencesKey("first_layer_speed")
        val NOZZLE_TEMP = intPreferencesKey("nozzle_temp")
        val BED_TEMP = intPreferencesKey("bed_temp")
        val RETRACT_LENGTH = floatPreferencesKey("retract_length")
        val RETRACT_SPEED = floatPreferencesKey("retract_speed")
        val SUPPORT_ENABLED = booleanPreferencesKey("support_enabled")
        val SUPPORT_ANGLE = floatPreferencesKey("support_angle")
        val SKIRT_LOOPS = intPreferencesKey("skirt_loops")
        val BRIM_WIDTH = floatPreferencesKey("brim_width")
        val NOZZLE_DIAMETER = floatPreferencesKey("nozzle_diameter")
        val FILAMENT_DIAMETER = floatPreferencesKey("filament_diameter")
        val FILAMENT_TYPE = stringPreferencesKey("filament_type")
        val WIPE_TOWER_ENABLED = booleanPreferencesKey("wipe_tower_enabled")
        val WIPE_TOWER_X = floatPreferencesKey("wipe_tower_x")
        val WIPE_TOWER_Y = floatPreferencesKey("wipe_tower_y")
        val WIPE_TOWER_WIDTH = floatPreferencesKey("wipe_tower_width")
        val PRINTER_URL = stringPreferencesKey("printer_url")
        val EXTRUDER_PRESETS = stringPreferencesKey("extruder_presets")
        val SLICING_OVERRIDES = stringPreferencesKey("slicing_overrides")
        val MAKERWORLD_COOKIES = stringPreferencesKey("makerworld_cookies")
        val PLATE_TYPE = stringPreferencesKey("plate_type")
        val AI_PAINT_PROVIDER = stringPreferencesKey("ai_paint_provider")
        val AI_PAINT_API_KEY = stringPreferencesKey("ai_paint_api_key")
        val AI_NAMING_ENABLED = booleanPreferencesKey("ai_naming_enabled")
        val LIBRARY_MIXES = stringPreferencesKey("library_mixes")
        val PROJECT_MIXES = stringPreferencesKey("project_mixes")
        val FILAMENT_LIBRARY_FAVOURITES = stringPreferencesKey("filament_library_favourites")
        val FILAMENT_LIBRARY_RECENTS = stringPreferencesKey("filament_library_recents")
    }

    val sliceConfig: Flow<SliceConfig> = context.appDataStore.data.map { prefs ->
        SliceConfig(
            layerHeight = prefs[Keys.LAYER_HEIGHT] ?: 0.2f,
            firstLayerHeight = prefs[Keys.FIRST_LAYER_HEIGHT] ?: 0.3f,
            perimeters = prefs[Keys.PERIMETERS] ?: 2,
            topSolidLayers = prefs[Keys.TOP_SOLID_LAYERS] ?: 5,
            bottomSolidLayers = prefs[Keys.BOTTOM_SOLID_LAYERS] ?: 4,
            fillDensity = prefs[Keys.FILL_DENSITY] ?: 0.15f,
            fillPattern = prefs[Keys.FILL_PATTERN] ?: "gyroid",
            printSpeed = prefs[Keys.PRINT_SPEED] ?: 200f,
            travelSpeed = prefs[Keys.TRAVEL_SPEED] ?: 500f,
            firstLayerSpeed = prefs[Keys.FIRST_LAYER_SPEED] ?: 50f,
            nozzleTemp = prefs[Keys.NOZZLE_TEMP] ?: 210,
            bedTemp = prefs[Keys.BED_TEMP] ?: 60,
            retractLength = prefs[Keys.RETRACT_LENGTH] ?: 0.8f,
            retractSpeed = prefs[Keys.RETRACT_SPEED] ?: 45f,
            supportEnabled = prefs[Keys.SUPPORT_ENABLED] ?: false,
            supportAngle = prefs[Keys.SUPPORT_ANGLE] ?: 45f,
            // Force skirt_loops=0: old default was 1 which stomps file settings via
            // applyConfigToPrusa(). Snapmaker U1 profiles don't use skirts by default.
            skirtLoops = 0,
            brimWidth = prefs[Keys.BRIM_WIDTH] ?: 0f,
            nozzleDiameter = prefs[Keys.NOZZLE_DIAMETER] ?: 0.4f,
            filamentDiameter = prefs[Keys.FILAMENT_DIAMETER] ?: 1.75f,
            filamentType = prefs[Keys.FILAMENT_TYPE] ?: "PLA",
            wipeTowerEnabled = prefs[Keys.WIPE_TOWER_ENABLED] ?: false,
            wipeTowerX = prefs[Keys.WIPE_TOWER_X] ?: 170f,
            wipeTowerY = prefs[Keys.WIPE_TOWER_Y] ?: 140f,
            wipeTowerWidth = prefs[Keys.WIPE_TOWER_WIDTH] ?: 60f
        )
    }

    val printerUrl: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[Keys.PRINTER_URL] ?: ""
    }

    val extruderPresets: Flow<List<ExtruderPreset>> = context.appDataStore.data.map { prefs ->
        parseExtruderPresets(prefs[Keys.EXTRUDER_PRESETS] ?: "")
    }

    /** F78 migration helper — returns the raw JSON string for the legacy presets key,
     *  or empty string if the key is unset. */
    val extruderPresetsJson: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[Keys.EXTRUDER_PRESETS] ?: ""
    }

    val slicingOverrides: Flow<SlicingOverrides> = context.appDataStore.data.map { prefs ->
        val json = prefs[Keys.SLICING_OVERRIDES] ?: ""
        if (json.isNotEmpty()) SlicingOverrides.fromJson(json) else SlicingOverrides()
    }

    val plateType: Flow<PlateType> = context.appDataStore.data.map { prefs ->
        PlateType.fromName(prefs[Keys.PLATE_TYPE])
    }

    val aiPaintProvider: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[Keys.AI_PAINT_PROVIDER] ?: AiPaintProvider.DEFAULT.name
    }

    val aiPaintApiKey: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[Keys.AI_PAINT_API_KEY] ?: ""
    }

    /** Per-provider API key. Reads ONLY from the provider's own slot — no fallback to the
     *  legacy single-key slot, because that fallback caused every provider to inherit the
     *  same key (the leak fix23 was supposed to fix). Blank when nothing's been entered. */
    fun aiPaintApiKeyFor(providerName: String): Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[stringPreferencesKey("ai_paint_key_$providerName")] ?: ""
    }

    /** F54: when true the AI Paint pipeline calls the configured provider for region naming +
     *  colour suggestions on top of the deterministic cascade. Defaults to false; gating users
     *  out of the experimental AI path keeps results predictable. */
    val aiNamingEnabled: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[Keys.AI_NAMING_ENABLED] ?: false
    }

    suspend fun saveAiNamingEnabled(enabled: Boolean) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.AI_NAMING_ENABLED] = enabled
        }
    }

    suspend fun saveExtruderPresets(presets: List<ExtruderPreset>) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.EXTRUDER_PRESETS] = serializeExtruderPresets(presets)
        }
    }

    suspend fun saveSliceConfig(config: SliceConfig) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.LAYER_HEIGHT] = config.layerHeight
            prefs[Keys.FIRST_LAYER_HEIGHT] = config.firstLayerHeight
            prefs[Keys.PERIMETERS] = config.perimeters
            prefs[Keys.TOP_SOLID_LAYERS] = config.topSolidLayers
            prefs[Keys.BOTTOM_SOLID_LAYERS] = config.bottomSolidLayers
            prefs[Keys.FILL_DENSITY] = config.fillDensity
            prefs[Keys.FILL_PATTERN] = config.fillPattern
            prefs[Keys.PRINT_SPEED] = config.printSpeed
            prefs[Keys.TRAVEL_SPEED] = config.travelSpeed
            prefs[Keys.FIRST_LAYER_SPEED] = config.firstLayerSpeed
            prefs[Keys.NOZZLE_TEMP] = config.nozzleTemp
            prefs[Keys.BED_TEMP] = config.bedTemp
            prefs[Keys.RETRACT_LENGTH] = config.retractLength
            prefs[Keys.RETRACT_SPEED] = config.retractSpeed
            prefs[Keys.SUPPORT_ENABLED] = config.supportEnabled
            prefs[Keys.SUPPORT_ANGLE] = config.supportAngle
            prefs[Keys.SKIRT_LOOPS] = config.skirtLoops
            prefs[Keys.BRIM_WIDTH] = config.brimWidth
            prefs[Keys.NOZZLE_DIAMETER] = config.nozzleDiameter
            prefs[Keys.FILAMENT_DIAMETER] = config.filamentDiameter
            prefs[Keys.FILAMENT_TYPE] = config.filamentType
            prefs[Keys.WIPE_TOWER_ENABLED] = config.wipeTowerEnabled
            prefs[Keys.WIPE_TOWER_X] = config.wipeTowerX
            prefs[Keys.WIPE_TOWER_Y] = config.wipeTowerY
            prefs[Keys.WIPE_TOWER_WIDTH] = config.wipeTowerWidth
        }
    }

    suspend fun savePlateType(plateType: PlateType) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.PLATE_TYPE] = plateType.name
        }
    }

    suspend fun saveSlicingOverrides(overrides: SlicingOverrides) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.SLICING_OVERRIDES] = overrides.toJson()
        }
    }

    suspend fun savePrinterUrl(url: String) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.PRINTER_URL] = url
        }
    }

    val makerWorldCookies: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[Keys.MAKERWORLD_COOKIES] ?: ""
    }

    suspend fun saveMakerWorldCookies(cookies: String) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.MAKERWORLD_COOKIES] = cookies
        }
    }

    /** Save just the selected provider, without touching any API keys. Called when the user
     *  picks a different provider in the dropdown — the key field should then auto-load that
     *  provider's stored key (or be blank if none). */
    suspend fun saveAiPaintProvider(provider: String) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.AI_PAINT_PROVIDER] = provider
        }
    }

    /** Save just the API key for a specific provider. Each provider's key is independent. */
    suspend fun saveAiPaintKey(provider: String, apiKey: String) {
        context.appDataStore.edit { prefs ->
            prefs[stringPreferencesKey("ai_paint_key_$provider")] = apiKey
        }
    }

    @Deprecated("Bundled save leaks the key across providers; use saveAiPaintProvider and " +
        "saveAiPaintKey separately so switching providers doesn't copy the visible key.")
    suspend fun saveAiPaintSettings(provider: String, apiKey: String) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.AI_PAINT_PROVIDER] = provider
            prefs[stringPreferencesKey("ai_paint_key_$provider")] = apiKey
        }
    }

    val libraryMixesFlow: Flow<List<MixedFilamentRow>> = context.appDataStore.data.map { prefs ->
        decodeLibraryMixes(prefs[Keys.LIBRARY_MIXES] ?: "")
    }

    suspend fun setLibraryMixes(rows: List<MixedFilamentRow>) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.LIBRARY_MIXES] = encodeLibraryMixes(rows)
        }
    }

    /**
     * Project-scoped mix slots. Moved from SessionState to SettingsRepository in
     * the M3-A bugfix round: the original SessionState-only persistence path
     * silently dropped saves when no model was loaded (saveProject's
     * `if (current != null)` guard), so a mix created from the Filaments tab on
     * cold-start app open did not survive an app kill. SettingsRepository is
     * always available regardless of model-loaded state.
     *
     * Trade-off: project mixes now persist across model swaps instead of
     * resetting. That's a deliberate UX simplification for v1; the
     * project-vs-library distinction in the UI is "recently active" vs
     * "starred for reuse" rather than "per-model" vs "global".
     */
    val projectMixesFlow: Flow<List<MixedFilamentRow>> = context.appDataStore.data.map { prefs ->
        decodeLibraryMixes(prefs[Keys.PROJECT_MIXES] ?: "")
    }

    suspend fun setProjectMixes(rows: List<MixedFilamentRow>) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.PROJECT_MIXES] = encodeLibraryMixes(rows)
        }
    }

    val filamentLibraryFavourites: Flow<List<String>> = context.appDataStore.data.map { prefs ->
        decodeSlugList(prefs[Keys.FILAMENT_LIBRARY_FAVOURITES] ?: "")
    }

    val filamentLibraryRecents: Flow<List<String>> = context.appDataStore.data.map { prefs ->
        decodeSlugList(prefs[Keys.FILAMENT_LIBRARY_RECENTS] ?: "")
    }

    suspend fun setFilamentLibraryFavourites(slugs: List<String>) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.FILAMENT_LIBRARY_FAVOURITES] = encodeSlugList(slugs)
        }
    }

    suspend fun setFilamentLibraryRecents(slugs: List<String>) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.FILAMENT_LIBRARY_RECENTS] = encodeSlugList(slugs)
        }
    }

    companion object {

        internal fun encodeLibraryMixes(rows: List<MixedFilamentRow>): String {
            if (rows.isEmpty()) return ""
            val arr = org.json.JSONArray()
            for (r in rows) {
                arr.put(org.json.JSONObject().apply {
                    put("id", r.id)
                    put("components", org.json.JSONArray(r.components))
                    put("weights", org.json.JSONArray(r.weights))
                    // legacy 2-way mirror so older builds still read these rows
                    put("componentA", r.componentA)
                    put("componentB", r.componentB)
                    put("mixBPercent", r.mixBPercent)
                    put("distributionMode", r.distributionMode.name)
                    put("label", r.label)
                    put("inLibrary", r.inLibrary)
                })
            }
            return arr.toString()
        }

        internal fun decodeLibraryMixes(encoded: String): List<MixedFilamentRow> {
            if (encoded.isEmpty()) return emptyList()
            return try {
                val arr = org.json.JSONArray(encoded)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val mode = MixedFilamentRow.MixDistributionMode.valueOf(o.getString("distributionMode"))
                    val comps = o.optJSONArray("components")
                    if (comps != null) {
                        val weightsArr = o.getJSONArray("weights")
                        MixedFilamentRow(
                            id = o.getLong("id"),
                            components = (0 until comps.length()).map { comps.getInt(it) },
                            weights = (0 until weightsArr.length()).map { weightsArr.getInt(it) },
                            distributionMode = mode,
                            label = o.getString("label"),
                            inLibrary = o.getBoolean("inLibrary"),
                        )
                    } else {
                        MixedFilamentRow.fromLegacy(
                            id = o.getLong("id"),
                            componentA = o.getInt("componentA"),
                            componentB = o.getInt("componentB"),
                            mixBPercent = o.getInt("mixBPercent"),
                            distributionMode = mode,
                            label = o.getString("label"),
                            inLibrary = o.getBoolean("inLibrary"),
                        )
                    }
                }
            } catch (e: org.json.JSONException) {
                emptyList()
            }
        }
    }

}

internal fun encodeSlugList(slugs: List<String>): String =
    org.json.JSONArray().apply { slugs.forEach { put(it) } }.toString()

internal fun decodeSlugList(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}
