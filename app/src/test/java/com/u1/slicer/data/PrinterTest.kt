package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class PrinterTest {

    @Test
    fun `Moonraker printer round-trip through JSON preserves all fields`() {
        val p = Printer(
            id = "uuid-1",
            nickname = "Workshop",
            kind = PrinterKind.MOONRAKER,
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
    fun `Bambu printer round-trip through JSON preserves provider config`() {
        val p = Printer(
            id = "uuid-bambu",
            nickname = "P1S Office",
            kind = PrinterKind.BAMBU_LAN,
            moonrakerUrl = "",
            bambu = BambuConfig(
                ip = "192.168.1.88",
                accessCode = "12345678",
                serial = "P1S123ABC",
                model = BambuModel.P1S,
            ),
            extruderPresets = emptyList(),
        )
        val json = Printer.toJsonObject(p).toString()
        val back = Printer.fromJsonObject(org.json.JSONObject(json))
        assertEquals(p as Any, back as Any)
    }

    @Test
    fun `Moonraker printer rejects bambu config`() {
        try {
            Printer(
                id = "uuid-1",
                nickname = "Workshop",
                kind = PrinterKind.MOONRAKER,
                moonrakerUrl = "http://192.168.1.50",
                bambu = BambuConfig(
                    ip = "192.168.1.88",
                    accessCode = "12345678",
                    serial = "P1S123ABC",
                    model = BambuModel.P1S,
                ),
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must not have bambu"))
        }
    }

    @Test
    fun `Bambu printer rejects moonraker url`() {
        try {
            Printer(
                id = "uuid-bambu",
                nickname = "P1S Office",
                kind = PrinterKind.BAMBU_LAN,
                moonrakerUrl = "http://192.168.1.50",
                bambu = BambuConfig(
                    ip = "192.168.1.88",
                    accessCode = "12345678",
                    serial = "P1S123ABC",
                    model = BambuModel.P1S,
                ),
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must not have moonrakerUrl"))
        }
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
                Printer(
                    id = "uuid-2",
                    nickname = "P2",
                    kind = PrinterKind.BAMBU_LAN,
                    moonrakerUrl = "",
                    bambu = BambuConfig(
                        ip = "192.168.1.88",
                        accessCode = "12345678",
                        serial = "P1S123ABC",
                        model = BambuModel.P1S,
                    ),
                    extruderPresets = emptyList(),
                ),
            ),
            activeId = "uuid-2",
        )
        val json = PrintersConfig.toJson(cfg)
        val back = PrintersConfig.fromJson(json)
        assertEquals(cfg as Any, back as Any)
    }

    @Test
    fun `Bambu printer JSON round-trip with empty extruder presets preserves exact empty list`() {
        val p = Printer(
            id = "uuid-empty",
            nickname = "EmptyPresets",
            kind = PrinterKind.BAMBU_LAN,
            moonrakerUrl = "",
            bambu = BambuConfig(
                ip = "192.168.1.88",
                accessCode = "12345678",
                serial = "P1S123ABC",
                model = BambuModel.P1S,
            ),
            extruderPresets = emptyList(),
        )
        val json = Printer.toJsonObject(p).toString()
        val back = Printer.fromJsonObject(org.json.JSONObject(json))
        assertTrue(back.extruderPresets.isEmpty())
        assertEquals("uuid-empty", back.id)
        assertEquals("EmptyPresets", back.nickname)
    }

    @Test
    fun `Printer JSON without extruder presets falls back to default snapmaker slots`() {
        val json = org.json.JSONObject().apply {
            put("id", "uuid-legacy")
            put("nickname", "Legacy")
            put("kind", PrinterKind.MOONRAKER.name)
            put("moonrakerUrl", "http://legacy")
        }

        val back = Printer.fromJsonObject(json)

        assertEquals(defaultExtruderPresets(), back.extruderPresets)
    }

    @Test
    fun `Moonraker printer JSON with empty extruder presets falls back to default snapmaker slots`() {
        val p = Printer(
            id = "uuid-empty-moonraker",
            nickname = "LegacyEmpty",
            kind = PrinterKind.MOONRAKER,
            moonrakerUrl = "http://x",
            extruderPresets = emptyList(),
        )

        val back = Printer.fromJsonObject(org.json.JSONObject(Printer.toJsonObject(p).toString()))

        assertEquals(defaultExtruderPresets(), back.extruderPresets)
    }

    @Test
    fun `PrintersConfig fromJson skips malformed bambu entries and keeps valid printers`() {
        val json = org.json.JSONObject().apply {
            put("activeId", "bad-bambu")
            put(
                "printers",
                org.json.JSONArray().apply {
                    put(
                        org.json.JSONObject().apply {
                            put("id", "stable-u1")
                            put("nickname", "Stable U1")
                            put("kind", PrinterKind.MOONRAKER.name)
                            put("moonrakerUrl", "http://printer.local:7125")
                        }
                    )
                    put(
                        org.json.JSONObject().apply {
                            put("id", "bad-bambu")
                            put("nickname", "Future Bambu")
                            put("kind", PrinterKind.BAMBU_LAN.name)
                            put(
                                "bambu",
                                org.json.JSONObject().apply {
                                    put("ip", "192.168.1.88")
                                    put("accessCode", "12345678")
                                    put("serial", "P1S123ABC")
                                    put("model", "FUTURE_MODEL")
                                }
                            )
                        }
                    )
                }
            )
        }.toString()

        val back = PrintersConfig.fromJson(json)

        assertEquals(listOf("stable-u1"), back.printers.map { it.id })
        assertEquals("stable-u1", back.activeId)
    }
}
