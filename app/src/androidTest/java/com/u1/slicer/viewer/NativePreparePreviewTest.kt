package com.u1.slicer.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.bambu.parseLayerToolSegments
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

    @Test
    fun getPreparePreviewMesh_preservesThreeColours_forDragonPlate3() {
        copyAssetToModelFile("Dragon Scale infinity.3mf")
        val originalInfo = ThreeMfParser.parse(modelFile)
        val sanitized = BambuSanitizer.process(modelFile, workDir, isBambu = originalInfo.isBambu)
        val plateObjectIds = originalInfo.plates
            .find { it.plateId == 3 }
            ?.objectIds
            ?.toSet()
        val plateExtruderMap = originalInfo.objectExtruderMap
            .filterKeys { key -> plateObjectIds?.contains(key) == true }

        val rawPlateFile = BambuSanitizer.extractPlate(
            sanitized,
            3,
            workDir,
            hasPlateJsons = originalInfo.hasPlateJsons,
            plateObjectIds = plateObjectIds,
            objectExtruderMap = plateExtruderMap
        )
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, workDir)

        assertTrue(lib.loadModel(plateFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()

        assertNotNull(preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())
        assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)
        val distinctPreviewIndices = preview.extruderIndices.map { it.toInt() and 0xFF }.toSet()
        assertTrue(
            "Expected Dragon plate 3 preview to preserve at least 3 colours, got $distinctPreviewIndices",
            distinctPreviewIndices.size >= 3
        )
    }

    @Test
    fun layerToolSegments_parsedFromFlippy_and_recolorByZBands_producesDistinctVertexColours() {
        // F46 regression: flippy+flappy+mini.3mf is a Hueforge/layer-tool model.
        // After parse, ThreeMfInfo.layerToolSegments must be non-null and non-empty.
        // After recolorByZBands, the MeshData must have at least 2 distinct RGBA colours
        // across its triangles (i.e., the mesh is no longer single-colour).
        copyAssetToModelFile("flippy+flappy+mini.3mf")

        val info = ThreeMfParser.parse(modelFile)
        assertTrue("flippy should be detected as hasLayerToolChanges", info.hasLayerToolChanges)

        val segments = info.layerToolSegments
        assertNotNull("layerToolSegments should be populated for flippy (F46)", segments)
        segments!!
        assertTrue("layerToolSegments should be non-empty for flippy", segments.isNotEmpty())
        assertTrue(
            "flippy should have at least 2 layer-tool segments (2 colours), got ${segments.size}",
            segments.size >= 2
        )

        // Load native preview mesh
        assertTrue(lib.loadModel(modelFile.absolutePath))
        val preview = lib.getPreparePreviewMesh()
        assertNotNull("Native preview mesh should be available for flippy", preview)
        preview!!

        // Convert to MeshData and apply Z-band recolour
        val meshData = preview.toMeshData()
        assertNotNull("MeshData conversion should succeed", meshData)
        meshData!!

        // Build a minimal 2-colour palette (red=extruder 1, green=extruder 2)
        val palette = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f, 1f)
        )
        meshData.recolorByZBands(segments, palette)

        // After recolour, check that at least 2 distinct R values appear across triangle vertices
        // (red = 1.0 for extruder 1, green = 0.0 R for extruder 2)
        val buf = meshData.vertices
        val triCount = meshData.vertexCount / 3
        val rValues = mutableSetOf<Float>()
        for (tri in 0 until triCount) {
            val rOffset = tri * 3 * 10 + 6  // first vertex of triangle, R channel at offset 6
            rValues.add(buf.get(rOffset))
            if (rValues.size >= 2) break
        }
        assertTrue(
            "recolorByZBands should produce at least 2 distinct R values for flippy (both extruder colours visible), got $rValues",
            rValues.size >= 2
        )
    }
}
