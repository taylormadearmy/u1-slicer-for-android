package com.u1.slicer.printer

import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentLibrary
import com.u1.slicer.data.FilamentLibraryEntry
import com.u1.slicer.data.LibrarySnapshotInfo
import com.u1.slicer.network.FilamentSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPreviewBuilderTest {

    private val presets = (0..3).map { ExtruderPreset(index = it, color = "#111111", materialType = "PLA") }
    private val lib = FilamentLibrary(
        entries = listOf(
            FilamentLibraryEntry("prusament-pla-galaxy-black", "Prusament", "PLA Prusa Galaxy Black", "PLA", hex = "#3E413F"),
        ),
        snapshot = LibrarySnapshotInfo("test", "2026-06-10", 1),
    )

    private fun slot(i: Int, vendor: String, type: String = "PLA", hex: String = "#3E413F") =
        FilamentSlot(index = i, label = "E${i + 1}", color = hex, loaded = true,
            materialType = type, subType = "", manufacturer = vendor)

    @Test
    fun `matched slot carries catalogue name colour material`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(0, "Prusament")), lib)
        val e = entries[0]
        assertEquals("Prusament PLA Prusa Galaxy Black", e.matchedName)
        assertEquals("prusament-pla-galaxy-black", e.matchedSlug)
        assertEquals("#3E413F", e.newColor)
        assertEquals("PLA", e.newType)
    }

    @Test
    fun `unmatched slot falls back to raw values exactly as before`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(0, "Snapmaker", hex = "#FF0000")), lib)
        val e = entries[0]
        assertNull(e.matchedName)
        assertNull(e.matchedSlug)
        assertEquals("#FF0000", e.newColor)
        assertEquals("PLA", e.newType)
    }

    @Test
    fun `null library means no matching - raw behaviour`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(0, "Prusament")), library = null)
        assertNull(entries[0].matchedName)
        assertEquals("#3E413F", entries[0].newColor)
    }

    @Test
    fun `missing printer slot yields null news`() {
        val entries = buildSyncPreviewEntries(presets, emptyList(), lib)
        assertEquals(4, entries.size)
        assertNull(entries[2].newColor)
        assertNull(entries[2].newType)
        assertNull(entries[2].matchedName)
    }

    @Test
    fun `four entries always built with current preset values`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(1, "Prusament")), lib)
        assertEquals(listOf("E1", "E2", "E3", "E4"), entries.map { it.label })
        assertEquals("#111111", entries[0].currentColor)
        assertEquals("Prusament PLA Prusa Galaxy Black", entries[1].matchedName)
    }
}
