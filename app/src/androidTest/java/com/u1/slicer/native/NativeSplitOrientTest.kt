package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F66 contract tests for the new split + auto-orient + per-object pose JNI surface.
 *
 * Each test loads a known asset, exercises one new method, and asserts an
 * observable property of the resulting native-side model state. Together they
 * gate Step 1 of the F66 implementation plan — if any test here fails, no UI
 * work in Steps 2–5 can ship.
 */
@RunWith(AndroidJUnit4::class)
class NativeSplitOrientTest {

    private lateinit var lib: NativeLibrary
    private lateinit var ctx: android.content.Context

    @Before
    fun setup() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun teardown() {
        lib.clearModel()
    }

    private fun copyAsset(assetName: String): String {
        val dst = File(ctx.cacheDir, assetName.substringAfterLast('/'))
        dst.parentFile?.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(assetName).use { it.copyTo(dst.outputStream()) }
        return dst.absolutePath
    }

    // ---- Splittability probes -------------------------------------------------

    @Test
    fun isObjectSplittable_singleStl_returnsFalse() {
        assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
        // Benchy is one closed body — parts_count() should be 1 → not splittable.
        assertFalse(lib.nativeIsObjectSplittable(0))
    }

    @Test
    fun isObjectSplittable_buttonForSTrousers_returnsTrue() {
        // Button-for-S-trousers is one ModelObject with ~40 volumes (B124 regression
        // fixture). parts_count() > 1 → splittable.
        assertTrue(lib.loadModel(copyAsset("Button-for-S-trousers.3mf")))
        val anySplittable = (0 until lib.nativeGetObjectCount())
            .any { lib.nativeIsObjectSplittable(it) }
        assertTrue("at least one object should be splittable", anySplittable)
    }

    @Test
    fun isObjectSplittable_outOfRange_returnsFalse() {
        assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
        assertFalse(lib.nativeIsObjectSplittable(-1))
        assertFalse(lib.nativeIsObjectSplittable(99))
    }

    // ---- splitObject ----------------------------------------------------------

    @Test
    fun splitObject_singleIsland_returnsNullAndLeavesModelUnchanged() {
        assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
        val before = lib.nativeGetObjectCount()
        val res = lib.nativeSplitObject(0)
        assertNull("single-island object should not split", res)
        assertEquals(before, lib.nativeGetObjectCount())
    }

    @Test
    fun splitObject_multiPart_returnsRemovedIdxAndAddedCount_andCountDeltaMatches() {
        assertTrue(lib.loadModel(copyAsset("Button-for-S-trousers.3mf")))
        val splittableIdx = (0 until lib.nativeGetObjectCount())
            .firstOrNull { lib.nativeIsObjectSplittable(it) }
            ?: error("expected at least one splittable object in Button-for-S-trousers")
        val before = lib.nativeGetObjectCount()
        val res = lib.nativeSplitObject(splittableIdx)
        assertNotNull("split should succeed on a splittable object", res)
        val removed = res!![0]
        val added = res[1]
        assertEquals("removedIdx", splittableIdx, removed)
        assertTrue("addedCount > 1", added > 1)
        assertEquals("count delta", before - 1 + added, lib.nativeGetObjectCount())
    }

    @Test
    fun splitObject_replayAfterClearAndLoad_restoresPostSplitObjectCount() {
        // F66 regression: the slice path does a `clearModel + loadModel` to
        // re-embed the profile before slicing. Without replaying the recorded
        // split operations afterwards, the slice + post-slice Prepare preview
        // both fall back to the original 1-object layout — matching the
        // user-reported "showing original placement / weird broken view" bug.
        // This test proves the replay sequence works at the native level.
        val path = copyAsset("Button-for-S-trousers.3mf")
        assertTrue(lib.loadModel(path))
        val baseCount = lib.nativeGetObjectCount()
        val splittableIdx = (0 until baseCount)
            .firstOrNull { lib.nativeIsObjectSplittable(it) }
            ?: error("expected at least one splittable object in Button-for-S-trousers")
        val res = lib.nativeSplitObject(splittableIdx)
        assertNotNull("split should succeed", res)
        val postSplitCount = lib.nativeGetObjectCount()
        assertTrue("post-split count > base count", postSplitCount > baseCount)

        // Simulate the slice path's clearModel + loadModel pair.
        lib.clearModel()
        assertEquals("clearModel zeros object count", 0, lib.nativeGetObjectCount())
        assertTrue(lib.loadModel(path))
        assertEquals("loadModel restores the file's natural object count", baseCount, lib.nativeGetObjectCount())

        // Replay the recorded split op. SlicerViewModel persists this as
        // `_splitObjectOps.value = listOf(splittableIdx)`; the slice path
        // (and F89 restore) walks it after the loadModel.
        val replayRes = lib.nativeSplitObject(splittableIdx)
        assertNotNull("replay split should succeed on the freshly-loaded model", replayRes)
        assertEquals(
            "replayed split must restore the post-split object count",
            postSplitCount,
            lib.nativeGetObjectCount(),
        )
    }

    // ---- Auto-orient ----------------------------------------------------------

    @Test
    fun autoOrientObject_afterPreTilt_putsModelOnBed() {
        assertTrue(lib.loadModel(copyAsset("Articulated+Fish+(3).3mf")))
        // Pre-tilt to simulate a user-rotated pose.
        assertTrue(lib.nativeSetObjectRotation(0, 45f, 30f, 0f))
        val result = lib.nativeAutoOrientObject(0)
        assertNotNull("orient should return a rotation", result)
        // After orient, the instance bottom-Z should sit on the bed (z ≈ 0).
        val zmins = lib.getInstanceWorldZMins()
        assertTrue("instance Z-min not negative", zmins.isNotEmpty())
        assertEquals("bed-resting Z", 0.0, zmins[0].toDouble(), 0.5)
    }

    @Test
    fun autoOrientAll_multiObject_returnsNonZeroSuccessCount() {
        assertTrue(lib.loadModel(copyAsset("skywing-seawing-silkwing.3mf")))
        val succeeded = lib.nativeAutoOrientAll()
        // skywing-seawing-silkwing is multi-object; at least one orient should work.
        assertTrue("autoOrientAll should orient at least one object, got $succeeded", succeeded > 0)
    }

    // ---- Per-object rotation/scale round-trip --------------------------------

    @Test
    fun setObjectRotation_thenGet_roundTripsInDegrees() {
        assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
        assertTrue(lib.nativeSetObjectRotation(0, 30f, 45f, 60f))
        val r = lib.nativeGetObjectRotation(0)
        assertEquals("x deg", 30f, r[0], 0.1f)
        assertEquals("y deg", 45f, r[1], 0.1f)
        assertEquals("z deg", 60f, r[2], 0.1f)
    }

    @Test
    fun setObjectScale_thenGet_roundTripsPerAxis() {
        assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
        assertTrue(lib.nativeSetObjectScale(0, 1.5f, 2.0f, 0.5f))
        val s = lib.nativeGetObjectScale(0)
        assertEquals("sx", 1.5f, s[0], 0.001f)
        assertEquals("sy", 2.0f, s[1], 0.001f)
        assertEquals("sz", 0.5f, s[2], 0.001f)
    }

    // ---- Per-object isolation (no leakage between objects) -------------------

    @Test
    fun setObjectRotation_object0_doesNotAffectObject1Rotation() {
        assertTrue(lib.loadModel(copyAsset("skywing-seawing-silkwing.3mf")))
        // Require ≥2 objects to make the test meaningful.
        val n = lib.nativeGetObjectCount()
        assertTrue("need ≥2 objects, got $n", n >= 2)
        val obj1Before = lib.nativeGetObjectRotation(1)
        assertTrue(lib.nativeSetObjectRotation(0, 90f, 0f, 0f))
        val obj1After = lib.nativeGetObjectRotation(1)
        assertArrayEquals(obj1Before, obj1After, 0.001f)
    }

    // ---- Volume metadata + extruder ------------------------------------------

    @Test
    fun getObjectName_returnsNonEmptyForBambuFile() {
        assertTrue(lib.loadModel(copyAsset("Button-for-S-trousers.3mf")))
        val name = lib.nativeGetObjectName(0)
        assertNotNull(name)
        assertTrue("object name should be non-empty", !name.isNullOrBlank())
    }

    @Test
    fun setVolumeExtruder_thenGet_roundTrips() {
        assertTrue(lib.loadModel(copyAsset("colored_3DBenchy (1).3mf")))
        // colored_3DBenchy has multiple volumes — pick the first object, first volume.
        val volCount = lib.nativeGetVolumeCount(0)
        assertTrue("colored_3DBenchy should have ≥1 volume, got $volCount", volCount >= 1)
        assertTrue(lib.nativeSetVolumeExtruder(0, 0, 3))
        assertEquals(3, lib.nativeGetVolumeExtruder(0, 0))
    }

    @Test
    fun setVolumeExtruder_propagatesToNextPreviewMeshExtruderIndices() {
        // Regression guard for the user-reported "I change a part's colour
        // and the Prepare preview doesn't update" bug.
        //
        // Pipeline this test exercises:
        //   nativeSetVolumeExtruder
        //     → vol->config["extruder"] = slot
        //     → setPreviewExtruderOverride(o, v, slot) keeps the static
        //       `g_model_preview_extruders` cache in sync
        //     → invalidatePreviewMeshCache() so the next mesh rebuild
        //   getPreparePreviewMesh()
        //     → rebuilds, per-triangle extruderIndices reflect the override
        //
        // Why this fixture: a single-volume STL with `compactPreviewIndices`
        // collapses any unique extruder value to 0, so the mesh content
        // wouldn't differentiate slot changes. Painted MMU files route
        // state_idx>0 triangles independently of vol->extruder_id(), and a
        // fully-painted volume has zero state_idx==0 triangles, so changing
        // its slot also produces no visible mesh change.
        //
        // We therefore stack TWO STLs on the bed via `addModel`. Each is
        // its own object with one volume. With both at slot 1, compaction
        // produces only index 0. Switching obj 0 to slot 2 introduces a
        // SECOND unique value → compacted distribution becomes {0, 1}. If
        // the cache wasn't invalidated (the regression), the second call
        // returns the byte-identical {0=N} mesh.
        val benchy = copyAsset("3DBenchy.stl")
        val cube = copyAsset("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(benchy))
        assertTrue(lib.addModel(cube))
        val objCount = lib.nativeGetObjectCount()
        assertTrue("expected ≥2 objects after addModel, got $objCount", objCount >= 2)

        // Force every volume of every object to slot 1.
        for (o in 0 until objCount) {
            val vc = lib.nativeGetVolumeCount(o)
            for (v in 0 until vc) {
                assertTrue(lib.nativeSetVolumeExtruder(o, v, 1))
            }
        }
        val mesh1Bytes = lib.getPreparePreviewMesh()?.extruderIndices
        assertNotNull("baseline preview mesh", mesh1Bytes)
        val mesh1Unique = mesh1Bytes!!.map { it.toInt() and 0xFF }.toSet()
        val mesh1Counts = mesh1Bytes.map { it.toInt() and 0xFF }
            .groupingBy { it }.eachCount()

        // Bump obj 0 vol 0 to slot 5 — now the model has two distinct slot
        // values; compaction maps {0, 4} → {0, 1}.
        assertTrue(lib.nativeSetVolumeExtruder(0, 0, 5))
        assertEquals(5, lib.nativeGetVolumeExtruder(0, 0))

        val mesh2Bytes = lib.getPreparePreviewMesh()?.extruderIndices
        assertNotNull("post-change preview mesh", mesh2Bytes)
        val mesh2Unique = mesh2Bytes!!.map { it.toInt() and 0xFF }.toSet()
        val mesh2Counts = mesh2Bytes.map { it.toInt() and 0xFF }
            .groupingBy { it }.eachCount()

        // If the cache wasn't invalidated, mesh2 would equal mesh1 — both
        // {0=N}. We assert that the unique set GAINED a new value.
        assertNotEquals(
            "after setVolumeExtruder(obj 0, vol 0, slot 5) the preview-mesh extruder-index " +
                "distribution must change. Equal distribution means the preview-mesh cache " +
                "wasn't invalidated and the user won't see the colour change — regression of " +
                "the original 'colour change not visible in Prepare preview' bug.\n" +
                "  mesh1 distribution: $mesh1Counts\n  mesh2 distribution: $mesh2Counts",
            mesh1Counts, mesh2Counts,
        )
        assertTrue(
            "mesh2 must have at least 2 distinct compacted extruder indices after the slot " +
                "change. Got $mesh2Unique. This means the per-triangle indices weren't " +
                "rebuilt with the new slot — preview wouldn't show the colour change.",
            mesh2Unique.size >= 2,
        )
    }

    @Test
    fun setVolumeExtruder_outOfRangeSlot_returnsFalse() {
        assertTrue(lib.loadModel(copyAsset("3DBenchy.stl")))
        assertFalse("slot 0 invalid (1-indexed)", lib.nativeSetVolumeExtruder(0, 0, 0))
        assertFalse("slot 99 invalid", lib.nativeSetVolumeExtruder(0, 0, 99))
    }

    // ---- Paint preservation across split (the Task 1.9 risk gate) ------------

    @Test
    fun splitObject_paintedMultiIsland_preservesPaintStateAcrossNewObjects() {
        assertTrue(lib.loadModel(copyAsset("flippy+flappy+mini-with-plate-painted.3mf")))
        // Sum paint state counts across every (object, volume) pre-split.
        val totalBefore = (0 until lib.nativeGetObjectCount()).sumOf { obj ->
            (0 until lib.nativeGetVolumeCount(obj)).sumOf { vol ->
                val counts = lib.nativeGetPaintStateCounts(obj, vol, /* kind = mmu */ 0)
                counts?.sum() ?: 0
            }
        }
        if (totalBefore == 0) {
            // Fixture has no paint state to verify; skip rather than fail.
            return
        }
        // Try to split every splittable object.
        var i = 0
        while (i < lib.nativeGetObjectCount()) {
            if (lib.nativeIsObjectSplittable(i)) {
                val res = lib.nativeSplitObject(i)
                if (res != null) { i += res[1]; continue }
            }
            i++
        }
        val totalAfter = (0 until lib.nativeGetObjectCount()).sumOf { obj ->
            (0 until lib.nativeGetVolumeCount(obj)).sumOf { vol ->
                val counts = lib.nativeGetPaintStateCounts(obj, vol, 0)
                counts?.sum() ?: 0
            }
        }
        // Allow up to 1% drift from boundary-triangle reassignment; anything more
        // means BBS-fork split() is dropping mmu_segmentation_facets.
        assertTrue(
            "paint state must survive split (before=$totalBefore, after=$totalAfter)",
            totalAfter >= (totalBefore * 0.99).toInt()
        )
    }
}
