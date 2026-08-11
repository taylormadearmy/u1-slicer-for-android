package com.u1.slicer.bambu

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BambuH2DGcodeTransformerTest {

    @Test
    fun `adds physical hotend arguments and embeds one based filament map`() {
        val transformed = BambuH2DGcodeTransformer.transform(
            source = """
                ; CONFIG_BLOCK_START
                ; printer_model = Bambu Lab H2D
                ; CONFIG_BLOCK_END
                M620 S0A
                T0
                M621 S0A
                ;LAYER_CHANGE
                M83
                G1 X30 Y10 E1
                T1
                G1 X10 Y10 E1
            """.trimIndent(),
            filamentMap = listOf(2, 1),
        )

        assertTrue(transformed.contains("; filament_map = 2,1"))
        assertTrue(transformed.contains("M620 S0A H1"))
        assertTrue(transformed.contains("\nT0 H1\n"))
        assertTrue(transformed.contains("M621 S0A H1"))
        assertTrue(transformed.contains("\nT1 H0\n"))
    }

    @Test
    fun `replaces generic nozzle placeholders with current H2D hotend metadata`() {
        val transformed = BambuH2DGcodeTransformer.transform(
            source = """
                ; CONFIG_BLOCK_START
                ; nozzle_diameter = 0.4,0.4,0.4,0.4
                ; nozzle_type = undefine
                ; nozzle_volume = 0
                ; CONFIG_BLOCK_END
            """.trimIndent(),
            filamentMap = listOf(2),
        )

        assertTrue(transformed.contains("; nozzle_diameter = 0.4,0.4"))
        assertTrue(transformed.contains("; nozzle_type = ${List(7) { "hardened_steel" }.joinToString(",")}"))
        assertTrue(transformed.contains("; nozzle_volume = 130,133,133,145,148,148,148"))
        assertTrue(transformed.contains("; printer_extruder_id = 1,1,1,2,2,2,2"))
        assertTrue(transformed.contains("; nozzle_volume_type = Standard,Standard"))
        assertTrue(!transformed.contains("nozzle_type = undefine"))
        assertTrue(!transformed.contains("nozzle_volume = 0"))
    }

    @Test
    fun `removes legacy single hotend load extrusion after H2D nozzle switch`() {
        val transformed = BambuH2DGcodeTransformer.transform(
            source = """
                M621 S1A
                G1 Z3.5 F3000
                G1 X196.312 Y259.900
                G1 Z.5 F30000
                G1 E2 F1800
                G4 S0
                ; CP TOOLCHANGE LOAD
                G1 X146.855 E63.0000 F1319
                G1 X196.312 E9.0000 F923
                G1 Y259.400
                ; CP TOOLCHANGE WIPE
                G1 X147.230 E1.8654 F1782
            """.trimIndent(),
            filamentMap = listOf(2),
        )

        assertTrue(transformed.contains("; H2D removed legacy single-hotend prime: G1 E2 F1800"))
        assertTrue(transformed.contains("G1 X146.855 F1319"))
        assertTrue(transformed.contains("G1 X196.312 F923"))
        assertTrue(!transformed.contains("E63.0000"))
        assertTrue(!transformed.contains("E9.0000"))
        assertTrue(transformed.contains("G1 X147.230 E1.8654 F1782"))
    }

    @Test
    fun `rejects any sibling H2D tower move with unsafe extrusion density`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BambuH2DGcodeTransformer.transform(
                source = """
                    G90
                    G1 X150 Y250
                    ; WIPE_TOWER_START
                    G1 X160 E20
                    ; WIPE_TOWER_END
                """.trimIndent(),
                filamentMap = listOf(1),
            )
        }

        assertTrue(error.message.orEmpty().contains("unsafe over-extrusion"))
    }

    @Test
    fun `tower density validation understands absolute extrusion`() {
        val transformed = BambuH2DGcodeTransformer.transform(
            source = """
                G90
                M82
                G92 E100
                G1 X150 Y250
                ; WIPE_TOWER_START
                G1 X160 E100.4
                G1 X170 E100.8
                ; WIPE_TOWER_END
            """.trimIndent(),
            filamentMap = listOf(1),
        )

        assertTrue(transformed.contains("G1 X170 E100.8"))
    }

    @Test
    fun `rejects extrusion outside the assigned physical nozzle reach`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BambuH2DGcodeTransformer.transform(
                source = """
                    T0
                    ;LAYER_CHANGE
                    M83
                    G1 X10 Y10 E1
                """.trimIndent(),
                filamentMap = listOf(2),
            )
        }

        assertTrue(error.message.orEmpty().contains("outside"))
        assertTrue(error.message.orEmpty().contains("nozzle 2"))
    }

    @Test
    fun `explicit nozzle assignment follows original sparse filament indices`() {
        assertTrue(
            resolveH2DFilamentMap(
                sourceFilamentIndices = listOf(1, 3),
                explicitNozzleAssignments = listOf(2, 1, 2, 2),
            ) == listOf(1, 2),
        )
    }

    @Test
    fun `auto assignment entries preserve inferred reach aware nozzle`() {
        assertTrue(
            resolveH2DFilamentMap(
                sourceFilamentIndices = listOf(0, 2),
                explicitNozzleAssignments = listOf(0, 1, 2),
                inferredNozzleAssignments = listOf(1, 1),
            ) == listOf(1, 2),
        )
    }

    @Test
    fun `compact explicit assignments are not confused with sparse source indices`() {
        assertTrue(
            resolveH2DFilamentMap(
                sourceFilamentIndices = listOf(1, 3),
                explicitNozzleAssignments = listOf(2, 1),
                inferredNozzleAssignments = listOf(1, 2),
            ) == listOf(2, 1),
        )
    }

    @Test
    fun `automatic assignment chooses the only nozzle that reaches each toolpath`() {
        val left = BambuH2DGcodeTransformer.inferFilamentMap(
            source = """
                T0
                ;LAYER_CHANGE
                M83
                G1 X10 Y10 E1
            """.trimIndent(),
            filamentCount = 1,
        )
        val right = BambuH2DGcodeTransformer.inferFilamentMap(
            source = """
                T0
                ;LAYER_CHANGE
                M83
                G1 X340 Y10 E1
            """.trimIndent(),
            filamentCount = 1,
        )
        val overlap = BambuH2DGcodeTransformer.inferFilamentMap(
            source = """
                T0
                ;LAYER_CHANGE
                M83
                G1 X175 Y10 E1
            """.trimIndent(),
            filamentCount = 1,
        )

        assertTrue(left == listOf(1))
        assertTrue(right == listOf(2))
        assertTrue(overlap == listOf(2))
    }
}
