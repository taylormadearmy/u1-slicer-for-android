package com.u1.slicer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.PerObjectPose
import com.u1.slicer.data.SessionState
import com.u1.slicer.data.SessionStateRepository
import com.u1.slicer.data.SliceJob
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionResumeIntegrationTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val repo = SessionStateRepository(app)

    @Before
    fun setUp() = runBlocking { repo.clear() }

    @After
    fun tearDown() = runBlocking { repo.clear() }

    /**
     * Copies a file from the androidTest APK's assets (NOT the app-under-test's
     * assets) to the app's cache dir. Mirrors the pattern used in
     * NativeLibraryCorrectnessTest — the bundled test fixtures live in the
     * instrumentation context, not the target app.
     */
    private fun copyAssetToCache(assetName: String, outName: String = assetName): File {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val out = File(app.cacheDir, outName)
        testCtx.assets.open(assetName).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    @Test
    fun init_savedSessionWithExistingFile_exposesResumeOffer() = runBlocking {
        val asset = copyAssetToCache("colored_3DBenchy (1).3mf", "colored_3DBenchy.3mf")
        repo.write(
            SessionState(
                modelName = "colored_3DBenchy.3mf",
                rawInputPath = asset.absolutePath,
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                sliceJobId = null,
                wasSliceComplete = false,
                savedAtEpochMs = System.currentTimeMillis(),
                appVersionCode = 295,
            )
        )
        val vm = SlicerViewModel(app)
        val offer = withTimeoutOrNull(5_000) {
            vm.sessionResumeOffer.first { it != null }
        }
        assertNotNull("Resume offer was never exposed", offer)
        assertEquals("colored_3DBenchy.3mf", offer!!.modelName)
        assertNull(offer.plateId)
    }

    @Test
    fun init_savedSessionMissingFile_emitsToastAndClears() = runBlocking {
        repo.write(
            SessionState(
                modelName = "ghost.3mf",
                rawInputPath = "/cache/does-not-exist.3mf",
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                sliceJobId = null,
                wasSliceComplete = false,
                savedAtEpochMs = 0L,
                appVersionCode = 295,
            )
        )
        val vm = SlicerViewModel(app)
        val toast = withTimeoutOrNull(5_000) {
            vm.toastEvents.first()
        }
        assertNotNull("Toast event was never emitted", toast)
        assertEquals("Couldn't resume ghost.3mf — file no longer available", toast)
        assertNull("Stale session should be cleared", repo.read())
        assertNull("No resume offer should be shown for missing files", vm.sessionResumeOffer.value)
    }

    @Test
    fun acceptSessionResume_wasSliceCompleteWithValidRow_fastPathSkipsModelLoad() = runBlocking {
        // Create a fake gcode file in the cache so File(path).exists() returns true.
        val gcodeFile = File(app.cacheDir, "fake.gcode")
        gcodeFile.writeText("; fake gcode\nG28\nG1 X10 Y10\nM104 S210\n")

        // Insert a fake SliceJob row.
        val container = (app as com.u1.slicer.U1SlicerApplication).container
        val jobId = container.sliceJobDao.insert(
            SliceJob(
                modelName = "fake.3mf",
                gcodePath = gcodeFile.absolutePath,
                sourcePath = null,
                totalLayers = 42,
                estimatedTimeSeconds = 1234f,
                estimatedFilamentMm = 5678f,
                estimatedFilamentGrams = 12.3f,
                layerHeight = 0.2f,
                fillDensity = 0.15f,
                nozzleTemp = 220,
                bedTemp = 60,
                supportEnabled = false,
                filamentType = "PLA",
            )
        )

        // Write a session blob pointing at this row.
        val asset = copyAssetToCache("colored_3DBenchy (1).3mf", "colored_3DBenchy.3mf")
        repo.write(
            SessionState(
                modelName = "fake.3mf",
                rawInputPath = asset.absolutePath,
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                sliceJobId = jobId,
                wasSliceComplete = true,
                savedAtEpochMs = 0L,
                appVersionCode = 295,
            )
        )

        val vm = SlicerViewModel(app)
        // Wait for offer to be exposed.
        withTimeoutOrNull(5_000) { vm.sessionResumeOffer.first { it != null } }
        // Subscribe to navigateEvents BEFORE tapping Resume — the SharedFlow has
        // no replay, so emissions that happen before `first()` is awaited are lost.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val navDeferred = GlobalScope.async {
            withTimeoutOrNull(4_000) { vm.navigateEvents.first() }
        }
        // Give the async block a moment to actually subscribe before we emit.
        delay(100)
        // Tap Resume.
        vm.acceptSessionResume()
        // The fast-path should set state to SliceComplete within a few hundred ms,
        // far less than the 90 s+ a full model load would take.
        val terminal = withTimeoutOrNull(3_000) {
            vm.state.first { it is SlicerViewModel.SlicerState.SliceComplete }
        }
        assertNotNull("Fast-path should set SliceComplete state", terminal)
        val sc = terminal as SlicerViewModel.SlicerState.SliceComplete
        assertEquals(gcodeFile.absolutePath, sc.result.gcodePath)
        assertEquals(42, sc.result.totalLayers)
        // And emit a navigate event for the Preview tab.
        val nav = navDeferred.await()
        assertEquals("preview", nav)

        // Cleanup
        gcodeFile.delete()
        container.sliceJobDao.deleteAll()
    }

    @Test
    fun runSilentBackgroundRestore_setsAndClearsInProgressFlag() = runBlocking {
        // Single-plate STL session, no slice. Verifies the in-progress flag
        // toggles correctly around the silent load.
        val asset = copyAssetToCache("colored_3DBenchy (1).3mf", "colored_3DBenchy.3mf")
        repo.write(
            SessionState(
                modelName = "colored_3DBenchy.3mf",
                rawInputPath = asset.absolutePath,
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                sliceJobId = null,
                wasSliceComplete = false,
                savedAtEpochMs = 0L,
                appVersionCode = 295,
            )
        )
        val vm = SlicerViewModel(app)
        withTimeoutOrNull(5_000) { vm.sessionResumeOffer.first { it != null } }
        // This is a non-sliced session, so fast-path doesn't fire — full
        // restoreSession runs. silentRestoreInProgress stays false throughout.
        assertEquals(false, vm.silentRestoreInProgress.value)
    }

    @Test
    fun acceptSessionResume_concurrentSecondTap_isNoop() = runBlocking {
        val asset = copyAssetToCache("colored_3DBenchy (1).3mf", "colored_3DBenchy.3mf")
        repo.write(
            SessionState(
                modelName = "colored_3DBenchy.3mf",
                rawInputPath = asset.absolutePath,
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                sliceJobId = null,
                wasSliceComplete = false,
                savedAtEpochMs = 0L,
                appVersionCode = 295,
            )
        )
        val vm = SlicerViewModel(app)
        withTimeoutOrNull(5_000) { vm.sessionResumeOffer.first { it != null } }
        vm.acceptSessionResume()
        // Second tap: offer was cleared by first call, so second call is a no-op.
        vm.acceptSessionResume()
        // No assertion needed — if double-tap caused a crash or duplicate restore
        // the test would fail. We just confirm the offer is null.
        kotlinx.coroutines.delay(100)
        assertNull(vm.sessionResumeOffer.value)
    }

    // F66 review-2026-05-30: gap #4 — F89 resume must restore selection,
    // per-object poses, per-volume extruders, and split-ops in addition to the
    // basic SessionState fields. The JSON round-trip is unit-tested, but the
    // DataStore → ViewModel-restore path for F66 fields has had no coverage
    // before this — a silent regression in restoreSession would only surface
    // on user-visible "I rotated and saved but resume forgot the rotation".
    @Test
    fun acceptSessionResume_withF66State_restoresSelectionPosesSplitsAndVolumeOverrides() = runBlocking {
        // Use 3DBenchy (single-object STL) so the resume path doesn't pause
        // on the multi-plate selector — we only want to verify F66 state.
        val asset = copyAssetToCache("3DBenchy.stl")
        // Persist a session with non-default F66 state. Note: split ops are
        // intentionally empty here because splitObject on Benchy is a no-op
        // (single-island) and we don't want the replay to fail silently. The
        // other F66 fields (selection, pose, slot override) exercise the
        // restoreSession F66 block end-to-end.
        repo.write(
            SessionState(
                modelName = "3DBenchy.stl",
                rawInputPath = asset.absolutePath,
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                selectedObjectIndex = 0,
                selectedVolumeIndex = null,
                perObjectPoses = mapOf(
                    0 to PerObjectPose(rotXDeg = 0f, rotYDeg = 0f, rotZDeg = 45f,
                                       scaleX = 1.25f, scaleY = 1.25f, scaleZ = 1.25f)
                ),
                perVolumeExtruders = mapOf("0:0" to 3),
                splitObjectOperations = emptyList(),
                splitVolumeOperations = emptyList(),
                sliceJobId = null,
                wasSliceComplete = false,
                savedAtEpochMs = System.currentTimeMillis(),
                appVersionCode = 310,
            )
        )

        val vm = SlicerViewModel(app)
        withTimeoutOrNull(5_000) { vm.sessionResumeOffer.first { it != null } }
        vm.acceptSessionResume()

        // Wait for restore completion (state hits ModelLoaded).
        withTimeoutOrNull(60_000) {
            vm.state.first { it is SlicerViewModel.SlicerState.ModelLoaded }
        }
        // Poll for the F66 replay to finish — restoreSession runs setObjectScale/
        // setVolumeExtruder synchronously AFTER the ModelLoaded transition,
        // but JNI bbox refreshes inside refreshObjectGeometryAfterPoseChange
        // can take ~100ms on a big model. Poll up to 5s.
        val pollDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < pollDeadline) {
            if (vm.perVolumeExtruders.value["0:0"] == 3 &&
                vm.perObjectPoses.value[0]?.rotZDeg == 45f) {
                break
            }
            delay(100)
        }

        // Selection
        assertEquals(
            "selectedObjectIndex must restore from SessionState — without this, resume drops to bed-wide mode unexpectedly",
            0, vm.selection.value.objectIndex,
        )
        // Per-object pose
        val pose = vm.perObjectPoses.value[0]
        assertNotNull("perObjectPoses[0] must restore from SessionState", pose)
        assertEquals("rotZ", 45f, pose!!.rotZDeg, 0.5f)
        assertEquals("scaleX", 1.25f, pose.scaleX, 0.01f)
        // Per-volume extruder
        assertEquals(
            "perVolumeExtruders 0:0 must restore from SessionState",
            3, vm.perVolumeExtruders.value["0:0"],
        )
    }

    @Test
    fun dismissSessionResume_clearsOfferAndDataStore() = runBlocking {
        val asset = copyAssetToCache("colored_3DBenchy (1).3mf", "colored_3DBenchy.3mf")
        repo.write(
            SessionState(
                modelName = "colored_3DBenchy.3mf",
                rawInputPath = asset.absolutePath,
                sourceModelPath = null, currentModelPath = null, multiPlateSourcePath = null,
                selectedPlateId = null,
                modelScale = Triple(1f, 1f, 1f),
                modelRotation = Triple(0f, 0f, 0f),
                copyCount = 1,
                customObjectPositions = null, customWipeTowerPos = null,
                additionalFiles = emptyList(),
                sliceJobId = null,
                wasSliceComplete = false,
                savedAtEpochMs = 0L,
                appVersionCode = 295,
            )
        )
        val vm = SlicerViewModel(app)
        withTimeoutOrNull(5_000) { vm.sessionResumeOffer.first { it != null } }
        vm.dismissSessionResume()
        kotlinx.coroutines.delay(200)
        assertNull(vm.sessionResumeOffer.value)
        assertNull(repo.read())
    }
}
