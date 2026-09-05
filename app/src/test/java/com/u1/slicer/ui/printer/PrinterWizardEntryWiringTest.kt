package com.u1.slicer.ui.printer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterWizardEntryWiringTest {
    @Test
    fun `printer settings keeps U1 and Bambu add paths beside catalogue wizard`() {
        val source = File("src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt").readText()

        assertTrue(source.contains("Snapmaker U1"))
        assertTrue(source.contains("Bambu Lab (LAN)"))
        assertTrue(source.contains("Orca catalogue printer"))
        assertTrue(source.contains("PrinterWizardScreen"))
        assertTrue(source.contains("PrinterEditDialog"))
    }

    @Test
    fun `wizard confirmation is wired to native validation before profile persistence`() {
        val settings = File("src/main/java/com/u1/slicer/ui/SettingsScreen.kt").readText()
        val wizard = File("src/main/java/com/u1/slicer/machine/PrinterWizardViewModel.kt").readText()

        assertTrue(settings.contains("machineProfileValidator::validate"))
        assertTrue(settings.contains("printerViewModel::addProfilePrinter"))
        assertTrue(settings.contains("cachedOrcaProfileLookup.refreshVendor(vendor)"))
        assertTrue(wizard.indexOf("validate(profile)") < wizard.indexOf("save(profile"))
    }
}
