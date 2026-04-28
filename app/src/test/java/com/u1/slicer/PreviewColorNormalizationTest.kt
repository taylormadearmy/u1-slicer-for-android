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
    /**
     * Phase 2 (2026-04-28) — the slicer-order/semm-permutation/useDirectSlots
     * legacy branches were retired with Group B (slice-time tool remap is
     * dead in Phase 2's canonical contract). Tests that asserted those code
     * paths' specific behaviour were removed alongside; the canonical-driven
     * path tests (`collision case ...`, `non-contiguous slots ...`,
     * `Dragon-style ...`, `falls back to file filament ...`) below cover all
     * remaining behaviour.
     */

    /**
     * Pre-canonical caller: no resolvedFilamentColors, non-null colorMapping —
     * preserves the simple compact-index → slot-preset mapping for callers
     * that don't pass the canonical filament list yet (e.g. mid-load, before
     * the resolver populates).
     */
    @Test
    fun `normalizeGcodePreviewColors no canonical with compact mapping uses compact-to-slot`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
        val result = normalizeGcodePreviewColors(
            extruderColors = extruderColors,
            colorMapping = listOf(2, 3),
        )
        // Compact 0 → slot 2 (blue), compact 1 → slot 3 (white).
        assertEquals("#0000FF", result[0])
        assertEquals("#FFFFFF", result[1])
        assertEquals("#0000FF", result[2])
        assertEquals("#FFFFFF", result[3])
    }

    // ── Phase 2 §4 Step 7 — G-code Preview slot-collision fix ───────────────

    /**
     * S-Buttons plate 1 user repro: 12 file filaments collapse onto 4 slots
     * via findClosestExtruder + redistributeDuplicateSlots. The auto mapping
     * uses all four slots {0,1,2,3} but multiple file filaments map to the
     * same slot. Pre-fix behaviour: `normalized[slot] = resolvedFilamentColors[firstIdx]`
     * — the first-occurrence file filament's hex (often a brownish near-red
     * for the slot 0 closest match) drowned out the slot's actual loaded
     * preset colour. The user perceived "missing red" because slot 0's E1
     * preset (red) never reached the renderer.
     *
     * Fix: drive the palette by `compactSlotOrder = colorMapping.distinct().sorted()`,
     * indexing by COMPACT slicer T-index (which is what the renderer reads
     * via `extruderColors[move.extruder]` under Phase 2.5's
     * skipSliceTimeRemap=true regime). For each compact c, prefer the slot's
     * loaded preset colour (`extruderColors[compactSlotOrder[c]]`).
     */
    @Test
    fun `normalizeGcodePreviewColors collision case prefers slot preset over first-file-filament`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")  // E1=red, E2=green, E3=blue, E4=white
        // 12 file filaments mapping to all 4 slots, with collisions.
        // The first file filament mapped to slot 0 is brown (#6F5034) —
        // closest-by-RGB-distance to red but visually distinct.
        val resolvedFilamentColors = listOf(
            "#6F5034", "#FFD700", "#0066CC", "#F5F5F5",     // closest matches
            "#A52A2A", "#9ACD32", "#1E90FF", "#FFFAFA",     // duplicates
            "#8B4513", "#7FFF00", "#4169E1", "#FFE4E1",     // duplicates
        )
        val colorMapping = listOf(0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3)
        val result = normalizeGcodePreviewColors(
            extruderColors = extruderColors,
            colorMapping = colorMapping,
            resolvedFilamentColors = resolvedFilamentColors,
        )
        // compactSlotOrder = [0,1,2,3], so compact c = slot c. Each compact
        // index renders with the slot's preset colour, NOT the first
        // file filament's hex. User's "missing red" symptom resolves
        // because compact 0 = E1 preset = #FF0000.
        assertEquals("compact 0 must render in E1 preset (red), not file filament's brown",
            "#FF0000", result[0])
        assertEquals("compact 1 must render in E2 preset (green)",
            "#00FF00", result[1])
        assertEquals("compact 2 must render in E3 preset (blue)",
            "#0000FF", result[2])
        assertEquals("compact 3 must render in E4 preset (white)",
            "#FFFFFF", result[3])
    }

    /**
     * Non-contiguous slot mapping (e.g. user picks E1, E3, E4 only — slot 1
     * unused). `compactSlotOrder = [0, 2, 3]`. Renderer T0/T1/T2 must map to
     * slots 0/2/3 respectively — NOT to slots 0/1/2 as the pre-fix code
     * implied (the slot-keyed normalize indexed by physical slot left
     * `normalized[1]` blank or stuck on extruderColors[1]'s init value).
     */
    @Test
    fun `normalizeGcodePreviewColors non-contiguous slots maps compact c to compactSlotOrder slot`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
        val resolvedFilamentColors = listOf("#FF1010", "#1010FF", "#F0F0F0")
        // Three file filaments → slots 0, 2, 3 (slot 1 unused).
        val colorMapping = listOf(0, 2, 3)
        val result = normalizeGcodePreviewColors(
            extruderColors = extruderColors,
            colorMapping = colorMapping,
            resolvedFilamentColors = resolvedFilamentColors,
        )
        // compactSlotOrder = [0, 2, 3]. Compact 0 → slot 0 (red), compact 1
        // → slot 2 (blue), compact 2 → slot 3 (white). Compact 3 unused; the
        // init-loop preset for slot 3 stays.
        assertEquals("compact 0 → slot 0 = red", "#FF0000", result[0])
        assertEquals("compact 1 → slot 2 = blue (NOT slot 1 = green)",
            "#0000FF", result[1])
        assertEquals("compact 2 → slot 3 = white", "#FFFFFF", result[2])
    }

    /**
     * Dragon plate 3 regression guard. Phase 2 (2026-04-28, post-
     * adversarial-review) — palette is canonical-fileIndex space
     * (slicer body emits `T<fileIndex>`, not `T<compactSlot>`), so
     * `result[fileIdx]` returns the slot preset that the user mapped
     * `fileIdx` to. For colorMapping=[2,0,3,1]:
     *   - fileIdx 0 → slot 2 → blue
     *   - fileIdx 1 → slot 0 → red
     *   - fileIdx 2 → slot 3 → white
     *   - fileIdx 3 → slot 1 → green
     *
     * Pre-revision the palette was indexed by compact-slot order
     * (`compactSlotOrder = colorMapping.distinct().sorted()`), giving
     * `[red, green, blue, white]` regardless of mapping permutation —
     * which was correct under v1.6.13's slicer-side T-index baking but
     * wrong under Phase 2's canonical-emit contract. The reviewer's
     * "G-code preview palette is still capped to four colors" finding
     * caught this.
     */
    @Test
    fun `normalizeGcodePreviewColors Dragon-style 4-distinct-slots renders mapped slot presets`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
        val resolvedFilamentColors = listOf("#A00000", "#00A000", "#0000A0", "#F0F0F0")
        // file filament i → slot per Dragon plate 3 shape (4 distinct slots).
        val colorMapping = listOf(2, 0, 3, 1)
        val result = normalizeGcodePreviewColors(
            extruderColors = extruderColors,
            colorMapping = colorMapping,
            resolvedFilamentColors = resolvedFilamentColors,
        )
        // Each canonical fileIdx renders in the colour of the slot the
        // user picked for that filament in the Send dialog.
        assertEquals("fileIdx 0 → slot 2 → blue", "#0000FF", result[0])
        assertEquals("fileIdx 1 → slot 0 → red", "#FF0000", result[1])
        assertEquals("fileIdx 2 → slot 3 → white", "#FFFFFF", result[2])
        assertEquals("fileIdx 3 → slot 1 → green", "#00FF00", result[3])
    }

    /**
     * Phase 2 high-T canonical preview palette regression guard.
     * Reviewer-flagged scenario: H2C benchy slices to T0..T6, Buzz
     * plate 9 to T9+T10. Pre-fix the palette was capped at 4 entries
     * so high-T tools collapsed visually onto the last colour. Post-
     * fix the palette grows to canonical size (or colorMapping size).
     */
    @Test
    fun `normalizeGcodePreviewColors high-T canonical palette grows beyond 4`() {
        val extruderColors = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
        // 7 file filaments mapping across 4 physical slots with
        // collisions (H2C benchy shape).
        val colorMapping = listOf(0, 1, 2, 3, 0, 1, 2)
        val resolvedFilamentColors = listOf(
            "#A00000", "#00A000", "#0000A0", "#F0F0F0",
            "#660000", "#006600", "#000066",
        )
        val result = normalizeGcodePreviewColors(
            extruderColors = extruderColors,
            colorMapping = colorMapping,
            resolvedFilamentColors = resolvedFilamentColors,
        )
        // Palette length must grow to at least 7 (canonical extent).
        assertTrue("palette must extend to canonical size, got ${result.size}",
            result.size >= 7)
        // High-T entries resolve via colorMapping → slot preset.
        assertEquals("fileIdx 4 → slot 0 → red", "#FF0000", result[4])
        assertEquals("fileIdx 5 → slot 1 → green", "#00FF00", result[5])
        assertEquals("fileIdx 6 → slot 2 → blue", "#0000FF", result[6])
    }

    /**
     * Slot preset blank → file filament fallback path. Some test/debug
     * configurations leave a slot preset empty; the canonical-driven path
     * must still surface the file's own colour rather than rendering with
     * the renderer's default-palette stripe (the historical sky-blue bug).
     */
    @Test
    fun `normalizeGcodePreviewColors falls back to file filament when slot preset blank`() {
        val extruderColors = listOf("#FF0000", "", "#0000FF", "#FFFFFF")  // E2 missing
        val resolvedFilamentColors = listOf("#A0A000", "#00A0A0", "#0000A0", "#F0F0F0")
        // file filament 1 maps to slot 1 (which has a blank preset).
        val colorMapping = listOf(0, 1, 2, 3)
        val result = normalizeGcodePreviewColors(
            extruderColors = extruderColors,
            colorMapping = colorMapping,
            resolvedFilamentColors = resolvedFilamentColors,
        )
        // compact 1 → slot 1 (blank preset) → fallback to resolvedFilamentColors[1].
        assertEquals("compact 0 → slot 0 preset", "#FF0000", result[0])
        assertEquals("compact 1 falls back to file filament 1's colour",
            "#00A0A0", result[1])
        assertEquals("compact 2 → slot 2 preset", "#0000FF", result[2])
        assertEquals("compact 3 → slot 3 preset", "#FFFFFF", result[3])
    }

    // useDirectSlots was retired with Group B (Phase 2 canonical contract;
    // the slicer no longer baked physical slot indices into the G-code).
}
