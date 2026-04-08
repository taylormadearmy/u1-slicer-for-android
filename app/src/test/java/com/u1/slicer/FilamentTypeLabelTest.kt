package com.u1.slicer

import com.u1.slicer.data.ExtruderPreset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B59: filamentType label is wrong in three scenarios:
 *
 * 1. Single-color model on initial load — E1 preset material ignored, stays "PLA"
 * 2. Multi-color model load — applyMultiColorAssignments never sets filamentType
 * 3. Layer-tool (Hueforge) load — same gap as multi-color
 *
 * Fix: extract resolveFilamentTypeLabel(usedSlots, presets) helper used by all
 * three code paths.
 *
 * Rules:
 *  - Single slot used → that slot's materialType
 *  - All used slots share same materialType → that type
 *  - Slots differ → "Mixed"
 *  - No presets / unknown slot → "PLA" (safe default)
 */
class FilamentTypeLabelTest {

    private val presets = listOf(
        ExtruderPreset(0, materialType = "PLA"),
        ExtruderPreset(1, materialType = "PETG"),
        ExtruderPreset(2, materialType = "PETG"),
        ExtruderPreset(3, materialType = "ABS")
    )

    // ── Gap 1: single-color initial load ─────────────────────────────────────

    @Test
    fun `single-color load on E1 PLA returns PLA`() {
        assertEquals("PLA", resolveFilamentTypeLabel(listOf(0), presets))
    }

    @Test
    fun `single-color load on E2 PETG returns PETG`() {
        assertEquals("PETG", resolveFilamentTypeLabel(listOf(1), presets))
    }

    @Test
    fun `single-color load on E4 ABS returns ABS`() {
        assertEquals("ABS", resolveFilamentTypeLabel(listOf(3), presets))
    }

    // ── Gap 2: multi-color — all same material ────────────────────────────────

    @Test
    fun `multi-color E1+E2 both PLA returns PLA`() {
        val allPlaPresets = listOf(
            ExtruderPreset(0, materialType = "PLA"),
            ExtruderPreset(1, materialType = "PLA")
        )
        assertEquals("PLA", resolveFilamentTypeLabel(listOf(0, 1), allPlaPresets))
    }

    @Test
    fun `multi-color E2+E3 both PETG returns PETG`() {
        // presets[1] = PETG, presets[2] = PETG
        assertEquals("PETG", resolveFilamentTypeLabel(listOf(1, 2), presets))
    }

    // ── Gap 2: multi-color — mixed materials → "Mixed" ───────────────────────

    @Test
    fun `multi-color E1 PLA + E2 PETG returns Mixed`() {
        assertEquals("Mixed", resolveFilamentTypeLabel(listOf(0, 1), presets))
    }

    @Test
    fun `multi-color E1 PLA + E4 ABS returns Mixed`() {
        assertEquals("Mixed", resolveFilamentTypeLabel(listOf(0, 3), presets))
    }

    @Test
    fun `four-color all different returns Mixed`() {
        assertEquals("Mixed", resolveFilamentTypeLabel(listOf(0, 1, 2, 3), presets))
    }

    // ── Gap 3: layer-tool — treated same as multi-color ──────────────────────

    @Test
    fun `layer-tool with single slot E1 returns PLA`() {
        // layer-tool files use a single physical extruder
        assertEquals("PLA", resolveFilamentTypeLabel(listOf(0), presets))
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `unknown slot falls back to PLA`() {
        assertEquals("PLA", resolveFilamentTypeLabel(listOf(5), presets))
    }

    @Test
    fun `empty slot list falls back to PLA`() {
        assertEquals("PLA", resolveFilamentTypeLabel(emptyList(), presets))
    }

    @Test
    fun `empty preset list falls back to PLA`() {
        assertEquals("PLA", resolveFilamentTypeLabel(listOf(0), emptyList()))
    }
}
