package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M4 regression guard: the per-object "Assign to filament" dialog
 * ([FilamentChooserDialog] in PartsPanel.kt) renders the active mix rows and the
 * "+ Add mix" affordance AFTER the physical-filament rows. On a model with many
 * canonical filaments (e.g. Button-for-S-trousers declares 15) those mix rows +
 * the add button get pushed below the dialog fold and read as "missing" unless the
 * content Column scrolls. This guards both:
 *
 *  - a [rememberScrollState] is created
 *  - the content [Column] applies [verticalScroll]
 *
 * Same dialog-clipping class as [ModelInfoDialogScrollTest]; structural source-grep
 * guard because the project has no Compose UI test harness.
 */
class FilamentChooserDialogScrollTest {

    private fun partsPanelSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/ui/PartsPanel.kt"),
            File("../app/src/main/java/com/u1/slicer/ui/PartsPanel.kt"),
            File("src/main/java/com/u1/slicer/ui/PartsPanel.kt"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("PartsPanel.kt not found from ${File(".").absolutePath}")
        return f.readText()
    }

    private fun filamentChooserDialogBody(): String {
        val src = partsPanelSource()
        val header = "fun FilamentChooserDialog("
        val start = src.indexOf(header)
        require(start >= 0) { "FilamentChooserDialog not found in PartsPanel.kt" }
        val bodyStart = src.indexOf('{', src.indexOf(')', start))
        require(bodyStart >= 0) { "Could not locate FilamentChooserDialog body brace" }
        var depth = 0
        var i = bodyStart
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(bodyStart, i + 1)
                }
            }
            i++
        }
        error("FilamentChooserDialog body not terminated")
    }

    @Test
    fun filamentChooserDialog_usesRememberScrollState() {
        assertTrue(
            "FilamentChooserDialog must create a ScrollState via rememberScrollState()",
            filamentChooserDialogBody().contains("rememberScrollState()"),
        )
    }

    @Test
    fun filamentChooserDialog_contentColumnAppliesVerticalScroll() {
        assertTrue(
            "FilamentChooserDialog content Column must apply .verticalScroll(...) so the mix " +
                "rows + '+ Add mix' (rendered after the physical filament rows) stay reachable " +
                "on models with many canonical filaments",
            filamentChooserDialogBody().contains(".verticalScroll("),
        )
    }
}
