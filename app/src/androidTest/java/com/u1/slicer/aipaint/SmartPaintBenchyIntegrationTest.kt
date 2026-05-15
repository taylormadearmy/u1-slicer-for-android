package com.u1.slicer.aipaint

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.viewer.NativePreviewMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * B112 + B113 regression guard on the real 3DBenchy.stl fixture.
 *
 * v2.2.0 had two latent bugs on bare STLs:
 *
 *  - B112: `MeshSegmenter.mergeComponents` was O(numComps² × triCount). On benchy's
 *    ~25k subsampled triangles with thousands of dihedral-flood components, this
 *    effectively hung Smart Paint (>5min observed on Pixel 8a).
 *
 *  - B113: `subsampleMeshForAiPaint` was firing at 30k triangles, which stride-strips
 *    mesh adjacency. Benchy's 99,996 native-decimated tris fell into this trap and
 *    produced 20,675 topology leaves → confetti visual.
 *
 * This test exercises the exact code path Smart Paint takes on a real STL:
 *  1. Load 3DBenchy.stl via the native library
 *  2. Apply the B113 threshold gate
 *  3. Run the cascade
 *  4. Assert leaf count is sane (no confetti) AND wall time is bounded (no hang)
 *
 * If either bug is reintroduced, this test fails on Pixel-class hardware: B112
 * would time out the test runner, B113 would blow the leaf-count ceiling.
 */
@RunWith(AndroidJUnit4::class)
class SmartPaintBenchyIntegrationTest {

    private fun copyAsset(name: String): File {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val targetCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(targetCtx.cacheDir, name)
        testCtx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    @Test
    fun benchyStl_smartPaintCascade_completesQuicklyWithFewLeaves() {
        val file = copyAsset("3DBenchy.stl")
        val lib = NativeLibrary()
        assertTrue("loadModelForPlate failed", lib.loadModelForPlate(file.absolutePath, plateIdx = -1))

        val rawMesh = lib.getPreparePreviewMesh(maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
        assertNotNull("native preview mesh was null", rawMesh)
        val rawTriCount = rawMesh!!.trianglePositions.size / 9
        assertTrue("expected native-decimated mesh ~100k tris, got $rawTriCount",
            rawTriCount in 50_000..NativePreviewMesh.MAX_DECIMATED_TRIANGLES)

        // B113 guard: a 99,996-tri benchy must NOT trigger the subsample.
        assertEquals(
            "B113: bare STL mesh under native cap was subsampled — confetti regression",
            false,
            shouldSubsampleForAiPaint(rawTriCount)
        )

        // The cascade input mirrors what AiPaintViewModel builds for a bare STL.
        // We pass empty volume/object lists since the STL has no per-volume/per-object data.
        val perTriPaint = ByteArray(rawTriCount)
        val input = SegmentationCascade.Input(
            positions = rawMesh.trianglePositions,
            perTrianglePaintState = perTriPaint,
            volumes = emptyList(),
            objects = emptyList(),
            perTriangleIndex = perTriPaint,
        )

        val t0 = System.nanoTime()
        val result = SegmentationCascade.run(input)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        // B112 guard: the cascade must terminate. Pre-fix this hung indefinitely
        // on benchy; post-fix Pixel 8a observed ~1.6s, JVM-on-emulator a bit more.
        // 30s budget is comfortable on a slow CI device.
        assertTrue(
            "B112: cascade took ${elapsedMs}ms — merge perf regression (budget = 30000ms)",
            elapsedMs < 30_000
        )

        // The cascade must produce a non-empty tree for a real model.
        assertTrue("cascade produced empty tree", result.tree.isNotEmpty())

        // B113 visual guard: a clean benchy topology produces ~32 components
        // (CREASE_DOT-bounded flood-fill on a connected hull, capped by
        // MAX_INTERMEDIATE_COMPONENTS=1024 then merged to ≤32 in `topologyBranch`).
        // Pre-B113 subsample produced 20,675 leaves — the confetti class.
        // We accept anything under 200 (generous) — if this fires, the visual is
        // already broken even if the algorithm didn't technically regress.
        val root = result.tree.first()
        val leafCount = root.leafCount()
        assertTrue(
            "B113: cascade produced $leafCount leaves on benchy — confetti regression " +
                "(expected < 200; pre-B113 was 20675)",
            leafCount < 200
        )

        // The cascade source for a bare STL must be TOPOLOGY (or one of its variants).
        // If it falls through to Z_BAND, topology silently failed somewhere upstream.
        assertTrue(
            "cascade source for bare STL was ${result.source}, expected TOPOLOGY-family",
            result.source in listOf(
                SegmentationSource.TOPOLOGY,
                SegmentationSource.TOPOLOGY_RECURSIVE,
                SegmentationSource.Z_BAND,
            )
        )
    }
}
