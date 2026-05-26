package com.u1.slicer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "filament_profiles")
data class FilamentProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val material: String,        // PLA, PETG, ABS, TPU, ASA, PA, PVA
    val nozzleTemp: Int,
    val bedTemp: Int,
    val retractLength: Float,
    val retractSpeed: Float,
    val color: String = "#808080", // Hex color for UI display
    val density: Float = 1.24f,    // g/cm3 for weight estimation
    val isDefault: Boolean = false,
    // F91: extended OrcaSlicer filament settings. All nullable — when null, the bundled
    // Snapmaker filament profile default (and ultimately OrcaSlicer's compiled default)
    // stands. Each field maps 1:1 onto a `profile_keys[]`-whitelisted key in sapil_print.cpp,
    // so populating a field is sufficient for slice-time effect — no native rebuild needed.
    val nozzleTempInitialLayer: Int? = null,             // nozzle_temperature_initial_layer
    val bedTempInitialLayer: Int? = null,                // hot_plate_temp_initial_layer
    val flowRatio: Float? = null,                        // filament_flow_ratio (e.g. 1.0)
    val maxVolumetricSpeed: Float? = null,               // filament_max_volumetric_speed (mm^3/s)
    val filamentCost: Float? = null,                     // filament_cost (per kg)
    val fanMinSpeed: Int? = null,                        // fan_min_speed (%)
    val fanMaxSpeed: Int? = null,                        // fan_max_speed (%)
    val overhangFanSpeed: Int? = null,                   // overhang_fan_speed (%)
    val additionalCoolingFanSpeed: Int? = null,          // additional_cooling_fan_speed (%)
    val slowDownLayerTime: Float? = null,                // slow_down_layer_time (s)
    val slowDownMinSpeed: Float? = null,                 // slow_down_min_speed (mm/s)
    val closeFanFirstLayers: Int? = null,                // close_fan_the_first_x_layers
    val fullFanSpeedLayer: Int? = null,                  // full_fan_speed_layer
    val enablePressureAdvance: Boolean? = null,          // enable_pressure_advance
    val pressureAdvance: Float? = null,                  // pressure_advance
    val filamentMinimalPurgeOnWipeTower: Float? = null,  // filament_minimal_purge_on_wipe_tower
)
