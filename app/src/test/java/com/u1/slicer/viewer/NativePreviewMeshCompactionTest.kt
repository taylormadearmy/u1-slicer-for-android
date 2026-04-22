package com.u1.slicer.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B88: `NativePreviewMesh.toMeshData()` compacts raw extruder indices to 0..N-1.
 *
 * Buzz Lightyear plate 9 (11-filament source, plate uses filaments 10 & 11) came out of
 * the native mesh with raw indices 9 and 10. The Kotlin `MeshData.recolor` palette is
 * sized to the plate's compacted `detectedColors` (2 entries), so the raw high indices
 * clamped to `palette.lastIndex`, collapsing the preview to a single colour.
 */
class NativePreviewMeshCompactionTest {

    @Test
    fun compactExtruderIndices_buzzPlate9_mapsHighSparseIndicesToCompact() {
        // Indices 9 and 10 represent filaments 10 and 11 in the raw mesh.
        val raw = byteArrayOf(9, 9, 10, 10, 9, 10)
        val compacted = NativePreviewMesh.compactExtruderIndices(raw)
        // Sorted-unique order: 9 → 0, 10 → 1.
        assertArrayEquals(byteArrayOf(0, 0, 1, 1, 0, 1), compacted)
    }

    @Test
    fun compactExtruderIndices_alreadyCompact_isNoOp() {
        // Dragon/S-Buttons/Korok meshes arrive with compact 0..N-1 already.
        val raw = byteArrayOf(0, 1, 2, 3, 2, 1, 0)
        val compacted = NativePreviewMesh.compactExtruderIndices(raw)
        assertArrayEquals(raw, compacted)
    }

    @Test
    fun compactExtruderIndices_sparseGaps_remapsToContiguous() {
        // A model where filaments 0, 2, 5 are used (e.g. sparse H2C-style).
        val raw = byteArrayOf(0, 2, 5, 0, 5, 2)
        val compacted = NativePreviewMesh.compactExtruderIndices(raw)
        assertArrayEquals(byteArrayOf(0, 1, 2, 0, 2, 1), compacted)
    }

    @Test
    fun compactExtruderIndices_singleIndex_mapsToZero() {
        val raw = byteArrayOf(42, 42, 42)
        val compacted = NativePreviewMesh.compactExtruderIndices(raw)
        assertArrayEquals(byteArrayOf(0, 0, 0), compacted)
    }

    @Test
    fun compactExtruderIndices_empty_returnsEmpty() {
        val compacted = NativePreviewMesh.compactExtruderIndices(byteArrayOf())
        assertEquals(0, compacted.size)
    }

    @Test
    fun compactExtruderIndices_fullByteRange_preservesOrdering() {
        // Edge case: any value in 0..255 must remap without overflow.
        val raw = byteArrayOf(250.toByte(), 10, 250.toByte(), 10, 100)
        val compacted = NativePreviewMesh.compactExtruderIndices(raw)
        // Sorted: 10, 100, 250 → 0, 1, 2.
        assertArrayEquals(byteArrayOf(2, 0, 2, 0, 1), compacted)
    }
}
