package com.u1.slicer.bambu

import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget

fun resolveTargetedSliceConfig(
    target: SlicerTarget,
    base: SliceConfig,
): SliceConfig {
    require(target.supportsLocalSlicing) {
        "Local slicing is not yet supported for $target"
    }
    return bambuMachineProfileFor(target)?.let { profile ->
        val towerWidth = if (profile.nozzlePrintableAreas.size > 1) 60f else 35f
        val commonNozzleBounds = profile.nozzlePrintableAreas.reduce { common, bounds ->
            BambuPrintBounds(
                minX = maxOf(common.minX, bounds.minX),
                minY = maxOf(common.minY, bounds.minY),
                maxX = minOf(common.maxX, bounds.maxX),
                maxY = minOf(common.maxY, bounds.maxY),
            )
        }
        // Orca's prime-tower coordinates are its front-left corner. Keep a
        // conservative 3 mm clearance for the generated tower brim.
        val towerClearance = 3f
        val towerMinX = commonNozzleBounds.minX.toFloat() + towerClearance
        val towerMinY = commonNozzleBounds.minY.toFloat() + towerClearance
        val towerMaxX = commonNozzleBounds.maxX.toFloat() - towerWidth - towerClearance
        val towerMaxY = commonNozzleBounds.maxY.toFloat() - towerWidth - towerClearance
        require(towerMaxX >= towerMinX && towerMaxY >= towerMinY) {
            "Prime tower does not fit the common nozzle envelope for $target"
        }
        val singleNozzleTemplates = if (
            profile.nozzleDiameters.size == 1 &&
            target != SlicerTarget.BambuA1Mini
        ) {
            BambuSingleNozzleMachineGcode.forTarget(target)
        } else {
            null
        }
        base.copy(
            bedSizeX = profile.bedSizeX,
            bedSizeY = profile.bedSizeY,
            maxPrintHeight = profile.maxPrintHeight,
            wipeTowerWidth = towerWidth,
            wipeTowerX = base.wipeTowerX.coerceIn(towerMinX, towerMaxX),
            wipeTowerY = base.wipeTowerY.coerceIn(towerMinY, towerMaxY),
            machineStartGcode = when (target) {
                SlicerTarget.BambuA1Mini -> BambuA1MiniMachineGcode.start
                SlicerTarget.BambuH2D -> BambuH2DMachineGcode.start
                else -> singleNozzleTemplates?.start.orEmpty()
            },
            machineEndGcode = when (target) {
                SlicerTarget.BambuA1Mini -> BambuA1MiniMachineGcode.end
                SlicerTarget.BambuH2D -> BambuH2DMachineGcode.end
                else -> singleNozzleTemplates?.end.orEmpty()
            },
            machineChangeFilamentGcode = when (target) {
                SlicerTarget.BambuA1Mini -> BambuA1MiniMachineGcode.changeFilament
                SlicerTarget.BambuH2D -> BambuH2DMachineGcode.changeFilament
                else -> singleNozzleTemplates?.changeFilament.orEmpty()
            },
            machineTarget = target.nativeMachineTarget,
        )
    } ?: if (base.machineTarget.startsWith("BAMBU_")) {
        base.copy(
            machineStartGcode = "",
            machineEndGcode = "",
            machineChangeFilamentGcode = "",
            machineTarget = "SNAPMAKER_U1",
        )
    } else {
        base.copy(machineTarget = "SNAPMAKER_U1")
    }
}

private val SlicerTarget.nativeMachineTarget: String
    get() = when (this) {
        SlicerTarget.SnapmakerU1 -> "SNAPMAKER_U1"
        SlicerTarget.BambuX1C -> "BAMBU_X1C"
        SlicerTarget.BambuX1E -> "BAMBU_X1E"
        SlicerTarget.BambuP1S -> "BAMBU_P1S"
        SlicerTarget.BambuP1P -> "BAMBU_P1P"
        SlicerTarget.BambuA1 -> "BAMBU_A1"
        SlicerTarget.BambuA1Mini -> "BAMBU_A1_MINI"
        SlicerTarget.BambuH2D -> "BAMBU_H2D"
    }
