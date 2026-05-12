package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class MeshThicknessComputerTest {

    // Slab from z=0 to z=height: bottom face (2 tris) + top face (2 tris, reversed winding).
    // Bottom normal = (0,0,+1), top normal = (0,0,-1) — consistent outward normals.
    private fun slabPositions(height: Float): FloatArray {
        val bottom = floatArrayOf(
            0f, 0f, 0f,  1f, 0f, 0f,  1f, 1f, 0f,
            0f, 0f, 0f,  1f, 1f, 0f,  0f, 1f, 0f
        )
        val top = floatArrayOf(
            0f, 0f, height,  1f, 1f, height,  1f, 0f, height,
            0f, 0f, height,  0f, 1f, height,  1f, 1f, height
        )
        return bottom + top
    }

    private fun cubePositions(): FloatArray {
        val b  = floatArrayOf(0f,0f,0f, 1f,0f,0f, 1f,1f,0f,  0f,0f,0f, 1f,1f,0f, 0f,1f,0f)
        val t  = floatArrayOf(0f,0f,1f, 1f,1f,1f, 1f,0f,1f,  0f,0f,1f, 0f,1f,1f, 1f,1f,1f)
        val f  = floatArrayOf(0f,0f,0f, 1f,0f,1f, 1f,0f,0f,  0f,0f,0f, 0f,0f,1f, 1f,0f,1f)
        val bk = floatArrayOf(0f,1f,0f, 1f,1f,0f, 1f,1f,1f,  0f,1f,0f, 1f,1f,1f, 0f,1f,1f)
        val l  = floatArrayOf(0f,0f,0f, 0f,1f,1f, 0f,1f,0f,  0f,0f,0f, 0f,0f,1f, 0f,1f,1f)
        val r  = floatArrayOf(1f,0f,0f, 1f,1f,0f, 1f,1f,1f,  1f,0f,0f, 1f,1f,1f, 1f,0f,1f)
        return b + t + f + bk + l + r
    }

    @Test
    fun `empty positions returns empty thickness array`() {
        val thickness = MeshThicknessComputer.compute(FloatArray(0))
        assertEquals(0, thickness.size)
    }

    @Test
    fun `output size matches triangle count`() {
        val positions = cubePositions()
        val thickness = MeshThicknessComputer.compute(positions)
        assertEquals(positions.size / 9, thickness.size)
    }

    @Test
    fun `thin slab thickness is close to slab height`() {
        val height = 0.1f
        val thickness = MeshThicknessComputer.compute(slabPositions(height))
        // All 4 triangles should measure approximately 0.1
        thickness.forEach { t ->
            assertEquals("slab thickness", height, t, 0.02f)
        }
    }

    @Test
    fun `thick slab thickness is close to slab height`() {
        val height = 1.0f
        val thickness = MeshThicknessComputer.compute(slabPositions(height))
        thickness.forEach { t ->
            assertEquals("slab thickness", height, t, 0.05f)
        }
    }

    @Test
    fun `cube face thickness is approximately 1 (opposite face distance)`() {
        val thickness = MeshThicknessComputer.compute(cubePositions())
        // A unit cube: each face's opposite face is exactly 1 unit away
        thickness.forEach { t ->
            assertEquals("cube face thickness ~1", 1.0f, t, 0.05f)
        }
    }

    @Test
    fun `all thickness values are non-negative`() {
        val thickness = MeshThicknessComputer.compute(cubePositions())
        thickness.forEach { t ->
            assertTrue("thickness >= 0, got $t", t >= 0f)
        }
    }
}
