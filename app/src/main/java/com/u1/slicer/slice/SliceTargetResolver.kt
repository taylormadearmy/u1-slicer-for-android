package com.u1.slicer.slice

import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind

fun resolveDefaultSliceTarget(activePrinter: Printer?): SlicerTarget =
    when (activePrinter?.kind) {
        PrinterKind.BAMBU_LAN -> activePrinter.bambu?.model
            ?.let(SlicerTarget::forBambuModel)
            ?.takeIf { it.supportsLocalSlicing }
            ?: SlicerTarget.SnapmakerU1
        else -> SlicerTarget.SnapmakerU1
    }

fun isLocalSliceAvailable(activePrinter: Printer?): Boolean {
    return activePrinter?.kind != PrinterKind.BAMBU_LAN ||
        activePrinter.bambu?.model
            ?.let(SlicerTarget::forBambuModel)
            ?.supportsLocalSlicing == true
}
