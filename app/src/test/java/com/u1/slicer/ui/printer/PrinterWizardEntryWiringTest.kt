package com.u1.slicer.ui.printer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterWizardEntryWiringTest {
    @Test
    fun `printer settings and catalogue wizard present add paths as obvious buttons`() {
        val source = File("src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt").readText()
        val wizardScreen = File("src/main/java/com/u1/slicer/ui/machine/PrinterWizardScreen.kt").readText()

        assertTrue(source.contains("Snapmaker U1"))
        assertTrue(source.contains("Bambu Lab (LAN)"))
        assertTrue(source.contains("Other printer from Orca catalogue"))
        assertTrue(source.contains("PrinterWizardScreen"))
        assertTrue(source.contains("PrinterEditDialog"))
        assertTrue(wizardScreen.contains("OutlinedButton("))
        assertTrue(wizardScreen.contains("Icons.Default.ChevronRight"))
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
