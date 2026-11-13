package com.u1.slicer.slice

import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CosmosMachineProfileTest {
    @Test fun firmwareTemplatesMatchTheRecordedOfficialNonAfcProfile() {
        val official = JSONObject(javaClass.getResource("/machine/cosmos/official-0.4.json")!!.readText())
        val selected = CosmosMachineProfile.profile.settings
        for (key in listOf("machine_start_gcode", "machine_end_gcode", "machine_pause_gcode", "change_filament_gcode")) {
            val expected = official.getString(key).replace("\\\\n", "\n").replace("\\n", "\n")
            assertEquals(key, expected, selected[key])
        }
    }

    @Test fun profileKeepsCc1GeometryAndHasItsOwnFingerprint() {
        val cosmos = CosmosMachineProfile.profile
        val stock = GenericMachineProfile.centauriCarbon()
        assertEquals(stock.bedSizeX, cosmos.bedSizeX)
        assertEquals(stock.maxPrintHeight, cosmos.maxPrintHeight)
        assertEquals(1, cosmos.extruderCount)
        assertEquals("klipper", cosmos.gcodeFlavor)
        assertNotEquals(stock.fingerprint, cosmos.fingerprint)
        assertFalse(cosmos.resolvedJson.contains("M729"))
        assertFalse(cosmos.resolvedJson.contains("M8213"))
    }

    @Test fun sourceProjectCannotRestoreStockFirmwareCommands() {
        val config = CosmosMachineProfile.profile.compose(GenericMachineProfile.centauriCarbon().settings)
        assertTrue(config["machine_start_gcode"].toString().contains("PRINT_START EXTRUDER="))
        assertEquals("PRINT_END", config["machine_end_gcode"])
        assertFalse(config["machine_start_gcode"].toString().contains("M729"))
    }

    @Test fun savedPrinterRetainsCosmosSnapshotAndRejectsOldProfileArtifacts() {
        val printer = Printer("cosmos", "COSMOS", moonrakerUrl = "http://192.0.2.5:80", machineProfile = CosmosMachineProfile.profile)
        val restored = PrintersConfig.fromJson(PrintersConfig.toJson(PrintersConfig(listOf(printer), printer.id))).printers.single()
        assertEquals(printer.machineProfile, restored.machineProfile)
        assertEquals(SlicerTarget.GenericKlipper, resolveDefaultSliceTarget(restored))
        val stockArtifact = SliceArtifact.MoonrakerGcodeArtifact("old", File("old.gcode"), SlicerTarget.GenericKlipper,
            GenericMachineProfile.centauriCarbon().fingerprint)
        assertFalse(stockArtifact.isCompatibleWith(restored))
        assertTrue(stockArtifact.copy(machineProfileFingerprint = CosmosMachineProfile.profile.fingerprint).isCompatibleWith(restored))
    }
}
