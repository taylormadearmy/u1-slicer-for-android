package com.u1.slicer.viewer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshDataTest {

    /** Helper: create a MeshData with the given triangle count and extruder indices. */
    private fun makeMesh(triangleCount: Int, extruderIndices: ByteArray? = null): MeshData {
        val vertexCount = triangleCount * 3
        val buf = MeshData.allocateBuffer(triangleCount)
        // Fill position (0,0,0) + normal (0,0,1) + color (0,0,0,0) per vertex
        for (v in 0 until vertexCount) {
            buf.put(0f); buf.put(0f); buf.put(0f)   // x,y,z
            buf.put(0f); buf.put(0f); buf.put(1f)   // nx,ny,nz
            buf.put(0f); buf.put(0f); buf.put(0f); buf.put(0f) // r,g,b,a
        }
        buf.rewind()
        return MeshData(
            vertices = buf,
            vertexCount = vertexCount,
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 1f, maxY = 1f, maxZ = 1f,
            extruderIndices = extruderIndices
        )
    }

    @Test
    fun `recolor writes correct RGBA for each triangle`() {
        // 2 triangles: tri 0 = extruder 0 (red), tri 1 = extruder 1 (green)
        val indices = byteArrayOf(0, 1)
        val mesh = makeMesh(2, indices)
        val palette = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),  // red
            floatArrayOf(0f, 1f, 0f, 1f)   // green
        )

        mesh.recolor(palette)

        val buf = mesh.vertices
        // Triangle 0: 3 vertices, each should be red
        for (v in 0 until 3) {
            val base = v * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals("tri0 v$v R", 1f, buf.get(base), 0.001f)
            assertEquals("tri0 v$v G", 0f, buf.get(base + 1), 0.001f)
            assertEquals("tri0 v$v B", 0f, buf.get(base + 2), 0.001f)
            assertEquals("tri0 v$v A", 1f, buf.get(base + 3), 0.001f)
        }
        // Triangle 1: 3 vertices, each should be green
        for (v in 0 until 3) {
            val base = (3 + v) * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals("tri1 v$v R", 0f, buf.get(base), 0.001f)
            assertEquals("tri1 v$v G", 1f, buf.get(base + 1), 0.001f)
            assertEquals("tri1 v$v B", 0f, buf.get(base + 2), 0.001f)
            assertEquals("tri1 v$v A", 1f, buf.get(base + 3), 0.001f)
        }
    }

    @Test
    fun `recolor clamps out-of-range index to last palette entry`() {
        // 1 triangle with extruder index 5, palette has only 2 entries
        val indices = byteArrayOf(5)
        val mesh = makeMesh(1, indices)
        val palette = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),  // red
            floatArrayOf(0f, 0f, 1f, 1f)   // blue (last)
        )

        mesh.recolor(palette)

        val buf = mesh.vertices
        for (v in 0 until 3) {
            val base = v * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals("v$v R", 0f, buf.get(base), 0.001f)
            assertEquals("v$v G", 0f, buf.get(base + 1), 0.001f)
            assertEquals("v$v B", 1f, buf.get(base + 2), 0.001f)
            assertEquals("v$v A", 1f, buf.get(base + 3), 0.001f)
        }
    }

    @Test
    fun `recolor with null extruderIndices is no-op`() {
        val mesh = makeMesh(1, extruderIndices = null)
        val palette = listOf(floatArrayOf(1f, 0f, 0f, 1f))

        // Fill color slots with a known value to verify they're untouched
        val buf = mesh.vertices
        for (v in 0 until 3) {
            val base = v * MeshData.FLOATS_PER_VERTEX + 6
            buf.put(base, 0.5f)
            buf.put(base + 1, 0.5f)
            buf.put(base + 2, 0.5f)
            buf.put(base + 3, 0.5f)
        }

        mesh.recolor(palette)

        // Values should remain 0.5
        for (v in 0 until 3) {
            val base = v * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals(0.5f, buf.get(base), 0.001f)
            assertEquals(0.5f, buf.get(base + 1), 0.001f)
            assertEquals(0.5f, buf.get(base + 2), 0.001f)
            assertEquals(0.5f, buf.get(base + 3), 0.001f)
        }
    }

    @Test
    fun `recolor with empty palette is no-op`() {
        val indices = byteArrayOf(0)
        val mesh = makeMesh(1, indices)

        val buf = mesh.vertices
        for (v in 0 until 3) {
            val base = v * MeshData.FLOATS_PER_VERTEX + 6
            buf.put(base, 0.5f)
        }

        mesh.recolor(emptyList())

        assertEquals(0.5f, buf.get(6), 0.001f)
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
        // 3 triangles, each assigned to a different extruder (like dragon cube plate 3)
        val indices = byteArrayOf(0, 1, 2)
        val mesh = makeMesh(triangleCount = 3, extruderIndices = indices)
        val palette = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),  // E1 = red
            floatArrayOf(0f, 1f, 0f, 1f),  // E2 = green
            floatArrayOf(0f, 0f, 1f, 1f)   // E3 = blue
        )
        mesh.recolor(palette)
        val buf = mesh.vertices
        for (tri in 0..2) {
            for (v in 0..2) {
                val base = (tri * 3 + v) * MeshData.FLOATS_PER_VERTEX + 6
                assertEquals("tri$tri v$v R", palette[tri][0], buf.get(base), 0.001f)
                assertEquals("tri$tri v$v G", palette[tri][1], buf.get(base + 1), 0.001f)
                assertEquals("tri$tri v$v B", palette[tri][2], buf.get(base + 2), 0.001f)
            }
        }
    }

    @Test
    fun `recolor called twice applies second palette correctly`() {
        val indices = byteArrayOf(0, 1)
        val mesh = makeMesh(triangleCount = 2, extruderIndices = indices)
        // First recolor: red, green
        mesh.recolor(listOf(floatArrayOf(1f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 0f, 1f)))
        // Second recolor: blue, yellow
        val palette2 = listOf(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(1f, 1f, 0f, 1f))
        mesh.recolor(palette2)
        val buf = mesh.vertices
        // Triangle 0, vertex 0 should be blue now
        val base0 = 0 * MeshData.FLOATS_PER_VERTEX + 6
        assertEquals(0f, buf.get(base0), 0.001f)
        assertEquals(0f, buf.get(base0 + 1), 0.001f)
        assertEquals(1f, buf.get(base0 + 2), 0.001f)
        // Triangle 1, vertex 0 should be yellow
        val base1 = 3 * MeshData.FLOATS_PER_VERTEX + 6
        assertEquals(1f, buf.get(base1), 0.001f)
        assertEquals(1f, buf.get(base1 + 1), 0.001f)
        assertEquals(0f, buf.get(base1 + 2), 0.001f)
    }

    @Test
    fun `recolor clamps index 255 to last palette entry`() {
        // 0xFF = 255 unsigned; buildMeshData never stores 0xFF (NONE falls back to volumeExtruderIdx)
        // but recolor() must handle it safely by clamping to lastIndex
        val indices = byteArrayOf(-1) // 0xFF = 255
        val mesh = makeMesh(1, indices)
        val palette = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),  // index 0
            floatArrayOf(0f, 1f, 0f, 1f)   // index 1 = last
        )

        mesh.recolor(palette)

        // 255 > palette size, clamps to last entry (green)
        val buf = mesh.vertices
        val base = 6
        assertEquals(0f, buf.get(base), 0.001f)
        assertEquals(1f, buf.get(base + 1), 0.001f)
    }
}
