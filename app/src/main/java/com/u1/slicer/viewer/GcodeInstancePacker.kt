package com.u1.slicer.viewer

import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode

data class InstanceLayerRange(val firstInstance: Int, val instanceCount: Int)

data class InstancePackResult(
    val instanceData: FloatArray,
    val totalInstances: Int,
    val layerRanges: List<InstanceLayerRange>
)

object GcodeInstancePacker {

    const val FLOATS_PER_INSTANCE = 12
    private const val HALF_WIDTH = 0.28f
    private const val HALF_HEIGHT = 0.18f

    fun pack(
        gcode: ParsedGcode,
        extruderColors: Array<FloatArray>,
        featureTypeColors: Array<FloatArray>,
        useFeatureColors: Boolean
    ): InstancePackResult {
        val totalLayers = gcode.layers.size
        if (totalLayers == 0) return InstancePackResult(FloatArray(0), 0, emptyList())

        var totalExtrudeMoves = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.type == MoveType.EXTRUDE) totalExtrudeMoves++
            }
        }
        if (totalExtrudeMoves == 0) return InstancePackResult(FloatArray(0), 0, emptyList())

        val data = FloatArray(totalExtrudeMoves * FLOATS_PER_INSTANCE)
        var offset = 0
        val layerRanges = mutableListOf<InstanceLayerRange>()
        var instanceCount = 0

        for ((layerIdx, layer) in gcode.layers.withIndex()) {
            val layerFirstInstance = instanceCount
            val layerBrightness = if (totalLayers <= 1) 1.0f
            else 0.45f + 0.55f * (layerIdx.toFloat() / (totalLayers - 1))

            for (move in layer.moves) {
                if (move.type != MoveType.EXTRUDE) continue
                val dx = move.x1 - move.x0
                val dy = move.y1 - move.y0
                if (dx * dx + dy * dy < 0.000001f) continue

                val baseColor = if (useFeatureColors) {
                    featureTypeColors[move.featureType.toInt().coerceIn(0, featureTypeColors.size - 1)]
                } else {
                    extruderColors[move.extruder.coerceIn(0, extruderColors.size - 1)]
                }

                data[offset++] = move.x0; data[offset++] = move.y0; data[offset++] = layer.z
                data[offset++] = move.x1; data[offset++] = move.y1; data[offset++] = layer.z
                data[offset++] = (baseColor[0] * layerBrightness).coerceAtMost(1.0f)
                data[offset++] = (baseColor[1] * layerBrightness).coerceAtMost(1.0f)
                data[offset++] = (baseColor[2] * layerBrightness).coerceAtMost(1.0f)
                data[offset++] = baseColor[3]
                data[offset++] = HALF_WIDTH
                data[offset++] = HALF_HEIGHT
                instanceCount++
            }
            layerRanges.add(InstanceLayerRange(layerFirstInstance, instanceCount - layerFirstInstance))
        }

        val trimmedData = if (offset < data.size) data.copyOf(offset) else data
        return InstancePackResult(trimmedData, instanceCount, layerRanges)
    }
}
