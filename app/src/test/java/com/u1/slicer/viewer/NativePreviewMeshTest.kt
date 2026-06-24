package com.u1.slicer.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

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
        
        val matIndices = mesh.batches[0].materialIndices
        assertNotNull(matIndices)
        val extracted = ByteArray(matIndices!!.remaining())
        matIndices.get(extracted)
        assertArrayEquals(byteArrayOf(0, 3), extracted)
    }

    @Test
    fun `toMeshData preserves sparse extruder indices for canonical palette lookup`() {
        val preview = NativePreviewMesh(
            trianglePositions = FloatArray(5 * 9) { idx ->
                when (idx % 9) {
                    0 -> 0f; 1 -> 0f; 2 -> 0f
                    3 -> 1f; 4 -> 0f; 5 -> 0f
                    6 -> 0f; 7 -> 1f; else -> 0f
                }
            },
            extruderIndices = byteArrayOf(0, 3, 4, 6, 9)
        )
        val mesh = preview.toMeshData()
        assertNotNull(mesh)
        val matIndices = mesh!!.batches[0].materialIndices
        val extracted = ByteArray(matIndices!!.remaining())
        matIndices.get(extracted)
        assertArrayEquals(byteArrayOf(0, 3, 4, 6, 9), extracted)
    }

    @Test
    fun `toMeshData carries modifier block start through to MeshData`() {
        val preview = NativePreviewMesh(
            trianglePositions = FloatArray(3 * 9) { idx ->
                when (idx % 9) {
                    0 -> 0f; 1 -> 0f; 2 -> 0f
                    3 -> 1f; 4 -> 0f; 5 -> 0f
                    6 -> 0f; 7 -> 1f; else -> 0f
                }
            },
            extruderIndices = byteArrayOf(0, 1, 0)
        )
        preview.modifierBlockStartTriangle = 2
        preview.batchRanges = listOf(0..0, 1..2)

        val mesh = preview.toMeshData()
        assertNotNull(mesh)
        assertEquals(2, mesh!!.modifierBlockStartTriangle)
    }

    @Test
    fun `toMeshData reports null modifier block when none present`() {
        val preview = NativePreviewMesh(
            trianglePositions = floatArrayOf(
                0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f
            ),
            extruderIndices = byteArrayOf(0)
        )

        val mesh = preview.toMeshData()
        assertNotNull(mesh)
        assertEquals(null, mesh!!.modifierBlockStartTriangle)
    }

    @Test
    fun `wouldExceedSafePreviewBudget safety net threshold is effectively unreachable`() {
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
        val triCount = 100_000
        val positions = FloatArray(triCount * 9) { idx ->
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
