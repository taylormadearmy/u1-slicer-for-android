package com.u1.slicer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B144 structural guard — the Send "Filament mapping" dialog must present
 * PHYSICAL slots (not the model's file filaments) for a mix-tool-space slice.
 *
 * A mix-assigned slice emits a G-code body already in physical-slot space
 * (E1..E4 baked in). Pre-fix, the Send dialog still ran the canonical /
 * `buildWideGcodeMapping` path and synthesised the mix's component slots as
 * `SUPPORT_FILAMENT` rows mislabelled "support". The fix wires
 * [buildMixSlotMapping] into the `plateNarrowed` remember (gated on
 * `viewModel.sliceMixToolSpace`) and passes `physicalSlotSpace = ` to the
 * dialog so rows relabel "E<n>".
 *
 * Enforced via source-grep (no Compose UI harness in this project) — the
 * behavioural math lives in `BuildMixSlotMappingTest`.
 */
class MixSendDialogWiringTest {

    private val mainActivitySource: String by lazy {
        val f = File("src/main/java/com/u1/slicer/MainActivity.kt")
        require(f.exists()) { "MainActivity.kt not found at ${f.absolutePath} — run from app/ module" }
        f.readText()
    }

    @Test
    fun mainActivity_passesPhysicalSlotSpace_toFilamentMappingDialog() {
        assertTrue(
            "MainActivity must pass `physicalSlotSpace = ` to FilamentMappingDialog " +
                "so a mix slice's Send dialog relabels rows E<n> instead of 'Filament N'.",
            mainActivitySource.contains("physicalSlotSpace = "),
        )
    }

    @Test
    fun plateNarrowedRemember_referencesMixToolSpace_andBuildMixSlotMapping() {
        val src = mainActivitySource
        // The mix branch must be wired into the dialog's filament-list derivation.
        assertTrue(
            "MainActivity must collect `viewModel.sliceMixToolSpace` in the Send-dialog " +
                "composable so the mix branch can fire.",
            src.contains("viewModel.sliceMixToolSpace.collectAsState()"),
        )
        assertTrue(
            "MainActivity must call `buildMixSlotMapping(` to build the physical-slot " +
                "dialog model for a mix-tool-space slice.",
            src.contains("buildMixSlotMapping("),
        )
        // The plateNarrowed remember must consult the mix mapping first.
        val plateNarrowedIdx = src.indexOf("val plateNarrowed:")
        assertTrue("plateNarrowed remember must still exist.", plateNarrowedIdx >= 0)
        assertTrue(
            "The plateNarrowed remember must short-circuit to the mix mapping " +
                "(`mixSlotMapping?.let`) so a mix slice shows physical slots first.",
            src.contains("mixSlotMapping?.let"),
        )
    }
}
