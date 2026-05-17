package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B109 follow-ups (DC15 reopens 2026-05-17): the rotated-mesh-AABB math itself
 * lives in [com.u1.slicer.model.CopyArrangeCalculator.effectivePlacementFootprint]
 * and is covered by direct unit tests there. This file is the **one** remaining
 * structural-grep guard for the Compose-only contract that can't be unit-tested:
 *
 *   The placement `LaunchedEffect` in `InlineModelPreview` must include the
 *   rotated-footprint values (`effPlaceSizeX`, `effPlaceSizeY`) in its key
 *   list. Without them the lambda body keeps closing over the pre-rotation
 *   footprint and the drag stays clamped to the load-time AABB until some
 *   unrelated key changes (the v2.2.4→v2.2.5 reopen).
 *
 * Earlier versions of this file also grepped for the helper call shape. That
 * concern is now redundant — the helper is invoked by a single, unit-tested
 * path, and changing the call site without updating the helper would surface
 * as a compile error or a math-test failure rather than a silent regression.
 */
class InlineModelPreviewRotationKeysTest {

    private fun mainActivitySource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/MainActivity.kt"),
            File("../app/src/main/java/com/u1/slicer/MainActivity.kt"),
            File("src/main/java/com/u1/slicer/MainActivity.kt")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("MainActivity.kt not found from ${File(".").absolutePath}")
        return f.readText()
    }

    private fun placementLaunchedEffectKeys(): String {
        val src = mainActivitySource()
        val anchor = "Update renderer with placement data"
        val anchorAt = src.indexOf(anchor)
        require(anchorAt >= 0) {
            "anchor comment not found — the placement LaunchedEffect has been " +
                "renamed or removed. Find the LaunchedEffect that sets " +
                "v.onObjectMoved and update this test."
        }
        val open = src.indexOf("LaunchedEffect(", anchorAt)
        require(open >= 0) { "LaunchedEffect( not found after anchor" }
        val argsStart = open + "LaunchedEffect(".length
        val argsEnd = src.indexOf(") {", argsStart)
        require(argsEnd >= 0) { "could not find end of LaunchedEffect arg list" }
        return src.substring(argsStart, argsEnd)
    }

    @Test
    fun placementLaunchedEffect_keysOnRotatedFootprint() {
        val keys = placementLaunchedEffectKeys()
        assertTrue(
            "B109: the placement LaunchedEffect must list `effPlaceSizeX` as a key " +
                "so the drag callback re-captures the rotated footprint when the " +
                "user rotates. Without it the lambda closes over pre-rotation " +
                "bounds. Got keys: $keys",
            keys.contains("effPlaceSizeX")
        )
        assertTrue(
            "B109: the placement LaunchedEffect must list `effPlaceSizeY` as a key " +
                "alongside `effPlaceSizeX` so both axes recompute on rotation. " +
                "Got keys: $keys",
            keys.contains("effPlaceSizeY")
        )
    }

    /**
     * B109 v2.2.6 review #2 contract: the `onMeshCached` lambda wired into
     * `InlineModelPreview` must call `viewModel.setRotatedMeshSize(...)`
     * with the live mesh bounds. Without it the ViewModel's
     * `_rotatedMeshSizeXY` StateFlow stays null forever, `getPlacementPositions`
     * and `setCopyCount` silently regress to the box-rotation approximation,
     * and Dragon-Scale-class auto-center drift returns.
     */
    @Test
    fun onMeshCached_callsSetRotatedMeshSize() {
        val src = mainActivitySource()
        val anchor = "onMeshCached = "
        val at = src.indexOf(anchor)
        require(at >= 0) { "onMeshCached wiring not found in MainActivity.kt" }
        // Walk to the lambda's closing brace.
        val open = src.indexOf('{', at)
        require(open >= 0) { "onMeshCached lambda opening brace not found" }
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        require(i < src.length) { "onMeshCached lambda body not terminated" }
        val lambdaBody = src.substring(open, i + 1)
        assertTrue(
            "B109 v2.2.6: the `onMeshCached` lambda must call " +
                "`viewModel.setRotatedMeshSize(...)` with the fresh mesh bounds, " +
                "or `_rotatedMeshSizeXY` never repopulates after a rotation and " +
                "auto-center silently regresses to the box-rotation approximation. " +
                "Got: $lambdaBody",
            lambdaBody.contains("setRotatedMeshSize")
        )
    }

    @Test
    fun placementLaunchedEffect_keysOnWipeTowerDimensions() {
        // B109 review #4: the wipe-tower drag clamp inside the same lambda
        // closes over wipeTowerWidth/wipeTowerDepth. If the user changes Prime
        // Tower Width or Depth in settings mid-session, the callback must
        // re-capture or the wipe tower clamps to the stale value.
        val keys = placementLaunchedEffectKeys()
        assertTrue(
            "B109 review #4: placement LaunchedEffect must list `wipeTowerWidth` " +
                "as a key so the wipe-tower drag clamp recomposes when the user " +
                "changes Prime Tower Width. Got keys: $keys",
            keys.contains("wipeTowerWidth")
        )
        assertTrue(
            "B109 review #4: placement LaunchedEffect must list `wipeTowerDepth` " +
                "as a key so the wipe-tower drag clamp recomposes when the user " +
                "changes Prime Tower Depth. Got keys: $keys",
            keys.contains("wipeTowerDepth")
        )
    }
}
