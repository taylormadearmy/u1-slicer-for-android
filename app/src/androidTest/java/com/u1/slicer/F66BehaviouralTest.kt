package com.u1.slicer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.PerObjectPose
import com.u1.slicer.ui.ObjectSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F66 — end-to-end behavioural tests, written AFTER chasing four symptom
 * patches in a row that each looked plausible in isolation. Each test asserts
 * a behaviour the user can actually observe.
 *
 * These exist because the original plan's "instrumented tests" section listed
 * many of these test names but they were never written. The four-agent review
 * round identified the missing coverage as a key reason regressions kept
 * slipping through.
 */
@RunWith(AndroidJUnit4::class)
class F66BehaviouralTest {

    private lateinit var ctx: Application
    private lateinit var vm: SlicerViewModel

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        // Run VM creation on the main thread (SlicerViewModel touches
        // AndroidViewModel internals).
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = SlicerViewModel(ctx)
        }
    }

    private fun copyAsset(name: String): File {
        val dst = File(ctx.cacheDir, name.substringAfterLast('/'))
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        return dst
    }

    private fun awaitLoaded(timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (vm.state.value is SlicerViewModel.SlicerState.ModelLoaded) return
            Thread.sleep(50)
        }
        error("model didn't reach ModelLoaded within ${timeoutMs / 1000}s; last state = ${vm.state.value}")
    }

    // ---- Selection ergonomics ------------------------------------------------

    @Test
    fun tapSameObjectTwice_remainsSelected() {
        // selectObject(same idx) is idempotent — re-tapping the selected
        // object keeps it selected. Toggle semantics caused every other tap
        // on a single-STL load to look like a dropped tap (alternating
        // select/deselect with no visible second-tap distinction).
        vm.selectObject(0)
        assertEquals(0, vm.selection.value.objectIndex)
        vm.selectObject(0)
        assertEquals(0, vm.selection.value.objectIndex)
    }

    @Test
    fun selectObjectNull_returnsToBedWide() {
        // Deselect path: tap on empty bed → viewModel.deselect() (or
        // selectObject(null)) → bed-wide mode.
        vm.selectObject(0)
        vm.selectObject(null)
        assertNull(vm.selection.value.objectIndex)
    }

    @Test
    fun tapDifferentObject_switchesSelection() {
        vm.selectObject(1)
        assertEquals(1, vm.selection.value.objectIndex)
        vm.selectObject(3)
        assertEquals(3, vm.selection.value.objectIndex)
        // Did NOT deselect — only same-index taps toggle.
    }

    // ---- Teardown on file load + clearModel ---------------------------------

    @Test
    fun clearModel_resetsAllF66State() {
        // Manual symptom: loading file B after editing file A kept A's
        // selection / split history / per-volume overrides pointing at B's
        // object indices.
        vm.selectObject(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.clearModel()
        }
        assertNull(vm.selection.value.objectIndex)
        assertTrue(vm.perObjectPoses.value.isEmpty())
        assertTrue(vm.loadTimePoses.value.isEmpty())
        assertTrue(vm.perVolumeExtruders.value.isEmpty())
        assertTrue(vm.splitObjectOps.value.isEmpty())
        assertTrue(vm.splitVolumeOps.value.isEmpty())
    }

    // ---- Load-time baselines + Reset ----------------------------------------

    @Test
    fun afterLoad_loadTimePosesIsPopulated() = runBlocking {
        // Manual symptom: Reset rotation / scale buttons were no-ops because
        // _loadTimePoses was never populated on a normal load (only on F89
        // resume). After this fix, the Loading→ModelLoaded state transition
        // calls snapshotLoadTimePoses() so every object has a baseline.
        val stl = copyAsset("3DBenchy.stl")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.loadModelFromFile(stl)
        }
        awaitLoaded()
        // Wait one extra tick for the state-watcher coroutine to fire.
        Thread.sleep(200)
        val baseline = vm.loadTimePoses.value
        assertTrue("expected a baseline for object 0", baseline.containsKey(0))
        assertNotNull(baseline[0])
    }

    @Test
    fun resetObjectRotation_afterAutoOrient_restoresLoadTimeRotation() = runBlocking {
        val stl = copyAsset("3DBenchy.stl")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.loadModelFromFile(stl)
        }
        awaitLoaded()
        Thread.sleep(200)
        val baselineRot = vm.loadTimePoses.value[0]?.rotXDeg ?: 0f

        // Rotate object 0 explicitly to non-baseline values.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.setObjectRotation(0, 30f, 45f, 60f)
        }
        val rotated = vm.perObjectPoses.value[0]!!
        assertEquals(30f, rotated.rotXDeg, 0.5f)

        // Reset.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.resetObjectRotation(0)
        }
        val reset = vm.perObjectPoses.value[0]!!
        assertEquals(
            "Reset must restore the load-time rotation X, not collapse to zero",
            baselineRot, reset.rotXDeg, 0.5f,
        )
    }

    // ---- invalidatePrepareMeshCache bumps modelAddVersion -------------------

    @Test
    fun invalidatePrepareMeshCache_bumpsModelAddVersion() {
        // Manual symptom: Auto-orient and per-object rotate/scale never
        // visually refreshed the preview because invalidatePrepareMeshCache()
        // only cleared @Volatile vars that Compose doesn't observe. After
        // the fix, every cache invalidation bumps modelAddVersion — which is
        // a key on the mesh-fetch LaunchedEffect, so Compose actually re-runs
        // the fetch.
        val before = vm.modelAddVersion.value
        vm.invalidatePrepareMeshCache()
        assertTrue(
            "modelAddVersion must bump on cache invalidation so the InlineModelPreview LaunchedEffect re-keys",
            vm.modelAddVersion.value > before,
        )
    }

    // ---- Slice + plate-switch auto-deselect ---------------------------------

    @Test
    fun nativeGetObjectWorldAABBMins_matchesObjectCount_forMultiObjectFile() {
        // Issue 4 — Button-for-S-trousers loads as N native ModelObjects. The
        // promote-on-load path reads each object's world-space AABB min via
        // the new JNI and uses it as customObjectPositions. This test exercises
        // the JNI directly (bypassing the ViewModel's full load state machine,
        // which routes through plate selection for some 3MFs and is awkward to
        // drive from an instrumented test).
        val lib = com.u1.slicer.NativeLibrary()
        val asset = copyAsset("Button-for-S-trousers.3mf")
        assertTrue(lib.loadModel(asset.absolutePath))
        val objectCount = lib.nativeGetObjectCount()
        assertTrue("expected multi-object load, got $objectCount", objectCount >= 2)

        val worldMins = lib.nativeGetObjectWorldAABBMins()
        assertEquals(
            "world-AABB-min array must have 2 floats per object so the renderer can match positions to ranges",
            objectCount * 2,
            worldMins.size,
        )
        // The values come directly from the engine's instance-transform AABB
        // computation. We deliberately don't bed-bounds-check them here —
        // some files declare raw layouts spanning >270mm and the ViewModel's
        // `promoteToMultiObjectIfApplicable` centres the group before
        // surfacing the positions to the renderer.
        lib.clearModel()
    }

    @Test
    fun startSlicing_deselectsCurrentObject() {
        // Manual symptom: after slicing the Edit panel stayed in object-scoped
        // mode pointing at a stale objIdx that the re-embed had invalidated.
        vm.selectObject(0)
        assertEquals(0, vm.selection.value.objectIndex)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.startSlicing()  // refuses without a model + may report error — that's fine
        }
        // The selection must clear regardless of whether the slice succeeded.
        assertNull(
            "startSlicing must clear selection so the Edit panel returns to bed-wide mode",
            vm.selection.value.objectIndex,
        )
    }
}
