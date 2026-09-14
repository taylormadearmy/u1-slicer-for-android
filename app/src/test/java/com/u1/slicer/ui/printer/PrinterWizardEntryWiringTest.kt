package com.u1.slicer.ui.printer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterWizardEntryWiringTest {
    @Test
    fun `printer settings keeps U1 and experimental add paths beside catalogue wizard`() {
        val source = File("src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt").readText()

        assertTrue(source.contains("Snapmaker U1"))
        assertTrue(source.contains("Bambu Lab (LAN) · Experimental"))
        assertTrue(source.contains("Other printer from Orca catalogue · Experimental"))
        assertTrue(source.contains("PrinterWizardScreen"))
        assertTrue(source.contains("PrinterEditDialog"))
    }

    @Test
    fun `wizard confirmation is wired to native validation before profile persistence`() {
        val settings = File("src/main/java/com/u1/slicer/ui/SettingsScreen.kt").readText()
        val wizard = File("src/main/java/com/u1/slicer/machine/PrinterWizardViewModel.kt").readText()

        assertTrue(settings.contains("machineProfileValidator::validate"))
        assertTrue(settings.contains("hostType = connection.hostType"))
        assertTrue(settings.contains("apiKey = connection.apiKey"))
        assertTrue(settings.contains("username = connection.username"))
        assertTrue(settings.contains("password = connection.password"))
        assertTrue(settings.contains("cachedOrcaProfileLookup.refreshVendor(vendor)"))
        assertTrue(wizard.indexOf("validate(profile)") < wizard.indexOf("save("))
    }
}
