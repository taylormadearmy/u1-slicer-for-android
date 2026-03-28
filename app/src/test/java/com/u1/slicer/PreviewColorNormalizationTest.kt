package com.u1.slicer

import com.u1.slicer.bambu.LayerToolSegment
import com.u1.slicer.bambu.parseLayerToolSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewColorNormalizationTest {

    @Test
    fun `parseLayerToolSegments returns empty list for empty xml`() {
        val result = parseLayerToolSegments("<custom_gcodes_per_layer></custom_gcodes_per_layer>")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseLayerToolSegments extracts type 1 and type 2 layers sorted by topZ`() {
        val xml = """
            <custom_gcodes_per_layer>
                <layer top_z="2.0" type="2" extruder="2" color="#368CF9" extra="" gcode="tool_change"/>
                <layer top_z="0.4" type="1" extruder="1" color="#F4D976" extra="" gcode="M601"/>
            </custom_gcodes_per_layer>
        """.trimIndent()
        val result = parseLayerToolSegments(xml)
        assertEquals(2, result.size)
        assertEquals(LayerToolSegment(topZ = 0.4f, extruderBambu = 1), result[0])
        assertEquals(LayerToolSegment(topZ = 2.0f, extruderBambu = 2), result[1])
    }

    @Test
    fun `parseLayerToolSegments ignores non-tool-change types`() {
        val xml = """
            <custom_gcodes_per_layer>
                <layer top_z="1.0" type="3" extruder="2" color="#FF0000" extra="" gcode="custom"/>
                <layer top_z="2.0" type="1" extruder="1" color="#00FF00" extra="" gcode="M601"/>
            </custom_gcodes_per_layer>
        """.trimIndent()
        val result = parseLayerToolSegments(xml)
        assertEquals(1, result.size)
        assertEquals(LayerToolSegment(topZ = 2.0f, extruderBambu = 1), result[0])
    }

    @Test
    fun `parseLayerToolSegments defaults extruder to 1 when missing`() {
        val xml = """
            <custom_gcodes_per_layer>
                <layer top_z="1.2" type="2" color="#AABBCC" extra="" gcode="tool_change"/>
            </custom_gcodes_per_layer>
        """.trimIndent()
        val result = parseLayerToolSegments(xml)
        assertEquals(1, result.size)
        assertEquals(1, result[0].extruderBambu)
    }
}
