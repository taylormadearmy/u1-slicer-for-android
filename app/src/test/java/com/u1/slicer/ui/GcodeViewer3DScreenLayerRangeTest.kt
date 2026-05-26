package com.u1.slicer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B129 structural guards for the Compose + ViewModel wiring that keeps the
 * G-code preview layer slider from resetting to the top when the user leaves to
 * move/rotate the model. The pure clamp logic is in [GcodeLayerRangeTest]; this
 * file guards the contracts a unit test can't reach:
 *
 *  1. The screen exposes `initialLayerRange` / `onLayerRangeChange`, persists
 *     slider moves, and re-applies the range after `uploadGcode` (which resets
 *     the renderer to the full print).
 *  2. NavGraph wires the ViewModel's `previewLayerRange` in and persists out.
 *  3. The ViewModel resets the remembered range ONLY through
 *     `setParsedGcodeWithRangeReset` — so every new/cleared parsed-G-code path
 *     resets to the top, while plain navigation (which never reassigns
 *     `_parsedGcode`) preserves the position.
 */
class GcodeViewer3DScreenLayerRangeTest {

    private fun source(relative: String): String {
        val f = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")
        return f.readText()
    }

    private val screen get() = source("app/src/main/java/com/u1/slicer/ui/GcodeViewer3DScreen.kt")
    private val navGraph get() = source("app/src/main/java/com/u1/slicer/navigation/NavGraph.kt")
    private val viewModel get() = source("app/src/main/java/com/u1/slicer/SlicerViewModel.kt")

    @Test
    fun screen_exposesLayerRangeParams() {
        val src = screen
        assertTrue(
            "GcodeViewer3DScreen must accept `initialLayerRange` + `onLayerRangeChange`.",
            src.contains("initialLayerRange: Pair<Int, Int>?") &&
                src.contains("onLayerRangeChange: (Int, Int) -> Unit"),
        )
    }

    @Test
    fun screen_sliderPersistsRange() {
        assertTrue(
            "The RangeSlider onValueChange must persist via onLayerRangeChange.",
            screen.contains("onLayerRangeChange(minLayer, maxLayer)"),
        )
    }

    @Test
    fun screen_reAppliesRangeAfterUpload() {
        val src = screen
        // uploadGcode resets the renderer window; the effect must re-apply the
        // remembered range afterwards (call appears after uploadGcode).
        val uploadAt = src.indexOf("uploadGcode(")
        val reapplyAt = src.indexOf("v.setLayerRange(minLayer, maxLayer)")
        assertTrue("uploadGcode call not found", uploadAt >= 0)
        assertTrue(
            "B129: the screen must re-apply v.setLayerRange(...) after uploadGcode().",
            reapplyAt > uploadAt,
        )
    }

    @Test
    fun navGraph_wiresPreviewLayerRangeBothWays() {
        val src = navGraph
        assertTrue(
            "NavGraph must feed previewLayerRange in and persist via setPreviewLayerRange.",
            src.contains("initialLayerRange = previewLayerRange") &&
                src.contains("viewModel.setPreviewLayerRange("),
        )
    }

    @Test
    fun viewModel_allParsedGcodeAssignmentsResetRange() {
        val src = viewModel
        // The only direct `_parsedGcode.value =` assignment must be inside
        // setParsedGcodeWithRangeReset. Every other producer routes through it,
        // guaranteeing a new/cleared preview resets the slider to the top.
        val directAssignments = Regex("_parsedGcode\\.value\\s*=").findAll(src).count()
        assertEquals(
            "B129: `_parsedGcode.value =` must appear exactly once (inside " +
                "setParsedGcodeWithRangeReset). Found $directAssignments — a new " +
                "assignment site bypasses the layer-range reset.",
            1, directAssignments,
        )
        assertTrue(
            "setParsedGcodeWithRangeReset must reset _previewLayerRange to null.",
            src.contains("private fun setParsedGcodeWithRangeReset") &&
                Regex("setParsedGcodeWithRangeReset[\\s\\S]{0,160}_previewLayerRange\\.value = null")
                    .containsMatchIn(src),
        )
    }
}
