package com.u1.slicer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
