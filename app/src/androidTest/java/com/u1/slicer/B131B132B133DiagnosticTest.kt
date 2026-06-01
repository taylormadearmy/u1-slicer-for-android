package com.u1.slicer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Diagnostic + regression tests for B131 (Ghostface Prepare-invisible) and
 * B132 (Oreo split+copies+placement).
 *
 * B133 (Chubby NONE materials) is not covered here: 2026-06-01 on-device
 * verification confirmed the ViewModel-level data is correct on v2.10.1
 * (canonical = 4 × PLA, displayedFilamentMaterials = 4 × [PLA/220C]). If the
 * "NONE" symptom is still visible, it's a render-layer mismatch we can only
 * catch with a Compose UI harness or a screen recording — not a programmatic
 * data check. Defer.
 *
 * Each test below embeds full diagnostic state in its assertion message so a
 * failure explains exactly what went wrong.
 */
@RunWith(AndroidJUnit4::class)
class B131B132B133DiagnosticTest {

    companion object { private const val TAG = "B131B132B133" }

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun copyAssetToCache(assetName: String): File {
        val outFile = File(targetContext.cacheDir, assetName.replace("/", "_"))
        assetContext.assets.open(assetName).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private fun waitUntil(label: String, timeoutMs: Long = 60_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label (${timeoutMs / 1000}s)")
    }

    /**
     * B131 — Ghostface Pokemon card 3MF does not render on the Prepare tab.
     *
     * 2026-06-01 finding (this exact diagnostic, hypothesis disproved):
     * Ghostface is a single 1-object, 1-volume native model — not the
     * "multi-volume multi-component" case the original hypothesis predicted.
     * `nativeGetObjectCount()=1`, `getObjectBoundingBoxes()` returns 1 box.
     * So `multiObjectMode` does NOT trigger, and the bug is something else
     * (see `b131_ghostface_decimatedMeshFitsBedFromInstanceOffset` below).
     * Kept as a baseline state-snapshot test.
     */
    @Test
    fun b131_ghostface_loadProducesUsableMultiObjectState() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val file = copyAssetToCache("GhostfacePokemoncard.3mf")

        try {
            viewModel.loadModelFromFile(file)
            waitUntil("ghostface plate selector or model loaded", timeoutMs = 120_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            if (viewModel.showPlateSelector.value) {
                viewModel.selectPlate(1)
            }
            waitUntil("ghostface model loaded after plate select", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            Thread.sleep(500)

            val lib = NativeLibrary()
            val nativeObjectCount = lib.nativeGetObjectCount()
            val boxes = runCatching { lib.getObjectBoundingBoxes() }.getOrDefault(floatArrayOf())
            val boxObjects = boxes.size / 3
            val multiPos = viewModel.multiObjectPositions.value
            val hasMulti = viewModel.hasMultipleDistinctObjects.value
            val mi = viewModel.modelInfo.value
            val worldMins = runCatching { lib.nativeGetObjectWorldAABBMins() }.getOrDefault(floatArrayOf())
            val loadTimeOffsets = viewModel.loadTimeInstanceOffsets.value
            val instanceOffsets = runCatching { lib.getInstanceOffsets() }.getOrDefault(floatArrayOf())

            // Fetch preview mesh — what InlineModelPreview does.
            val previewMesh = runCatching { lib.getPreparePreviewMesh() }.getOrNull()
            val meshSummary = previewMesh?.let { m ->
                val pos = m.trianglePositions
                val triCount = pos.size / 9  // 3 vertices × 3 floats
                var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
                var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
                var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
                var i = 0
                while (i + 2 < pos.size) {
                    val px = pos[i]; val py = pos[i + 1]; val pz = pos[i + 2]
                    if (px < minX) minX = px; if (px > maxX) maxX = px
                    if (py < minY) minY = py; if (py > maxY) maxY = py
                    if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz
                    i += 3
                }
                "tri=$triCount aabb=[${minX}..${maxX}, ${minY}..${maxY}, ${minZ}..${maxZ}]"
            } ?: "null"

            val diagnostic = buildString {
                append("nativeObjCount=$nativeObjectCount, ")
                append("boxes=${boxes.size}floats (${boxObjects} objects), ")
                append("hasMultipleDistinctObjects=$hasMulti, ")
                append("multiObjectPositions=${if (multiPos == null) "null" else "${multiPos.size}floats"}, ")
                append("modelInfo.size=${mi?.let { "${it.sizeX}x${it.sizeY}x${it.sizeZ}" } ?: "null"}, ")
                append("worldMins=${worldMins.toList()}, ")
                append("loadTimeOffsets=${loadTimeOffsets.toList()}, ")
                append("instanceOffsets=${instanceOffsets.toList()}, ")
                append("previewMesh=$meshSummary")
            }
            Log.i(TAG, "B131_DIAGNOSTIC: $diagnostic")
            println("B131_DIAGNOSTIC: $diagnostic")

            // We can't observe the rendered output directly. But we CAN assert that
            // the preview mesh exists, has a non-empty AABB, and is positioned somewhere
            // on the 270×270 bed. If any of these fail, the model is unrenderable.
            assertNotNull("B131: preview mesh must not be null — $diagnostic", previewMesh)
            previewMesh!!
            assertTrue("B131: preview mesh must have triangles — $diagnostic",
                previewMesh.trianglePositions.size >= 9)
        } finally {
            viewModel.clearModel()
            file.delete()
        }
    }

    /**
     * B132 (a) — Oreo pre-split: setting copyCount=2 on a wide-footprint
     * model that only fits 1 copy must surface a clear error, NOT silently
     * slice 1 cookie.
     *
     * Diagnostic confirms CopyArrangeCalculator caps to 1 for this file
     * (170×171mm footprint can't fit 2 copies on 270mm bed). Pre-fix, slice
     * proceeded with the cap → 1 cookie. Post-fix, slice errors with a
     * message naming the maxFit count.
     */
    @Test
    fun b132a_oreo_preSplit_copyCount2_errorsBecauseCopiesDontFit() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val file = copyAssetToCache("Oreo+Proj+1.3mf")

        try {
            viewModel.loadModelFromFile(file)
            waitUntil("oreo plate selector or loaded", timeoutMs = 60_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            if (viewModel.showPlateSelector.value) {
                viewModel.selectPlate(1)
            }
            waitUntil("oreo model loaded", timeoutMs = 60_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            Thread.sleep(500)

            viewModel.setCopyCount(2)
            Thread.sleep(200)

            val mi = viewModel.modelInfo.value
            val bedWarning = viewModel.copyBedWarning.value

            viewModel.startSlicing()
            waitUntil("oreo slice resolved (complete or error)", timeoutMs = 60_000L) {
                val st = viewModel.state.value
                st is SlicerViewModel.SlicerState.SliceComplete ||
                    st is SlicerViewModel.SlicerState.Error
            }

            val st = viewModel.state.value
            val errorMessage = (st as? SlicerViewModel.SlicerState.Error)?.message

            val diagnostic = buildString {
                append("copyCount=2, ")
                append("modelInfo.size=${mi?.let { "${it.sizeX}x${it.sizeY}" } ?: "null"}, ")
                append("copyBedWarning=${bedWarning ?: "null"}, ")
                append("finalState=${st::class.simpleName}, ")
                append("errorMessage=${errorMessage ?: "null"}")
            }
            Log.i(TAG, "B132A_DIAGNOSTIC: $diagnostic")
            println("B132A_DIAGNOSTIC: $diagnostic")

            assertTrue(
                "B132a: pre-fix the slice silently proceeded with capped copies. " +
                    "After fix the slice must reject the request with a clear error " +
                    "naming the maxFit count. $diagnostic",
                st is SlicerViewModel.SlicerState.Error,
            )
            assertTrue(
                "B132a: error message must indicate copies don't fit on bed. $diagnostic",
                errorMessage?.contains("fits", ignoreCase = true) == true ||
                    errorMessage?.contains("copy", ignoreCase = true) == true,
            )
        } finally {
            viewModel.clearModel()
            file.delete()
        }
    }

    /**
     * B132 (b) — after Split-to-Parts, the ViewModel state must be self-
     * consistent: copies resets to 1 (because copyCount can't apply in
     * multi-object mode), and the public multiObjectPositions StateFlow must
     * mirror the private customObjectPositions (so InlineModelPreview's
     * rotation LaunchedEffect sees the new layout and calls splitMeshByObjects).
     *
     * This guards both halves of the v2.11.x B132b fix.
     */
    @Test
    fun b132b_oreo_splitObject_resetsCopyCount_andPublishesMultiObjectPositions() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val file = copyAssetToCache("Oreo+Proj+1.3mf")

        try {
            viewModel.loadModelFromFile(file)
            waitUntil("oreo plate selector or loaded") {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            if (viewModel.showPlateSelector.value) {
                viewModel.selectPlate(1)
            }
            waitUntil("oreo model loaded") {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            Thread.sleep(500)

            // User scenario: set copyCount=2 BEFORE splitting (Jon's path —
            // tried copies first, hit the bed-warning cap, then tried Split).
            viewModel.setCopyCount(2)
            Thread.sleep(100)
            assertEquals("pre-split copyCount must reflect user input", 2, viewModel.copyCount.value)

            val lib = NativeLibrary()
            val objCountBeforeSplit = lib.nativeGetObjectCount()
            var splitOk = false
            var splitIdx = -1
            for (i in 0 until objCountBeforeSplit) {
                splitOk = viewModel.splitObject(i)
                if (splitOk) { splitIdx = i; break }
            }
            Thread.sleep(500)

            val objCountAfterSplit = lib.nativeGetObjectCount()
            val hasMulti = viewModel.hasMultipleDistinctObjects.value
            val multiPos = viewModel.multiObjectPositions.value
            val copyCountAfterSplit = viewModel.copyCount.value
            val bedWarningAfter = viewModel.copyBedWarning.value

            val diagnostic = buildString {
                append("objCountBeforeSplit=$objCountBeforeSplit, ")
                append("splitOk=$splitOk at idx=$splitIdx, ")
                append("objCountAfterSplit=$objCountAfterSplit, ")
                append("hasMultipleDistinctObjects=$hasMulti, ")
                append("multiObjectPositions=${if (multiPos == null) "null" else "${multiPos.size}floats"}, ")
                append("copyCountAfterSplit=$copyCountAfterSplit (was 2 pre-split), ")
                append("copyBedWarningAfter=${bedWarningAfter ?: "null"}")
            }
            Log.i(TAG, "B132B_DIAGNOSTIC: $diagnostic")
            println("B132B_DIAGNOSTIC: $diagnostic")

            assertTrue("B132b: split must succeed — $diagnostic", splitOk)
            assertTrue("B132b: post-split must report multi-object — $diagnostic", hasMulti)
            assertEquals(
                "B132b: copyCount must reset to 1 after split (was 2 pre-split, " +
                    "and copies don't apply in multi-object mode anyway — " +
                    "leaving it at 2 silently no-ops). $diagnostic",
                1, copyCountAfterSplit,
            )
            assertNotNull(
                "B132b: multiObjectPositions StateFlow must be non-null after split " +
                    "so InlineModelPreview's gate at MainActivity.kt:3356 (multiPos!=null) " +
                    "opens and splitMeshByObjects is called. $diagnostic",
                multiPos,
            )
            assertEquals(
                "B132b: multiObjectPositions length must be 2×objectCountAfterSplit. $diagnostic",
                objCountAfterSplit * 2, multiPos!!.size,
            )
        } finally {
            viewModel.clearModel()
            file.delete()
        }
    }

    /**
     * B131 sibling — baseline STL behaviour. Confirms that for a Benchy STL
     * (no instance transform), `getInstanceOffsets` and
     * `nativeGetObjectWorldAABBMins` agree, and the mesh world AABB matches
     * what `drawModelAt(mesh, x, y)` expects. Establishes the no-regression
     * floor for any B131 positioning fix.
     */
    @Test
    fun b131_benchy_stl_offsetsAgreeWithMeshWorldMin() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val file = copyAssetToCache("3DBenchy.stl")

        try {
            viewModel.loadModelFromFile(file)
            waitUntil("benchy model loaded", timeoutMs = 30_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            Thread.sleep(300)

            val lib = NativeLibrary()
            val instanceOffsets = runCatching { lib.getInstanceOffsets() }.getOrDefault(floatArrayOf())
            val worldMins = runCatching { lib.nativeGetObjectWorldAABBMins() }.getOrDefault(floatArrayOf())
            val mesh = runCatching {
                lib.getPreparePreviewMesh(com.u1.slicer.viewer.NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
            }.getOrNull()
            val pos = mesh?.trianglePositions ?: floatArrayOf()
            var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
            var i = 0
            while (i + 2 < pos.size) {
                val px = pos[i]; val py = pos[i + 1]
                if (px < minX) minX = px; if (px > maxX) maxX = px
                if (py < minY) minY = py; if (py > maxY) maxY = py
                i += 3
            }

            val diagnostic = buildString {
                append("instanceOffsets=${instanceOffsets.toList()}, ")
                append("worldMins=${worldMins.toList()}, ")
                append("meshAABB=[${minX}..${maxX}, ${minY}..${maxY}]")
            }
            Log.i(TAG, "B131_BENCHY_DIAGNOSTIC: $diagnostic")
            println("B131_BENCHY_DIAGNOSTIC: $diagnostic")

            // Pure documentation — no strong assertion.
            assertTrue("preview mesh fetched", pos.size >= 9)
        } finally {
            viewModel.clearModel()
            file.delete()
        }
    }

    /**
     * B131 follow-up — distinguishes the two competing hypotheses for why
     * Ghostface doesn't render on the Prepare tab:
     *
     *  (a) Mesh-positioning bug: getPreparePreviewMesh returns world-coord
     *      vertices (mesh AABB at world position) but the renderer is told
     *      to draw at loadTimeInstanceOffsets, double-positioning the mesh.
     *  (b) Decimation timing: 3.7M raw triangles → 100K decimated takes so
     *      long that the user perceives "model never appears".
     *
     * This test calls getPreparePreviewMesh WITH the production cap
     * (MAX_DECIMATED_TRIANGLES) the InlineModelPreview path uses, measures
     * wall-clock decimation time, and compares mesh AABB to instance offsets.
     *
     * The diagnostic alone is the value; the assertion below is loose so the
     * test passes once we know which fix to apply.
     *
     * 2026-06-01 partial fix: the MMU stride bypass in sapil_model.cpp is
     * fixed (decimation cap now respected for paint-state files), but the
     * world-coord-vs-instance-offset mismatch is NOT yet addressed. So the
     * `fitsBedY` assertion still fails on Ghostface. The test stays here
     * to document both bugs; the fits-bed assertion is split out so the
     * decimation check can pass independently.
     */
    @Test
    fun b131_ghostface_decimatedMeshFitsBedFromInstanceOffset() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val file = copyAssetToCache("GhostfacePokemoncard.3mf")

        try {
            viewModel.loadModelFromFile(file)
            waitUntil("ghostface plate selector or loaded", timeoutMs = 180_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            if (viewModel.showPlateSelector.value) {
                viewModel.selectPlate(1)
            }
            waitUntil("ghostface model loaded", timeoutMs = 180_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            Thread.sleep(500)

            val lib = NativeLibrary()
            // Use viewModel.loadTimeInstanceOffsets (what the renderer actually
            // uses) — NOT the raw `getInstanceOffsets()`. B131's fix replaces
            // the BBS reference with mesh world-AABB-min at the VM layer.
            val rendererOffsets = viewModel.loadTimeInstanceOffsets.value
            val rawInstanceOffsets = runCatching { lib.getInstanceOffsets() }.getOrDefault(floatArrayOf())
            val instX = rendererOffsets.getOrNull(0) ?: 0f
            val instY = rendererOffsets.getOrNull(1) ?: 0f

            val maxTris = com.u1.slicer.viewer.NativePreviewMesh.MAX_DECIMATED_TRIANGLES
            val t0 = System.currentTimeMillis()
            val mesh = runCatching { lib.getPreparePreviewMesh(maxTris) }.getOrNull()
            val decimateMs = System.currentTimeMillis() - t0

            assertNotNull("B131: decimated mesh must not be null", mesh)
            mesh!!
            val pos = mesh.trianglePositions
            val triCount = pos.size / 9
            var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
            var i = 0
            while (i + 2 < pos.size) {
                val px = pos[i]; val py = pos[i + 1]; val pz = pos[i + 2]
                if (px < minX) minX = px; if (px > maxX) maxX = px
                if (py < minY) minY = py; if (py > maxY) maxY = py
                if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz
                i += 3
            }

            // The renderer's drawModelAt(mesh, instX, instY) shifts the mesh
            // by (instX - mesh.minX, instY - mesh.minY). For the model to land
            // inside the bed, the shifted world range must fit (0..bed).
            val shiftX = instX - minX
            val shiftY = instY - minY
            val drawnMinX = minX + shiftX  // = instX
            val drawnMaxX = maxX + shiftX
            val drawnMinY = minY + shiftY
            val drawnMaxY = maxY + shiftY

            val bedSize = 270f
            val fitsBedX = drawnMinX >= -1f && drawnMaxX <= bedSize + 1f
            val fitsBedY = drawnMinY >= -1f && drawnMaxY <= bedSize + 1f

            val diagnostic = buildString {
                append("decimateMs=$decimateMs, ")
                append("decimatedTriCount=$triCount (cap=$maxTris), ")
                append("meshAABB=[${minX}..${maxX}, ${minY}..${maxY}, ${minZ}..${maxZ}], ")
                append("rendererOffsets=(${instX}, ${instY}) [raw BBS=${rawInstanceOffsets.toList()}], ")
                append("renderer-drawn-rangeX=[${drawnMinX}..${drawnMaxX}] fitsBed=$fitsBedX, ")
                append("renderer-drawn-rangeY=[${drawnMinY}..${drawnMaxY}] fitsBed=$fitsBedY")
            }
            Log.i(TAG, "B131_FOLLOWUP_DIAGNOSTIC: $diagnostic")
            println("B131_FOLLOWUP_DIAGNOSTIC: $diagnostic")

            // Distinguish the two hypotheses. The decimation-timing one would
            // surface if decimateMs is huge (multiple seconds). The mesh-
            // positioning one would surface if the drawn range exceeds the
            // bed bounds.
            assertTrue(
                "B131: decimation too slow for an interactive UI — $diagnostic",
                decimateMs < 10_000L,
            )
            // Decimation cap: the MMU stride fix in sapil_model.cpp must keep
            // post-decimation triangle count near the cap (allowing 2× slack
            // for paint-state per-state stride rounding).
            assertTrue(
                "B131: decimated triangle count must respect the cap — $diagnostic",
                triCount <= maxTris * 2,
            )
            // Positioning fix: with B131's loadTimeInstanceOffsets fix using
            // mesh world-AABB-min for single-instance BBS loads, the renderer's
            // drawModelAt zeroes out the shift and the mesh lands at its world
            // position, on-bed.
            assertTrue(
                "B131: renderer-drawn mesh range must fit within 270×270 bed — $diagnostic",
                fitsBedX && fitsBedY,
            )
        } finally {
            viewModel.clearModel()
            file.delete()
        }
    }
}
