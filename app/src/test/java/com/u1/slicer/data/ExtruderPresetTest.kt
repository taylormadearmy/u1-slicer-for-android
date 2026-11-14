package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtruderPresetTest {
    @Test
    fun `default presets use requested one two and four slot counts`() {
        assertEquals(listOf(0), defaultExtruderPresets(1).map { it.index })
        assertEquals(listOf(0, 1), defaultExtruderPresets(2).map { it.index })
        assertEquals(listOf(0, 1, 2, 3), defaultExtruderPresets(4).map { it.index })
        assertEquals(
            ExtruderPreset.DEFAULT_COLORS,
            defaultExtruderPresets(4).map { it.color },
        )
    }

    @Test
    fun `default presets clamp unsupported counts to one through four`() {
        assertEquals(1, defaultExtruderPresets(0).size)
        assertEquals(4, defaultExtruderPresets(5).size)
    }

    @Test
    fun `parser fills and trims to requested slot count`() {
        val raw = """[
            {"index":0,"color":"#123456","materialType":"PETG"},
            {"index":3,"color":"#ABCDEF","materialType":"ABS"}
        ]""".trimIndent()

        val one = parseExtruderPresets(raw, 1)
        val two = parseExtruderPresets(raw, 2)
        val four = parseExtruderPresets(raw, 4)

        assertEquals(listOf(0), one.map { it.index })
        assertEquals("#123456", one.single().color)
        assertEquals(listOf(0, 1), two.map { it.index })
        assertEquals(listOf(0, 1, 2, 3), four.map { it.index })
        assertEquals("#ABCDEF", four.last().color)
    }

    @Test
    fun `blank and malformed input use requested defaults`() {
        assertEquals(defaultExtruderPresets(1), parseExtruderPresets("", 1))
        assertEquals(defaultExtruderPresets(2), parseExtruderPresets("not-json", 2))
        assertEquals(defaultExtruderPresets(4), parseExtruderPresets("", 4))
    }
}
