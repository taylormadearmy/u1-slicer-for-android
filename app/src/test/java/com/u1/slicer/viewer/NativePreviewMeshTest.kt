package com.u1.slicer.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `wouldExceedSafePreviewBudget safety net threshold is effectively unreachable`() {
        // After F48 decimation, post-decimate counts stay at MAX_DECIMATED_TRIANGLES (100K),
        // well below any budget threshold. The 50M triangle hard-cap is a last-resort OOM guard.
        assertFalse(NativePreviewMesh.wouldExceedSafePreviewBudget(100_000))
        assertFalse(NativePreviewMesh.wouldExceedSafePreviewBudget(500_000))
        assertTrue(NativePreviewMesh.wouldExceedSafePreviewBudget(NativePreviewMesh.MAX_SAFE_TRIANGLES + 1))
    }

    @Test
    fun `MAX_DECIMATED_TRIANGLES is 100000`() {
        assertEquals(100_000, NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
    }

    @Test
    fun `toMeshData on subsampled NativePreviewMesh produces correct vertex count`() {
        // Simulate a model with 300K triangles decimated to 100K (stride=3)
        // by constructing a NativePreviewMesh with exactly 100K triangles.
        val triCount = 100_000
        val positions = FloatArray(triCount * 9) { idx ->
            // Alternate between two non-degenerate triangles
            when (idx % 9) {
                0 -> 0f; 1 -> 0f; 2 -> 0f
                3 -> 1f; 4 -> 0f; 5 -> 0f
                6 -> 0f; 7 -> 1f; else -> 0f
            }
        }
        val indices = ByteArray(triCount) { (it % 4).toByte() }
        val preview = NativePreviewMesh(positions, indices)
        val mesh = preview.toMeshData()
        assertNotNull(mesh)
        assertEquals(triCount * 3, mesh!!.vertexCount)
    }
}
