package com.u1.slicer.viewer

import com.u1.slicer.bambu.LayerToolSegment
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
    fun `recolorByZBands assigns colour based on Z centroid of each triangle`() {
        val triangleCount = 2
        val buf = MeshData.allocateBuffer(triangleCount)
        // Fill triangle 0: z=0.1 at offset 2 for each of 3 vertices
        for (v in 0 until 3) { buf.put(v * 10 + 2, 0.1f) }
        // Fill triangle 1: z=1.0 at offset 2 for each of 3 vertices
        for (v in 0 until 3) { buf.put((3 + v) * 10 + 2, 1.0f) }

        val meshData = MeshData(
            vertices = buf,
            vertexCount = triangleCount * 3,
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 1f, maxY = 1f, maxZ = 2f
        )

        val segments = listOf(
            LayerToolSegment(topZ = 0.5f, extruderBambu = 1),  // Z <= 0.5 → extruder 1
            LayerToolSegment(topZ = 1.0f, extruderBambu = 2)   // Z <= 1.0 → extruder 2
        )
        val red   = floatArrayOf(1f, 0f, 0f, 1f)
        val green = floatArrayOf(0f, 1f, 0f, 1f)
        val palette = listOf(red, green)

        meshData.recolorByZBands(segments, palette)

        // Triangle 0 centroid=0.1: lastOrNull { topZ <= 0.1 } = none → default extruder 1 → extruder0=0 → index 0 → red
        for (v in 0 until 3) {
            assertEquals(1f, buf.get(v * 10 + 6), 0.001f)   // r=1
            assertEquals(0f, buf.get(v * 10 + 7), 0.001f)   // g=0
        }
        // Triangle 1 centroid=1.0: lastOrNull { topZ <= 1.0 } = topZ=1.0 → extruder 2 → extruder0=1 → index 1 → green
        for (v in 0 until 3) {
            assertEquals(0f, buf.get((3 + v) * 10 + 6), 0.001f)   // r=0
            assertEquals(1f, buf.get((3 + v) * 10 + 7), 0.001f)   // g=1
        }
    }

    @Test
    fun `recolorByZBands falls back to extruder 1 when Z is below all segments`() {
        val triangleCount = 1
        val buf = MeshData.allocateBuffer(triangleCount)
        for (v in 0 until 3) { buf.put(v * 10 + 2, 0.0f) }  // Z=0 — below all segments
        val meshData = MeshData(
            vertices = buf, vertexCount = 3,
            minX = 0f, minY = 0f, minZ = 0f, maxX = 1f, maxY = 1f, maxZ = 1f
        )
        val segments = listOf(LayerToolSegment(topZ = 1.0f, extruderBambu = 2))
        val blue = floatArrayOf(0f, 0f, 1f, 1f)
        val red  = floatArrayOf(1f, 0f, 0f, 1f)
        val palette = listOf(blue, red)

        meshData.recolorByZBands(segments, palette)

        // Z=0 is below topZ=1.0 so no segment matches → default extruder 1 → extruder0=0 → index 0 → blue
        assertEquals(0f, buf.get(6), 0.001f)   // r=0
        assertEquals(0f, buf.get(7), 0.001f)   // g=0
        assertEquals(1f, buf.get(8), 0.001f)   // b=1
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

    @Test
    fun `recolor paints trailing modifier block translucent and leaves normal triangles on palette`() {
        // F95: negative/modifier volumes are emitted by native as a contiguous trailing
        // block. recolor must paint triangles at/after modifierBlockStartTriangle with a
        // fixed translucent colour (so the renderer's blended second pass shows them as
        // see-through), while model-part triangles before the boundary keep their palette
        // colour and full opacity. The boundary is explicit — NOT a magic extruder index —
        // so the "255 clamps to last palette entry" contract below is unaffected.
        val indices = byteArrayOf(0, 1, 0) // tri2's extruder index is irrelevant once it's in the modifier block
        val vertexCount = 3 * 3
        val buf = MeshData.allocateBuffer(3)
        for (v in 0 until vertexCount) {
            buf.put(0f); buf.put(0f); buf.put(0f)
            buf.put(0f); buf.put(0f); buf.put(1f)
            buf.put(0f); buf.put(0f); buf.put(0f); buf.put(0f)
        }
        buf.rewind()
        val mesh = MeshData(
            vertices = buf, vertexCount = vertexCount,
            minX = 0f, minY = 0f, minZ = 0f, maxX = 1f, maxY = 1f, maxZ = 1f,
            extruderIndices = indices,
            modifierBlockStartTriangle = 2,
        )
        val palette = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),  // E1 red
            floatArrayOf(0f, 1f, 0f, 1f)   // E2 green
        )

        mesh.recolor(palette)

        val b = mesh.vertices
        // tri 0 → red, opaque (model part)
        run {
            val base = 0 * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals("tri0 R", 1f, b.get(base), 0.001f)
            assertEquals("tri0 G", 0f, b.get(base + 1), 0.001f)
            assertEquals("tri0 A opaque", 1f, b.get(base + 3), 0.001f)
        }
        // tri 1 → green, opaque (model part)
        run {
            val base = 3 * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals("tri1 R", 0f, b.get(base), 0.001f)
            assertEquals("tri1 G", 1f, b.get(base + 1), 0.001f)
            assertEquals("tri1 A opaque", 1f, b.get(base + 3), 0.001f)
        }
        // tri 2 → translucent modifier colour on all 3 vertices
        val expected = MeshData.MODIFIER_PREVIEW_COLOR
        assertTrue("modifier preview colour must be translucent (alpha < 1)", expected[3] < 1f)
        for (v in 0 until 3) {
            val base = (2 * 3 + v) * MeshData.FLOATS_PER_VERTEX + 6
            assertEquals("mod v$v R", expected[0], b.get(base), 0.001f)
            assertEquals("mod v$v G", expected[1], b.get(base + 1), 0.001f)
            assertEquals("mod v$v B", expected[2], b.get(base + 2), 0.001f)
            assertEquals("mod v$v A", expected[3], b.get(base + 3), 0.001f)
        }
    }

    @Test
    fun `toWorldSpacePickingPositions returns empty array for oversize mesh to avoid OOM`() {
        // Chubby_Darth_Vader_MULTI_COLOR.3mf regression (user report 2026-05-31):
        // a painted multi-colour 3MF arrived in Kotlin with ~2.85M triangles
        // (8.5M vertices) because MMU paint state geometry bypasses the QEM
        // decimation path. The F66 toWorldSpacePickingPositions function tried
        // to allocate a 8.5M-float (~34 MB) FloatArray that compounded with the
        // already-loaded VBO + native cache, OOM-killing the app within seconds
        // of the preview appearing.
        //
        // Guard: when vertexCount > MAX_PICKING_VERTEX_COUNT, return EMPTY.
        // The viewer's setTrianglePickingPositions(empty) falls back to
        // whole-object tap dispatch via cb(0), which is the correct UX for a
        // single large object anyway.
        //
        // We don't actually build a 2.85M-tri mesh here (the test would OOM
        // itself). The triangle count is metadata; only the gate matters.
        val triCount = 1
        val mesh = makeMesh(triCount).copy(
            vertexCount = MeshData.MAX_PICKING_VERTEX_COUNT + 1
        )
        val out = mesh.toWorldSpacePickingPositions(
            objectMeshRanges = null,
            instancePositions = floatArrayOf(135f, 135f),
            modelScale = floatArrayOf(1f, 1f, 1f),
        )
        assertEquals(
            "Oversize mesh must return EMPTY picking positions — non-empty would " +
                "have allocated ${(MeshData.MAX_PICKING_VERTEX_COUNT + 1) * 3 * 4} " +
                "extra bytes on top of the VBO and OOM'd the app (Chubby Darth Vader).",
            0, out.size,
        )
    }

    @Test
    fun `toWorldSpacePickingPositions allocates normally for mesh at-or-below the cap`() {
        // Regression guard: the OOM-gate must NOT fire on normal-sized meshes.
        // A 3-triangle mesh is well under the 1M-vertex cap.
        val mesh = makeMesh(triangleCount = 3)
        val out = mesh.toWorldSpacePickingPositions(
            objectMeshRanges = null,
            instancePositions = floatArrayOf(0f, 0f),
            modelScale = floatArrayOf(1f, 1f, 1f),
        )
        // 3 tris × 3 verts × 3 floats = 27 entries
        assertEquals(27, out.size)
    }
}
