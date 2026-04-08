package com.u1.slicer.gcode

/** Feature-type byte constants matching OrcaSlicer's ;TYPE: comments. */
object FeatureType {
    const val OUTER_WALL: Byte       = 0
    const val INNER_WALL: Byte       = 1
    const val SPARSE_INFILL: Byte    = 2
    const val SOLID_INFILL: Byte     = 3
    const val TOP_SURFACE: Byte      = 4
    const val BOTTOM_SURFACE: Byte   = 5
    const val SUPPORT: Byte          = 6
    const val SUPPORT_INTERFACE: Byte = 7
    const val PRIME_TOWER: Byte      = 8
    const val BRIDGE: Byte           = 9
    const val SKIRT: Byte            = 10
    const val OTHER: Byte            = 11
}

data class GcodeMove(
    val type: MoveType,
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val extruder: Int = 0,
    val featureType: Byte = FeatureType.OUTER_WALL,
    val lineNumber: Int = -1,
    val featureLabel: String = ""
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
    val perExtruderFilamentMm: List<Float> = emptyList(),
    /** Total filament extruded inside prime/wipe tower regions (all extruders, mm). */
    val wipeTowerFilamentMm: Float = 0f,
    /**
     * Total move count across all layers — used for OOM guard in 3D preview.
     * When [GcodeParser] caps stored moves via `maxMoves`, this reflects the
     * true count parsed from the file, not the truncated stored count.
     * Default (-1) computes from stored layers.
     */
    private val _totalMoves: Int = -1,
    /**
     * True when the parser used stride-based sampling to stay within [GcodeParser.DEFAULT_MAX_MOVES].
     * UI can show an informational toast so the user knows the preview is approximate.
     */
    val isPreviewSimplified: Boolean = false
) {
    val totalMoves: Int get() = if (_totalMoves >= 0) _totalMoves else layers.sumOf { it.moves.size }
}

