package com.u1.slicer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "u1_slicer_settings")

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
    }

    val sliceConfig: Flow<SliceConfig> = context.dataStore.data.map { prefs ->
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
            skirtLoops = prefs[Keys.SKIRT_LOOPS] ?: 1,
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

    val printerUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.PRINTER_URL] ?: ""
    }

    val extruderPresets: Flow<List<ExtruderPreset>> = context.dataStore.data.map { prefs ->
        parseExtruderPresets(prefs[Keys.EXTRUDER_PRESETS] ?: "")
    }

    val slicingOverrides: Flow<SlicingOverrides> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.SLICING_OVERRIDES] ?: ""
        if (json.isNotEmpty()) SlicingOverrides.fromJson(json) else SlicingOverrides()
    }

    suspend fun saveExtruderPresets(presets: List<ExtruderPreset>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EXTRUDER_PRESETS] = serializeExtruderPresets(presets)
        }
    }

    suspend fun saveSliceConfig(config: SliceConfig) {
        context.dataStore.edit { prefs ->
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

    suspend fun saveSlicingOverrides(overrides: SlicingOverrides) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SLICING_OVERRIDES] = overrides.toJson()
        }
    }

    suspend fun savePrinterUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PRINTER_URL] = url
        }
    }

    val makerWorldCookies: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.MAKERWORLD_COOKIES] ?: ""
    }

    suspend fun saveMakerWorldCookies(cookies: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MAKERWORLD_COOKIES] = cookies
        }
    }
}
