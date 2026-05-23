package com.u1.slicer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.SessionState
import com.u1.slicer.data.SessionStateRepository
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
