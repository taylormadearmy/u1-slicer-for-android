package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class Find3DClientTest {

    private fun quad(): FloatArray = floatArrayOf(
        0f, 0f, 0f,  1f, 0f, 0f,  1f, 1f, 0f,   // tri 0 centroid (2/3, 1/3, 0)
        0f, 0f, 0f,  1f, 1f, 0f,  0f, 1f, 0f,   // tri 1 centroid (1/3, 2/3, 0)
    )

    @Test
    fun `buildAsciiPcdFromCentroids writes a valid PCD header with one point per triangle`() {
        val pcd = Find3DClient.buildAsciiPcdFromCentroids(quad()).decodeToString()
        assertTrue("missing FIELDS line: $pcd", pcd.contains("FIELDS x y z"))
        assertTrue("missing POINTS 2: $pcd", pcd.contains("POINTS 2"))
        assertTrue("missing WIDTH 2: $pcd", pcd.contains("WIDTH 2"))
        assertTrue("missing DATA ascii: $pcd", pcd.contains("DATA ascii"))
        // Centroid of tri 0 = ((0+1+1)/3, (0+0+1)/3, 0) = (0.6667, 0.3333, 0)
        assertTrue("missing tri 0 centroid: $pcd", pcd.contains("0.6667 0.3333 0.0000"))
        assertTrue("missing tri 1 centroid: $pcd", pcd.contains("0.3333 0.6667 0.0000"))
    }

    @Test
    fun `parseRgb accepts hex strings`() {
        val rgb = Find3DClient.parseRgb("#FF0000")!!
        assertEquals(1f, rgb[0], 0.001f)
        assertEquals(0f, rgb[1], 0.001f)
        assertEquals(0f, rgb[2], 0.001f)
    }

    @Test
    fun `parseRgb accepts rgb function strings`() {
        val rgb = Find3DClient.parseRgb("rgb(0, 255, 0)")!!
        assertEquals(0f, rgb[0], 0.001f)
        assertEquals(1f, rgb[1], 0.001f)
        assertEquals(0f, rgb[2], 0.001f)
    }

    @Test
    fun `parseRgb returns null on garbage`() {
        assertNull(Find3DClient.parseRgb("not a colour"))
        assertNull(Find3DClient.parseRgb(null))
    }

    @Test
    fun `nearestPaletteIndex picks the closest palette entry`() {
        // The Find3D palette: white, red, green, blue, yellow, magenta, cyan, grey
        assertEquals(0, Find3DClient.nearestPaletteIndex(floatArrayOf(0.95f, 0.97f, 0.99f), 8)) // ~white
        assertEquals(1, Find3DClient.nearestPaletteIndex(floatArrayOf(0.97f, 0.02f, 0.01f), 8)) // ~red
        assertEquals(2, Find3DClient.nearestPaletteIndex(floatArrayOf(0.10f, 0.92f, 0.05f), 8)) // ~green
        assertEquals(3, Find3DClient.nearestPaletteIndex(floatArrayOf(0.05f, 0.01f, 0.95f), 8)) // ~blue
    }

    @Test
    fun `nearestPaletteIndex respects labelLimit`() {
        // With limit=2, only white (0) and red (1) are options; blue should map to red.
        val rgb = floatArrayOf(0f, 0f, 0.95f)
        assertEquals(1, Find3DClient.nearestPaletteIndex(rgb, labelLimit = 2))
    }

    @Test
    fun `parseLabels reads marker color array and maps to palette indices`() {
        val payload = """
            [{"data":[{
                "type":"scatter3d",
                "marker":{"color":["#FFFFFF","#FF0000","#00FF00","#0000FF"]}
            }]}]
        """.trimIndent()
        val labels = Find3DClient.parseLabels(payload, expectedTriCount = 4, queryCount = 4)!!
        assertArrayEquals(intArrayOf(0, 1, 2, 3), labels)
    }

    @Test
    fun `parseLabels tolerates rgb function format`() {
        val payload = """
            [{"data":[{
                "marker":{"color":["rgb(255,255,255)","rgb(0,255,0)"]}
            }]}]
        """.trimIndent()
        val labels = Find3DClient.parseLabels(payload, expectedTriCount = 2, queryCount = 4)!!
        assertArrayEquals(intArrayOf(0, 2), labels)
    }

    @Test
    fun `parseLabels returns null on bad payload shape`() {
        assertNull(Find3DClient.parseLabels("not json", 4, 4))
        assertNull(Find3DClient.parseLabels("[{}]", 4, 4))
    }
}
