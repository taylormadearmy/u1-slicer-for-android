package com.u1.slicer.ui.printer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrinterEditDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun octoPrintRequiresImportedKlipperProfile() {
        compose.setContent {
            MaterialTheme {
                PrinterEditDialog(null, onSave = { error("Unresolved machine must not save") }, onTest = { error("No printer should be contacted") }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Moonraker").performClick()
        compose.onNodeWithText("OctoPrint").performClick()
        compose.onNodeWithText("Printer URL").performScrollTo().performTextInput("http://192.0.2.1")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Import machine profile").assertExists()
    }

    @Test
    fun genericMachineCannotSaveWithoutProfile() {
        compose.setContent {
            MaterialTheme {
                PrinterEditDialog(null, onSave = { error("Unresolved machine must not save") }, onTest = { error("No printer should be contacted") }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Snapmaker U1").performClick()
        compose.onNodeWithText("Generic Klipper").performClick()
        compose.onNodeWithText("Printer URL").performScrollTo().performTextInput("http://192.0.2.1:7125")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Test connection").assertIsNotEnabled()
        compose.onNodeWithText("Import machine profile").assertExists()
    }

    @Test
    fun centauriSelectionSavesMatchingMachineAndProtocolWithoutPrinting() {
        var saved: Printer? = null
        compose.setContent {
            MaterialTheme {
                PrinterEditDialog(null, onSave = { saved = it }, onTest = { error("No printer should be contacted") }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Moonraker").performClick()
        compose.onNodeWithText("Elegoo Centauri Carbon 1 (beta)").performClick()
        compose.onNodeWithText("Printer IP or hostname").performTextInput("192.0.2.2")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(PrinterKind.ELEGOO_CC1, saved?.kind)
            assertEquals(MachineKind.ELEGOO_CENTAURI_CARBON, saved?.machine)
            assertEquals("192.0.2.2", saved?.host?.url)
            assertEquals("", saved?.host?.apiKey)
        }
    }
}
