package com.u1.slicer.ui.printer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.isToggleable
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

    @Test fun changingGenericMachineToU1ClearsThePreviousProfileBinding() {
        val snapshot = com.u1.slicer.slice.GenericMachineProfile.fromJson(
            org.json.JSONObject(com.u1.slicer.slice.GenericMachineProfile.centauriCarbon().resolvedJson)
                .put("machine_start_gcode", "G28").put("machine_end_gcode", "M104 S0").toString()
        )
        val existing = Printer("generic", "Workshop", moonrakerUrl = "http://127.0.0.1:9",
            machineProfile = snapshot, machineProfileId = "old-generic-profile")
        var saved: Printer? = null
        compose.setContent {
            MaterialTheme {
                PrinterEditDialog(existing, onSave = { saved = it },
                    onTest = { error("No printer should be contacted") }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Generic Klipper").performScrollTo().performClick()
        compose.onNode(hasText("Snapmaker U1") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(MachineKind.SNAPMAKER_U1, saved?.machine)
            org.junit.Assert.assertNull(saved?.machineProfile)
            org.junit.Assert.assertNull(saved?.machineProfileId)
        }
    }

    @Test fun existingOfflineU1SavesInstalledNozzleDiameterForEachHead() {
        val existing = Printer("offline-u1", "Offline U1")
        var saved: Printer? = null
        compose.setContent {
            MaterialTheme {
                PrinterEditDialog(existing, onSave = { saved = it },
                    onTest = { error("Offline settings must not contact a printer") }, onDismiss = {})
            }
        }
        repeat(4) { slot ->
            compose.onNodeWithText("E${slot + 1} nozzle").performScrollTo()
                .assertTextContains("0.4 mm")
        }
        listOf(0.2f, 0.4f, 0.6f, 0.8f).forEachIndexed { slot, diameter ->
            compose.onNodeWithText("E${slot + 1} nozzle").performScrollTo().performClick()
            compose.onNode(hasText("$diameter mm") and hasAnyAncestor(isPopup())).performClick()
        }
        compose.onNodeWithText("Test connection").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf(0.2f, 0.4f, 0.6f, 0.8f), saved?.u1Nozzles?.diametersMm)
            assertEquals("", saved?.moonrakerUrl)
            assertEquals(existing.id, saved?.id)
        }
    }

    @Test fun defaultU1AddRouteHidesExperimentalConnectionsAndMachines() {
        compose.setContent {
            MaterialTheme {
                PrinterEditDialog(null, onSave = { error("No printer should save") },
                    onTest = { error("No host should be contacted") }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Moonraker").performClick()
        compose.onNodeWithText("OctoPrint").assertDoesNotExist()
        compose.onNodeWithText("Elegoo Centauri Carbon 1 (beta)").assertDoesNotExist()
        compose.onAllNodesWithText("Moonraker")[1].performClick()
        compose.onNodeWithText("Snapmaker U1").performClick()
        compose.onNodeWithText("Generic Klipper").assertDoesNotExist()
        compose.onNodeWithText("Generic Marlin").assertDoesNotExist()
    }

    @Test fun experimentalChooserMakesCc1DiscoverableAndSavesItsBuiltInMachine() {
        val enabled = androidx.compose.runtime.mutableStateOf(false)
        var saved: Printer? = null
        compose.setContent {
            MaterialTheme {
                PrintersSettingsCard(emptyList(), null, onAdd = { saved = it }, onEdit = {}, onDelete = {},
                    onTestConnection = { error("No host should be contacted") },
                    experimentalPrinterSupportEnabled = enabled.value,
                    onExperimentalPrinterSupportEnabledChange = { enabled.value = it })
            }
        }
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Elegoo Centauri Carbon 1 (beta)").assertDoesNotExist()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Elegoo Centauri Carbon 1 (beta)").performClick()
        compose.onNodeWithText("Printer IP or hostname").performScrollTo().performTextInput("192.0.2.5")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(PrinterKind.ELEGOO_CC1, saved?.kind)
            assertEquals(com.u1.slicer.data.HostType.ELEGOO_CC1, saved?.hostType)
            assertEquals(MachineKind.ELEGOO_CENTAURI_CARBON, saved?.machine)
            assertEquals("192.0.2.5", saved?.host?.url)
            assertEquals(com.u1.slicer.slice.SlicerTarget.ElegooCentauriCarbon,
                com.u1.slicer.slice.resolveDefaultSliceTarget(saved))
            assertEquals(257f, com.u1.slicer.bambu.resolveTargetedSliceConfig(
                com.u1.slicer.slice.SlicerTarget.ElegooCentauriCarbon, com.u1.slicer.data.SliceConfig()).bedSizeX)
        }
    }

    @Test fun savedCc1RemainsEditableWhenExperimentalSupportIsOff() {
        val printer = Printer("saved-cc1", "Saved CC1", PrinterKind.ELEGOO_CC1,
            host = com.u1.slicer.data.PrintHostConfig("192.0.2.6"))
        var saved: Printer? = null
        compose.setContent {
            MaterialTheme {
                PrintersSettingsCard(listOf(printer), printer.id, onAdd = {}, onEdit = { saved = it }, onDelete = {},
                    onTestConnection = { error("No host should be contacted") }, experimentalPrinterSupportEnabled = false)
            }
        }
        compose.onNodeWithText("Saved CC1  (active)").assertExists()
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(printer, saved) }
    }

    @Test fun u1AddRouteCannotSwitchToBambuEvenWhenExperimentalSupportIsEnabled() {
        compose.setContent {
            MaterialTheme {
                PrinterEditDialog(null, onSave = { error("No printer should save") },
                    onTest = { error("No host should be contacted") }, onDismiss = {}, bambuEnabled = true)
            }
        }
        compose.onNodeWithText("Moonraker").performClick()
        compose.onNodeWithText("Bambu LAN").assertDoesNotExist()
    }

    @Test fun experimentalOptOutKeepsSavedBambuVisibleAndEditable() {
        val printer = Printer("saved-bambu", "Saved Bambu", PrinterKind.BAMBU_LAN,
            bambu = com.u1.slicer.data.BambuConfig("192.0.2.4", "code", "SERIAL", com.u1.slicer.data.BambuModel.P1S))
        var saved: Printer? = null
        compose.setContent {
            MaterialTheme {
                PrintersSettingsCard(listOf(printer), printer.id, onAdd = { error("No printer should add") },
                    onEdit = { saved = it }, onDelete = { error("No printer should delete") },
                    onTestConnection = { error("No host should be contacted") }, experimentalPrinterSupportEnabled = false)
            }
        }
        compose.onNodeWithText("Saved Bambu  (active)").assertExists()
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(printer, saved) }
    }

    @Test fun editingCatalogueMarlinPrinterPreservesSnapshotAndPrusaCredentials() {
        val profile = com.u1.slicer.slice.GenericMachineProfile.fromJson("""{
            "type":"machine","name":"Marlin edit fixture","gcode_flavor":"marlin2",
            "printable_area":["0x0","200x0","200x210","0x210"],"printable_height":"220",
            "nozzle_diameter":["0.4"],"machine_start_gcode":"G28","machine_end_gcode":"M84"
        }""")
        val existing = Printer("catalogue", "Marlin", hostType = com.u1.slicer.data.HostType.PRUSALINK,
            moonrakerUrl = "http://192.0.2.3", apiKey = "api", username = "maker", password = "secret",
            machineProfile = profile, machineProfileId = "fixture-profile", machine = MachineKind.GENERIC_MARLIN)
        var saved: Printer? = null
        compose.setContent {
            MaterialTheme {
                PrinterEditDialog(existing, onSave = { saved = it }, onTest = { error("No host should be contacted") }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(existing, saved)
            assertEquals(profile.fingerprint, saved?.machineProfile?.fingerprint)
        }
    }

    @Test fun octoPrintRequiresImportedKlipperProfile() {
        compose.setContent {
            MaterialTheme {
                PrinterEditDialog(null, onSave = { error("Unresolved machine must not save") }, onTest = { error("No printer should be contacted") }, onDismiss = {}, experimentalPrinterSupportEnabled = true)
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
                PrinterEditDialog(null, onSave = { error("Unresolved machine must not save") }, onTest = { error("No printer should be contacted") }, onDismiss = {}, experimentalPrinterSupportEnabled = true)
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
                PrinterEditDialog(null, onSave = { saved = it }, onTest = { error("No printer should be contacted") }, onDismiss = {}, initialKind = PrinterKind.ELEGOO_CC1)
            }
        }
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
