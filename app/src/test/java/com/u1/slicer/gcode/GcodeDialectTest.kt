package com.u1.slicer.gcode

import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeDialectTest {
    @Test
    fun `selects dialect from machine family and flavor`() {
        assertSame(
            KlipperSnapmakerDialect,
            profile(MachineFamily.SNAPMAKER, "klipper").gcodeDialect(),
        )
        assertSame(
            KlipperGenericDialect,
            profile(MachineFamily.GENERIC, "klipper").gcodeDialect(),
        )
        assertSame(
            MarlinDialect,
            profile(MachineFamily.GENERIC, "marlin").gcodeDialect(),
        )
    }

    @Test
    fun `generic Klipper rules contain no Snapmaker behavior`() {
        val pause = KlipperGenericDialect.pauseInjection

        assertEquals(
            "SM_PRINT_AUTO_FEED EXTRUDER=0",
            KlipperGenericDialect.remapToolLine(
                "SM_PRINT_AUTO_FEED EXTRUDER=0",
                mapOf(0 to 2),
            ),
        )
        assertEquals("; PAUSE_PRINT", pause.marker)
        assertEquals("PAUSE", pause.fallbackCommand)
        assertFalse(pause.useProfileCommand)
        assertFalse(pause.emitToolChange)
        assertFalse(pause.emitToolTemperature)
        assertEquals(ThumbnailFormat.KLIPPER, KlipperGenericDialect.thumbnailFormat)
        assertTrue(KlipperGenericDialect.supportsExcludeObject)
        assertTrue(KlipperGenericDialect.validatorRules.requiredCommandPrefixes.isEmpty())
        assertTrue(KlipperGenericDialect.validatorRules.forbiddenCommandPrefixes.isEmpty())
    }

    @Test
    fun `Marlin rules use firmware-safe commands`() {
        val pause = MarlinDialect.pauseInjection

        assertEquals(
            "SM_PRINT_FLOW_CALIBRATE INDEX=0",
            MarlinDialect.remapToolLine(
                "SM_PRINT_FLOW_CALIBRATE INDEX=0",
                mapOf(0 to 2),
            ),
        )
        assertEquals("; PAUSE_PRINT", pause.marker)
        assertEquals("M600", pause.fallbackCommand)
        assertFalse(pause.useProfileCommand)
        assertFalse(pause.emitToolChange)
        assertFalse(pause.emitToolTemperature)
        assertEquals(ThumbnailFormat.PRUSA, MarlinDialect.thumbnailFormat)
        assertFalse(MarlinDialect.supportsExcludeObject)
        assertEquals(
            setOf("M104", "M109"),
            MarlinDialect.validatorRules.requiredCommandPrefixes,
        )
        assertEquals(
            setOf("EXCLUDE_OBJECT_", "SET_HEATER_TEMPERATURE"),
            MarlinDialect.validatorRules.forbiddenCommandPrefixes,
        )
    }

    @Test
    fun `thumbnail formats preserve Klipper bytes and add Prusa wrappers`() {
        val encoded = "A".repeat(160)
        val klipper = formatThumbnailBlock(48, 48, encoded, ThumbnailFormat.KLIPPER)
        val prusa = formatThumbnailBlock(48, 48, encoded, ThumbnailFormat.PRUSA)

        assertTrue(klipper.startsWith("; thumbnail begin 48x48 160\n"))
        assertTrue(klipper.endsWith("; thumbnail end\n;\n"))
        assertFalse(klipper.contains("THUMBNAIL_BLOCK_START"))
        assertEquals(listOf(76, 76, 8), encodedLineLengths(klipper))

        assertTrue(prusa.startsWith("; THUMBNAIL_BLOCK_START\n\n;\n; thumbnail begin 48x48 160\n"))
        assertTrue(prusa.endsWith("; thumbnail end\n; THUMBNAIL_BLOCK_END\n\n"))
        assertEquals(listOf(78, 78, 4), encodedLineLengths(prusa))
    }

    private fun encodedLineLengths(block: String): List<Int> = block.lineSequence()
        .filter { it.startsWith("; A") }
        .map { it.removePrefix("; ").length }
        .toList()

    private fun profile(family: MachineFamily, flavor: String): MachineProfile =
        requireNotNull(
            MachineProfile.fromResolvedOrcaJson(
                """
                {
                  "type": "machine",
                  "name": "Dialect test",
                  "printable_area": ["0x0", "200x0", "200x200", "0x200"],
                  "printable_height": "200",
                  "nozzle_diameter": ["0.4"],
                  "gcode_flavor": "$flavor"
                }
                """.trimIndent(),
                family,
            ),
        )
}
