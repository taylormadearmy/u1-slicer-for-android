package com.u1.slicer.viewer

import org.junit.Assert.*
import org.junit.Test

class TrianglePickerTest {

    // Two triangles forming a unit quad on Z=0
    private fun quad(): FloatArray = floatArrayOf(
        0f, 0f, 0f,  1f, 0f, 0f,  1f, 1f, 0f,   // tri 0: lower-right
        0f, 0f, 0f,  1f, 1f, 0f,  0f, 1f, 0f    // tri 1: upper-left
    )

    @Test
    fun `vertical ray straight down hits lower triangle near 0_75 0_25`() {
        val hit = TrianglePicker.pick(
            quad(),
            ox = 0.75f, oy = 0.25f, oz = 10f,
            dx = 0f, dy = 0f, dz = -1f
        )
        assertEquals(0, hit)
    }

    @Test
    fun `vertical ray straight down hits upper triangle near 0_25 0_75`() {
        val hit = TrianglePicker.pick(
            quad(),
            ox = 0.25f, oy = 0.75f, oz = 10f,
            dx = 0f, dy = 0f, dz = -1f
        )
        assertEquals(1, hit)
    }

    @Test
    fun `ray that misses the quad returns -1`() {
        val hit = TrianglePicker.pick(
            quad(),
            ox = 5f, oy = 5f, oz = 10f,
            dx = 0f, dy = 0f, dz = -1f
        )
        assertEquals(-1, hit)
    }

    @Test
    fun `closer triangle wins when stacked along ray`() {
        val stacked = floatArrayOf(
            0f, 0f, 0f,  1f, 0f, 0f,  0f, 1f, 0f,   // tri 0 at Z=0
            0f, 0f, 5f,  1f, 0f, 5f,  0f, 1f, 5f    // tri 1 at Z=5
        )
        // Ray from above pointing down — tri 1 (Z=5) is closer
        val hit = TrianglePicker.pick(
            stacked,
            ox = 0.1f, oy = 0.1f, oz = 10f,
            dx = 0f, dy = 0f, dz = -1f
        )
        assertEquals(1, hit)
    }

    @Test
    fun `ray parallel to triangle plane returns -1`() {
        val hit = TrianglePicker.pick(
            quad(),
            ox = 0.5f, oy = 0.5f, oz = 1f,
            dx = 1f, dy = 0f, dz = 0f  // moves in +X only, never touches Z=0
        )
        assertEquals(-1, hit)
    }
}
