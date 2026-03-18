package com.u1.slicer.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ThreeMfParser
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class NativePreparePreviewTest {

    private lateinit var lib: NativeLibrary
    private lateinit var modelFile: File
    private lateinit var workDir: File
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setup() {
        assertTrue("Native library must be loaded on device", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        modelFile = File(targetContext.cacheDir, "native_prepare_preview.3mf")
        modelFile.parentFile?.mkdirs()
        workDir = File(targetContext.cacheDir, "native_prepare_preview_work").apply { mkdirs() }
    }

    @After
    fun teardown() {
        lib.clearModel()
        modelFile.delete()
        workDir.deleteRecursively()
    }

    private fun copyAssetToModelFile(assetName: String) {
        assetContext.assets.open(assetName).use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun previewBounds(preview: NativePreviewMesh): FloatArray {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (i in preview.trianglePositions.indices step 3) {
            val x = preview.trianglePositions[i]
            val y = preview.trianglePositions[i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        return floatArrayOf(minX, maxX, minY, maxY)
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

    @Test
    fun getPreparePreviewMesh_keepsSydneyButtonsPlate1SpreadAfterPlateSelection() {
        copyAssetToModelFile("Button-for-S-trousers.3mf")
        val originalInfo = ThreeMfParser.parse(modelFile)
        val sanitized = BambuSanitizer.process(modelFile, workDir, isBambu = originalInfo.isBambu)
        val plateObjectIds = originalInfo.plates
            .find { it.plateId == 1 }
            ?.objectIds
            ?.toSet()
        val plateExtruderMap = originalInfo.objectExtruderMap
            .filterKeys { key -> plateObjectIds?.contains(key) == true }
        assertTrue("Sydney Buttons original parse should expose per-object extruders", plateExtruderMap.isNotEmpty())
        val rawPlateFile = BambuSanitizer.extractPlate(
            sanitized,
            1,
            workDir,
            hasPlateJsons = originalInfo.hasPlateJsons,
            plateObjectIds = plateObjectIds,
            objectExtruderMap = plateExtruderMap
        )
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, workDir)

        ZipFile(plateFile).use { zip ->
            val hasModelSettings = zip.getEntry("Metadata/model_settings.config") != null
            Log.i("NativePreparePreviewTest", "Sydney plate file has model_settings.config=$hasModelSettings")
            assertTrue("Sydney Buttons selected plate should preserve model_settings.config", hasModelSettings)
        }

        assertTrue(lib.loadModel(plateFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()

        assertNotNull(preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())
        assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)

        val distinctPreviewIndices = preview.extruderIndices.map { it.toInt() and 0xFF }.toSet()
        assertTrue(
            "Expected at least 4 preview colors on Sydney Buttons plate 1, got $distinctPreviewIndices",
            distinctPreviewIndices.size >= 4
        )

        val (minX, maxX, minY, maxY) = previewBounds(preview)
        val width = maxX - minX
        val height = maxY - minY
        assertTrue(
            "Sydney Buttons plate 1 preview width should stay plate-scale after extraction, got $width",
            width >= 80f
        )
        assertTrue(
            "Sydney Buttons plate 1 preview height should stay plate-scale after extraction, got $height",
            height >= 80f
        )
    }
}
