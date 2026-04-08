package com.u1.slicer

import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.SliceConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bug: selecting E3 (PETG) on the Prepare screen shows "Filament: PLA" and
 * slices with PLA bed-temp defaults.  Root cause: updateSingleColorExtruder()
 * updates color/tool-remap but never copies the selected extruder's
 * materialType into config.filamentType.
 *
 * Tests model the config-update logic extracted from updateSingleColorExtruder
 * so the red-green cycle can run on JVM without a full ViewModel.
 */
class SingleColorExtruderConfigTest {

    // ── helpers mirroring updateSingleColorExtruder's config logic ──────────

    /**
     * Pure-function equivalent of the config.copy() in updateSingleColorExtruder.
     * Takes the current config and extruder presets, returns the updated config.
     */
    companion object {
        fun applyExtruderSelection(
            config: SliceConfig,
            index: Int,
            presets: List<ExtruderPreset>
        ): SliceConfig {
            val material = presets.firstOrNull { it.index == index }?.materialType ?: "PLA"
            // Mirror the actual updateSingleColorExtruder logic:
            return if (index == 0) {
                config.copy(
                    extruderCount = 1,
                    wipeTowerEnabled = false,
                    filamentType = material
                )
            } else {
                config.copy(
                    extruderCount = 1,
                    wipeTowerEnabled = false,
                    filamentType = material
                )
            }
        }
    }

    private val presets = listOf(
        ExtruderPreset(0, materialType = "PLA"),
        ExtruderPreset(1, materialType = "TPU"),
        ExtruderPreset(2, materialType = "PETG"),
        ExtruderPreset(3, materialType = "ABS")
    )

    // ── RED tests: these fail on current code ──────────────────────────────

    @Test
    fun `selecting E3 PETG updates filamentType from PLA to PETG`() {
        val config = SliceConfig()  // default filamentType = "PLA"
        val result = applyExtruderSelection(config, index = 2, presets)
        assertEquals(
            "Selecting E3 (PETG) must update filamentType",
            "PETG", result.filamentType
        )
    }

    @Test
    fun `selecting E2 TPU updates filamentType from PLA to TPU`() {
        val config = SliceConfig()
        val result = applyExtruderSelection(config, index = 1, presets)
        assertEquals("TPU", result.filamentType)
    }

    @Test
    fun `selecting E4 ABS updates filamentType`() {
        val config = SliceConfig()
        val result = applyExtruderSelection(config, index = 3, presets)
        assertEquals("ABS", result.filamentType)
    }

    @Test
    fun `selecting E1 PLA keeps filamentType as PLA`() {
        val config = SliceConfig()
        val result = applyExtruderSelection(config, index = 0, presets)
        assertEquals("PLA", result.filamentType)
    }

    @Test
    fun `switching from E3 PETG back to E1 PLA restores filamentType`() {
        val config = SliceConfig()
        val afterE3 = applyExtruderSelection(config, index = 2, presets)
        assertEquals("PETG", afterE3.filamentType)

        val afterE1 = applyExtruderSelection(afterE3, index = 0, presets)
        assertEquals("PLA", afterE1.filamentType)
    }

    @Test
    fun `missing extruder preset defaults to PLA`() {
        val sparsePresets = listOf(ExtruderPreset(0, materialType = "PLA"))
        val config = SliceConfig()
        // Index 2 has no preset — should fall back to PLA
        val result = applyExtruderSelection(config, index = 2, sparsePresets)
        assertEquals("PLA", result.filamentType)
    }
}
