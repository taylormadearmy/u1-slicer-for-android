package com.u1.slicer.bambu

import org.junit.Assert.assertEquals
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
