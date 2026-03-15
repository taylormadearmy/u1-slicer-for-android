package com.u1.slicer.viewer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StlParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parse binary STL single triangle`() {
        val file = createBinaryStl(
            triangles = listOf(
                Triangle(
                    normal = floatArrayOf(0f, 0f, 1f),
                    v1 = floatArrayOf(0f, 0f, 0f),
                    v2 = floatArrayOf(10f, 0f, 0f),
                    v3 = floatArrayOf(5f, 10f, 0f)
                )
            )
        )
        val mesh = StlParser.parse(file)

        assertEquals(3, mesh.vertexCount)
        assertEquals(0f, mesh.minX, 0.001f)
        assertEquals(10f, mesh.maxX, 0.001f)
        assertEquals(0f, mesh.minY, 0.001f)
        assertEquals(10f, mesh.maxY, 0.001f)
        assertEquals(0f, mesh.minZ, 0.001f)
        assertEquals(0f, mesh.maxZ, 0.001f)
    }

    @Test
    fun `parse binary STL bounding box`() {
        val file = createBinaryStl(
            triangles = listOf(
                Triangle(
                    normal = floatArrayOf(0f, 0f, 1f),
                    v1 = floatArrayOf(-5f, -3f, 0f),
                    v2 = floatArrayOf(15f, 0f, 0f),
                    v3 = floatArrayOf(0f, 20f, 8f)
                )
            )
        )
        val mesh = StlParser.parse(file)

        assertEquals(-5f, mesh.minX, 0.001f)
        assertEquals(15f, mesh.maxX, 0.001f)
        assertEquals(-3f, mesh.minY, 0.001f)
        assertEquals(20f, mesh.maxY, 0.001f)
        assertEquals(0f, mesh.minZ, 0.001f)
        assertEquals(8f, mesh.maxZ, 0.001f)
    }

    @Test
    fun `parse binary STL center calculation`() {
        val file = createBinaryStl(
            triangles = listOf(
                Triangle(
                    normal = floatArrayOf(0f, 0f, 1f),
                    v1 = floatArrayOf(0f, 0f, 0f),
                    v2 = floatArrayOf(20f, 0f, 0f),
                    v3 = floatArrayOf(10f, 10f, 10f)
                )
            )
        )
        val mesh = StlParser.parse(file)

        assertEquals(10f, mesh.centerX, 0.001f)
        assertEquals(5f, mesh.centerY, 0.001f)
        assertEquals(5f, mesh.centerZ, 0.001f)
    }

    @Test
    fun `parse binary STL multiple triangles`() {
        val file = createBinaryStl(
            triangles = listOf(
                Triangle(
                    normal = floatArrayOf(0f, 0f, 1f),
                    v1 = floatArrayOf(0f, 0f, 0f),
                    v2 = floatArrayOf(1f, 0f, 0f),
                    v3 = floatArrayOf(0f, 1f, 0f)
                ),
                Triangle(
                    normal = floatArrayOf(0f, 0f, 1f),
                    v1 = floatArrayOf(1f, 0f, 0f),
                    v2 = floatArrayOf(1f, 1f, 0f),
                    v3 = floatArrayOf(0f, 1f, 0f)
                )
            )
        )
        val mesh = StlParser.parse(file)

        assertEquals(6, mesh.vertexCount) // 2 triangles * 3 vertices
    }

    @Test
    fun `parse ASCII STL single triangle`() {
        val file = tempFolder.newFile("test.stl")
        file.writeText("""
            solid test
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 10 0 0
                  vertex 5 10 0
                endloop
              endfacet
            endsolid test
        """.trimIndent())

        val mesh = StlParser.parse(file)

        assertEquals(3, mesh.vertexCount)
        assertEquals(0f, mesh.minX, 0.001f)
        assertEquals(10f, mesh.maxX, 0.001f)
        assertEquals(0f, mesh.minY, 0.001f)
        assertEquals(10f, mesh.maxY, 0.001f)
    }

    @Test
    fun `parse ASCII STL with scientific notation`() {
        val file = tempFolder.newFile("test.stl")
        file.writeText("""
            solid sci
              facet normal 0.0e+0 0.0e+0 1.0e+0
                outer loop
                  vertex 1.5e+1 0.0e+0 0.0e+0
                  vertex 0.0e+0 2.0e+1 0.0e+0
                  vertex 0.0e+0 0.0e+0 5.0e+0
                endloop
              endfacet
            endsolid sci
        """.trimIndent())

        val mesh = StlParser.parse(file)

        assertEquals(3, mesh.vertexCount)
        assertEquals(15f, mesh.maxX, 0.001f)
        assertEquals(20f, mesh.maxY, 0.001f)
        assertEquals(5f, mesh.maxZ, 0.001f)
    }

    @Test
    fun `MeshData size calculations`() {
        val file = createBinaryStl(
            triangles = listOf(
                Triangle(
                    normal = floatArrayOf(0f, 0f, 1f),
                    v1 = floatArrayOf(0f, 0f, 0f),
                    v2 = floatArrayOf(30f, 0f, 0f),
                    v3 = floatArrayOf(0f, 20f, 10f)
                )
            )
        )
        val mesh = StlParser.parse(file)

        assertEquals(30f, mesh.sizeX, 0.001f)
        assertEquals(20f, mesh.sizeY, 0.001f)
        assertEquals(10f, mesh.sizeZ, 0.001f)
        assertEquals(30f, mesh.maxDimension, 0.001f)
    }

    @Test
    fun `MeshData allocateBuffer correct size`() {
        val buf = MeshData.allocateBuffer(2)
        // 2 triangles * 3 verts * 10 floats = 60 floats capacity
        assertEquals(60, buf.capacity())
    }

    @Test
    fun `parse vertex data is interleaved position and normal`() {
        val file = createBinaryStl(
            triangles = listOf(
                Triangle(
                    normal = floatArrayOf(0f, 0f, 1f),
                    v1 = floatArrayOf(1f, 2f, 3f),
                    v2 = floatArrayOf(4f, 5f, 6f),
                    v3 = floatArrayOf(7f, 8f, 9f)
                )
            )
        )
        val mesh = StlParser.parse(file)

        // Read back: x,y,z,nx,ny,nz,r,g,b,a for first vertex
        mesh.vertices.position(0)
        assertEquals(1f, mesh.vertices.get(), 0.001f) // x
        assertEquals(2f, mesh.vertices.get(), 0.001f) // y
        assertEquals(3f, mesh.vertices.get(), 0.001f) // z
        assertEquals(0f, mesh.vertices.get(), 0.001f) // nx
        assertEquals(0f, mesh.vertices.get(), 0.001f) // ny
        assertEquals(1f, mesh.vertices.get(), 0.001f) // nz
        assertEquals(0.7f, mesh.vertices.get(), 0.001f) // r
        assertEquals(0.7f, mesh.vertices.get(), 0.001f) // g
        assertEquals(0.7f, mesh.vertices.get(), 0.001f) // b
        assertEquals(1.0f, mesh.vertices.get(), 0.001f) // a
    }

    @Test
    fun `parsed STL has null extruderIndices`() {
        val binaryFile = createBinaryStl(
            triangles = listOf(
                Triangle(
                    normal = floatArrayOf(0f, 0f, 1f),
                    v1 = floatArrayOf(0f, 0f, 0f),
                    v2 = floatArrayOf(1f, 0f, 0f),
                    v3 = floatArrayOf(0f, 1f, 0f)
                )
            )
        )
        val binaryMesh = StlParser.parse(binaryFile)
        assertNull("Binary STL should have null extruderIndices", binaryMesh.extruderIndices)
        assertFalse(binaryMesh.hasPerVertexColor)

        val asciiFile = tempFolder.newFile("ascii.stl")
        asciiFile.writeText("""
            solid test
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 1 0 0
                  vertex 0 1 0
                endloop
              endfacet
            endsolid test
        """.trimIndent())
        val asciiMesh = StlParser.parse(asciiFile)
        assertNull("ASCII STL should have null extruderIndices", asciiMesh.extruderIndices)
        assertFalse(asciiMesh.hasPerVertexColor)
    }

    // --- Helpers ---

    private data class Triangle(
        val normal: FloatArray,
        val v1: FloatArray,
        val v2: FloatArray,
        val v3: FloatArray
    )

    private fun createBinaryStl(triangles: List<Triangle>): File {
        val file = tempFolder.newFile("test.stl")
        val headerSize = 80
        val countSize = 4
        val triangleSize = 50 // 12 floats * 4 bytes + 2 byte attribute
        val totalSize = headerSize + countSize + triangles.size * triangleSize

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        // Header (80 bytes of zeros)
        buf.put(ByteArray(80))
        // Triangle count
        buf.putInt(triangles.size)
        // Triangles
        for (tri in triangles) {
            buf.putFloat(tri.normal[0]); buf.putFloat(tri.normal[1]); buf.putFloat(tri.normal[2])
            buf.putFloat(tri.v1[0]); buf.putFloat(tri.v1[1]); buf.putFloat(tri.v1[2])
            buf.putFloat(tri.v2[0]); buf.putFloat(tri.v2[1]); buf.putFloat(tri.v2[2])
            buf.putFloat(tri.v3[0]); buf.putFloat(tri.v3[1]); buf.putFloat(tri.v3[2])
            buf.putShort(0) // attribute byte count
        }

        file.writeBytes(buf.array())
        return file
    }
}
