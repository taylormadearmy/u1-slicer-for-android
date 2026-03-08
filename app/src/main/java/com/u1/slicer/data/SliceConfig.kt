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

    // Speed (mm/s) — Snapmaker U1 defaults (200mm/s walls, 300mm/s travel)
    @JvmField var printSpeed: Float = 200f,
    @JvmField var travelSpeed: Float = 300f,
    @JvmField var firstLayerSpeed: Float = 20f,

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

    // Skirt/Brim
    @JvmField var skirtLoops: Int = 1,
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

    // Multi-extruder (up to 4 for Snapmaker U1)
    @JvmField var extruderCount: Int = 1,
    @JvmField var extruderTemps: IntArray = intArrayOf(),
    @JvmField var extruderRetractLength: FloatArray = floatArrayOf(),
    @JvmField var extruderRetractSpeed: FloatArray = floatArrayOf(),

    // Wipe tower (for multi-extruder)
    @JvmField var wipeTowerEnabled: Boolean = false,
    @JvmField var wipeTowerX: Float = 170f,
    @JvmField var wipeTowerY: Float = 140f,
    @JvmField var wipeTowerWidth: Float = 60f
)
