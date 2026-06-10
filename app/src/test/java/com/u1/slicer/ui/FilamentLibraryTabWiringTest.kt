package com.u1.slicer.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guards (spec §4.4): the Library tab exists ONLY in physical-slot
 * contexts. AiPaint slot dialog + PrinterScreen ExtruderSlotEditDialog host it;
 * CreateMixSlotDialog and the Prepare per-file dialog (MainActivity) stay HSV-only.
 */
class FilamentLibraryTabWiringTest {

    private fun src(p: String) = File(p).readText()

    @Test
    fun `colour dialog gains optional library tab`() {
        val dialog = src("src/main/java/com/u1/slicer/ui/FilamentColorEditDialog.kt")
        assertTrue(dialog.contains("libraryContent"))
        assertTrue(dialog.contains("TabRow") || dialog.contains("SegmentedButton"))
        assertTrue(dialog.contains("\"Library\""))
    }

    @Test
    fun `aipaint slot dialog passes library content`() {
        val s = src("src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt")
        assertTrue(s.contains("libraryContent"))
        assertTrue(s.contains("FilamentLibraryPicker"))
        assertTrue(s.contains("onPickLibraryFilament"))
    }

    @Test
    fun `mix and prepare dialogs stay hsv-only`() {
        assertFalse(src("src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt").contains("libraryContent"))
        // MainActivity hosts the Prepare per-file colour dialog — must not opt in.
        val mainActivity = src("src/main/java/com/u1/slicer/MainActivity.kt")
        assertFalse(mainActivity.contains("libraryContent"))
    }

    @Test
    fun `printer slot edit dialog hosts the picker`() {
        val s = src("src/main/java/com/u1/slicer/ui/PrinterScreen.kt")
        assertTrue(s.contains("FilamentLibraryPicker"))
        assertTrue(s.contains("\"Library\""))
    }

    @Test
    fun `slicer viewmodel applies library pick to preset colour and material`() {
        val s = src("src/main/java/com/u1/slicer/SlicerViewModel.kt")
        assertTrue(s.contains("fun applyLibraryPick"))
        assertTrue(s.contains("recordRecent"))
    }

    @Test
    fun `printer viewmodel exposes library state and profile import`() {
        val s = src("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt")
        assertTrue(s.contains("filamentLibraryRepository") || s.contains("libraryRepo"))
        assertTrue(s.contains("fun importLibraryProfile"))
    }
}
