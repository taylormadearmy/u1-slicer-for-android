package com.u1.slicer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OrcaProfileImportWiringTest {
    @Test
    fun `printer settings imports orca printer bundles through SAF and the validated importer`() {
        val card = File("src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt").readText()
        val settings = File("src/main/java/com/u1/slicer/ui/SettingsScreen.kt").readText()

        assertTrue(card.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(card.contains("Import .orca_printer bundle"))
        assertTrue(settings.contains("orcaProfileBundleImporter.import"))
        assertTrue(settings.contains("addProfilePrinter"))
    }

    @Test
    fun `filament library routes orca filament bundles through the same importer`() {
        val source = File("src/main/java/com/u1/slicer/ui/FilamentScreen.kt").readText()

        assertTrue(source.contains(".orca_filament"))
        assertTrue(source.contains("orcaProfileBundleImporter.import"))
        assertTrue(source.contains("parseFilamentJson"))
    }
}
