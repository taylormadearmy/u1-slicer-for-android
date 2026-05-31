package com.u1.slicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural-grep guard for the "Split to Parts" button visibility gate in
 * [ObjectScopedEditSection] (the F66 Edit panel).
 *
 * Bug: pre-fix the button only appeared when `volumeCount > 1`. That hid the
 * "Split to Parts" affordance for files like `chain-20-links.stl` (one volume
 * containing 20 disconnected mesh islands) and `Jumping+frog.3mf` Object 1
 * (one volume, multi-island) — both legitimately splittable per
 * `volume->is_splittable()` in the native engine.
 *
 * OrcaSlicer desktop's "Split → To Parts" explicitly covers the
 * one-volume-with-multiple-islands case. This gate must do the same.
 *
 * Compose-only contract; this test reads the source to verify the gate
 * predicate includes the single-volume-splittable case.
 */
class SplitToPartsGateTest {

    private fun editPanelSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/ui/EditPanel.kt"),
            File("../app/src/main/java/com/u1/slicer/ui/EditPanel.kt"),
            File("src/main/java/com/u1/slicer/ui/EditPanel.kt"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("EditPanel.kt not found from ${File(".").absolutePath}")
        return f.readText()
    }

    @Test
    fun split_to_parts_button_visible_when_single_volume_is_splittable() {
        val src = editPanelSource()
        // The visibility gate must check the single-volume-splittable case.
        // Acceptable shapes: a `firstVolumeSplittable` flag or an inline
        // `isVolumeSplittable(objIdx, 0)` call inside the gate.
        val gateHasSingleVolumeBranch =
            src.contains("firstVolumeSplittable") ||
                Regex("""volumeCount\s*>\s*1\s*\|\|.*isVolumeSplittable\s*\(\s*objIdx\s*,\s*0\s*\)""")
                    .containsMatchIn(src)
        assertTrue(
            "Split to Parts gate must accept single-volume meshes whose lone " +
                "volume is splittable. Without this, chain-20-links.stl and " +
                "Jumping+frog.3mf Object 1 hide the Split to Parts button " +
                "even though native is_splittable() returns true for them. " +
                "Expected `volumeCount > 1 || firstVolumeSplittable` (or " +
                "equivalent) shape in EditPanel.kt's ObjectScopedEditSection.",
            gateHasSingleVolumeBranch,
        )
    }

    @Test
    fun split_to_parts_click_routes_to_first_splittable_volume() {
        val src = editPanelSource()
        // Click must route to splitVolume(objIdx, target) where target is the
        // first volume with `isVolumeSplittable == true`. For single-volume
        // files target == 0. The prior pattern that bailed on no-splittable
        // is fine; the test only requires the route to be present.
        assertTrue(
            "Split to Parts onClick must invoke viewModel.splitVolume(objIdx, ...). " +
                "Without this, the button is decorative.",
            src.contains("viewModel.splitVolume(objIdx, target)") ||
                src.contains("viewModel.splitVolume(objIdx, firstSplittable)"),
        )
    }

    @Test
    fun split_to_parts_enabled_only_when_any_volume_splittable() {
        val src = editPanelSource()
        // The button must keep the "enabled = anyVolumeSplittable" gate from
        // the earlier P1 fix. Without it, tapping a non-splittable target is
        // a silent no-op.
        assertTrue(
            "Split to Parts must be enabled-gated on anyVolumeSplittable so a " +
                "tap on a non-splittable single-volume mesh disables instead " +
                "of silently no-op'ing.",
            src.contains("enabled = anyVolumeSplittable"),
        )
    }
}
