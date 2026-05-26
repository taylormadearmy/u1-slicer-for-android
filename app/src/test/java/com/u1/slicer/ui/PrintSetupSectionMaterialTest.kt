package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B128 structural-grep guard for the Compose-only contract that can't be
 * unit-tested directly (no Compose UI harness in this project): the Prepare
 * `PrintSetupSection` filament list must show the file's declared material via
 * the resolver-backed `filamentMaterials` list, not just the user's slot preset.
 *
 * The resolution math itself (file-declared material wins for a declared,
 * injective, multi-colour filament) is unit-tested in
 * [com.u1.slicer.data.PerFilamentResolverTest]; the ViewModel exposes it via
 * `displayedFilamentMaterials` (a thin `combine` over the same resolver). This
 * test only guards the two Compose wiring points that a unit test can't reach.
 */
class PrintSetupSectionMaterialTest {

    private fun mainActivitySource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/MainActivity.kt"),
            File("../app/src/main/java/com/u1/slicer/MainActivity.kt"),
            File("src/main/java/com/u1/slicer/MainActivity.kt"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("MainActivity.kt not found from ${File(".").absolutePath}")
        return f.readText()
    }

    @Test
    fun printSetupSection_declaresFilamentMaterialsParam() {
        val src = mainActivitySource()
        assertTrue(
            "B128: PrintSetupSection must accept a `filamentMaterials` param " +
                "(resolved per-filament material+temp) so the chip strip can " +
                "show the file's declared materials.",
            src.contains("filamentMaterials: List<Pair<String, Int>>"),
        )
    }

    @Test
    fun printSetupSection_callSitePassesDisplayedFilamentMaterials() {
        val src = mainActivitySource()
        assertTrue(
            "B128: the PrintSetupSection call site must feed " +
                "viewModel.displayedFilamentMaterials so display == slice.",
            src.contains("viewModel.displayedFilamentMaterials") &&
                src.contains("filamentMaterials = filamentMaterials"),
        )
    }

    @Test
    fun printSetupSection_rowPrefersResolvedMaterialOverSlotPreset() {
        val src = mainActivitySource()
        // The per-row material must read the resolved value first; the
        // slot-preset (`suggestedPreset`) is only the non-canonical fallback.
        assertTrue(
            "B128: the filament row must resolve material as " +
                "`resolved?.first ?: ... suggestedPreset?.materialType` so the " +
                "file's declared material is authoritative when present.",
            src.contains("val resolved = filamentMaterials.getOrNull(colorIdx)") &&
                src.contains("resolved?.first"),
        )
    }
}
