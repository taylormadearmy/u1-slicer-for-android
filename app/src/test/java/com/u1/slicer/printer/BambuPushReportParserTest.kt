package com.u1.slicer.printer

import com.u1.slicer.network.FilamentRouting
import com.u1.slicer.network.NozzleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BambuPushReportParserTest {

    @Test
    fun `parse maps running report to printer status and filament slots`() {
        val report = """
            {
              "print": {
                "gcode_state": "RUNNING",
                "mc_percent": 42,
                "subtask_name": "benchy.3mf",
                "nozzle_temper": 219.5,
                "nozzle_target_temper": 220,
                "bed_temper": 64.2,
                "bed_target_temper": 65,
                "mc_remaining_time": 31
              },
              "ams": {
                "ams": [
                  {
                    "tray": [
                      { "id": 0, "tray_type": "PLA", "tray_sub_brands": "Basic", "tray_color": "FF0000FF", "remain": 85 },
                      { "id": 1, "tray_type": "PETG", "tray_color": "00FF00FF", "remain": 20 },
                      { "id": 2, "tray_type": "", "tray_color": "", "remain": 0 }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = BambuPushReportParser.parse(report)

        assertEquals("printing", parsed.status.state)
        assertEquals(0.42f, parsed.status.progress)
        assertEquals("benchy.3mf", parsed.status.filename)
        assertEquals(219.5f, parsed.status.nozzleTemp)
        assertEquals(220f, parsed.status.nozzleTarget)
        assertEquals(64.2f, parsed.status.bedTemp)
        assertEquals(65f, parsed.status.bedTarget)
        assertEquals(3, parsed.filamentSlots.size)
        assertEquals("AMS 1", parsed.filamentSlots[0].label)
        assertEquals("#FF0000", parsed.filamentSlots[0].color)
        assertTrue(parsed.filamentSlots[0].loaded)
        assertEquals("PLA", parsed.filamentSlots[0].materialType)
        assertEquals("Basic", parsed.filamentSlots[0].subType)
        assertEquals("#808080", parsed.filamentSlots[2].color)
        assertTrue(!parsed.filamentSlots[2].loaded)
        assertEquals("Empty", parsed.filamentSlots[2].materialType)
    }

    @Test
    fun `parse maps h2d style payload to dual extruder status and routed ams slots`() {
        val report = """
            {
              "print": {
                "gcode_state": "PAUSE",
                "mc_percent": 7,
                "subtask_name": "twinned.3mf",
                "nozzle_temper": [205.0, 198.0],
                "nozzle_target_temper": [210.0, 0.0],
                "bed_temper": 55.0,
                "bed_target_temper": 60.0
              },
              "ams": {
                "ams": [
                  { "id": "0", "tray": [
                    { "id": 0, "tray_type": "PLA", "tray_color": "112233FF", "remain": 50 },
                    { "id": 1, "tray_type": "ABS", "tray_color": "445566FF", "remain": 40 }
                  ]},
                  { "id": "1", "tray": [
                    { "id": 0, "tray_type": "PETG", "tray_color": "778899FF", "remain": 75 }
                  ]}
                ]
              }
            }
        """.trimIndent()

        val parsed = BambuPushReportParser.parse(report)

        assertEquals("paused", parsed.status.state)
        assertEquals(0.07f, parsed.status.progress)
        assertEquals(2, parsed.status.extruders.size)
        assertEquals(205.0f, parsed.status.extruders[0].temp)
        assertEquals(210.0f, parsed.status.extruders[0].target)
        assertEquals(198.0f, parsed.status.extruders[1].temp)
        assertEquals(0.0f, parsed.status.extruders[1].target)
        assertEquals(3, parsed.filamentSlots.size)
        assertEquals("AMS 1 / 1", parsed.filamentSlots[0].label)
        assertEquals("AMS 1 / 2", parsed.filamentSlots[1].label)
        assertEquals("AMS 2 / 1", parsed.filamentSlots[2].label)
        assertEquals(4, parsed.filamentSlots[2].index)
        assertEquals("#778899", parsed.filamentSlots[2].color)
        assertTrue(parsed.filamentSlots[2].loaded)
    }

    @Test
    fun `parse captures single and dual nozzle hardware field variants`() {
        val single = BambuPushReportParser.parse(
            """{"print":{"gcode_state":"IDLE","nozzle_type":"hardened_steel","nozzle_diameter":"0.6"}}""",
        )
        assertEquals(1, single.status.nozzles.size)
        assertEquals(0.6f, single.status.nozzles.single().diameter)
        assertEquals("hardened_steel", single.status.nozzles.single().type)

        val dual = BambuPushReportParser.parse(
            """
                {"print":{
                  "gcode_state":"IDLE",
                  "left_nozzle_type":"HH01",
                  "left_nozzle_diameter":"0.4",
                  "nozzle_type_2":"HS00",
                  "nozzle_diameter_2":0.6
                }}
            """.trimIndent(),
        )
        assertEquals(listOf(0, 1), dual.status.nozzles.map { it.index })
        assertEquals(listOf(0.4f, 0.6f), dual.status.nozzles.map { it.diameter })
        assertEquals(listOf("HH01", "HS00"), dual.status.nozzles.map { it.type })
    }

    @Test
    fun `device nozzle info overrides legacy fields and ignores rack entries`() {
        val parsed = BambuPushReportParser.parse(
            """
                {"print":{
                  "gcode_state":"IDLE",
                  "left_nozzle_diameter":"0.6",
                  "device":{"nozzle":{"info":[
                    {"id":0,"type":"HH01","diameter":"0.4"},
                    {"id":1,"type":"HS00","diameter":0.6},
                    {"id":16,"type":"rack","diameter":0.8}
                  ]}}
                }}
            """.trimIndent(),
        )

        assertEquals(2, parsed.status.nozzles.size)
        assertEquals(0.4f, parsed.status.nozzles[0].diameter)
        assertEquals(0.6f, parsed.status.nozzles[1].diameter)
    }

    @Test
    fun `parse preserves ams ht and h2d external spool route ids`() {
        val report = """
            {
              "print": {
                "gcode_state": "IDLE",
                "vir_slot": [
                  { "id": "254", "tray_type": "PLA", "tray_color": "AABBCCFF" },
                  { "id": "255", "tray_type": "PETG", "tray_color": "DDEEFFff" }
                ]
              },
              "ams": {
                "ams": [
                  {
                    "id": "128",
                    "tray": [
                      { "id": "0", "tray_type": "PA-CF", "tray_color": "102030FF" }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = BambuPushReportParser.parse(report)

        assertTrue(parsed.hasFilamentSlots)
        assertEquals(listOf(128, 254, 255), parsed.filamentSlots.map { it.index })
        assertEquals("AMS-HT 1", parsed.filamentSlots[0].label)
        assertEquals("External left", parsed.filamentSlots[1].label)
        assertEquals("External right", parsed.filamentSlots[2].label)
        assertEquals("#AABBCC", parsed.filamentSlots[1].color)
        assertEquals("PETG", parsed.filamentSlots[2].materialType)
    }

    @Test
    fun `parse reads legacy single external spool from vt tray`() {
        val parsed = BambuPushReportParser.parse(
            """
                {
                  "print": {
                    "gcode_state": "IDLE",
                    "vt_tray": {
                      "id": "254",
                      "tray_type": "PLA",
                      "tray_color": "123456FF"
                    }
                  }
                }
            """.trimIndent(),
        )

        assertTrue(parsed.hasFilamentSlots)
        assertEquals(1, parsed.filamentSlots.size)
        assertEquals(254, parsed.filamentSlots.single().index)
        assertEquals("External spool", parsed.filamentSlots.single().label)
        assertEquals("#123456", parsed.filamentSlots.single().color)
        assertEquals(NozzleSide.UNKNOWN, parsed.filamentSlots.single().nozzleSide)
        assertEquals(FilamentRouting.UNKNOWN, parsed.filamentSlots.single().routing)
        assertFalse(parsed.status.filamentTrackSwitch.installed)
    }

    @Test
    fun `parse decodes real h2d ams info topology including ams ht and external spools`() {
        val parsed = BambuPushReportParser.parse(
            """
                {
                  "print": {
                    "gcode_state": "IDLE",
                    "vir_slot": [
                      { "id": "254", "tray_type": "PLA" },
                      { "id": "255", "tray_type": "PETG" }
                    ],
                    "ams": {
                      "ams": [
                        {
                          "id": "0",
                          "info": "10001003",
                          "tray": [{ "id": 0, "tray_type": "PLA" }]
                        },
                        {
                          "id": "128",
                          "info": "10002104",
                          "tray": [{ "id": 0, "tray_type": "PA-CF" }]
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
        )

        val byIndex = parsed.filamentSlots.associateBy { it.index }
        assertEquals(NozzleSide.RIGHT, byIndex.getValue(0).nozzleSide)
        assertEquals(NozzleSide.LEFT, byIndex.getValue(128).nozzleSide)
        assertEquals(NozzleSide.LEFT, byIndex.getValue(254).nozzleSide)
        assertEquals(NozzleSide.RIGHT, byIndex.getValue(255).nozzleSide)
        assertTrue(byIndex.values.all { it.routing == FilamentRouting.FIXED })
    }

    @Test
    fun `parse accepts direct ams extruder map with numeric and string values`() {
        val parsed = BambuPushReportParser.parse(
            """
                {
                  "print": {
                    "gcode_state": "IDLE",
                    "ams_extruder_map": { "0": "1", "1": 0, "128": 14 },
                    "ams": {
                      "ams": [
                        { "id": "0", "tray": [{ "id": 0, "tray_type": "PLA" }] },
                        { "id": "1", "tray": [{ "id": 0, "tray_type": "PETG" }] },
                        { "id": "128", "tray": [{ "id": 0, "tray_type": "ABS" }] }
                      ]
                    }
                  }
                }
            """.trimIndent(),
        )

        val byIndex = parsed.filamentSlots.associateBy { it.index }
        assertEquals(NozzleSide.LEFT, byIndex.getValue(0).nozzleSide)
        assertEquals(NozzleSide.RIGHT, byIndex.getValue(4).nozzleSide)
        assertEquals(NozzleSide.UNKNOWN, byIndex.getValue(128).nozzleSide)
        assertEquals(FilamentRouting.FIXED, byIndex.getValue(0).routing)
        assertEquals(FilamentRouting.FIXED, byIndex.getValue(4).routing)
        assertEquals(FilamentRouting.UNKNOWN, byIndex.getValue(128).routing)
    }

    @Test
    fun `single nozzle ams info keeps topology unknown for older printers`() {
        val parsed = BambuPushReportParser.parse(
            """
                {
                  "print": {
                    "gcode_state": "IDLE",
                    "nozzle_temper": 25,
                    "ams": {
                      "ams": [{
                        "id": "0",
                        "info": "10001003",
                        "tray": [{ "id": 0, "tray_type": "PLA" }]
                      }]
                    }
                  }
                }
            """.trimIndent(),
        )

        assertEquals(NozzleSide.UNKNOWN, parsed.filamentSlots.single().nozzleSide)
        assertEquals(FilamentRouting.UNKNOWN, parsed.filamentSlots.single().routing)
    }

    @Test
    fun `parse marks ams slots switchable and captures fts routing state`() {
        val parsed = BambuPushReportParser.parse(
            """
                {
                  "print": {
                    "gcode_state": "RUNNING",
                    "device": {
                      "fila_switch": {
                        "in": [-1, "2"],
                        "out": [0, "1", 9],
                        "stat": "3",
                        "info": 2
                      }
                    },
                    "ams": {
                      "ams": [
                        {
                          "id": "0",
                          "info": "10001e03",
                          "tray": [{ "id": 0, "tray_type": "PLA" }]
                        },
                        {
                          "id": "128",
                          "info": "10002e04",
                          "tray": [{ "id": 0, "tray_type": "PETG" }]
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
        )

        val switch = parsed.status.filamentTrackSwitch
        assertTrue(switch.installed)
        assertEquals(listOf(-1, 2), switch.inputSlots)
        assertEquals(listOf(0, 1, 9), switch.outputExtruderIds)
        assertEquals(
            listOf(NozzleSide.RIGHT, NozzleSide.LEFT, NozzleSide.UNKNOWN),
            switch.outputNozzleSides,
        )
        assertEquals(3, switch.statusFlags)
        assertEquals(2, switch.infoFlags)
        assertTrue(parsed.filamentSlots.all { it.routing == FilamentRouting.SWITCHABLE })
        assertTrue(parsed.filamentSlots.all { it.nozzleSide == NozzleSide.UNKNOWN })
    }

    @Test
    fun `parse treats fila switch presence as installed when optional fields are absent`() {
        val parsed = BambuPushReportParser.parse(
            """
                {
                  "print": {
                    "gcode_state": "IDLE",
                    "device": { "fila_switch": {} },
                    "ams": {
                      "ams": [{
                        "id": 0,
                        "tray": [{ "id": 0, "tray_type": "PLA" }]
                      }]
                    }
                  }
                }
            """.trimIndent(),
        )

        assertTrue(parsed.status.filamentTrackSwitch.installed)
        assertTrue(parsed.status.filamentTrackSwitch.inputSlots.isEmpty())
        assertTrue(parsed.status.filamentTrackSwitch.outputExtruderIds.isEmpty())
        assertEquals(FilamentRouting.SWITCHABLE, parsed.filamentSlots.single().routing)
    }

    @Test
    fun `parse tolerates malformed payload by returning disconnected status`() {
        val parsed = BambuPushReportParser.parse("{not valid")

        assertEquals("disconnected", parsed.status.state)
        assertTrue(parsed.filamentSlots.isEmpty())
    }

    @Test
    fun `parse reads nested print ams payload used by live bambu reports`() {
        val report = """
            {
              "print": {
                "gcode_state": "FINISH",
                "mc_percent": 100,
                "subtask_name": "completed.3mf",
                "ams": {
                  "ams": [
                    {
                      "tray": [
                        { "id": 0, "tray_type": "PLA", "tray_color": "ABCDEFff", "remain": 70 },
                        { "id": 1, "tray_type": "PETG", "tray_color": "123456ff", "remain": 15 }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val parsed = BambuPushReportParser.parse(report)

        assertEquals("complete", parsed.status.state)
        assertTrue(parsed.hasFilamentSlots)
        assertEquals(2, parsed.filamentSlots.size)
        assertEquals("PLA", parsed.filamentSlots[0].materialType)
        assertEquals("#ABCDEF", parsed.filamentSlots[0].color)
        assertEquals("PETG", parsed.filamentSlots[1].materialType)
        assertEquals("#123456", parsed.filamentSlots[1].color)
    }
}
