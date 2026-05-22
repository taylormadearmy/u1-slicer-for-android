package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class PrinterTest {

    @Test
    fun `Printer round-trip through JSON preserves all fields`() {
        val p = Printer(
            id = "uuid-1",
            nickname = "Workshop",
            moonrakerUrl = "http://192.168.1.50",
            extruderPresets = listOf(
                ExtruderPreset(index = 0, color = "#FF0000", materialType = "PLA"),
                ExtruderPreset(index = 1, color = "#00FF00", materialType = "PETG"),
            ),
        )
        val json = Printer.toJsonObject(p).toString()
        val back = Printer.fromJsonObject(org.json.JSONObject(json))
        assertEquals(p as Any, back as Any)
    }

    @Test
    fun `PrintersConfig constructor rejects empty printer list`() {
        try {
            PrintersConfig(printers = emptyList(), activeId = "x")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least one"))
        }
    }

    @Test
    fun `PrintersConfig constructor rejects activeId not in list`() {
        val p = Printer(id = "uuid-1", nickname = "P1", moonrakerUrl = "http://x")
        try {
            PrintersConfig(printers = listOf(p), activeId = "uuid-2")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("activeId"))
        }
    }

    @Test
    fun `PrintersConfig round-trip through JSON preserves printers and active`() {
        val cfg = PrintersConfig(
            printers = listOf(
                Printer(id = "uuid-1", nickname = "P1", moonrakerUrl = "http://1"),
                Printer(id = "uuid-2", nickname = "P2", moonrakerUrl = "http://2"),
            ),
            activeId = "uuid-2",
        )
        val json = PrintersConfig.toJson(cfg)
        val back = PrintersConfig.fromJson(json)
        assertEquals(cfg as Any, back as Any)
    }
}
