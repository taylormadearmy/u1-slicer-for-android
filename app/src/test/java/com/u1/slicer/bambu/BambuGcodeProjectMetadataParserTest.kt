package com.u1.slicer.bambu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BambuGcodeProjectMetadataParserTest {

    @Test
    fun `parser extracts print statistics and real first layer bounds`() {
        val metadata = BambuGcodeProjectMetadataParser.parse(
            """
            ; HEADER_BLOCK_START
            ; max_z_height: 47.90
            ; HEADER_BLOCK_END
            ; CONFIG_BLOCK_START
            ; curr_bed_type = Textured PEI Plate
            ; enable_support = 1
            ; layer_height = 0.16
            ; CONFIG_BLOCK_END
            EXCLUDE_OBJECT_DEFINE NAME=cube CENTER=90,90 POLYGON=[[80,81],[100,81],[100,99],[80,99]]
            ;LAYER_CHANGE
            G0 X75 Y76
            G1 X75 Y104 E0.5
            G1 X105 Y104 E0.5
            ;LAYER_CHANGE
            G1 X160 Y160 E0.5
            ; filament used [mm] = 3956.74,1000
            ; filament used [g] = 11.80,3.20
            ; estimated printing time (normal mode) = 1h 2m 3s
            """.trimIndent(),
        )

        assertEquals(3_723, metadata.predictionSeconds)
        assertEquals(listOf(3.95674, 1.0), metadata.filamentUsedMetres)
        assertEquals(listOf(11.8, 3.2), metadata.filamentUsedGrams)
        assertEquals(15.0, metadata.totalWeightGrams, 0.0001)
        assertEquals(47.9, metadata.maxZ, 0.0001)
        assertEquals(0.16, metadata.layerHeight, 0.0001)
        assertEquals(BambuPrintBounds(80.0, 81.0, 100.0, 99.0), metadata.objectBounds)
        assertEquals(BambuPrintBounds(75.0, 76.0, 105.0, 104.0), metadata.plateBounds)
        assertEquals(360.0, metadata.objectArea, 0.0001)
        assertEquals("textured_plate", metadata.bedType)
        assertTrue(metadata.supportUsed)
    }
}
