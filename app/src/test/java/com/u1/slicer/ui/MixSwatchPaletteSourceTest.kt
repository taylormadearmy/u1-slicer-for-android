package com.u1.slicer.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mixes blend PHYSICAL extruders (E1..E4), so every mix swatch / Create-Mix
 * component palette must derive from the printer's slot presets
 * (extruderPresets), never from the model's file-declared filament colours.
 *
 * 2026-06-10 sanity-test bug: the object-selector FilamentChooserDialog built
 * its mix palette preferring resolvedFilamentColors — a CMYW-loaded printer
 * showed red/black mix swatches for a red/black model. The file-resolved
 * colours are correct for the canonical filament ROWS (resolveFilamentChip);
 * they are wrong for mixes.
 */
class MixSwatchPaletteSourceTest {

    private val src = File("src/main/java/com/u1/slicer/ui/PartsPanel.kt").readText()

    @Test
    fun `chooser mix palette is built from printer slot presets`() {
        assertTrue(
            "FilamentChooserDialog's physicalColours must key on extruderPresets",
            src.contains("remember(numPhysical, extruderPresets)")
        )
    }

    @Test
    fun `chooser mix palette never reads file-resolved colours`() {
        // The exact shape of the 2026-06-10 bug. resolveFilamentChip's
        // `resolvedColors.getOrNull(idx)` (canonical rows) remains legitimate.
        assertFalse(src.contains("resolvedColors.getOrNull(slot - 1)"))
    }
}
