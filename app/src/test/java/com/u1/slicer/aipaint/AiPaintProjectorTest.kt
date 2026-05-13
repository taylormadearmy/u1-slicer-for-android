package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class AiPaintProjectorTest {

    // A quad spanning X=-1..1 (left/right) and Z=0..1 (vertical, 3D-printing convention).
    private fun unitMesh(): FloatArray = floatArrayOf(
        -1f, 0f, 0f,   1f, 0f, 0f,   1f, 0f, 1f,
        -1f, 0f, 0f,   1f, 0f, 1f,  -1f, 0f, 1f,
    )

    @Test
    fun `projected centroid lies inside the rendered viewport`() {
        val positions = unitMesh()
        for (angle in CameraAngle.entries) {
            val projector = AiPaintProjector.build(positions, angle, 512, 512)
            val (sx, sy) = projector.project(0f, 0f, 0.5f)
            assertTrue("$angle sx out of range: $sx", sx in 0f..1f)
            assertTrue("$angle sy out of range: $sy", sy in 0f..1f)
        }
    }

    @Test
    fun `left vs right are mirrored in front view`() {
        val positions = unitMesh()
        val projector = AiPaintProjector.build(positions, CameraAngle.FRONT, 512, 512)
        val (leftSx, _)  = projector.project(-1f, 0f, 0.5f)
        val (rightSx, _) = projector.project( 1f, 0f, 0.5f)
        assertTrue("left-most point should land in left half: $leftSx",  leftSx  < 0.5f)
        assertTrue("right-most point should land in right half: $rightSx", rightSx > 0.5f)
    }

    @Test
    fun `top of model lands near top of image`() {
        val positions = unitMesh()
        val projector = AiPaintProjector.build(positions, CameraAngle.FRONT, 512, 512)
        // The renderer flips Y so the top of the model (highest Z in 3D-printing convention)
        // should land at the top of the image (low sy).
        val (_, topSy)    = projector.project(0f, 0f, 1f)
        val (_, bottomSy) = projector.project(0f, 0f, 0f)
        assertTrue("top should have lower sy than bottom: top=$topSy bottom=$bottomSy", topSy < bottomSy)
    }
}

class AiRegionBoxesTest {

    @Test
    fun `isInBox accepts points inside the box`() {
        val r = AiRegionBoxes("X", "#fff", mapOf(CameraAngle.FRONT to floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f)))
        assertTrue(r.isInBox(CameraAngle.FRONT, 0.5f, 0.5f))
        assertTrue(r.isInBox(CameraAngle.FRONT, 0.1f, 0.1f))  // exact corner
        assertTrue(r.isInBox(CameraAngle.FRONT, 0.9f, 0.9f))  // opposite corner
    }

    @Test
    fun `isInBox rejects points outside the box`() {
        val r = AiRegionBoxes("X", "#fff", mapOf(CameraAngle.FRONT to floatArrayOf(0.2f, 0.2f, 0.4f, 0.4f)))
        assertFalse(r.isInBox(CameraAngle.FRONT, 0.5f, 0.5f))
        assertFalse(r.isInBox(CameraAngle.FRONT, 0.1f, 0.3f))
    }

    @Test
    fun `isInBox rejects an empty box`() {
        val r = AiRegionBoxes("X", "#fff", mapOf(CameraAngle.FRONT to floatArrayOf(0f, 0f, 0f, 0f)))
        assertFalse(r.isInBox(CameraAngle.FRONT, 0f, 0f))
    }

    @Test
    fun `summedArea adds box areas across views`() {
        val r = AiRegionBoxes(
            "X", "#fff",
            mapOf(
                CameraAngle.FRONT     to floatArrayOf(0f, 0f, 0.5f, 0.5f),     // 0.25
                CameraAngle.BACK      to floatArrayOf(0f, 0f, 0.4f, 0.5f),     // 0.20
                CameraAngle.LEFT_ISO  to floatArrayOf(0f, 0f, 0f, 0f),         // empty
                CameraAngle.RIGHT_ISO to floatArrayOf(0.5f, 0.5f, 1f, 1f),     // 0.25
            )
        )
        assertEquals(0.70f, r.summedArea(), 0.001f)
    }
}

class AiPaintRegionAssignerTest {

    @Test
    fun `single full-screen region wins every triangle`() {
        val positions = floatArrayOf(
            0f, 0f, 0f,  1f, 0f, 0f,  0f, 1f, 0f,
            5f, 5f, 0f,  6f, 5f, 0f,  5f, 6f, 0f,
        )
        val regions = listOf(
            AiRegionBoxes("Body", "#888", CameraAngle.entries.associateWith { floatArrayOf(0f, 0f, 1f, 1f) })
        )
        val projectors = CameraAngle.entries.associateWith { AiPaintProjector.build(positions, it, 512, 512) }
        val perTri = AiPaintRegionAssigner.assign(positions, regions, projectors)
        assertEquals(2, perTri.size)
        assertEquals(0, perTri[0]); assertEquals(0, perTri[1])
    }

    @Test
    fun `smaller region wins on tie via area tiebreak`() {
        val positions = floatArrayOf(
            -1f, 0f, -1f,   1f, 0f, -1f,   1f, 0f, 1f,
            -1f, 0f, -1f,   1f, 0f,  1f,  -1f, 0f, 1f,
        )
        val small = AiRegionBoxes("Detail", "#f00", CameraAngle.entries.associateWith { floatArrayOf(0.3f, 0.3f, 0.7f, 0.7f) })
        val large = AiRegionBoxes("Body",   "#888", CameraAngle.entries.associateWith { floatArrayOf(0f, 0f, 1f, 1f) })
        val regions = listOf(large, small)
        val projectors = CameraAngle.entries.associateWith { AiPaintProjector.build(positions, it, 512, 512) }
        val perTri = AiPaintRegionAssigner.assign(positions, regions, projectors)
        assertEquals("smaller region should win on tie", 1, perTri[0])
    }

    @Test
    fun `triangles outside every box default to region 0`() {
        val positions = floatArrayOf(0f, 0f, 0f,  1f, 0f, 0f,  0f, 1f, 0f)
        val offscreen = AiRegionBoxes("Empty", "#fff",
            CameraAngle.entries.associateWith { floatArrayOf(0f, 0f, 0f, 0f) })
        val projectors = CameraAngle.entries.associateWith { AiPaintProjector.build(positions, it, 512, 512) }
        val perTri = AiPaintRegionAssigner.assign(positions, listOf(offscreen), projectors)
        assertEquals(0, perTri[0])
    }
}
