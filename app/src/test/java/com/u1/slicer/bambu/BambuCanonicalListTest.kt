package com.u1.slicer.bambu

import com.u1.slicer.data.FilamentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.1 — JVM unit tests for [buildFromProjectSettings].
 *
 * The file-reading entry point [bambuFileColourList] is exercised in the
 * instrumented test `BambuCanonicalListInstrumentedTest`; this file covers
 * the pure JSON-to-canonical-list logic.
 */
class BambuCanonicalListTest {

    @Test
    fun `single-colour project settings produces one FILE_COLOUR entry`() {
        // Mirrors the Die fixture (G:/My Drive/tes-data/Die+Single+Colour+-+Die.3mf)
        // which we ship as app/src/androidTest/assets/die-single-colour.3mf.
        val json = """{
            "filament_colour": ["#A6A9AA"],
            "filament_type":   ["PLA"],
            "filament_settings_id": ["Bambu PLA Basic @BBL X1C"]
        }"""

        val list = buildFromProjectSettings(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertFalse(list.isMultiColour)

        val only = list.filaments[0]
        assertEquals(0, only.fileIndex)
        assertEquals("#A6A9AA", only.color)
        assertEquals("PLA", only.materialType)
        assertEquals(FilamentSource.FILE_COLOUR, only.source)
        assertTrue(list.paintStateMap.isEmpty())
    }

    @Test
    fun `multi-colour project settings produces N FILE_COLOUR entries in order`() {
        val json = """{
            "filament_colour": ["#FF0000", "#00FF00", "#0000FF", "#FFFFFF"],
            "filament_type":   ["PLA",     "PETG",    "PLA",     "PLA"]
        }"""

        val list = buildFromProjectSettings(json)
        assertNotNull(list)
        assertEquals(4, list!!.size)
        assertTrue(list.isMultiColour)

        assertEquals("#FF0000", list.filaments[0].color)
        assertEquals("PLA", list.filaments[0].materialType)
        assertEquals("#00FF00", list.filaments[1].color)
        assertEquals("PETG", list.filaments[1].materialType)
        assertEquals("#0000FF", list.filaments[2].color)
        assertEquals("#FFFFFF", list.filaments[3].color)

        list.filaments.forEachIndexed { i, e ->
            assertEquals(i, e.fileIndex)
            assertEquals(FilamentSource.FILE_COLOUR, e.source)
        }
    }

    @Test
    fun `colour with alpha channel truncates to 7-char hex`() {
        // OrcaSlicer occasionally emits "#RRGGBBAA". Canonical list keeps "#RRGGBB".
        val json = """{ "filament_colour": ["#FF0000FF"] }"""
        val list = buildFromProjectSettings(json)
        assertNotNull(list)
        assertEquals("#FF0000", list!!.filaments[0].color)
    }

    @Test
    fun `missing filament_type fields produce null materialType`() {
        val json = """{ "filament_colour": ["#FF0000", "#00FF00"] }"""
        val list = buildFromProjectSettings(json)
        assertNotNull(list)
        assertEquals(2, list!!.size)
        assertNull(list.filaments[0].materialType)
        assertNull(list.filaments[1].materialType)
    }

    @Test
    fun `partial filament_type list — entries without a matching type get null`() {
        val json = """{
            "filament_colour": ["#FF0000", "#00FF00", "#0000FF"],
            "filament_type":   ["PLA",     "PETG"]
        }"""
        val list = buildFromProjectSettings(json)
        assertEquals("PLA", list!!.filaments[0].materialType)
        assertEquals("PETG", list.filaments[1].materialType)
        assertNull(list.filaments[2].materialType)
    }

    @Test
    fun `empty filament_colour returns null`() {
        val json = """{ "filament_colour": [] }"""
        assertNull(buildFromProjectSettings(json))
    }

    @Test
    fun `missing filament_colour returns null`() {
        val json = """{ "filament_type": ["PLA"] }"""
        assertNull(buildFromProjectSettings(json))
    }

    @Test
    fun `malformed JSON returns null`() {
        assertNull(buildFromProjectSettings("not json"))
        assertNull(buildFromProjectSettings(""))
    }

    @Test
    fun `colours without hex prefix still parse if regex matches`() {
        // Defensive — some files emit colours embedded inside other strings.
        val json = """{ "filament_colour": ["before#ABCDEFafter"] }"""
        val list = buildFromProjectSettings(json)
        assertEquals("#ABCDEF", list!!.filaments[0].color)
    }
}
