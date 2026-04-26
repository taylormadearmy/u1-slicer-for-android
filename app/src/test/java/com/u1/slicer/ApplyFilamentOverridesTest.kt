package com.u1.slicer

import com.u1.slicer.data.ExtruderPreset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2.7 — pure-Kotlin equivalent of
 * [SlicerViewModel.applyFilamentOverridesToPresets] for unit testing.
 * The ViewModel uses the same logic, just reading from its own state.
 */
class ApplyFilamentOverridesTest {

    private fun apply(
        presets: List<ExtruderPreset>,
        overrides: Map<Int, SlicerViewModel.FilamentOverride>,
        mapping: List<Int>?,
    ): List<ExtruderPreset> {
        if (overrides.isEmpty() || mapping.isNullOrEmpty()) return presets
        return presets.map { preset ->
            val fileIdx = mapping.indexOf(preset.index)
            if (fileIdx < 0) return@map preset
            val ov = overrides[fileIdx] ?: return@map preset
            preset.copy(
                materialType = ov.materialType ?: preset.materialType,
                color = ov.color ?: preset.color,
            )
        }
    }

    private val basePresets = listOf(
        ExtruderPreset(0, "#FF0000", "PLA"),
        ExtruderPreset(1, "#00FF00", "PLA"),
        ExtruderPreset(2, "#0000FF", "PLA"),
        ExtruderPreset(3, "#FFFFFF", "PLA"),
    )

    @Test
    fun `no overrides returns presets unchanged`() {
        val result = apply(basePresets, emptyMap(), listOf(0, 1, 2, 3))
        assertEquals(basePresets, result)
    }

    @Test
    fun `null mapping returns presets unchanged`() {
        val result = apply(
            basePresets,
            mapOf(0 to SlicerViewModel.FilamentOverride(materialType = "PETG")),
            null,
        )
        assertEquals(basePresets, result)
    }

    @Test
    fun `material override applied to mapped slot`() {
        // File filament 0 → slot 0 (per mapping). Override its material to PETG.
        val result = apply(
            presets = basePresets,
            overrides = mapOf(0 to SlicerViewModel.FilamentOverride(materialType = "PETG")),
            mapping = listOf(0, 1),
        )
        assertEquals("PETG", result[0].materialType)
        assertEquals("PLA", result[1].materialType)
        assertEquals("PLA", result[2].materialType)
        assertEquals("PLA", result[3].materialType)
        // Colour unchanged since override.color is null
        assertEquals("#FF0000", result[0].color)
    }

    @Test
    fun `colour override applied to mapped slot`() {
        val result = apply(
            presets = basePresets,
            overrides = mapOf(1 to SlicerViewModel.FilamentOverride(color = "#FFA500")),
            mapping = listOf(0, 1, 2, 3),
        )
        assertEquals("#FFA500", result[1].color)
        assertEquals("PLA", result[1].materialType)  // unchanged
    }

    @Test
    fun `both colour and material override applied`() {
        val result = apply(
            presets = basePresets,
            overrides = mapOf(2 to SlicerViewModel.FilamentOverride(
                color = "#888888", materialType = "ABS",
            )),
            mapping = listOf(0, 1, 2, 3),
        )
        assertEquals("#888888", result[2].color)
        assertEquals("ABS", result[2].materialType)
    }

    @Test
    fun `non-identity mapping resolves slot correctly`() {
        // Mapping says file filament 0 → slot 2, file filament 1 → slot 0.
        // Override on file filament 0 (PETG) should apply to slot 2,
        // not slot 0.
        val result = apply(
            presets = basePresets,
            overrides = mapOf(0 to SlicerViewModel.FilamentOverride(materialType = "PETG")),
            mapping = listOf(2, 0),
        )
        assertEquals("PETG", result[2].materialType)  // slot 2 (which is mapping[0]) overridden
        assertEquals("PLA", result[0].materialType)
    }

    @Test
    fun `override for fileIdx beyond mapping size is ignored`() {
        // mapping has 2 entries (file filaments 0..1), but override targets
        // fileIdx 5 which doesn't exist. Result: no preset changed.
        val result = apply(
            presets = basePresets,
            overrides = mapOf(5 to SlicerViewModel.FilamentOverride(materialType = "PETG")),
            mapping = listOf(0, 1),
        )
        assertEquals(basePresets, result)
    }

    @Test
    fun `override with null material and null color is a no-op`() {
        val result = apply(
            presets = basePresets,
            overrides = mapOf(0 to SlicerViewModel.FilamentOverride()),
            mapping = listOf(0, 1, 2, 3),
        )
        assertEquals(basePresets, result)
    }
}
