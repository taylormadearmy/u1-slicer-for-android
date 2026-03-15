package com.u1.slicer.gcode

data class GcodeMove(
    val type: MoveType,
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val extruder: Int = 0
)

enum class MoveType {
    TRAVEL,
    EXTRUDE
}

data class GcodeLayer(
    val index: Int,
    val z: Float,
    val moves: List<GcodeMove>
)

data class ParsedGcode(
    val layers: List<GcodeLayer>,
    val bedWidth: Float = 270f,
    val bedHeight: Float = 270f,
    val perExtruderFilamentMm: List<Float> = emptyList()
) {
    /** Total move count across all layers — used for OOM guard in 3D preview. */
    val totalMoves: Int by lazy { layers.sumOf { it.moves.size } }
}

