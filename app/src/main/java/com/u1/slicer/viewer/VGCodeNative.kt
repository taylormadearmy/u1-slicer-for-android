package com.u1.slicer.viewer

import com.u1.slicer.gcode.FeatureType
import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode

/**
 * JNI bridge to PrusaSlicer's libvgcode for instanced G-code toolpath rendering.
 * All methods that touch GL must be called on the GL thread.
 */
object VGCodeNative {

    init {
        try {
            System.loadLibrary("vgcode-jni")
        } catch (_: UnsatisfiedLinkError) {
            // JVM unit tests — native lib not available, pure Kotlin methods still work
        }
    }

    // --- Native methods ---
    external fun create(): Long
    external fun init(ptr: Long)
    external fun load(
        ptr: Long,
        positions: FloatArray,
        heights: FloatArray,
        widths: FloatArray,
        feedrates: FloatArray,
        moveTypes: ByteArray,
        roles: ByteArray,
        extruderIds: ByteArray,
        layerIds: IntArray,
        toolColors: IntArray
    )
    external fun render(ptr: Long, viewMatrix: FloatArray, projMatrix: FloatArray)
    external fun setLayersViewRange(ptr: Long, min: Int, max: Int)
    external fun getLayersCount(ptr: Long): Long
    external fun setViewType(ptr: Long, type: Int)
    external fun getViewType(ptr: Long): Int
    external fun setToolColors(ptr: Long, colors: IntArray)
    external fun toggleOptionVisibility(ptr: Long, option: Int)
    external fun isOptionVisible(ptr: Long, option: Int): Boolean
    external fun shutdown(ptr: Long)
    external fun destroy(ptr: Long)

    // --- View type constants (matches libvgcode EViewType) ---
    const val VIEW_TYPE_FEATURE = 0
    const val VIEW_TYPE_HEIGHT = 1
    const val VIEW_TYPE_WIDTH = 2
    const val VIEW_TYPE_SPEED = 3
    const val VIEW_TYPE_TOOL = 11

    // --- Option type constants (matches libvgcode EOptionType) ---
    const val OPTION_TRAVELS = 0

    // --- EMoveType mapping (libvgcode ordinals) ---
    private const val NATIVE_MOVE_EXTRUDE: Byte = 10  // EMoveType::Extrude
    private const val NATIVE_MOVE_TRAVEL: Byte = 8    // EMoveType::Travel

    /** Map our FeatureType byte to libvgcode EGCodeExtrusionRole ordinal. */
    internal fun mapRole(featureType: Byte): Byte = when (featureType) {
        FeatureType.OUTER_WALL -> 2        // ExternalPerimeter
        FeatureType.INNER_WALL -> 1        // Perimeter
        FeatureType.SPARSE_INFILL -> 4     // InternalInfill
        FeatureType.SOLID_INFILL -> 5      // SolidInfill
        FeatureType.TOP_SURFACE -> 6       // TopSolidInfill
        FeatureType.BOTTOM_SURFACE -> 5    // SolidInfill (no separate bottom in libvgcode)
        FeatureType.SUPPORT -> 11          // SupportMaterial
        FeatureType.SUPPORT_INTERFACE -> 12 // SupportMaterialInterface
        FeatureType.PRIME_TOWER -> 13      // WipeTower
        FeatureType.BRIDGE -> 8            // BridgeInfill
        FeatureType.SKIRT -> 10            // Skirt
        FeatureType.OTHER -> 14            // Custom
        else -> 0                          // None
    }

    /** Map our MoveType to libvgcode EMoveType ordinal. */
    internal fun mapMoveType(type: MoveType): Byte = when (type) {
        MoveType.EXTRUDE -> NATIVE_MOVE_EXTRUDE
        MoveType.TRAVEL -> NATIVE_MOVE_TRAVEL
    }

    /**
     * Pack a ParsedGcode into parallel arrays for JNI transfer and call native load().
     * Must be called on the GL thread (after init).
     */
    fun loadGcode(ptr: Long, gcode: ParsedGcode, extruderColors: IntArray) {
        var totalMoves = 0
        for (layer in gcode.layers) totalMoves += layer.moves.size
        if (totalMoves == 0) return

        val positions = FloatArray(totalMoves * 3)
        val heights = FloatArray(totalMoves)
        val widths = FloatArray(totalMoves)
        val feedrates = FloatArray(totalMoves)
        val moveTypes = ByteArray(totalMoves)
        val roles = ByteArray(totalMoves)
        val extruderIds = ByteArray(totalMoves)
        val layerIds = IntArray(totalMoves)

        var idx = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                positions[idx * 3] = move.x1
                positions[idx * 3 + 1] = move.y1
                positions[idx * 3 + 2] = layer.z
                heights[idx] = 0.2f  // default layer height
                widths[idx] = if (move.type == MoveType.EXTRUDE) 0.42f else 0f
                feedrates[idx] = 0f
                moveTypes[idx] = mapMoveType(move.type)
                roles[idx] = if (move.type == MoveType.EXTRUDE) mapRole(move.featureType) else 0
                extruderIds[idx] = move.extruder.toByte()
                layerIds[idx] = layer.index
                idx++
            }
        }

        load(ptr, positions, heights, widths, feedrates, moveTypes, roles, extruderIds, layerIds, extruderColors)
    }

    /** Convert hex color strings to packed RGB int array for JNI. */
    fun packToolColors(hexColors: List<String>): IntArray {
        val defaults = intArrayOf(0xFF9900, 0x33B3FF, 0x00E666, 0xE63380)
        val result = defaults.copyOf()
        hexColors.forEachIndexed { i, hex ->
            if (i >= result.size || hex.isBlank()) return@forEachIndexed
            try {
                val clean = hex.removePrefix("#")
                if (clean.length == 6) {
                    result[i] = clean.toInt(16)
                }
            } catch (_: Exception) { /* keep default */ }
        }
        return result
    }
}
