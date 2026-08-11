package com.u1.slicer.bambu

import com.u1.slicer.slice.SlicerTarget

internal data class BambuMachineProfile(
    val bedSizeX: Float,
    val bedSizeY: Float,
    val maxPrintHeight: Float,
    val printerModel: String = "",
    val printerModelId: String = "",
    val printerSettingsId: String = "",
    val nozzleDiameters: List<Float> = listOf(0.4f),
    val nozzleTypes: List<String> = emptyList(),
    val nozzleVolumes: List<Int> = emptyList(),
    val printerExtruderIds: List<Int> = emptyList(),
    val printerExtruderVariants: List<String> = emptyList(),
    val extruderVariantList: List<String> = emptyList(),
    val nozzlePrintableAreas: List<BambuPrintBounds> = listOf(
        BambuPrintBounds(0.0, 0.0, bedSizeX.toDouble(), bedSizeY.toDouble()),
    ),
    val masterNozzle: Int = 0,
    val supportsFilamentTrackSwitch: Boolean = false,
    val defaultPlaFilamentSettingsId: String,
)

/**
 * Machine envelopes and identities used for local slicing and gcode.3mf
 * packaging. H2D values are pinned to BambuStudio's official H2D 0.4 profile:
 * the 350x320 union bed is larger than either physical nozzle's reachable area.
 */
internal val BAMBU_MACHINE_PROFILES: Map<SlicerTarget, BambuMachineProfile> = mapOf(
    SlicerTarget.BambuX1C to BambuMachineProfile(
        bedSizeX = 256f,
        bedSizeY = 256f,
        maxPrintHeight = 250f,
        printerModel = "Bambu Lab X1 Carbon",
        printerModelId = "BL-P001",
        printerSettingsId = "Bambu Lab X1 Carbon 0.4 nozzle",
        defaultPlaFilamentSettingsId = "Bambu PLA Basic @BBL X1C",
    ),
    SlicerTarget.BambuX1E to BambuMachineProfile(
        bedSizeX = 256f,
        bedSizeY = 256f,
        maxPrintHeight = 250f,
        printerModel = "Bambu Lab X1E",
        printerModelId = "C13",
        printerSettingsId = "Bambu Lab X1E 0.4 nozzle",
        defaultPlaFilamentSettingsId = "Bambu PLA Basic @BBL X1C",
    ),
    SlicerTarget.BambuP1S to BambuMachineProfile(
        bedSizeX = 256f,
        bedSizeY = 256f,
        maxPrintHeight = 250f,
        printerModel = "Bambu Lab P1S",
        printerModelId = "C12",
        printerSettingsId = "Bambu Lab P1S 0.4 nozzle",
        defaultPlaFilamentSettingsId = "Bambu PLA Basic @BBL X1C",
    ),
    SlicerTarget.BambuP1P to BambuMachineProfile(
        bedSizeX = 256f,
        bedSizeY = 256f,
        maxPrintHeight = 250f,
        printerModel = "Bambu Lab P1P",
        printerModelId = "C11",
        printerSettingsId = "Bambu Lab P1P 0.4 nozzle",
        defaultPlaFilamentSettingsId = "Bambu PLA Basic @BBL X1",
    ),
    SlicerTarget.BambuA1 to BambuMachineProfile(
        bedSizeX = 256f,
        bedSizeY = 256f,
        maxPrintHeight = 256f,
        printerModel = "Bambu Lab A1",
        printerModelId = "N2S",
        printerSettingsId = "Bambu Lab A1 0.4 nozzle",
        defaultPlaFilamentSettingsId = "Bambu PLA Basic @BBL A1",
    ),
    SlicerTarget.BambuA1Mini to BambuMachineProfile(
        bedSizeX = 180f,
        bedSizeY = 180f,
        maxPrintHeight = 180f,
        printerModel = "Bambu Lab A1 mini",
        printerModelId = "N1",
        printerSettingsId = "Bambu Lab A1 mini 0.4 nozzle",
        defaultPlaFilamentSettingsId = "Bambu PLA Basic @BBL A1M",
    ),
    SlicerTarget.BambuH2D to BambuMachineProfile(
        bedSizeX = 350f,
        bedSizeY = 320f,
        maxPrintHeight = 325f,
        printerModel = "Bambu Lab H2D",
        printerModelId = "O1D",
        printerSettingsId = "Bambu Lab H2D 0.4 nozzle",
        nozzleDiameters = listOf(0.4f, 0.4f),
        // The H2D profile contains one entry for every selectable hotend
        // variant, not merely one entry per physical toolhead. Standard-flow
        // hotends are the first variant for each side (volumes 130 and 145).
        nozzleTypes = List(7) { "hardened_steel" },
        nozzleVolumes = listOf(130, 133, 133, 145, 148, 148, 148),
        printerExtruderIds = listOf(1, 1, 1, 2, 2, 2, 2),
        printerExtruderVariants = listOf(
            "Direct Drive Standard",
            "Direct Drive High Flow",
            "Direct Drive E3D High Flow",
            "Direct Drive Standard",
            "Direct Drive High Flow",
            "Direct Drive TPU High Flow",
            "Direct Drive E3D High Flow",
        ),
        extruderVariantList = listOf(
            "Direct Drive Standard,Direct Drive High Flow,Direct Drive E3D High Flow",
            "Direct Drive Standard,Direct Drive High Flow,Direct Drive TPU High Flow,Direct Drive E3D High Flow",
        ),
        nozzlePrintableAreas = listOf(
            BambuPrintBounds(0.0, 0.0, 325.0, 320.0),
            BambuPrintBounds(25.0, 0.0, 350.0, 320.0),
        ),
        // Official current profile uses master_extruder_id=2 (one based).
        masterNozzle = 1,
        // Current H2D firmware supports the optional FTS, but this flag records
        // whether our locally generated G-code implements dynamic routing. It
        // deliberately remains false until the postprocessor can emit it.
        supportsFilamentTrackSwitch = false,
        defaultPlaFilamentSettingsId = "Bambu PLA Basic @BBL H2D",
    ),
)

internal fun bambuMachineProfileFor(target: SlicerTarget): BambuMachineProfile? =
    BAMBU_MACHINE_PROFILES[target]
