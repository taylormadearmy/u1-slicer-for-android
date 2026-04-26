package com.u1.slicer.bambu

import com.u1.slicer.data.FilamentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.1 — JVM unit tests for [buildFromPrusaConfig] and [parsePrusaIni].
 */
class PrusaSlicerCanonicalListTest {

    @Test
    fun `parsePrusaIni strips leading semicolon and whitespace`() {
        val ini = """
            ; extruder_colour = #000000;#DB5182;#008000
            ; filament_type = PLA;PETG;PLA
            ; foo = bar
        """.trimIndent()
        val map = parsePrusaIni(ini)
        assertEquals("#000000;#DB5182;#008000", map["extruder_colour"])
        assertEquals("PLA;PETG;PLA", map["filament_type"])
        assertEquals("bar", map["foo"])
    }

    @Test
    fun `parsePrusaIni handles uncommented lines too`() {
        val ini = """
            extruder_colour = #FF0000;#00FF00
            ; filament_colour = #FFFFFF
        """.trimIndent()
        val map = parsePrusaIni(ini)
        assertEquals("#FF0000;#00FF00", map["extruder_colour"])
        assertEquals("#FFFFFF", map["filament_colour"])
    }

    @Test
    fun `parsePrusaIni ignores empty and malformed lines`() {
        val ini = """
            ; valid = yes
            ;
            no equals here
            ; = empty key
        """.trimIndent()
        val map = parsePrusaIni(ini)
        assertEquals(1, map.size)
        assertEquals("yes", map["valid"])
    }

    @Test
    fun `buildFromPrusaConfig prefers extruder_colour over filament_colour`() {
        // Mirrors Korok's actual layout: extruder_colour is the per-slot
        // visible palette; filament_colour is all-same (a single "current
        // filament" colour from the slicer's filament-profile pane).
        val ini = """
            ; extruder_colour = #000000;#DB5182;#008000;#00FF00;#FBEB7D
            ; filament_colour = #FF8000;#FF8000;#FF8000;#FF8000;#FF8000
            ; filament_type = PLA;PETG;PLA;PLA;PLA
        """.trimIndent()
        val list = buildFromPrusaConfig(ini)
        assertNotNull(list)
        assertEquals(5, list!!.size)
        assertEquals("#000000", list.filaments[0].color)
        assertEquals("#DB5182", list.filaments[1].color)
        assertEquals("#008000", list.filaments[2].color)
        assertEquals("#00FF00", list.filaments[3].color)
        assertEquals("#FBEB7D", list.filaments[4].color)
        assertEquals("PLA", list.filaments[0].materialType)
        assertEquals("PETG", list.filaments[1].materialType)
        list.filaments.forEach { e ->
            assertEquals(FilamentSource.FILE_COLOUR, e.source)
        }
    }

    @Test
    fun `buildFromPrusaConfig falls back to filament_colour when extruder_colour absent`() {
        val ini = """; filament_colour = #FF0000;#00FF00""".trimIndent()
        val list = buildFromPrusaConfig(ini)
        assertNotNull(list)
        assertEquals(2, list!!.size)
        assertEquals("#FF0000", list.filaments[0].color)
        assertEquals("#00FF00", list.filaments[1].color)
    }

    @Test
    fun `buildFromPrusaConfig truncates 8-char hex to 7-char`() {
        val ini = """; extruder_colour = #FF0000FF;#00FF00FF""".trimIndent()
        val list = buildFromPrusaConfig(ini)
        assertEquals("#FF0000", list!!.filaments[0].color)
        assertEquals("#00FF00", list.filaments[1].color)
    }

    @Test
    fun `buildFromPrusaConfig returns null when no colour key present`() {
        val ini = """
            ; layer_height = 0.2
            ; nozzle_diameter = 0.4
        """.trimIndent()
        assertNull(buildFromPrusaConfig(ini))
    }

    @Test
    fun `buildFromPrusaConfig returns null when colour value is blank`() {
        val ini = """; extruder_colour = """.trimIndent()
        assertNull(buildFromPrusaConfig(ini))
    }

    @Test
    fun `buildFromPrusaConfig fileIndex matches order`() {
        val ini = """; extruder_colour = #FF0000;#00FF00;#0000FF""".trimIndent()
        val list = buildFromPrusaConfig(ini)
        list!!.filaments.forEachIndexed { i, e ->
            assertEquals(i, e.fileIndex)
        }
    }

    @Test
    fun `buildFromPrusaConfig filaments are FILE_COLOUR`() {
        val ini = """; extruder_colour = #FF0000;#00FF00""".trimIndent()
        val list = buildFromPrusaConfig(ini)
        assertTrue(list!!.filaments.all { it.source == FilamentSource.FILE_COLOUR })
    }
}
