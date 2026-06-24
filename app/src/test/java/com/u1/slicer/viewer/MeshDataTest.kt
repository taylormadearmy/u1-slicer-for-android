package com.u1.slicer.viewer

import com.u1.slicer.bambu.LayerToolSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class MeshDataTest {

    private fun makeMesh(
        triangleCount: Int,
        extruderIndices: ByteArray? = ByteArray(triangleCount) { 0 }
    ): MeshData {
        val buf = MeshData.allocateBuffer(triangleCount)
        // Fill geometry with some dummy data so it's not all zeros
        for (i in 0 until buf.capacity()) {
            buf.put(i, i.toFloat())
        }

        val matIndices = extruderIndices?.let {
            val b = ByteBuffer.allocateDirect(it.size)
            b.put(it)
            b.position(0)
            b
        }

        val batch = NativeRenderBatch(buf, matIndices, triangleCount)
        return MeshData(
            batches = listOf(batch),
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 1f, maxY = 1f, maxZ = 1f
        )
    }

    private fun makeMeshWithModifier(
        triangleCount: Int,
        extruderIndices: ByteArray?,
        modifierStart: Int
    ): MeshData {
        val mesh = makeMesh(triangleCount, extruderIndices)
        return mesh.copy(modifierBlockStartTriangle = modifierStart)
    }

    @Test
    fun `recolor with null extruderIndices is no-op`() {
        val mesh = makeMesh(1, extruderIndices = null)
        val palette = listOf(floatArrayOf(1f, 0f, 0f, 1f))
        mesh.recolor(palette)
        assertNull(mesh.batches[0].colorBuffer)
    }

    @Test
    fun `recolor with empty palette is no-op`() {
        val indices = byteArrayOf(0)
        val mesh = makeMesh(1, indices)
        mesh.recolor(emptyList())
        assertNull(mesh.batches[0].colorBuffer)
    }

    @Test
    fun `allocateBuffer produces correct size for 10-float format`() {
        val triangleCount = 4
        val buf = MeshData.allocateBuffer(triangleCount)
        val expectedFloats = triangleCount * 3 * MeshData.FLOATS_PER_VERTEX
        assertEquals(expectedFloats, buf.capacity())
        assertEquals(10, MeshData.FLOATS_PER_VERTEX)
        assertEquals(40, MeshData.BYTES_PER_VERTEX)
    }

    @Test
    fun `hasPerVertexColor reflects extruderIndices presence`() {
        val withIndices = makeMesh(1, byteArrayOf(0))
        assertTrue(withIndices.hasPerVertexColor)

        val without = makeMesh(1, null)
        assertFalse(without.hasPerVertexColor)
    }

    @Test
    fun `recolor with 3-extruder palette applies distinct colors per object`() {
        val indices = byteArrayOf(0, 1, 2)
        val mesh = makeMesh(triangleCount = 3, extruderIndices = indices)
        val palette = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f)
        )
        mesh.recolor(palette)
        val cb = mesh.batches[0].colorBuffer!!
        for (tri in 0..2) {
            for (v in 0..2) {
                val base = (tri * 3 + v) * 4
                assertEquals("tri\$tri v\$v R", palette[tri][0], cb.get(base), 0.001f)
                assertEquals("tri\$tri v\$v G", palette[tri][1], cb.get(base + 1), 0.001f)
                assertEquals("tri\$tri v\$v B", palette[tri][2], cb.get(base + 2), 0.001f)
            }
        }
    }

    @Test
    fun `recolor called twice applies second palette correctly`() {
        val indices = byteArrayOf(0, 1)
        val mesh = makeMesh(triangleCount = 2, extruderIndices = indices)
        mesh.recolor(listOf(floatArrayOf(1f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 0f, 1f)))
        val palette2 = listOf(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(1f, 1f, 0f, 1f))
        mesh.recolor(palette2)
        val cb = mesh.batches[0].colorBuffer!!
        val base0 = 0 * 4
        assertEquals(0f, cb.get(base0), 0.001f)
        assertEquals(0f, cb.get(base0 + 1), 0.001f)
        assertEquals(1f, cb.get(base0 + 2), 0.001f)
        val base1 = 3 * 4
        assertEquals(1f, cb.get(base1), 0.001f)
        assertEquals(1f, cb.get(base1 + 1), 0.001f)
        assertEquals(0f, cb.get(base1 + 2), 0.001f)
    }

    @Test
    fun `recolorByZBands assigns colour based on Z centroid of each triangle`() {
        val triangleCount = 2
        val buf = MeshData.allocateBuffer(triangleCount)
        for (v in 0 until 3) { buf.put(v * 10 + 2, 0.1f) }
        for (v in 0 until 3) { buf.put((3 + v) * 10 + 2, 1.0f) }

        val batch = NativeRenderBatch(buf, null, triangleCount)
        val meshData = MeshData(
            batches = listOf(batch),
            minX = 0f, minY = 0f, minZ = 0f, maxX = 1f, maxY = 1f, maxZ = 2f
        )
        val segments = listOf(
            LayerToolSegment(topZ = 0.5f, extruderBambu = 1),
            LayerToolSegment(topZ = 1.0f, extruderBambu = 2)
        )
        val red   = floatArrayOf(1f, 0f, 0f, 1f)
        val green = floatArrayOf(0f, 1f, 0f, 1f)
        val palette = listOf(red, green)

        meshData.recolorByZBands(segments, palette)
        val cb = batch.colorBuffer!!

        for (v in 0 until 3) {
            assertEquals(1f, cb.get(v * 4), 0.001f)
            assertEquals(0f, cb.get(v * 4 + 1), 0.001f)
        }
        for (v in 0 until 3) {
            assertEquals(0f, cb.get((3 + v) * 4), 0.001f)
            assertEquals(1f, cb.get((3 + v) * 4 + 1), 0.001f)
        }
    }

    @Test
    fun `recolorByZBands falls back to extruder 1 when Z is below all segments`() {
        val triangleCount = 1
        val buf = MeshData.allocateBuffer(triangleCount)
        for (v in 0 until 3) { buf.put(v * 10 + 2, 0.0f) }
        val batch = NativeRenderBatch(buf, null, triangleCount)
        val meshData = MeshData(
            batches = listOf(batch),
            minX = 0f, minY = 0f, minZ = 0f, maxX = 1f, maxY = 1f, maxZ = 1f
        )
        val segments = listOf(LayerToolSegment(topZ = 1.0f, extruderBambu = 2))
        val blue = floatArrayOf(0f, 0f, 1f, 1f)
        val red  = floatArrayOf(1f, 0f, 0f, 1f)
        val palette = listOf(blue, red)

        meshData.recolorByZBands(segments, palette)
        val cb = batch.colorBuffer!!

        assertEquals(0f, cb.get(0), 0.001f)
        assertEquals(0f, cb.get(1), 0.001f)
        assertEquals(1f, cb.get(2), 0.001f)
    }

    @Test
    fun `recolor clamps index 255 to last palette entry`() {
        val indices = byteArrayOf(-1) // 0xFF = 255
        val mesh = makeMesh(1, indices)
        val palette = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f, 1f)
        )
        mesh.recolor(palette)
        val cb = mesh.batches[0].colorBuffer!!
        assertEquals(0f, cb.get(0), 0.001f)
        assertEquals(1f, cb.get(1), 0.001f)
    }

    @Test
    fun `recolor paints trailing modifier block translucent and leaves normal triangles on palette`() {
        val indices = byteArrayOf(0, 1, 0)
        val mesh = makeMeshWithModifier(3, indices, 2)
        val palette = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f, 1f)
        )
        mesh.recolor(palette)
        val cb = mesh.batches[0].colorBuffer!!
        run {
            val base = 0 * 4
            assertEquals("tri0 R", 1f, cb.get(base), 0.001f)
            assertEquals("tri0 G", 0f, cb.get(base + 1), 0.001f)
            assertEquals("tri0 A opaque", 1f, cb.get(base + 3), 0.001f)
        }
        run {
            val base = 3 * 4
            assertEquals("tri1 R", 0f, cb.get(base), 0.001f)
            assertEquals("tri1 G", 1f, cb.get(base + 1), 0.001f)
            assertEquals("tri1 A opaque", 1f, cb.get(base + 3), 0.001f)
        }
        val expected = MeshData.MODIFIER_PREVIEW_COLOR
        assertTrue("modifier preview colour must be translucent", expected[3] < 1f)
        for (v in 0 until 3) {
            val base = (2 * 3 + v) * 4
            assertEquals("mod v\$v R", expected[0], cb.get(base), 0.001f)
            assertEquals("mod v\$v G", expected[1], cb.get(base + 1), 0.001f)
            assertEquals("mod v\$v B", expected[2], cb.get(base + 2), 0.001f)
            assertEquals("mod v\$v A", expected[3], cb.get(base + 3), 0.001f)
        }
    }

    @Test
    fun `toWorldSpacePickingPositions returns empty array for oversize mesh to avoid OOM`() {
        val triCount = MeshData.MAX_PICKING_VERTEX_COUNT / 3 + 1
        val mesh = makeMesh(1)
        val bigMesh = mesh.copy(batches = listOf(NativeRenderBatch(mesh.batches[0].geometry, null, triCount)))
        val out = bigMesh.toWorldSpacePickingPositions(
            objectMeshRanges = null,
            instancePositions = floatArrayOf(135f, 135f),
            modelScale = floatArrayOf(1f, 1f, 1f)
        )
        assertEquals(0, out.size)
    }
}
