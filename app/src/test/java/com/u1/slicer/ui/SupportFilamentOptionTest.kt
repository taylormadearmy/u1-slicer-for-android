package com.u1.slicer.ui

import com.u1.slicer.data.ExtruderPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportFilamentOptionTest {

    @Test
    fun `h2c mapping shows physical slots but writes mapped file filament values`() {
        val options = buildSupportFilamentOptions(
            extruderPresets = listOf(
                ExtruderPreset(0, color = "#0086D6", materialType = "PLA"),
                ExtruderPreset(1, color = "#FFFF00", materialType = "PLA"),
                ExtruderPreset(2, color = "#FFFFFF", materialType = "PETG"),
                ExtruderPreset(3, color = "#6A00D5", materialType = "PLA"),
            ),
            colorMapping = listOf(0, 1, 2, 3, 0, 1, 2),
            filamentCount = 7,
        )

        assertEquals(3, options[2].configValue)
        assertTrue(options[2].label.contains("E3"))
        assertTrue(options[2].label.contains("PETG"))
        assertEquals(4, options[3].configValue)
    }

    @Test
    fun `missing mapped slot falls back to synthetic slot index`() {
        val options = buildSupportFilamentOptions(
            extruderPresets = emptyList(),
            colorMapping = listOf(0, 1),
            filamentCount = 2,
        )

        assertEquals(4, options[3].configValue)
    }

    @Test
    fun `sparse mapping gives unmapped physical slot synthetic value instead of another slot value`() {
        val options = buildSupportFilamentOptions(
            extruderPresets = emptyList(),
            colorMapping = listOf(0, 2),
            filamentCount = 2,
        )

        assertEquals(1, options[0].configValue)
        assertEquals(3, options[1].configValue)
        assertEquals(2, options[2].configValue)
    }

    @Test
    fun `stl without mapping can still choose E4 support filament`() {
        val options = buildSupportFilamentOptions(
            extruderPresets = listOf(ExtruderPreset(3, materialType = "PETG")),
            colorMapping = null,
            filamentCount = 1,
        )

        assertEquals(4, options[3].configValue)
        assertTrue(options[3].label.contains("E4"))
        assertTrue(options[3].label.contains("PETG"))
    }

    @Test
    fun `non identity mapping translates physical slots to first matching file filament`() {
        val options = buildSupportFilamentOptions(
            extruderPresets = emptyList(),
            colorMapping = listOf(2, 0, 1),
            filamentCount = 3,
        )

        assertEquals(2, options[0].configValue)
        assertEquals(3, options[1].configValue)
        assertEquals(1, options[2].configValue)
    }
}
