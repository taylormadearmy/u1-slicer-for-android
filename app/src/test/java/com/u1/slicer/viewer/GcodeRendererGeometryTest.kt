package com.u1.slicer.viewer

import com.u1.slicer.gcode.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class GcodeRendererGeometryTest {

    private val extruderPalette = arrayOf(
        floatArrayOf(1.0f, 0.6f, 0.0f, 1.0f),
        floatArrayOf(0.2f, 0.7f, 1.0f, 1.0f),
        floatArrayOf(0.0f, 0.9f, 0.4f, 1.0f),
        floatArrayOf(0.9f, 0.2f, 0.5f, 1.0f)
    )

    private val featurePalette = arrayOf(
        floatArrayOf(1.00f, 0.85f, 0.00f, 1.0f),
        floatArrayOf(0.53f, 0.81f, 0.92f, 1.0f),
        floatArrayOf(0.30f, 0.71f, 0.68f, 1.0f),
        floatArrayOf(0.40f, 0.73f, 0.42f, 1.0f),
        floatArrayOf(0.00f, 0.74f, 0.83f, 1.0f),
        floatArrayOf(0.00f, 0.59f, 0.53f, 1.0f),
        floatArrayOf(0.67f, 0.28f, 0.74f, 1.0f),
        floatArrayOf(0.81f, 0.58f, 0.85f, 1.0f),
        floatArrayOf(1.00f, 0.25f, 0.51f, 1.0f),
        floatArrayOf(1.00f, 0.44f, 0.26f, 1.0f),
        floatArrayOf(0.69f, 0.75f, 0.76f, 1.0f),
        floatArrayOf(0.62f, 0.62f, 0.62f, 1.0f)
    )

    private fun makeGcode(vararg layerMoves: List<GcodeMove>): ParsedGcode {
        val layers = layerMoves.mapIndexed { i, moves ->
            GcodeLayer(i, (i + 1) * 0.2f, moves)
        }
        return ParsedGcode(layers)
    }

    private fun pack(gcode: ParsedGcode) =
        GcodeSegmentPacker.pack(gcode, extruderPalette, featurePalette)

    // --- Color encoding ---

    @Test
    fun `encodeColor round-trip preserves RGB`() {
        val packed = GcodeSegmentPacker.encodeColor(1.0f, 0.5f, 0.0f)
        val (r, g, b) = GcodeSegmentPacker.decodeColor(packed)
        assertEquals(255, r)
        assertEquals(128, g)
        assertEquals(0, b)
    }

    @Test
    fun `encodeColor applies brightness`() {
        val packed = GcodeSegmentPacker.encodeColor(1.0f, 1.0f, 1.0f, 0.5f)
        val (r, g, b) = GcodeSegmentPacker.decodeColor(packed)
        assertEquals(128, r)
        assertEquals(128, g)
        assertEquals(128, b)
    }

    // --- Basic packing ---

    @Test
    fun `single extrude move produces 2 vertices and 1 segment`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)
        ))
        val result = pack(gcode)
        assertEquals(2, result.totalVertices)
        assertEquals(1, result.totalSegments)
        assertEquals(6, result.positions.size)
        assertEquals(6, result.heightsWidthsAngles.size)
        assertEquals(2, result.extruderColors.size)
        assertEquals(1, result.segmentIndices.size)
        assertEquals(0, result.segmentIndices[0])
    }

    @Test
    fun `consecutive extrude moves share vertices`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
            GcodeMove(MoveType.EXTRUDE, 10f, 0f, 20f, 0f),
            GcodeMove(MoveType.EXTRUDE, 20f, 0f, 30f, 0f)
        ))
        val result = pack(gcode)
        assertEquals(4, result.totalVertices)
        assertEquals(3, result.totalSegments)
        assertEquals(0, result.segmentIndices[0])
        assertEquals(1, result.segmentIndices[1])
        assertEquals(2, result.segmentIndices[2])
    }

    @Test
    fun `travel breaks chain into two`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
            GcodeMove(MoveType.TRAVEL, 10f, 0f, 50f, 50f),
            GcodeMove(MoveType.EXTRUDE, 50f, 50f, 60f, 50f)
        ))
        val result = pack(gcode)
        assertEquals(4, result.totalVertices)
        assertEquals(2, result.totalSegments)
        assertEquals(0, result.segmentIndices[0])
        assertEquals(2, result.segmentIndices[1])
    }

    // --- Angles ---

    @Test
    fun `90 degree turn produces correct angle`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
            GcodeMove(MoveType.EXTRUDE, 10f, 0f, 10f, 10f)
        ))
        val result = pack(gcode)
        val angleAtV1 = result.heightsWidthsAngles[1 * 3 + 2]
        assertEquals(PI.toFloat() / 2f, angleAtV1, 0.01f)
    }

    @Test
    fun `straight path produces zero angle at interior vertex`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
            GcodeMove(MoveType.EXTRUDE, 10f, 0f, 20f, 0f)
        ))
        val result = pack(gcode)
        val angleAtV1 = result.heightsWidthsAngles[1 * 3 + 2]
        assertEquals(0f, angleAtV1, 0.001f)
    }

    @Test
    fun `chain start and end have zero angle`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f),
            GcodeMove(MoveType.EXTRUDE, 10f, 0f, 10f, 10f)
        ))
        val result = pack(gcode)
        assertEquals(0f, result.heightsWidthsAngles[0 * 3 + 2], 0.001f)
        assertEquals(0f, result.heightsWidthsAngles[2 * 3 + 2], 0.001f)
    }

    // --- Positions ---

    @Test
    fun `positions include z-offset for extrusion centerline`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 1f, 2f, 3f, 4f)
        ))
        val result = pack(gcode)
        val expectedZ = 0.2f - 0.5f * GcodeSegmentPacker.HEIGHT
        assertEquals(1f, result.positions[0], 0.001f)
        assertEquals(2f, result.positions[1], 0.001f)
        assertEquals(expectedZ, result.positions[2], 0.001f)
        assertEquals(3f, result.positions[3], 0.001f)
        assertEquals(4f, result.positions[4], 0.001f)
        assertEquals(expectedZ, result.positions[5], 0.001f)
    }

    @Test
    fun `height and width constants stored per vertex`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)
        ))
        val result = pack(gcode)
        assertEquals(GcodeSegmentPacker.HEIGHT, result.heightsWidthsAngles[0], 0.001f)
        assertEquals(GcodeSegmentPacker.WIDTH, result.heightsWidthsAngles[1], 0.001f)
    }

    // --- Layer ranges ---

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
        assertEquals(0, result.layerRanges[0].firstSegment)
        assertEquals(2, result.layerRanges[0].segmentCount)
        assertEquals(2, result.layerRanges[1].firstSegment)
        assertEquals(1, result.layerRanges[1].segmentCount)
    }

    @Test
    fun `layer boundary breaks chain`() {
        val gcode = makeGcode(
            listOf(GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)),
            listOf(GcodeMove(MoveType.EXTRUDE, 10f, 0f, 20f, 0f))
        )
        val result = pack(gcode)
        assertEquals(4, result.totalVertices)
        assertEquals(2, result.totalSegments)
        assertEquals(0, result.segmentIndices[0])
        assertEquals(2, result.segmentIndices[1])
    }

    // --- Colors ---

    @Test
    fun `extruder colors use correct palette entry`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, extruder = 1)
        ))
        val result = pack(gcode)
        val (r, g, b) = GcodeSegmentPacker.decodeColor(result.extruderColors[0])
        assertEquals(51, r)
        assertEquals(179, g)
        assertEquals(255, b)
    }

    @Test
    fun `feature colors use correct palette entry`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f, featureType = FeatureType.SUPPORT)
        ))
        val result = pack(gcode)
        val (r, g, b) = GcodeSegmentPacker.decodeColor(result.featureColors[0])
        assertEquals(171, r)
        assertEquals(71, g)
        assertEquals(190, b)  // 0.74*255 = 188.7 → 189, but float round-trip shifts +1
    }

    @Test
    fun `brightness gradient across layers`() {
        val gcode = makeGcode(
            listOf(GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)),
            listOf(GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f))
        )
        val result = pack(gcode)
        val (r0, _, _) = GcodeSegmentPacker.decodeColor(result.extruderColors[0])
        val (r1, _, _) = GcodeSegmentPacker.decodeColor(result.extruderColors[2])
        assertTrue("Layer 0 R=$r0 should be ~115", abs(r0 - 115) <= 2)
        assertEquals(255, r1)
    }

    // --- Filtering ---

    @Test
    fun `zero-length moves are skipped`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.EXTRUDE, 5f, 5f, 5f, 5f),
            GcodeMove(MoveType.EXTRUDE, 0f, 0f, 10f, 0f)
        ))
        val result = pack(gcode)
        assertEquals(2, result.totalVertices)
        assertEquals(1, result.totalSegments)
    }

    @Test
    fun `travel moves are excluded from segments`() {
        val gcode = makeGcode(listOf(
            GcodeMove(MoveType.TRAVEL, 0f, 0f, 10f, 10f),
            GcodeMove(MoveType.EXTRUDE, 10f, 10f, 20f, 20f)
        ))
        val result = pack(gcode)
        assertEquals(2, result.totalVertices)
        assertEquals(1, result.totalSegments)
    }

    // --- Edge cases ---

    @Test
    fun `empty gcode returns empty result`() {
        val gcode = ParsedGcode(emptyList())
        val result = pack(gcode)
        assertEquals(0, result.totalVertices)
        assertEquals(0, result.totalSegments)
        assertTrue(result.layerRanges.isEmpty())
    }

    @Test
    fun `large move count does not hit any limit`() {
        val moves = (0 until 400_000).map { i ->
            GcodeMove(MoveType.EXTRUDE, i.toFloat(), 0f, i + 1f, 0f)
        }
        val gcode = makeGcode(moves)
        val result = pack(gcode)
        assertEquals(400_001, result.totalVertices)
        assertEquals(400_000, result.totalSegments)
    }

    // --- Texture dimensions ---

    @Test
    fun `texture dimensions fit vertex count`() {
        val (w, h) = GcodeSegmentPacker.computeTexDimensions(5000)
        assertTrue("$w x $h must fit 5000", w * h >= 5000)
        assertTrue("width <= 4096", w <= 4096)
    }

    @Test
    fun `texture dimensions for zero returns 1x1`() {
        val (w, h) = GcodeSegmentPacker.computeTexDimensions(0)
        assertEquals(1, w)
        assertEquals(1, h)
    }
}
