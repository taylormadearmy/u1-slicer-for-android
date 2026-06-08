package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards that all three RESTORED Smart Paint selectors expose every active mix + a create-mix
 * "+", that the per-row selector is bounded so it can't squeeze the region title, and that
 * [MixedSlotSwatch] renders the blend RATIO (proportional split) rather than a fixed accent
 * corner. Source-grep guards — the project has no Compose UI test harness (see
 * ModelInfoDialogScrollTest / AiPaintViewerCameraResetTest for the same pattern).
 */
class MixSelectorAugmentationTest {
    private fun read(p: String) = listOf(
        "app/src/main/java/com/u1/slicer/ui/$p",
        "src/main/java/com/u1/slicer/ui/$p",
        "../app/src/main/java/com/u1/slicer/ui/$p",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText() ?: error("$p not found")

    private val viewer = read("AiPaintViewer.kt")        // SlotPaletteRow ("Extruders →")
    private val overlay = read("HighlightSlotPicker.kt")  // model-tap "Move to slot"
    private val row = read("AiPaintTreeRow.kt")           // per-region/part rows
    private val swatch = read("MixedSlotSwatch.kt")
    private val dialog = read("CreateMixSlotDialog.kt")

    @Test fun extrudersRowRendersMixesAndAddButton() {
        assertTrue("SlotPaletteRow must iterate active mixes", viewer.contains("mixes.forEachIndexed"))
        assertTrue("SlotPaletteRow must render mix swatches", viewer.contains("MixedSlotSwatch("))
        assertTrue("SlotPaletteRow must offer a create-mix '+'", viewer.contains("onCreateMix()"))
    }

    @Test fun moveToSlotOverlayRendersMixesAndAddButton() {
        assertTrue("HighlightSlotPicker must iterate mixes", overlay.contains("mixes.forEachIndexed"))
        assertTrue("HighlightSlotPicker must render mix swatches", overlay.contains("MixedSlotSwatch("))
        assertTrue("HighlightSlotPicker must offer a create-mix '+'", overlay.contains("onCreateMix()"))
    }

    @Test fun perRegionRowRendersAllMixesAndAddButton() {
        // de6d5c1: the per-row selector must show ALL active mixes (not just the region's
        // current one), so a mix created from any selector appears here too.
        assertTrue("AiPaintTreeRow must iterate ALL active mixes", row.contains("activeMixes.forEachIndexed"))
        assertTrue("AiPaintTreeRow must offer a create-mix '+'", row.contains("onCreateMix()"))
    }

    @Test fun perRegionSelectorBoundedToAvoidSqueezingTitle() {
        // 090ea7a/de6d5c1: weight(1f) + horizontalScroll on the trailing chip row prevents the
        // zero-width-title regression even with many mixes.
        assertTrue(
            "the per-row chip selector must be weight-bounded + scrollable",
            row.contains("Modifier.weight(1f).horizontalScroll"),
        )
    }

    @Test fun mixSwatchRendersProportionalBlendRatio() {
        // 5ef44b2: an actual mix passes secondaryFraction and the swatch splits proportionally.
        assertTrue("MixedSlotSwatch must accept secondaryFraction", swatch.contains("secondaryFraction"))
        assertTrue(
            "MixedSlotSwatch must draw the secondary over the right `fraction` of the width",
            swatch.contains("topLeft = Offset(w * (1f - f)"),
        )
    }

    @Test fun createMixDialogPreviewIsProportionalNSegmentBar() {
        // Task 9 replaced the old single ratio-slider + secondaryFraction preview with a
        // draggable N-segment MixWeightBar.  The dialog must render the bar and size each
        // segment proportionally via mixSegmentOffsets / rebalanceAfterDrag.
        assertTrue(
            "CreateMixSlotDialog must render the proportional N-segment weight bar",
            dialog.contains("MixWeightBar("),
        )
        assertTrue(
            "the weight bar must size segments proportionally via mixSegmentOffsets or rebalanceAfterDrag",
            dialog.contains("mixSegmentOffsets") || dialog.contains("rebalanceAfterDrag"),
        )
    }

    @Test fun smartPaint_mixChip_longPress_opensEditor() {
        assertTrue("AiPaintTreeRow mix chip must wire long-press to onEditMix",
            row.contains("onLongClick") && row.contains("onEditMix"))
    }

    @Test fun mixSlotId_basedOnMaxOfPhysicalAndCanonical_noCollision() {
        val base = maxOf(4, 6)   // 6 canonical filaments, 4 physical
        org.junit.Assert.assertEquals(6, com.u1.slicer.ui.FilamentMixChipRow.mixSlotId(0, base))
        org.junit.Assert.assertEquals(7, com.u1.slicer.ui.FilamentMixChipRow.mixSlotId(1, base))
    }
}
