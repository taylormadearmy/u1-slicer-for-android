package com.u1.slicer.bambu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerToolCustomGcodeXmlTest {

    @Test
    fun `type 1 layers contribute colours and extruders like type 2`() {
        val xml = """
            <custom_gcodes_per_layer>
                <layer top_z="0.4" type="1" extruder="2" color="#F4D976" extra="" gcode="M601"/>
                <layer top_z="1.6" type="2" extruder="3" color="#368CF9" extra="" gcode="tool_change"/>
            </custom_gcodes_per_layer>
        """.trimIndent()

        val info = parseLayerToolCustomGcodeXml(xml)
        assertTrue(info.hasToolChanges)
        assertEquals(listOf("#F4D976", "#368CF9"), info.colors)
        assertEquals(setOf(2, 3), info.extruders)
    }

    @Test
    fun `parseLayerToolCustomGcodeXmlPerPlate returns per-plate info keyed by plate id`() {
        val xml = """
            <custom_gcodes_per_layer>
            <plate>
            <plate_info id="1"/>
            <layer top_z="9.2" type="2" extruder="2" color="#2323F7" extra="" gcode="tool_change"/>
            <mode value="MultiAsSingle"/>
            </plate>
            <plate>
            <plate_info id="2"/>
            <layer top_z="1.4" type="2" extruder="2" color="#F4D976" extra="" gcode="tool_change"/>
            <mode value="MultiAsSingle"/>
            </plate>
            <plate>
            <plate_info id="3"/>
            <mode value="MultiAsSingle"/>
            </plate>
            </custom_gcodes_per_layer>
        """.trimIndent()

        val perPlate = parseLayerToolCustomGcodeXmlPerPlate(xml)

        // Plate 1: has tool change with off-palette color
        assertTrue(perPlate[1]!!.hasToolChanges)
        assertEquals(listOf("#2323F7"), perPlate[1]!!.colors)
        assertEquals(setOf(2), perPlate[1]!!.extruders)

        // Plate 2: has tool change with real palette color
        assertTrue(perPlate[2]!!.hasToolChanges)
        assertEquals(listOf("#F4D976"), perPlate[2]!!.colors)
        assertEquals(setOf(2), perPlate[2]!!.extruders)

        // Plate 3: no tool changes
        assertFalse(perPlate[3]!!.hasToolChanges)
        assertTrue(perPlate[3]!!.colors.isEmpty())
        assertTrue(perPlate[3]!!.extruders.isEmpty())
    }

    @Test
    fun `type 2 only still parses`() {
        val xml = """
            <custom_gcodes_per_layer>
                <layer top_z="0.4" type="2" extruder="2" color="#F4D976" extra="" gcode="tool_change"/>
            </custom_gcodes_per_layer>
        """.trimIndent()

        val info = parseLayerToolCustomGcodeXml(xml)
        assertTrue(info.hasToolChanges)
        assertEquals(listOf("#F4D976"), info.colors)
        assertEquals(setOf(2), info.extruders)
    }
}
