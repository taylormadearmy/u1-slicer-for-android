package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class AiPaintMeshBuilderTest {

    private fun quad(): FloatArray = floatArrayOf(
        0f, 0f, 0f,  1f, 0f, 0f,  1f, 1f, 0f,
        0f, 0f, 0f,  1f, 1f, 0f,  0f, 1f, 0f
    )

    @Test
    fun `build encodes component ids as extruder indices`() {
        val mesh = AiPaintMeshBuilder.build(quad(), intArrayOf(0, 3))
        val idx = mesh.extruderIndices!!
        assertEquals(2, idx.size)
        assertEquals(0, idx[0].toInt())
        assertEquals(3, idx[1].toInt())
    }

    @Test
    fun `build computes bounding box from positions`() {
        val mesh = AiPaintMeshBuilder.build(quad(), intArrayOf(0, 0))
        assertEquals(0f, mesh.minX, 0.001f)
        assertEquals(0f, mesh.minY, 0.001f)
        assertEquals(0f, mesh.minZ, 0.001f)
        assertEquals(1f, mesh.maxX, 0.001f)
        assertEquals(1f, mesh.maxY, 0.001f)
        assertEquals(0f, mesh.maxZ, 0.001f)
    }

    @Test
    fun `build vertexCount is 3 per triangle`() {
        val mesh = AiPaintMeshBuilder.build(quad(), intArrayOf(0, 0))
        assertEquals(6, mesh.vertexCount)
    }

    @Test
    fun `buildPalette indexes by component id, not region id`() {
        val red   = floatArrayOf(1f, 0f, 0f, 1f)
        val green = floatArrayOf(0f, 1f, 0f, 1f)
        // 3 components: c0 → region 1 (green), c1 → region 0 (red), c2 → region 1 (green)
        val palette = AiPaintMeshBuilder.buildPalette(
            numComponents = 3,
            componentToRegion = intArrayOf(1, 0, 1),
            regionColours = listOf(red, green)
        )
        assertEquals(3, palette.size)
        assertArrayEquals(green, palette[0], 0.001f)
        assertArrayEquals(red,   palette[1], 0.001f)
        assertArrayEquals(green, palette[2], 0.001f)
    }

    @Test
    fun `buildPalette highlight overrides region colours`() {
        val red = floatArrayOf(1f, 0f, 0f, 1f)
        val palette = AiPaintMeshBuilder.buildPalette(
            numComponents = 4,
            componentToRegion = intArrayOf(0, 0, 0, 0),
            regionColours = listOf(red),
            highlightComponentId = 2
        )
        // Highlighted component should be yellow-ish (high R+G, low B)
        val hl = palette[2]
        assertTrue("highlighted R should be ≥ 0.9: ${hl[0]}", hl[0] >= 0.9f)
        assertTrue("highlighted G should be ≥ 0.9: ${hl[1]}", hl[1] >= 0.9f)
        assertTrue("highlighted B should be ≤ 0.3: ${hl[2]}", hl[2] <= 0.3f)
        // Others should be dim, not red
        val dim = palette[0]
        assertTrue("dimmed R should be < 0.4: ${dim[0]}", dim[0] < 0.4f)
    }

    @Test
    fun `buildPalette uses fallback grey when componentToRegion has out-of-range region id`() {
        val red = floatArrayOf(1f, 0f, 0f, 1f)
        val palette = AiPaintMeshBuilder.buildPalette(
            numComponents = 2,
            componentToRegion = intArrayOf(0, 99),  // 99 is invalid
            regionColours = listOf(red)
        )
        // c0 gets red, c1 gets fallback (not red, not crash)
        assertArrayEquals(red, palette[0], 0.001f)
        assertFalse("invalid mapping should not yield red", palette[1].contentEquals(red))
    }
}
