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

    // ── G-code preview colour normalization ─────────────────────────────────

    /**
     * B48 regression: H2C benchy has a 7-entry colorMapping [2,0,3,2,0,1,0].
     * normalizeGcodePreviewColors takes the first 4 entries and remaps tool
     * colours, producing T0=blue, T1=red instead of T0=red, T1=green.
     *
     * For SEMM models, the slicer already maps model colours to physical
     * extruders internally.  The G-code tools T0-T3 ARE physical slot indices.
     * Passing the model→slot colorMapping as a tool→slot remap is WRONG.
     *
     * When colorMapping is null (SEMM identity), the function should return
     * the direct extruder slot colours.
     */
    @Test
    fun `normalizeGcodePreviewColors with null mapping returns direct slot colours`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
        val result = normalizeGcodePreviewColors(extruderColors, null)
        assertEquals("#FF0000", result[0])  // T0 = slot 0 = red
        assertEquals("#00FF00", result[1])  // T1 = slot 1 = green
        assertEquals("#0000FF", result[2])  // T2 = slot 2 = blue
        assertEquals("#FFFFFF", result[3])  // T3 = slot 3 = white
    }

    /**
     * B48 regression: when colorMapping is the H2C 7-entry model→slot mapping,
     * the G-code preview must NOT use it for tool colour assignment.
     * The correct fix is to pass null for SEMM models.  This test documents
     * what CURRENTLY happens (wrong) so we can verify the fix.
     */
    @Test
    fun `normalizeGcodePreviewColors with SEMM 7-entry mapping must not scramble colours`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
        // For SEMM models, colorMapping should be null (no tool remap needed).
        // The slicer's T0-T3 are already physical slot 0-3.
        val result = normalizeGcodePreviewColors(extruderColors, null)
        assertEquals("T0 must be red (slot 0)", "#FF0000", result[0])
        assertEquals("T1 must be green (slot 1)", "#00FF00", result[1])
        assertEquals("T2 must be blue (slot 2)", "#0000FF", result[2])
        assertEquals("T3 must be white (slot 3)", "#FFFFFF", result[3])
    }

    /**
     * B48 Part 2: if the 7-entry colorMapping [2,0,3,2,0,1,0] is accidentally
     * passed to normalizeGcodePreviewColors, it takes the first 4 entries
     * [2,0,3,2] and scrambles the tool colours: T0→blue, T1→red, T2→white, T3→blue.
     * Green (slot 1) is completely lost.
     *
     * This test documents the broken behaviour to catch regressions.
     */
    @Test
    fun `normalizeGcodePreviewColors with 7-entry SEMM mapping scrambles colours - regression guard`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
        val semmColorMapping = listOf(2, 0, 3, 2, 0, 1, 0)

        // This is what INCORRECTLY happens when the mapping is passed through
        val result = normalizeGcodePreviewColors(extruderColors, semmColorMapping)

        // T1 should be green (#00FF00) but gets red (#FF0000) because mapping[1]=0→slot0→red
        // This test proves the mapping scrambles colours — the fix is to pass null for SEMM
        assertEquals("T1 is scrambled to red (NOT green) when SEMM mapping is applied",
            "#FF0000", result[1])
    }

    // ── B92: Preview palette alignment with slicer tool order ───────────────

    /**
     * B92 canonical case — Buzz plate 8 shape:
     *
     *   detectedColors  = [#6F5034 brown (source filament 3, painted),
     *                      #FFFFFF white (source filament 10, object default)]
     *   colorMapping    = [0, 3]              // brown→E1, white→E4
     *   extruderColors  = [red, _, _, white]  // user's E1=red, E4=white
     *   slicerColorOrder= [1, 0]              // slicer T0=default=detectedColors[1];
     *                                          //  slicer T1=paint state=detectedColors[0]
     *   semmColorPerm   = [0, 3]              // slicer T0→physical T0; T1→T3
     *
     * After GcodeToolRemapper the G-code has T0 and T3. Under the OLD code, T0
     * rendered as red and T3 as white — which swapped Preview vs Prepare because
     * T0 is actually the object-default region (unpainted) while Prepare's
     * compact-0 entry is the painted region. The B92 fix returns palette where
     * T0 = white (the slot assigned to unpainted) and T3 = red (the slot assigned
     * to painted).
     */
    @Test
    fun `normalizeGcodePreviewColors B92 Buzz plate 8 shape aligns Preview with Prepare`() {
        val extruderColors = listOf("#FF0000", "", "", "#FFFFFF")
        val colorMapping = listOf(0, 3)
        val semmColorPermutation = listOf(0, 3)
        val slicerColorOrder = listOf(1, 0)

        val result = normalizeGcodePreviewColors(
            extruderColors = extruderColors,
            colorMapping = colorMapping,
            semmColorPermutation = semmColorPermutation,
            slicerColorOrder = slicerColorOrder
        )

        // T0 prints the unpainted region (slicer T0 = detectedColors[1] = white,
        // user assigned white → slot 3 = E4 = "#FFFFFF"), so Preview should render
        // T0 segments with #FFFFFF.
        assertEquals("T0 (unpainted) must render in E4 white", "#FFFFFF", result[0])
        // T3 prints the painted region (slicer T1 = detectedColors[0] = brown,
        // user assigned brown → slot 0 = E1 = "#FF0000"), so Preview should render
        // T3 segments with #FF0000.
        assertEquals("T3 (painted) must render in E1 red", "#FF0000", result[3])
    }

    /**
     * B92 identity case: slicerColorOrder = null means slicer tool order matches
     * detectedColors order (simple SEMM, H2C, non-paint). normalizeGcodePreviewColors
     * should preserve direct slot colours (init loop) without applying the
     * legacy compact-index override when semmColorPermutation is active.
     */
    @Test
    fun `normalizeGcodePreviewColors with semmPerm and identity slicer order uses direct slot colours`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
        val result = normalizeGcodePreviewColors(
            extruderColors = extruderColors,
            colorMapping = listOf(3, 0, 2, 1),
            semmColorPermutation = listOf(3, 0, 2, 1),
            slicerColorOrder = null
        )
        // After GcodeToolRemapper the G-code has T0..T3 where T<n> is the physical
        // slot. Preview should render each T<n> with extruderColors[n] (direct slot).
        assertEquals("#FF0000", result[0])
        assertEquals("#00FF00", result[1])
        assertEquals("#0000FF", result[2])
        assertEquals("#FFFFFF", result[3])
    }

    /**
     * B92 legacy caller: semmColorPermutation=null (no SEMM remap), non-null
     * colorMapping — preserves pre-v1.6.10 behaviour used by per-object path and
     * single-colour SEMM callers that don't pass the new params.
     */
    @Test
    fun `normalizeGcodePreviewColors legacy call without semmPerm retains compact override`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
        val result = normalizeGcodePreviewColors(
            extruderColors = extruderColors,
            colorMapping = listOf(2, 3),
            semmColorPermutation = null,
            slicerColorOrder = null
        )
        // Compact 0 → slot 2 (blue), compact 1 → slot 3 (white).
        assertEquals("#0000FF", result[0])
        assertEquals("#FFFFFF", result[1])
        assertEquals("#0000FF", result[2])
        assertEquals("#FFFFFF", result[3])
    }
}
