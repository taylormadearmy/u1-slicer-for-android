package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.1 — data-class contract for [CanonicalFilamentList] and [FilamentEntry].
 *
 * These tests exercise the pure data shape. Format-specific normalize tests
 * (Bambu 3MF, SEMM, layer-tool, STL, PrusaSlicer 3MF) live in instrumented
 * tests because they need real file fixtures.
 */
class CanonicalFilamentListTest {

    @Test
    fun `empty list reports size 0 and not multi-colour`() {
        val list = CanonicalFilamentList(filaments = emptyList())
        assertEquals(0, list.size)
        assertFalse(list.isMultiColour)
        assertTrue(list.paintStateMap.isEmpty())
    }

    @Test
    fun `single-entry list reports size 1 and not multi-colour`() {
        val list = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(
                    fileIndex = 0,
                    color = "#FF0000",
                    materialType = "PLA",
                    source = FilamentSource.FILE_COLOUR,
                )
            )
        )
        assertEquals(1, list.size)
        assertFalse(list.isMultiColour)
    }

    @Test
    fun `multi-entry list reports size N and is multi-colour`() {
        val list = CanonicalFilamentList(
            filaments = (0..3).map { i ->
                FilamentEntry(
                    fileIndex = i,
                    color = "#%06X".format(i * 0x111111),
                    materialType = "PLA",
                    source = FilamentSource.FILE_COLOUR,
                )
            }
        )
        assertEquals(4, list.size)
        assertTrue(list.isMultiColour)
    }

    @Test
    fun `FilamentEntry preserves source provenance`() {
        val entries = listOf(
            FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
            FilamentEntry(1, "#00FF00", "PLA", FilamentSource.PAINT_DERIVED),
            FilamentEntry(2, "#0000FF", "PETG", FilamentSource.OBJECT_DEFAULT),
            FilamentEntry(3, "#FFFFFF", null, FilamentSource.LAYER_TOOL),
            FilamentEntry(4, "#000000", "PLA", FilamentSource.STL_DEFAULT),
        )
        val list = CanonicalFilamentList(filaments = entries)
        assertEquals(FilamentSource.FILE_COLOUR, list.filaments[0].source)
        assertEquals(FilamentSource.PAINT_DERIVED, list.filaments[1].source)
        assertEquals(FilamentSource.OBJECT_DEFAULT, list.filaments[2].source)
        assertEquals(FilamentSource.LAYER_TOOL, list.filaments[3].source)
        assertEquals(FilamentSource.STL_DEFAULT, list.filaments[4].source)
    }

    @Test
    fun `FilamentEntry materialType may be null`() {
        val entry = FilamentEntry(
            fileIndex = 0,
            color = "#FFFFFF",
            materialType = null,
            source = FilamentSource.LAYER_TOOL,
        )
        assertNull(entry.materialType)
    }

    @Test
    fun `paintStateMap maps decoded paint state to fileIndex`() {
        // Buzz plate 9 shape: bit-packed paint state 11 addresses filament index 10
        // (the third entry of an 11-entry filament list, 0-based).
        val list = CanonicalFilamentList(
            filaments = (0..10).map { i ->
                FilamentEntry(i, "#%06X".format(i * 0x101010), "PLA", FilamentSource.FILE_COLOUR)
            },
            paintStateMap = mapOf(
                6 to 10,   // bit-packed "3C" decodes to state 6, addresses filament 10
                11 to 10,  // bit-packed "8C" decodes to state 11, also filament 10
            ),
        )
        assertEquals(10, list.paintStateMap[6])
        assertEquals(10, list.paintStateMap[11])
        assertEquals(11, list.size)
    }

    @Test
    fun `fileIndex is the G-code identity`() {
        // Documents the index-stability invariant: filament 3's color may
        // change but its fileIndex (and therefore the slicer's T-emission)
        // does not. The contract is exercised by data-class equality.
        val before = FilamentEntry(3, "#FF0000", "PLA", FilamentSource.FILE_COLOUR)
        val afterRecolor = before.copy(color = "#0000FF")
        assertEquals(before.fileIndex, afterRecolor.fileIndex)
    }

    // ─── stlCanonicalList ─────────────────────────────────────────────────

    @Test
    fun `stlCanonicalList synthesises a single STL_DEFAULT entry`() {
        val list = stlCanonicalList(presetColor = "#FF0000", presetMaterialType = "PLA")
        assertEquals(1, list.size)
        assertFalse(list.isMultiColour)
        assertTrue(list.paintStateMap.isEmpty())

        val only = list.filaments[0]
        assertEquals(0, only.fileIndex)
        assertEquals("#FF0000", only.color)
        assertEquals("PLA", only.materialType)
        assertEquals(FilamentSource.STL_DEFAULT, only.source)
    }

    @Test
    fun `stlCanonicalList passes nullable materialType through`() {
        val list = stlCanonicalList(presetColor = "#FFFFFF", presetMaterialType = null)
        assertEquals(1, list.size)
        assertNull(list.filaments[0].materialType)
    }

    @Test
    fun `stlCanonicalList reflects different colours per call`() {
        // The contract is "re-call rather than cache" — verify the call
        // produces a fresh entry each time so callers can re-render after
        // the user edits the extruder preset.
        val red = stlCanonicalList("#FF0000", "PLA")
        val orange = stlCanonicalList("#FFA500", "PLA")
        assertEquals("#FF0000", red.filaments[0].color)
        assertEquals("#FFA500", orange.filaments[0].color)
    }
}
