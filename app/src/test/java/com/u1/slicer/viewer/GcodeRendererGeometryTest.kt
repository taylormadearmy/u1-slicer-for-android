package com.u1.slicer.viewer

import com.u1.slicer.gcode.*
import org.junit.Assert.*
import org.junit.Test

class GcodeRendererGeometryTest {

    private val defaultExtruderColors = arrayOf(
        floatArrayOf(1.0f, 0.6f, 0.0f, 1.0f),
        floatArrayOf(0.2f, 0.7f, 1.0f, 1.0f),
        floatArrayOf(0.0f, 0.9f, 0.4f, 1.0f),
        floatArrayOf(0.9f, 0.2f, 0.5f, 1.0f)
    )

    private val defaultFeatureColors = arrayOf(
        floatArrayOf(1.00f, 0.85f, 0.00f, 1.0f),  // OUTER_WALL
        floatArrayOf(0.53f, 0.81f, 0.92f, 1.0f),  // INNER_WALL
        floatArrayOf(0.30f, 0.71f, 0.68f, 1.0f),  // SPARSE_INFILL
        floatArrayOf(0.40f, 0.73f, 0.42f, 1.0f),  // SOLID_INFILL
        floatArrayOf(0.00f, 0.74f, 0.83f, 1.0f),  // TOP_SURFACE
        floatArrayOf(0.00f, 0.59f, 0.53f, 1.0f),  // BOTTOM_SURFACE
        floatArrayOf(0.67f, 0.28f, 0.74f, 1.0f),  // SUPPORT
        floatArrayOf(0.81f, 0.58f, 0.85f, 1.0f),  // SUPPORT_INTERFACE
        floatArrayOf(1.00f, 0.25f, 0.51f, 1.0f),  // PRIME_TOWER
        floatArrayOf(1.00f, 0.44f, 0.26f, 1.0f),  // BRIDGE
        floatArrayOf(0.69f, 0.75f, 0.76f, 1.0f),  // SKIRT
        floatArrayOf(0.62f, 0.62f, 0.62f, 1.0f)   // OTHER
    )

    private fun makeGcode(vararg layerMoves: List<GcodeMove>): ParsedGcode {
        val layers = layerMoves.mapIndexed { i, moves ->
            GcodeLayer(i, (i + 1) * 0.2f, moves)
        }
        return ParsedGcode(layers)
    }

    private fun pack(gcode: ParsedGcode, useFeature: Boolean = false) =
        GcodeInstancePacker.pack(gcode, defaultExtruderColors, defaultFeatureColors, useFeature)

    @Test
    fun `single extrude move produces 12 floats`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 10f)
        ))
        val result = pack(gcode)
        assertEquals(1, result.totalInstances)
        assertEquals(12, result.instanceData.size)
    }

    @Test
    fun `travel moves are excluded`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.TRAVEL, 0f, 0f, 10f, 10f),
            GcodeMove(MoveType.EXTRUDE, 10f, 10f, 20f, 20f)
        ))
        val result = pack(gcode)
        assertEquals(1, result.totalInstances)
    }

    @Test
    fun `zero-length moves are skipped`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 5f, 5f, 5f, 5f),
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)
        ))
        val result = pack(gcode)
        assertEquals(1, result.totalInstances)
        // Start position of the surviving move
        assertEquals(0f, result.instanceData[0], 0.001f)
    }

    @Test
    fun `layer ranges tracked correctly`() {
        val gcode = makeGcode(
            listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
                GcodeMove(MoveType.EXTRUDE, 10f, 0f, 20f, 0f)
            ),
            listOf(
                GcodeMove(MoveType.EXTRUDE, 0f, 5f, 10f, 5f)
            )
        )
        val result = pack(gcode)
        assertEquals(2, result.layerRanges.size)
        assertEquals(0, result.layerRanges[0].firstInstance)
        assertEquals(2, result.layerRanges[0].instanceCount)
        assertEquals(2, result.layerRanges[1].firstInstance)
        assertEquals(1, result.layerRanges[1].instanceCount)
        assertEquals(3, result.totalInstances)
    }

    @Test
    fun `colors use extruder index by default`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 1)
        ))
        val result = pack(gcode)
        // Single layer → brightness = 1.0, so color = extruderColors[1] directly
        val r = result.instanceData[6]
        val g = result.instanceData[7]
        val b = result.instanceData[8]
        assertEquals(0.2f, r, 0.001f)
        assertEquals(0.7f, g, 0.001f)
        assertEquals(1.0f, b, 0.001f)
    }

    @Test
    fun `feature type colors when enabled`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, featureType = FeatureType.SUPPORT)
        ))
        val result = pack(gcode, useFeature = true)
        // Single layer → brightness 1.0
        val r = result.instanceData[6]
        val g = result.instanceData[7]
        assertEquals(0.67f, r, 0.001f)
        assertEquals(0.28f, g, 0.001f)
    }

    @Test
    fun `brightness gradient across layers`() {
        val gcode = makeGcode(
            listOf(GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)),
            listOf(GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f))
        )
        val result = pack(gcode)
        // Layer 0 brightness = 0.45, Layer 1 brightness = 1.0
        val layer0R = result.instanceData[6]  // first instance color R
        val layer1R = result.instanceData[18] // second instance color R (offset 12 + 6)
        // T0 base color R = 1.0; layer0 = 1.0*0.45=0.45, layer1 = 1.0*1.0=1.0
        assertEquals(0.45f, layer0R, 0.001f)
        assertEquals(1.0f, layer1R, 0.001f)
    }

    @Test
    fun `halfWidth and halfHeight values correct`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)
        ))
        val result = pack(gcode)
        assertEquals(0.28f, result.instanceData[10], 0.001f) // halfWidth
        assertEquals(0.18f, result.instanceData[11], 0.001f) // halfHeight
    }

    @Test
    fun `large move count does not hit any limit`() {
        val moves = (0 until 400_000).map { i ->
            GcodeMove(MoveType.EXTRUDE, i.toFloat(), 0f, i + 1f, 0f)
        }
        val gcode = makeGcode(moves)
        val result = pack(gcode)
        assertEquals(400_000, result.totalInstances)
        assertEquals(400_000 * 12, result.instanceData.size)
    }

    @Test
    fun `empty gcode returns empty result`() {
        val gcode = ParsedGcode(emptyList())
        val result = pack(gcode)
        assertEquals(0, result.totalInstances)
        assertEquals(0, result.instanceData.size)
        assertTrue(result.layerRanges.isEmpty())
    }

    @Test
    fun `start and end positions are packed correctly`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 1f, 2f, 3f, 4f)
        ))
        val result = pack(gcode)
        assertEquals(1f, result.instanceData[0], 0.001f)  // x0
        assertEquals(2f, result.instanceData[1], 0.001f)  // y0
        assertEquals(0.2f, result.instanceData[2], 0.001f) // z (layer 0 at 0.2)
        assertEquals(3f, result.instanceData[3], 0.001f)  // x1
        assertEquals(4f, result.instanceData[4], 0.001f)  // y1
        assertEquals(0.2f, result.instanceData[5], 0.001f) // z
    }
}
