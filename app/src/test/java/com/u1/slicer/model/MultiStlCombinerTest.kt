package com.u1.slicer.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MultiStlCombinerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Generate a minimal binary STL with [triangles] (each is 9 floats: 3 verts × xyz).
     * Normals are all (0, 0, 1) so we can ignore them in assertions.
     */
    private fun writeBinaryStl(name: String, triangles: List<FloatArray>): File {
        val f = tmp.newFile(name)
        val payload = ByteBuffer.allocate(80 + 4 + triangles.size * 50)
            .order(ByteOrder.LITTLE_ENDIAN)
        // 80-byte header (zeroed) — already zeroed by allocate().
        payload.position(80)
        payload.putInt(triangles.size)
        for (tri in triangles) {
            // normal (0, 0, 1)
            payload.putFloat(0f); payload.putFloat(0f); payload.putFloat(1f)
            for (i in 0 until 9) payload.putFloat(tri[i])
            payload.putShort(0) // attribute count
        }
        f.writeBytes(payload.array())
        return f
    }

    private fun readTriangleCount(file: File): Int {
        val bytes = file.readBytes()
        return ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /** Read the AABB of all vertices in a binary STL. */
    private fun readAabb(file: File): FloatArray {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80)
        val triCount = buf.int
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (t in 0 until triCount) {
            buf.position(buf.position() + 12)
            for (v in 0 until 3) {
                val x = buf.float; val y = buf.float; val z = buf.float
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
            buf.position(buf.position() + 2)
        }
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** Single 10×10 mm cube at origin (12 triangles via 6 quads). Simplified: one triangle. */
    private fun simpleTriangle(originX: Float, originY: Float, sizeMm: Float = 10f): List<FloatArray> {
        // 1 triangle: (0,0,0), (sizeMm,0,0), (0,sizeMm,sizeMm) shifted by origin.
        return listOf(
            floatArrayOf(
                originX, originY, 0f,
                originX + sizeMm, originY, 0f,
                originX, originY + sizeMm, sizeMm,
            )
        )
    }

    @Test fun `combine three small STLs produces a single STL with all triangles`() {
        val a = writeBinaryStl("a.stl", simpleTriangle(0f, 0f))
        val b = writeBinaryStl("b.stl", simpleTriangle(0f, 0f))
        val c = writeBinaryStl("c.stl", simpleTriangle(0f, 0f))
        val out = tmp.newFile("combined.stl")

        val result = MultiStlCombiner.combine(listOf(a, b, c), out)
        assertNotNull(result)
        result!!
        assertEquals(3, result.placedParts.size)
        assertTrue(result.failed.isEmpty())
        assertTrue(result.oversize.isEmpty())
        assertEquals(3, readTriangleCount(out))
        assertEquals(3, result.totalTriangles)
    }

    @Test fun `combine snaps each part to bed plane on Z`() {
        // Input STL whose min Z is 50 (floating above bed). Combined output's
        // global minZ should be 0 because the combiner snaps each part.
        val triangles = listOf(
            floatArrayOf(0f, 0f, 50f, 10f, 0f, 50f, 0f, 10f, 60f)
        )
        val f = writeBinaryStl("a.stl", triangles)
        val out = tmp.newFile("combined.stl")
        MultiStlCombiner.combine(listOf(f), out)!!
        val aabb = readAabb(out)
        assertEquals("Z minimum must snap to 0", 0f, aabb[2], 0.001f)
    }

    @Test fun `combine lays out parts so their footprints don't overlap on the bed`() {
        val a = writeBinaryStl("a.stl", simpleTriangle(0f, 0f, sizeMm = 20f))
        val b = writeBinaryStl("b.stl", simpleTriangle(0f, 0f, sizeMm = 20f))
        val c = writeBinaryStl("c.stl", simpleTriangle(0f, 0f, sizeMm = 20f))
        val out = tmp.newFile("combined.stl")
        MultiStlCombiner.combine(listOf(a, b, c), out)!!

        val aabb = readAabb(out)
        // Three 20mm parts in a row with 5mm margin = 70mm wide. Combined
        // XY span must be at least 60 mm (3 parts plus gaps) — proves the
        // combiner translated parts to distinct grid cells, not stacked them.
        val width = aabb[3] - aabb[0]
        val depth = aabb[4] - aabb[1]
        assertTrue("Combined width $width should reflect grid layout, not overlap", width >= 60f)
        // For a single row, depth equals one cell.
        assertTrue("Combined depth $depth should be a single grid cell (~20mm)", depth < 30f)
    }

    @Test fun `combine reports failed STLs but continues with the rest`() {
        val good = writeBinaryStl("good.stl", simpleTriangle(0f, 0f))
        val bogus = tmp.newFile("bogus.stl").apply { writeBytes(ByteArray(10)) }
        val out = tmp.newFile("combined.stl")

        val result = MultiStlCombiner.combine(listOf(good, bogus), out)!!
        assertEquals(1, result.placedParts.size)
        assertEquals(listOf(bogus), result.failed)
        assertTrue(result.oversize.isEmpty())
    }

    @Test fun `combine rejects parts exceeding bed dimensions`() {
        // 300 mm part exceeds 270 mm bed — should be classified as oversize.
        val huge = writeBinaryStl(
            "huge.stl",
            listOf(floatArrayOf(0f, 0f, 0f, 300f, 0f, 0f, 0f, 300f, 0f))
        )
        val small = writeBinaryStl("small.stl", simpleTriangle(0f, 0f))
        val out = tmp.newFile("combined.stl")
        val result = MultiStlCombiner.combine(listOf(huge, small), out)!!
        assertEquals(1, result.placedParts.size)
        assertEquals(listOf(huge), result.oversize)
    }

    @Test fun `combine returns null when no parts placeable`() {
        val huge = writeBinaryStl(
            "huge.stl",
            listOf(floatArrayOf(0f, 0f, 0f, 300f, 0f, 0f, 0f, 300f, 0f))
        )
        val out = tmp.newFile("combined.stl")
        val result = MultiStlCombiner.combine(listOf(huge), out)
        assertNull("No placeable parts → null", result)
        assertFalse(out.exists() && out.length() > 80L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `combine requires at least one input file`() {
        MultiStlCombiner.combine(emptyList(), tmp.newFile("x.stl"))
    }
}
