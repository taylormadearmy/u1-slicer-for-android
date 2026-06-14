package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Top-surface mixing (BETA) section in the mix editor — structural guards.
 *
 * Source-grep style (no Compose UI harness in this project; mirrors
 * [ModelInfoDialogScrollTest]). Asserts that:
 *
 * 1. `CreateMixSlotDialog` contains a "Top surface mixing" section header with a
 *    BETA marker (the shared [BetaPill] used by the other mix BETA affordances).
 * 2. The section offers four mode options labelled Off / Stripes / Proportional /
 *    Dither, bound to `MixedFilamentRow.TopMixMode`.
 * 3. Two toggles labelled "Fine top lines" and "Ironing glaze" bound to
 *    `fineTopLines` / `ironingGlaze` state.
 * 4. Editing an existing mix seeds the section from the row's saved values
 *    (`editingRow?.topMixMode` etc.).
 * 5. The save path persists all three settings: the dialog's confirm callback
 *    threads them out, and the call sites persist via
 *    `MixedFilamentManager.updateTopSurfaceSettings`.
 */
class TopSurfaceMixSettingsUiTest {

    private fun src(relative: String): String {
        val candidates = listOf(
            File("app/$relative"),
            File("../app/$relative"),
            File(relative),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")
        return f.readText()
    }

    private val dialog = src("src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt")

    @Test
    fun editor_hasTopSurfaceMixingHeader_withBetaMarker() {
        assertTrue(
            "CreateMixSlotDialog must contain a 'Top surface mixing' section header",
            dialog.contains("Top surface mixing"),
        )
        // The header must carry the BETA marker: at least two BetaPill() calls in the
        // file (one on the dialog title, one on the new section).
        val betaPills = Regex("BetaPill\\(\\)").findAll(dialog).count()
        assertTrue(
            "the 'Top surface mixing' section must carry a BETA pill " +
                "(expected >=2 BetaPill() calls in CreateMixSlotDialog.kt, found $betaPills)",
            betaPills >= 2,
        )
    }

    @Test
    fun editor_offersStripesProportionalDither_boundToTopMixMode() {
        for (label in listOf("\"Off\"", "\"Stripes\"", "\"Proportional\"", "\"Dither\"")) {
            assertTrue(
                "mode picker must render an option labelled $label",
                dialog.contains(label),
            )
        }
        for (value in listOf(
            "TopMixMode.OFF",
            "TopMixMode.STRIPES",
            "TopMixMode.PROPORTIONAL",
            "TopMixMode.DITHER",
        )) {
            assertTrue(
                "mode picker options must be bound to MixedFilamentRow.$value",
                dialog.contains(value),
            )
        }
    }

    @Test
    fun editor_hasFineTopLinesAndIroningGlazeToggles() {
        assertTrue(
            "must render a toggle labelled \"Fine top lines\"",
            dialog.contains("\"Fine top lines\""),
        )
        assertTrue(
            "must render a toggle labelled \"Ironing glaze\"",
            dialog.contains("\"Ironing glaze\""),
        )
        assertTrue(
            "toggles must be bound to fineTopLines / ironingGlaze state",
            dialog.contains("fineTopLines") && dialog.contains("ironingGlaze"),
        )
        assertTrue(
            "toggles must use Switch (Material3) bound via onCheckedChange",
            dialog.contains("Switch(") && dialog.contains("onCheckedChange"),
        )
    }

    @Test
    fun editingExistingMix_seedsSavedValues() {
        assertTrue(
            "topMixMode state must be seeded from editingRow?.topMixMode so Edit mode " +
                "shows the saved mode",
            dialog.contains("editingRow?.topMixMode"),
        )
        assertTrue(
            "fineTopLines state must be seeded from editingRow?.fineTopLines",
            dialog.contains("editingRow?.fineTopLines"),
        )
        assertTrue(
            "ironingGlaze state must be seeded from editingRow?.ironingGlaze",
            dialog.contains("editingRow?.ironingGlaze"),
        )
    }

    @Test
    fun savePath_threadsSettingsOutOfDialog_andPersistsViaUpdateTopSurfaceSettings() {
        // (a) The confirm callback must thread the three settings out of the dialog.
        assertTrue(
            "onConfirmN signature must include topMixMode, fineTopLines and ironingGlaze " +
                "so the save path receives the section's values",
            dialog.contains("topMixMode: MixedFilamentRow.TopMixMode") &&
                dialog.contains("fineTopLines: Boolean") &&
                dialog.contains("ironingGlaze: Boolean"),
        )
        // (b) Every production save path must persist via updateTopSurfaceSettings.
        // FilamentScreen drives MixedFilamentManager directly; the other three call
        // sites (MainActivity / NavGraph / PartsPanel) go through SlicerViewModel's
        // createMixN / editMixN, which must forward to updateTopSurfaceSettings.
        val filamentScreen = src("src/main/java/com/u1/slicer/ui/FilamentScreen.kt")
        assertTrue(
            "FilamentScreen's CreateMixSlotDialog confirm must persist via " +
                "MixedFilamentManager.updateTopSurfaceSettings",
            filamentScreen.contains("updateTopSurfaceSettings("),
        )
        val viewModel = src("src/main/java/com/u1/slicer/SlicerViewModel.kt")
        assertTrue(
            "SlicerViewModel.createMixN/editMixN must persist the top-surface settings " +
                "via mixedFilamentManager.updateTopSurfaceSettings",
            viewModel.contains("updateTopSurfaceSettings("),
        )
    }
}
