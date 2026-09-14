package com.u1.slicer.ui.printer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalPrinterAddWiringTest {
    private val card = File("src/main/java/com/u1/slicer/ui/printer/PrintersSettingsCard.kt").readText()
    private val editor = File("src/main/java/com/u1/slicer/ui/printer/PrinterEditDialog.kt").readText()
    private val settings = File("src/main/java/com/u1/slicer/ui/SettingsScreen.kt").readText()
    private val settingsRepository = File("src/main/java/com/u1/slicer/data/SettingsRepository.kt").readText()
    private val printerViewModel = File("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt").readText()

    @Test
    fun `add chooser exposes every printer route and labels third party routes experimental`() {
        assertTrue(card.contains("Snapmaker U1"))
        assertTrue(card.contains("Bambu Lab (LAN) · Experimental"))
        assertTrue(card.contains("Other printer from Orca catalogue · Experimental"))
        assertTrue(card.contains("Import .orca_printer bundle · Experimental"))
        assertTrue(card.contains("I understand, continue"))
    }

    @Test
    fun `acknowledgement resumes the chosen route while cancel launches nothing`() {
        AddPrinterChoice.entries.forEach { choice ->
            assertTrue("missing selection wiring for $choice", card.contains("selectAddChoice(AddPrinterChoice.${choice.name})"))
        }
        assertTrue(card.contains("launchAddChoice(choice)"))
        assertTrue(card.contains("onDismissRequest = { pendingExperimentalChoice = null }"))
    }

    @Test
    fun `experimental acknowledgement never hides or switches saved Bambu printers`() {
        assertTrue(settings.contains("printers = printerList"))
        assertFalse(settings.contains("filter { it.kind != com.u1.slicer.data.PrinterKind.BAMBU_LAN }"))
        assertFalse(printerViewModel.contains("disableBambuMode"))
    }

    @Test
    fun `legacy Bambu acknowledgement remains the one time experimental acknowledgement`() {
        assertTrue(settingsRepository.contains("experimentalPrinterSupportAcknowledged"))
        assertTrue(settingsRepository.contains("prefs[Keys.BAMBU_BETA_ENABLED] ?: false"))
        assertTrue(settingsRepository.contains("prefs[Keys.BAMBU_BETA_ENABLED] = true"))
        assertFalse(settingsRepository.contains("EXPERIMENTAL_PRINTER_SUPPORT"))
    }

    @Test
    fun `Bambu creation stays on its acknowledged add route`() {
        assertTrue(editor.contains("if (initialKind == PrinterKind.BAMBU_LAN)"))
        assertTrue(editor.contains("listOf(HostType.BAMBU_LAN)"))
        assertTrue(editor.contains("listOf(HostType.MOONRAKER, HostType.OCTOPRINT, HostType.PRUSALINK)"))
    }
}
