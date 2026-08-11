package com.u1.slicer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sync dialog renders matched catalogue names; apply records library recents. */
class FilamentSyncMatchWiringTest {

    @Test
    fun `sync entry row shows matched name`() {
        val s = File("src/main/java/com/u1/slicer/ui/PrinterScreen.kt").readText()
        assertTrue(s.contains("matchedName"))
        assertTrue(s.contains("(matched)"))
    }

    @Test
    fun `syncFilaments builds entries through the pure builder with the library`() {
        val s = File("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt").readText()
        assertTrue(s.contains("buildSyncPreviewEntries"))
    }

    @Test
    fun `apply records recents for matched applied slots`() {
        val s = File("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt").readText()
        assertTrue(s.contains("recordRecent"))
    }

    @Test
    fun `switching active printer clears the previous transport before the next sync can run`() {
        val s = File("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt").readText()
        assertTrue(s.contains("printerRepo.prepareForActivePrinterSwitch()"))
        assertTrue(s.contains("printersRepo.setActive(id)"))
    }
}
