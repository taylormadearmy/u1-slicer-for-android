package com.u1.slicer.slice

import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.PrinterKind

enum class SliceTargetFamily {
    SNAPMAKER,
    BAMBU,
}

enum class SlicerTarget(
    val family: SliceTargetFamily,
    val beta: Boolean,
    val supportsLocalSlicing: Boolean,
) {
    SnapmakerU1(
        family = SliceTargetFamily.SNAPMAKER,
        beta = false,
        supportsLocalSlicing = true,
    ),
    BambuX1C(
        family = SliceTargetFamily.BAMBU,
        beta = true,
        supportsLocalSlicing = true,
    ),
    BambuX1E(
        family = SliceTargetFamily.BAMBU,
        beta = true,
        supportsLocalSlicing = true,
    ),
    BambuP1S(
        family = SliceTargetFamily.BAMBU,
        beta = true,
        supportsLocalSlicing = true,
    ),
    BambuP1P(
        family = SliceTargetFamily.BAMBU,
        beta = true,
        supportsLocalSlicing = true,
    ),
    /** P2S uses its own backported official 0.4 mm machine templates. */
    BambuP2S(
        family = SliceTargetFamily.BAMBU,
        beta = true,
        supportsLocalSlicing = true,
    ),
    BambuA1(
        family = SliceTargetFamily.BAMBU,
        beta = true,
        supportsLocalSlicing = true,
    ),
    BambuA1Mini(
        family = SliceTargetFamily.BAMBU,
        beta = true,
        supportsLocalSlicing = true,
    ),
    BambuH2D(
        family = SliceTargetFamily.BAMBU,
        beta = true,
        supportsLocalSlicing = true,
    );

    fun isCompatibleWith(
        kind: PrinterKind,
        bambuModel: BambuModel?,
    ): Boolean = when (this) {
        SnapmakerU1 -> kind == PrinterKind.MOONRAKER
        BambuX1C -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.X1C
        BambuX1E -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.X1E
        BambuP1S -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.P1S
        BambuP1P -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.P1P
        BambuP2S -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.P2S
        BambuA1 -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.A1
        BambuA1Mini -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.A1_MINI
        BambuH2D -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.H2D
    }

    companion object {
        fun forBambuModel(model: BambuModel): SlicerTarget = when (model) {
            BambuModel.X1C -> BambuX1C
            BambuModel.X1E -> BambuX1E
            BambuModel.P1S -> BambuP1S
            BambuModel.P1P -> BambuP1P
            BambuModel.P2S -> BambuP2S
            BambuModel.A1 -> BambuA1
            BambuModel.A1_MINI -> BambuA1Mini
            BambuModel.H2D -> BambuH2D
        }
    }
}
