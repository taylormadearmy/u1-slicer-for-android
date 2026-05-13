package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class AiPaintMeshBuilderTest {

    private fun quad(): FloatArray = floatArrayOf(
        0f, 0f, 0f,  1f, 0f, 0f,  1f, 1f, 0f,
        0f, 0f, 0f,  1f, 1f, 0f,  0f, 1f, 0f
    )

    @Test
    fun `build encodes per-triangle keys as extruder indices`() {
        val mesh = AiPaintMeshBuilder.build(quad(), byteArrayOf(0, 3))
        val idx = mesh.extruderIndices!!
        assertEquals(2, idx.size)
        assertEquals(0, idx[0].toInt())
        assertEquals(3, idx[1].toInt())
    }

    @Test
    fun `build computes bounding box from positions`() {
        val mesh = AiPaintMeshBuilder.build(quad(), byteArrayOf(0, 0))
        assertEquals(0f, mesh.minX, 0.001f)
        assertEquals(0f, mesh.minY, 0.001f)
        assertEquals(0f, mesh.minZ, 0.001f)
        assertEquals(1f, mesh.maxX, 0.001f)
        assertEquals(1f, mesh.maxY, 0.001f)
        assertEquals(0f, mesh.maxZ, 0.001f)
    }

    @Test
    fun `build vertexCount is 3 per triangle`() {
        val mesh = AiPaintMeshBuilder.build(quad(), byteArrayOf(0, 0))
        assertEquals(6, mesh.vertexCount)
    }

    @Test
    fun `regionPalette returns the supplied colours when non-empty`() {
        val red   = floatArrayOf(1f, 0f, 0f, 1f)
        val green = floatArrayOf(0f, 1f, 0f, 1f)
        val palette = AiPaintMeshBuilder.regionPalette(listOf(red, green))
        assertEquals(2, palette.size)
        assertSame(red, palette[0])
        assertSame(green, palette[1])
    }

    @Test
    fun `regionPalette returns a single grey fallback when input is empty`() {
        val palette = AiPaintMeshBuilder.regionPalette(emptyList())
        assertEquals(1, palette.size)
        val grey = palette[0]
        assertEquals(0.55f, grey[0], 0.001f)
        assertEquals(0.55f, grey[1], 0.001f)
        assertEquals(0.55f, grey[2], 0.001f)
        assertEquals(1f, grey[3], 0.001f)
    }
}
