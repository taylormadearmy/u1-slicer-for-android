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
    fun `add chooser uses obvious buttons and hides experimental routes behind a switch`() {
        assertTrue(card.contains("Snapmaker U1"))
        assertTrue(card.contains("AddPrinterChoiceButton("))
        assertTrue(card.contains("OutlinedButton("))
        assertTrue(card.contains("Icons.Default.ChevronRight"))
        assertTrue(card.contains("Switch("))
        assertTrue(card.contains("Experimental printer support"))
        assertTrue(card.contains("if (experimentalPrinterSupportEnabled)"))
    }

    @Test
    fun `enabled chooser exposes every experimental route without a second acknowledgement`() {
        AddPrinterChoice.entries.forEach { choice ->
            assertTrue("missing launch wiring for $choice", card.contains("launchAddChoice(AddPrinterChoice.${choice.name})"))
        }
        assertTrue(card.contains("Bambu Lab (LAN)"))
        assertTrue(card.contains("Other printer from Orca catalogue"))
        assertTrue(card.contains("Import .orca_printer bundle"))
        assertFalse(card.contains("I understand, continue"))
        assertFalse(card.contains("pendingExperimentalChoice"))
    }

    @Test
    fun `experimental acknowledgement never hides or switches saved Bambu printers`() {
        assertTrue(settings.contains("printers = printerList"))
        assertFalse(settings.contains("filter { it.kind != com.u1.slicer.data.PrinterKind.BAMBU_LAN }"))
        assertFalse(printerViewModel.contains("disableBambuMode"))
    }

    @Test
    fun `legacy Bambu opt in key backs the reversible experimental switch`() {
        assertTrue(settingsRepository.contains("experimentalPrinterSupportEnabled"))
        assertTrue(settingsRepository.contains("prefs[Keys.BAMBU_BETA_ENABLED] ?: false"))
        assertTrue(settingsRepository.contains("prefs[Keys.BAMBU_BETA_ENABLED] = enabled"))
        assertFalse(settingsRepository.contains("EXPERIMENTAL_PRINTER_SUPPORT"))
    }

    @Test
    fun `Bambu creation stays on its acknowledged add route`() {
        assertTrue(editor.contains("if (initialKind == PrinterKind.BAMBU_LAN)"))
        assertTrue(editor.contains("listOf(HostType.BAMBU_LAN)"))
        assertTrue(editor.contains("listOf(HostType.MOONRAKER, HostType.OCTOPRINT, HostType.PRUSALINK)"))
    }
}
