package com.u1.slicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Smart Paint camera-reset regression guard.
 *
 * Regression history: commit fd59a72 (B114/B115/B116 Smart Paint fix) changed the
 * `mesh` remember key in [AiPaintViewer] from `remember(recenteredPositions)` to
 * `remember(recenteredPositions, displayRegions)`. Because `displayRegions` is derived
 * from `state.triangleRegions` — which gets a fresh ByteArray on EVERY paint stroke and
 * every region colour change — the `mesh` value was rebuilt on every paint. That rebuild
 * fired `LaunchedEffect(mesh, viewerView)`, which calls `v.applyCameraState(fitCamera)`,
 * snapping the 3D viewer back to the default fit orientation each time the user painted
 * or picked a colour.
 *
 * The contract: the VBO mesh is built ONCE per model/pipeline load (keyed on geometry,
 * i.e. `recenteredPositions`). Per-triangle colour changes flow through the SEPARATE
 * recolor `LaunchedEffect` (keyed on `displayRegions`) via `updateExtruderIndices` +
 * `recolorMesh`, neither of which touches the camera. `displayRegions` may still be READ
 * inside the mesh-build lambda (B115 needs it for the full-mesh array size) — it just must
 * not be a remember KEY.
 *
 * This is a Compose-only contract with no UI test harness in the project, so it is guarded
 * by a source grep — same pattern as [InlineModelPreviewRotationKeysTest].
 */
class AiPaintViewerCameraResetTest {

    private fun viewerSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/ui/AiPaintViewer.kt"),
            File("../app/src/main/java/com/u1/slicer/ui/AiPaintViewer.kt"),
            File("src/main/java/com/u1/slicer/ui/AiPaintViewer.kt"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("AiPaintViewer.kt not found from ${File(".").absolutePath}")
        return f.readText()
    }

    /** Returns the key list between `remember(` and `) {` for the `val mesh = remember(...)` declaration. */
    private fun meshRememberKeys(): String {
        val src = viewerSource()
        val anchor = "val mesh = remember("
        val at = src.indexOf(anchor)
        require(at >= 0) {
            "`val mesh = remember(` not found — the mesh declaration was renamed or " +
                "removed. Find the remember that builds the AiPaintMeshBuilder mesh and " +
                "update this test."
        }
        val argsStart = at + anchor.length
        val argsEnd = src.indexOf(") {", argsStart)
        require(argsEnd >= 0) { "could not find end of mesh remember key list" }
        return src.substring(argsStart, argsEnd)
    }

    @Test
    fun meshRemember_notKeyedOnPerPaintRegions() {
        val keys = meshRememberKeys()
        assertFalse(
            "Smart Paint camera-reset regression: the `mesh` remember must NOT key on " +
                "`displayRegions`. `displayRegions` changes on every paint stroke and " +
                "colour change, so keying on it rebuilds the VBO and re-fires the " +
                "LaunchedEffect(mesh) that calls applyCameraState(fitCamera) — snapping " +
                "the viewer back to the default orientation on every paint. Colour " +
                "changes belong on the recolor LaunchedEffect (updateExtruderIndices), " +
                "not on the mesh rebuild. Got keys: $keys",
            keys.contains("displayRegions"),
        )
        assertFalse(
            "Smart Paint camera-reset regression: the `mesh` remember must NOT key on " +
                "`triangleRegions` for the same reason as `displayRegions`. Got keys: $keys",
            keys.contains("triangleRegions"),
        )
    }

    @Test
    fun meshRemember_keyedOnGeometry() {
        val keys = meshRememberKeys()
        assertTrue(
            "The `mesh` remember must key on `recenteredPositions` so the VBO rebuilds " +
                "exactly when the model geometry changes (a new pipeline load) and not on " +
                "paint. Got keys: $keys",
            keys.contains("recenteredPositions"),
        )
    }

    /**
     * Complementary guard against the *adjacent* foot-gun. The camera can only ever be
     * disturbed via `setMesh` (rebuild → fitCamera re-apply) or `applyCameraState`. The
     * per-paint LaunchedEffect — the one keyed on `displayRegions`, which fires on every
     * paint stroke and colour change — must stay camera-safe: it may only call
     * `updateExtruderIndices` / `recolorMesh`. If a future change drops a `setMesh` or
     * `applyCameraState` into that effect, the view-reset regression returns through a
     * different door than the mesh remember key, so we pin it here too.
     */
    @Test
    fun perPaintRecolorEffect_neverRebuildsMeshOrMovesCamera() {
        val src = viewerSource()
        // Locate the LaunchedEffect whose key list contains `displayRegions` — that is the
        // per-paint recolor effect (the mesh effect is keyed on `mesh, viewerView`).
        var searchFrom = 0
        val bodyStart: Int
        while (true) {
            val le = src.indexOf("LaunchedEffect(", searchFrom)
            require(le >= 0) {
                "no LaunchedEffect keyed on `displayRegions` found — the recolor effect " +
                    "was renamed or its keys changed. Update this test."
            }
            val keyEnd = src.indexOf(") {", le)
            require(keyEnd >= 0) { "malformed LaunchedEffect arg list near offset $le" }
            val keyList = src.substring(le + "LaunchedEffect(".length, keyEnd)
            if (keyList.contains("displayRegions")) {
                bodyStart = keyEnd + 1 // the `{`
                break
            }
            searchFrom = le + 1
        }
        // Brace-match the effect body.
        var depth = 0
        var i = bodyStart
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        require(i < src.length) { "per-paint LaunchedEffect body not terminated" }
        val body = src.substring(bodyStart, i + 1)
        assertFalse(
            "Smart Paint camera-reset regression (adjacent path): the per-paint recolor " +
                "LaunchedEffect (keyed on `displayRegions`) must NOT call `setMesh` — that " +
                "rebuilds the VBO on every paint and re-applies fitCamera, resetting the " +
                "view. Use `updateExtruderIndices` to mutate colours in place. Got body: $body",
            body.contains("setMesh"),
        )
        assertFalse(
            "Smart Paint camera-reset regression (adjacent path): the per-paint recolor " +
                "LaunchedEffect (keyed on `displayRegions`) must NOT call `applyCameraState` " +
                "— that moves the camera on every paint/colour change. Got body: $body",
            body.contains("applyCameraState"),
        )
    }
}
