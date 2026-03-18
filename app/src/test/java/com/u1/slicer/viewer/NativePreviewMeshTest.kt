package com.u1.slicer.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NativePreviewMeshTest {

    @Test
    fun `toMeshData converts triangle payload into mesh data`() {
        val preview = NativePreviewMesh(
            trianglePositions = floatArrayOf(
                0f, 0f, 0f,
                10f, 0f, 0f,
                0f, 10f, 0f,
                20f, 0f, 0f,
                30f, 0f, 0f,
                20f, 10f, 0f
            ),
            extruderIndices = byteArrayOf(0, 3)
        )

        val mesh = preview.toMeshData()

        assertNotNull(mesh)
        assertEquals(6, mesh!!.vertexCount)
        assertEquals(0f, mesh.minX, 0.001f)
        assertEquals(30f, mesh.maxX, 0.001f)
        assertEquals(0f, mesh.minY, 0.001f)
        assertEquals(10f, mesh.maxY, 0.001f)
        assertArrayEquals(byteArrayOf(0, 3), mesh.extruderIndices)
    }
}
