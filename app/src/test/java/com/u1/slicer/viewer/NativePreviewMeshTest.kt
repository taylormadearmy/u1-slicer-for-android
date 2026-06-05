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
        // Phase 2 (Approach A1): toMeshData preserves raw native extruder
        // indices verbatim — they are file-filament-indexed (0-based) so the
        // Phase 2 canonical palette resolves the correct colour at each index.
        // The previous B88 Kotlin-side compaction was correct for the pre-
        // Phase-2 plate-narrowed palette but produced wrong colours for Buzz
        // Lightyear plate 9 once the palette became file-filament-indexed.
        assertArrayEquals(byteArrayOf(0, 3), mesh.extruderIndices)
    }

    @Test
    fun `toMeshData preserves sparse extruder indices for canonical palette lookup`() {
        // Phase 2: sparse / high raw paint-state indices stay as-is so
        // `MeshData.recolor(palette)` — where the palette is the file's
        // canonical filament list (file-filament-indexed, palette[i] is file
        // filament i+1) — resolves the correct colour for each triangle.
        // Buzz Lightyear plate 9 ships raw indices 7/9 (paint states 8/10);
        // those must reach the recolor step intact so palette[7] (file
        // filament 8 / peach) and palette[9] (file filament 10 / white) light
        // up rather than collapsing to palette[0] / palette[1].
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
        // Phase 2 contract: indices preserved verbatim.
        assertArrayEquals(byteArrayOf(0, 3, 4, 6, 9), mesh!!.extruderIndices)
    }

    @Test
    fun `toMeshData carries modifier block start through to MeshData`() {
        // F95: native appends negative/modifier-volume triangles as a contiguous trailing
        // block and reports where it starts. toMeshData must forward that boundary so
        // recolor() and the renderer can treat the trailing triangles as translucent.
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

        val mesh = preview.toMeshData()
        assertNotNull(mesh)
        assertEquals(2, mesh!!.modifierBlockStartTriangle)
    }

    @Test
    fun `toMeshData reports null modifier block when none present`() {
        // Default (no modifier volumes): the sentinel -1 maps to null on MeshData so
        // recolor() takes the all-model-parts path.
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
