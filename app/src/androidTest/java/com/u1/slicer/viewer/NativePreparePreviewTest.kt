package com.u1.slicer.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativePreparePreviewTest {

    private lateinit var lib: NativeLibrary
    private lateinit var modelFile: File
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setup() {
        assertTrue("Native library must be loaded on device", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        modelFile = File(targetContext.cacheDir, "native_prepare_preview.3mf")
        modelFile.parentFile?.mkdirs()
    }

    @After
    fun teardown() {
        lib.clearModel()
        modelFile.delete()
    }

    private fun copyAssetToModelFile(assetName: String) {
        assetContext.assets.open(assetName).use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Test
    fun getPreparePreviewMesh_returnsDistinctExtruderIndices_forDualColor3mf() {
        copyAssetToModelFile("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()

        assertNotNull(preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())
        assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)
        assertTrue(
            "Expected at least 2 preview colors from native model import",
            preview.extruderIndices.toSet().size >= 2
        )
    }

    @Test
    fun getPreparePreviewMesh_preservesAtLeastFourDistinctExtruderIndices_forSydneyButtonsAsset() {
        copyAssetToModelFile("Button-for-S-trousers.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()

        assertNotNull(preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())
        assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)
        val distinctPreviewIndices = preview.extruderIndices.map { it.toInt() and 0xFF }.toSet()
        assertTrue(
            "Expected at least 4 preview colors from native model import, got $distinctPreviewIndices",
            distinctPreviewIndices.size >= 4
        )
    }

    @Test
    fun getPreparePreviewMesh_preservesRichPreviewIndices_forOldPaintedAsset() {
        copyAssetToModelFile("old.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()

        assertNotNull(preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())
        assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)
        val distinctPreviewIndices = preview.extruderIndices.map { it.toInt() and 0xFF }.toSet()
        assertTrue(
            "Expected at least 5 preview colors from native model import, got $distinctPreviewIndices",
            distinctPreviewIndices.size >= 5
        )
    }
}
