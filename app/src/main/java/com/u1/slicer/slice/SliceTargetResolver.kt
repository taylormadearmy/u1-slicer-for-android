package com.u1.slicer.slice

import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.MachineKind

fun resolveDefaultSliceTarget(activePrinter: Printer?): SlicerTarget =
    when (activePrinter?.machine) {
        MachineKind.BAMBU -> activePrinter.bambu?.model
            ?.let(SlicerTarget::forBambuModel)
            ?.takeIf { it.supportsLocalSlicing }
            ?: SlicerTarget.SnapmakerU1
        MachineKind.ELEGOO_CENTAURI_CARBON -> SlicerTarget.ElegooCentauriCarbon
        MachineKind.GENERIC_KLIPPER -> SlicerTarget.GenericKlipper
        else -> SlicerTarget.SnapmakerU1
    }

fun isLocalSliceAvailable(activePrinter: Printer?): Boolean {
    return when (activePrinter?.machine) {
        MachineKind.GENERIC_KLIPPER -> activePrinter.machineProfile != null
        MachineKind.BAMBU -> activePrinter.bambu?.model
            ?.let(SlicerTarget::forBambuModel)
            ?.supportsLocalSlicing == true
        else -> true
    }
}
