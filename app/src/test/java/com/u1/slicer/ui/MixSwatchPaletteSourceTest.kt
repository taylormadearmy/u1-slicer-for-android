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

    @Test
    fun `filament chip resolves mix slots to blend colour`() {
        // B142: a mix-assigned part showed a grey swatch + "PLA" because
        // resolveFilamentChip had no mix-slot branch.
        assertTrue(src.contains("FilamentMixPredictor.predict"))
        assertTrue(src.contains("MixSlotOrdering"))
    }

    @Test
    fun `gcode preview and summary use slot palette when slice is mix tool space`() {
        // B142b: a mix-assigned slice emits PHYSICAL-SLOT tools (mixPhysicalBase
        // expansion) — the post-slice G-code palette and per-extruder summary must
        // use slot colours + mix blends, not the canonical file palette (which
        // rendered tools 2/3 grey on a 2-filament 3MF).
        val vm = File("src/main/java/com/u1/slicer/SlicerViewModel.kt").readText()
        assertTrue(vm.contains("_sliceMixToolSpace.value = anyMixAssigned"))
        assertTrue(vm.contains("val slotPaletteWithMixBlends"))
        val main = File("src/main/java/com/u1/slicer/MainActivity.kt").readText()
        assertTrue(main.contains("if (sliceMixToolSpace) slotPaletteWithMixBlends"))
        assertTrue(main.contains("mixToolSpacePalette"))
        val nav = File("src/main/java/com/u1/slicer/navigation/NavGraph.kt").readText()
        assertTrue(nav.contains("if (sliceMixToolSpace) slotPaletteWithMixBlends"))
    }

    @Test
    fun `model mix blend colours come from printer slot presets`() {
        // B142: loadedModelMixColors blended from _activeExtruderColors (the
        // model-narrowed palette) — on a 2-filament 3MF, components E3/E4 fell
        // back to grey and every blend rendered grey-pink. Must use presets.
        val vm = File("src/main/java/com/u1/slicer/SlicerViewModel.kt").readText()
        val block = vm.substringAfter("private val loadedModelMixColors")
            .substringBefore(".stateIn(")
        assertTrue("blend palette must come from extruderPresets", block.contains("extruderPresets"))
        assertFalse("blend palette must not use _activeExtruderColors", block.contains("_activeExtruderColors"))
    }
}
