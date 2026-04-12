package com.u1.slicer.viewer

import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

data class SegmentLayerRange(val firstSegment: Int, val segmentCount: Int)

data class SegmentPackResult(
    val positions: FloatArray,
    val heightsWidthsAngles: FloatArray,
    val extruderColors: FloatArray,
    val featureColors: FloatArray,
    val segmentIndices: IntArray,
    val totalVertices: Int,
    val totalSegments: Int,
    val layerRanges: List<SegmentLayerRange>
)

object GcodeSegmentPacker {

    const val HEIGHT = 0.36f
    const val WIDTH = 0.56f

    /**
     * Encode RGB (0-1 range) with brightness into a single float for GPU texture.
     * Format: ((R_byte << 16) | (G_byte << 8) | B_byte) as float.
     * Matches libvgcode's color encoding decoded in the vertex shader.
     */
    fun encodeColor(r: Float, g: Float, b: Float, brightness: Float = 1f): Float {
        val ri = ((r * brightness).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = ((g * brightness).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = ((b * brightness).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return ((ri shl 16) or (gi shl 8) or bi).toFloat()
    }

    /** Decode packed color float back to (R, G, B) bytes 0-255. For testing. */
    fun decodeColor(packed: Float): Triple<Int, Int, Int> {
        val c = (packed + 0.5f).toInt()
        return Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    /**
     * Compute 2D texture dimensions that fit [count] texels.
     * Width = min(count, maxTexSize), height = ceil(count / width).
     */
    fun computeTexDimensions(count: Int, maxTexSize: Int = 4096): Pair<Int, Int> {
        if (count <= 0) return Pair(1, 1)
        val w = min(count, maxTexSize)
        val h = (count + w - 1) / w
        return Pair(w, h)
    }

    /**
     * Pack parsed G-code into arrays ready for GPU texture upload.
     *
     * Consecutive EXTRUDE moves form **chains** sharing vertices at endpoints.
     * Chains break at travel moves and layer boundaries. At each shared vertex,
     * the turning angle between segments is computed for beveled cap geometry.
     * Chain start/end vertices get angle = 0 (pointy caps).
     *
     * Two color arrays are produced: one for extruder-based coloring, one for
     * feature-type coloring. The renderer swaps between them without re-packing.
     */
    fun pack(
        gcode: ParsedGcode,
        extruderPalette: Array<FloatArray>,
        featurePalette: Array<FloatArray>
    ): SegmentPackResult {
        val totalLayers = gcode.layers.size
        if (totalLayers == 0) return emptyResult()

        var totalExtrudeMoves = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.type == MoveType.EXTRUDE) totalExtrudeMoves++
            }
        }
        if (totalExtrudeMoves == 0) return emptyResult()

        // Worst case: every move is its own chain -> 2 vertices per move.
        val maxVerts = totalExtrudeMoves * 2
        val pos = FloatArray(maxVerts * 3)
        val hwa = FloatArray(maxVerts * 3)
        val extCol = FloatArray(maxVerts)
        val featCol = FloatArray(maxVerts)
        val segIdx = IntArray(totalExtrudeMoves)

        var vc = 0          // vertex count
        var sc = 0          // segment count
        var chainOpen = false
        var prevDx = 0f
        var prevDy = 0f
        val layerRanges = mutableListOf<SegmentLayerRange>()

        for ((layerIdx, layer) in gcode.layers.withIndex()) {
            val layerFirstSeg = sc
            val brightness = if (totalLayers <= 1) 1f
                else 0.45f + 0.55f * (layerIdx.toFloat() / (totalLayers - 1))
            val z = layer.z - 0.5f * HEIGHT

            // Layer boundary always breaks chains
            if (chainOpen) {
                hwa[(vc - 1) * 3 + 2] = 0f
                chainOpen = false
            }

            for (move in layer.moves) {
                if (move.type != MoveType.EXTRUDE) {
                    if (chainOpen) {
                        hwa[(vc - 1) * 3 + 2] = 0f
                        chainOpen = false
                    }
                    continue
                }

                val dx = move.x1 - move.x0
                val dy = move.y1 - move.y0
                val len = sqrt(dx * dx + dy * dy)
                if (len < 0.001f) continue

                val dirX = dx / len
                val dirY = dy / len

                if (!chainOpen) {
                    // Start vertex (pointy cap: angle = 0)
                    val vi = vc * 3
                    pos[vi] = move.x0; pos[vi + 1] = move.y0; pos[vi + 2] = z
                    hwa[vi] = HEIGHT; hwa[vi + 1] = WIDTH; hwa[vi + 2] = 0f
                    val ec = extruderPalette[move.extruder.coerceIn(0, extruderPalette.size - 1)]
                    val fc = featurePalette[move.featureType.toInt().coerceIn(0, featurePalette.size - 1)]
                    extCol[vc] = encodeColor(ec[0], ec[1], ec[2], brightness)
                    featCol[vc] = encodeColor(fc[0], fc[1], fc[2], brightness)
                    vc++
                    chainOpen = true
                } else {
                    // Update turning angle at shared vertex
                    val cross = prevDx * dirY - prevDy * dirX
                    val dot = prevDx * dirX + prevDy * dirY
                    hwa[(vc - 1) * 3 + 2] = atan2(cross, dot)
                }

                // End vertex
                val vi = vc * 3
                pos[vi] = move.x1; pos[vi + 1] = move.y1; pos[vi + 2] = z
                hwa[vi] = HEIGHT; hwa[vi + 1] = WIDTH; hwa[vi + 2] = 0f
                val ec = extruderPalette[move.extruder.coerceIn(0, extruderPalette.size - 1)]
                val fc = featurePalette[move.featureType.toInt().coerceIn(0, featurePalette.size - 1)]
                extCol[vc] = encodeColor(ec[0], ec[1], ec[2], brightness)
                featCol[vc] = encodeColor(fc[0], fc[1], fc[2], brightness)

                segIdx[sc] = vc - 1   // segment: prev vertex -> this vertex
                sc++
                vc++

                prevDx = dirX
                prevDy = dirY
            }

            layerRanges.add(SegmentLayerRange(layerFirstSeg, sc - layerFirstSeg))
        }

        if (chainOpen) {
            hwa[(vc - 1) * 3 + 2] = 0f
        }

        return SegmentPackResult(
            positions = pos.copyOf(vc * 3),
            heightsWidthsAngles = hwa.copyOf(vc * 3),
            extruderColors = extCol.copyOf(vc),
            featureColors = featCol.copyOf(vc),
            segmentIndices = segIdx.copyOf(sc),
            totalVertices = vc,
            totalSegments = sc,
            layerRanges = layerRanges
        )
    }

    private fun emptyResult() = SegmentPackResult(
        FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
        IntArray(0), 0, 0, emptyList()
    )
}
