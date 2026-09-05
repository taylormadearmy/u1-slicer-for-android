package com.u1.slicer.machine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineProfileTest {
    @Test
    fun `parses U1 machine geometry and metadata`() {
        val profile = MachineProfile.fromResolvedOrcaJson(fixture("snapmaker_u1_resolved.json"), MachineFamily.SNAPMAKER)

        assertNotNull(profile)
        requireNotNull(profile)
        assertEquals("snapmaker-u1-0.4", profile.id)
        assertEquals("Snapmaker U1 (0.4 nozzle) - multiplate", profile.displayName)
        assertEquals("Snapmaker", profile.vendor)
        assertEquals("Snapmaker U1", profile.printerModel)
        assertEquals("0.4", profile.printerVariant)
        assertEquals(MachineFamily.SNAPMAKER, profile.family)
        assertEquals(270f, profile.bedSizeX)
        assertEquals(270f, profile.bedSizeY)
        assertEquals(270f, profile.maxPrintHeight)
        assertEquals(4, profile.extruderCount)
        assertEquals(0.4f, profile.nozzleDiameter)
        assertEquals(GcodeFlavor.KLIPPER, profile.gcodeFlavor)
        assertEquals(300, profile.maxNozzleTemperatureC)
        assertEquals(120, profile.maxBedTemperatureC)
        assertEquals(false, profile.singleExtruderMultiMaterial)
        assertTrue(profile.machineStartGcode.contains("PRINT_START"))
        assertEquals(fixture("snapmaker_u1_resolved.json"), profile.machineJson)
    }

    @Test
    fun `parses generic rectangular machine`() {
        val profile = MachineProfile.fromResolvedOrcaJson(fixture("generic_klipper_300.json"), MachineFamily.GENERIC)

        assertNotNull(profile)
        requireNotNull(profile)
        assertEquals(300f, profile.bedSizeX)
        assertEquals(300f, profile.bedSizeY)
        assertEquals(350f, profile.maxPrintHeight)
        assertEquals(1, profile.extruderCount)
        assertEquals(GcodeFlavor.KLIPPER, profile.gcodeFlavor)
        assertEquals(0.4f, profile.nozzleDiameter)
    }

    @Test
    fun `parses app heater limits from selected machine profile`() {
        val json = """
            {
              "type": "machine",
              "name": "Cool-running printer",
              "printable_area": ["0x0", "180x0", "180x180", "0x180"],
              "max_nozzle_temperature": "275",
              "max_bed_temperature": "95"
            }
        """.trimIndent()

        val profile = requireNotNull(MachineProfile.fromResolvedOrcaJson(json, MachineFamily.GENERIC))

        assertEquals(275, profile.maxNozzleTemperatureC)
        assertEquals(95, profile.maxBedTemperatureC)
    }

    @Test
    fun `malformed JSON returns null`() {
        assertNull(MachineProfile.fromResolvedOrcaJson("not-json", MachineFamily.GENERIC))
    }

    @Test
    fun `missing printable area returns null`() {
        assertNull(
            MachineProfile.fromResolvedOrcaJson(
                """{"type":"machine","name":"Missing area"}""",
                MachineFamily.GENERIC
            )
        )
    }

    @Test
    fun `non-machine profile returns null`() {
        assertNull(
            MachineProfile.fromResolvedOrcaJson(
                """{"type":"process","printable_area":["0x0","1x1"]}""",
                MachineFamily.GENERIC
            )
        )
    }

    @Test
    fun `maps gcode flavor aliases`() {
        assertEquals(GcodeFlavor.KLIPPER, GcodeFlavor.fromOrca("klipper"))
        assertEquals(GcodeFlavor.MARLIN, GcodeFlavor.fromOrca("marlin"))
        assertEquals(GcodeFlavor.MARLIN, GcodeFlavor.fromOrca("marlin2"))
        assertEquals(GcodeFlavor.OTHER, GcodeFlavor.fromOrca("reprap"))
        assertEquals(GcodeFlavor.OTHER, GcodeFlavor.fromOrca(null))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("machine/$name"))
            .readText()
}
