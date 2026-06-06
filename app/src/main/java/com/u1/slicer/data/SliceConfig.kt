package com.u1.slicer.data

/**
 * Mirrors sapil::SliceConfig in C++.
 * Maps to PrusaSlicer's DynamicPrintConfig.
 */
data class SliceConfig(
    // Print settings
    @JvmField var layerHeight: Float = 0.2f,
    @JvmField var firstLayerHeight: Float = 0.3f,
    @JvmField var perimeters: Int = 2,
    @JvmField var topSolidLayers: Int = 5,
    @JvmField var bottomSolidLayers: Int = 4,
    @JvmField var fillDensity: Float = 0.15f,
    @JvmField var fillPattern: String = "gyroid",

    // Speed (mm/s) — Snapmaker U1 defaults matching standard_0.20mm.json process profile.
    // printSpeed sets outer_wall_speed (inner wall, infill, travel use profile values).
    @JvmField var printSpeed: Float = 200f,
    @JvmField var travelSpeed: Float = 500f,
    @JvmField var firstLayerSpeed: Float = 50f,

    // Temperature
    @JvmField var nozzleTemp: Int = 210,
    @JvmField var bedTemp: Int = 60,

    // Retraction
    @JvmField var retractLength: Float = 0.8f,
    @JvmField var retractSpeed: Float = 45f,

    // Support
    @JvmField var supportEnabled: Boolean = false,
    @JvmField var supportType: String = "normal",
    @JvmField var supportAngle: Float = 45f,
    @JvmField var supportFilament: Int = 0,
    @JvmField var supportInterfaceFilament: Int = 0,

    // Skirt/Brim
    @JvmField var skirtLoops: Int = 0,
    @JvmField var skirtDistance: Float = 6f,
    @JvmField var brimWidth: Float = 0f,

    // Printer bed (Snapmaker U1: 270x270x270mm)
    @JvmField var bedSizeX: Float = 270f,
    @JvmField var bedSizeY: Float = 270f,
    @JvmField var maxPrintHeight: Float = 270f,

    // Nozzle
    @JvmField var nozzleDiameter: Float = 0.4f,

    // Filament
    @JvmField var filamentDiameter: Float = 1.75f,
    @JvmField var filamentType: String = "PLA",
    @JvmField var filamentTypes: Array<String> = arrayOf(),

    // Multi-extruder (up to 4 for Snapmaker U1)
    @JvmField var extruderCount: Int = 1,
    @JvmField var extruderTemps: IntArray = intArrayOf(),
    @JvmField var extruderRetractLength: FloatArray = floatArrayOf(),
    @JvmField var extruderRetractSpeed: FloatArray = floatArrayOf(),

    // Wipe tower (for multi-extruder)
    @JvmField var wipeTowerEnabled: Boolean = false,
    @JvmField var wipeTowerX: Float = 170f,
    @JvmField var wipeTowerY: Float = 140f,
    @JvmField var wipeTowerWidth: Float = 60f,

    // B106: machine G-code templates — populated from assets for STL files (no embedded
    // Snapmaker profile). OrcaSlicer resolves {variable} template expressions at generation
    // time. Empty string = use OrcaSlicer's built-in default (bare G28 for STL).
    @JvmField var machineStartGcode: String = "",
    @JvmField var machineEndGcode: String = "",

    // F91 (2026-05-25): per-extruder filament tuning sourced from the user's filament
    // library at slice time. Empty array = "user hasn't set this — let the
    // applyConfigToPrusa fallback / embed value stand". When non-empty, native treats
    // these as the user's explicit override and applies them after profile_keys[].
    // Fixes cheeky_b52's "16 mm³/s flow limit not abided by" 2026-05-25 — STL slices
    // skip ProfileEmbedder, so the previous filamentSettings-via-embed plumbing never
    // reached them. SliceConfig fields are the path that works for STL AND 3MF.
    @JvmField var filamentFlowRatios: FloatArray = floatArrayOf(),
    @JvmField var filamentMaxVolumetricSpeeds: FloatArray = floatArrayOf(),
    @JvmField var filamentFanMinSpeeds: IntArray = intArrayOf(),
    @JvmField var filamentFanMaxSpeeds: IntArray = intArrayOf(),
    @JvmField var filamentOverhangFanSpeeds: IntArray = intArrayOf(),
    @JvmField var filamentAdditionalCoolingFanSpeeds: IntArray = intArrayOf(),
    @JvmField var filamentSlowDownLayerTimes: FloatArray = floatArrayOf(),
    @JvmField var filamentSlowDownMinSpeeds: FloatArray = floatArrayOf(),
    @JvmField var filamentCloseFanFirstLayers: IntArray = intArrayOf(),
    @JvmField var filamentFullFanSpeedLayers: IntArray = intArrayOf(),
    @JvmField var filamentEnablePressureAdvance: IntArray = intArrayOf(),   // 0 = unset / off, 1 = on
    @JvmField var filamentPressureAdvances: FloatArray = floatArrayOf(),
    @JvmField var filamentMinimalPurgeOnWipeTower: FloatArray = floatArrayOf(),
    @JvmField var filamentNozzleTempInitialLayers: IntArray = intArrayOf(),
    @JvmField var filamentBedTempInitialLayers: IntArray = intArrayOf(),
    @JvmField var filamentCosts: FloatArray = floatArrayOf(),

    // Full-spectrum mixed-filament recipe (stage 2; serialized MixedFilamentManager output)
    @JvmField var mixedFilamentDefinitions: String = "",
)
