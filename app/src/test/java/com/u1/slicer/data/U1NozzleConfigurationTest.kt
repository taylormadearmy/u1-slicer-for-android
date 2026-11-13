package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class U1NozzleConfigurationTest {
    @Test fun `legacy printer defaults every head to 04`() {
        val json = Printer.toJsonObject(Printer("legacy", "U1"))
        json.remove("u1Nozzles")
        assertEquals(List(4) { 0.4f }, Printer.fromJsonObject(json).u1Nozzles.diametersMm)
    }

    @Test fun `all supported diameters round trip in physical head order`() {
        val printer = Printer("u1", "Workshop", u1Nozzles = U1NozzleConfiguration(listOf(0.2f, 0.4f, 0.6f, 0.8f)))
        assertEquals(printer, Printer.fromJsonObject(Printer.toJsonObject(printer)))
    }

    @Test fun `printer configurations keep independent installed nozzles`() {
        val first = Printer("a", "First", u1Nozzles = U1NozzleConfiguration().withDiameter(2, 0.2f))
        val second = Printer("b", "Second")
        val config = PrintersConfig(listOf(first, second), "a")
        val restored = PrintersConfig.fromJson(PrintersConfig.toJson(config))
        assertEquals(config, restored)
        assertEquals(0.2f, restored.printers[0].u1Nozzles.diameterMm(2))
        assertEquals(0.4f, restored.printers[1].u1Nozzles.diameterMm(2))
    }

    @Test fun `settings backup preserves installed nozzles`() {
        val printer = Printer("u1", "U1", u1Nozzles = U1NozzleConfiguration().withDiameter(1, 0.6f))
        val printers = PrintersConfig(listOf(printer), printer.id)
        val backup = SettingsBackup.BackupData(null, null, printers, null)
        assertEquals(printers, SettingsBackup.import(SettingsBackup.export(backup)).printersConfig)
    }

    @Test fun `changing a nozzle leaves original configuration and other heads unchanged`() {
        val original = U1NozzleConfiguration()
        assertEquals(listOf(0.4f, 0.4f, 0.8f, 0.4f), original.withDiameter(2, 0.8f).diametersMm)
        assertEquals(List(4) { 0.4f }, original.diametersMm)
    }

    @Test fun `configuration snapshots incoming list`() {
        val values = MutableList(4) { 0.4f }
        val config = U1NozzleConfiguration(values)
        values[0] = 0.2f
        assertEquals(0.4f, config.diameterMm(0))
    }

    @Test fun `invalid stored nozzle configuration is rejected`() {
        for (values in listOf(listOf(0.2), listOf(0.4, 0.4, 0.4, 0.5), listOf(0.4, 0.4, 0.4, -0.2))) {
            val json = Printer.toJsonObject(Printer("u1", "U1"))
            json.put("u1Nozzles", org.json.JSONArray(values))
            assertThrows(IllegalArgumentException::class.java) { Printer.fromJsonObject(json) }
        }
    }

    @Test fun `invalid diameter and physical slot are rejected`() {
        for (diameter in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 0.5f)) {
            assertThrows(IllegalArgumentException::class.java) { U1NozzleConfiguration().withDiameter(0, diameter) }
        }
        for (slot in listOf(-1, 4)) {
            assertThrows(IllegalArgumentException::class.java) { U1NozzleConfiguration().withDiameter(slot, 0.2f) }
        }
    }
}
