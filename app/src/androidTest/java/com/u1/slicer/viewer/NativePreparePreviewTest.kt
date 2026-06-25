package com.u1.slicer.viewer

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ByteOrder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.bambu.parseLayerToolSegments
import org.junit.After
import org.junit.Assert.assertEquals
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

    /** Returns [minX, maxX, minY, maxY, minZ, maxZ] */
    private fun previewBounds3D(preview: NativePreviewMesh): FloatArray {
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        for (i in preview.trianglePositions.indices step 3) {
            val x = preview.trianglePositions[i]
            val y = preview.trianglePositions[i + 1]
            val z = preview.trianglePositions[i + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }

        return floatArrayOf(minX, maxX, minY, maxY, minZ, maxZ)
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
        // H1 from post-Buzz code review (2026-04-30): pin exact distinct
        // count so a future fold-style over-count regression (e.g. H2C
        // state-fold double-counting state-N+4 into bucket-N) is caught
        // here too. Dual-colour calib cube emits exactly 2 extruder
        // indices in the preview mesh.
        val distinct = preview.extruderIndices.map { it.toInt() and 0xFF }.toSet()
        assertEquals(
            "Dual-colour calib cube must have exactly 2 distinct preview extruder " +
                "indices, got $distinct",
            2, distinct.size
        )
    }

    @Test
    fun getPreparePreviewMesh_emitsTranslucentModifierBlock_forNegativeVolumeFixture() {
        // F95: negative/modifier volumes are filtered out of the model-part preview passes
        // (is_model_part()==false) post-B137. They must instead be appended as a contiguous
        // trailing block so the renderer can draw them translucent. The articulated fixture
        // (B137's negative-volume-articulated.3mf) carries negative_part joint-clearance
        // cutters; the preview must surface them after the solid model-part triangles.
        copyAssetToModelFile("negative-volume-articulated.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()
        assertNotNull(preview)
        preview!!

        val triCount = preview.extruderIndices.size
        val modStart = lib.nativeGetPreviewModifierBlockStart()

        assertTrue("Preview must contain triangles", triCount > 0)
        assertTrue(
            "Modifier block start must be >= 0 when the model has negative volumes (got $modStart)",
            modStart >= 0
        )
        assertTrue(
            "Modifier block must come after at least one model-part triangle (start=$modStart)",
            modStart > 0
        )
        assertTrue(
            "Modifier block must contain at least one triangle (start=$modStart, total=$triCount)",
            modStart < triCount
        )

        // The trailing block recolors translucent via MeshData; verify the boundary survives
        // the toMeshData hand-off and recolor paints those triangles with alpha < 1.
        preview.modifierBlockStartTriangle = modStart
        val mesh = preview.toMeshData()
        assertNotNull(mesh)
        assertEquals(modStart, mesh!!.modifierBlockStartTriangle)
        // recolor with a simple opaque palette; modifier triangles must end up translucent.
        mesh.recolor(listOf(floatArrayOf(1f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 0f, 1f)))
        val modTriBase = (modStart * 3) * MeshData.FLOATS_PER_VERTEX + 6
        val modAlpha = mesh.vertices.get(modTriBase + 3)
        assertTrue("First modifier triangle must be translucent (alpha=$modAlpha)", modAlpha < 1f)
    }

    @Test
    fun getPreparePreviewMesh_reportsNoModifierBlock_forModelWithoutNegativeVolumes() {
        // Negative control: a plain dual-colour model has no negative/modifier volumes, so the
        // accessor must report -1. Guards against the modifier pass falsely tagging model parts.
        copyAssetToModelFile("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()
        assertNotNull(preview)

        assertEquals(
            "Model without negative/modifier volumes must report modifier-block-start = -1",
            -1, lib.nativeGetPreviewModifierBlockStart()
        )
    }

    @Test
    fun getPreparePreviewMesh_reportsBatchTriangleCountsThatSumToThePreviewMesh() {
        // F142 preview-scene follow-up: the native preview cache now keeps per-batch triangle
        // counts alongside the combined mesh. This is a compatibility bridge for future staged
        // / progressive preview delivery, so the metadata must stay aligned with the returned
        // mesh even before Kotlin consumes it.
        copyAssetToModelFile("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()
        assertNotNull(preview)
        preview!!

        val batchCounts = lib.nativeGetPreviewBatchTriangleCounts()
        assertNotNull("Preview batch counts must be populated after a mesh build", batchCounts)
        batchCounts!!
        assertTrue("Preview must expose at least one batch", batchCounts.isNotEmpty())

        val totalFromBatches = batchCounts.sum()
        assertEquals(
            "Batch triangle counts must sum to the preview mesh triangle count",
            preview.extruderIndices.size,
            totalFromBatches
        )
    }

    @Test
    fun getPreparePreviewMesh_sceneHandleStreamsBatchesThatReconstructTheFullPreview() {
        // The staged scene handle should let Kotlin consume batches as they arrive and still
        // reconstruct the same final preview mesh as the legacy one-shot API.
        copyAssetToModelFile("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val handle = lib.buildPrepareRenderScene()
        val batches = mutableListOf<com.u1.slicer.viewer.NativeRenderBatch>()
        try {
            val batchCount = lib.nativeGetPrepareRenderSceneBatchCount(handle)
            for (i in 0 until batchCount) {
                val geom = lib.nativeGetPrepareRenderSceneGeometryBuffer(handle, i)!!
                val mat = lib.nativeGetPrepareRenderSceneMaterialBuffer(handle, i)
                val triCount = lib.nativeGetPrepareRenderSceneTriangleCount(handle, i)
                batches.add(com.u1.slicer.viewer.NativeRenderBatch(geom.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer(), mat, triCount))
            }
        } finally {
            assertTrue(lib.nativeReleasePrepareRenderScene(handle))
        }

        assertTrue("Scene handle must yield at least one batch", batches.isNotEmpty())
        val streamedMesh = com.u1.slicer.viewer.MeshData(
            batches = batches,
            minX = 0f, minY = 0f, minZ = 0f,
            maxX = 100f, maxY = 100f, maxZ = 100f,
            modifierBlockStartTriangle = lib.nativeGetPreviewModifierBlockStart()
        )
        assertNotNull(streamedMesh)

        val legacy = lib.getPreparePreviewMesh()
        assertNotNull(legacy)
        assertEquals(
            legacy!!.trianglePositions.size / 9,
            streamedMesh.vertexCount / 3
        )
        assertEquals(
            legacy!!.extruderIndices.size,
            streamedMesh.vertexCount / 3
        )
    }

    /** Axis-aligned span [spanX, spanY, spanZ] of a preview mesh's vertices (mm). */
    private fun previewSpanXYZ(pos: FloatArray): FloatArray {
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 2 < pos.size) {
            val x = pos[i]; val y = pos[i + 1]; val z = pos[i + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 3
        }
        return floatArrayOf(maxX - minX, maxY - minY, maxZ - minZ)
    }

    /**
     * Dots-regression guard #1 — colored Benchy "holes/dots".
     * The MMU/paint preview must render SOLID: the capped mesh's triangle count must stay
     * close to the full, un-decimated count. B131 (v2.10.2) stride-skipped paint facets
     * (keep every Nth) → only ~1/stride of the surface → scattered triangles/holes. The
     * later QEM attempt also dropped the count (QEM erodes non-manifold patches). Both are
     * caught here: a solid preview keeps essentially all original facets.
     *
     * COUNT is the discriminator the old tests lacked — colour/cap/bounds all passed while
     * the surface was destroyed.
     */
    @Test
    fun getPreparePreviewMesh_paintPreviewIsSolid_notSubsampled() {
        copyAssetToModelFile("colored_3DBenchy (1).3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))
        val full = lib.getPreparePreviewMesh(100_000_000)
        assertNotNull(full); full!!
        val fullCount = full.extruderIndices.size
        assertTrue("colored Benchy must exceed the soft cap (got $fullCount)", fullCount > 100_000)

        // Reload to invalidate the native preview-mesh cache, then request a capped mesh.
        assertTrue(lib.loadModel(modelFile.absolutePath))
        val dec = lib.getPreparePreviewMesh(100_000)
        assertNotNull(dec); dec!!
        val ratio = dec.extruderIndices.size.toDouble() / fullCount
        assertTrue(
            "Paint preview must be SOLID (capped count ≈ full count), got " +
                "dec=${dec.extruderIndices.size} full=$fullCount ratio=$ratio. A low ratio " +
                "means the MMU path subsampled facets → scattered dots/holes.",
            ratio >= 0.9
        )
    }

    /**
     * Dots-regression guard #2 — H2C Benchy "empty plate".
     * The MMU/paint preview mesh must have SANE bounds (~model size). A blown-up AABB means
     * decimation corrupted vertices (QEM edge-collapse on non-manifold per-state patches
     * exploded vertices to ~±30000mm → model rendered off-screen → empty plate).
     */
    @Test
    fun getPreparePreviewMesh_paintPreviewBoundsAreSane_h2cBenchy() {
        copyAssetToModelFile("3DBenchy-H2C-Multi-Color.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))
        val mesh = lib.getPreparePreviewMesh(100_000)
        assertNotNull(mesh); mesh!!
        assertTrue("H2C preview must have triangles", mesh.extruderIndices.isNotEmpty())
        val span = previewSpanXYZ(mesh.trianglePositions)
        // Benchy is ~60×31×48mm. A sane mesh is well under 500mm on every axis; the
        // explosion bug produced ~33000mm spans.
        assertTrue(
            "H2C preview mesh bounds must be sane (model ~60×31×48mm), got span=" +
                "[${span[0]}, ${span[1]}, ${span[2]}]mm — a huge span means decimation " +
                "corrupted vertices (off-screen → empty plate).",
            span[0] < 500f && span[1] < 500f && span[2] < 500f
        )
    }

    @Test
    fun getPreparePreviewMesh_ghostfaceFlatHugePaintedFile_staysCapped() {
        copyAssetToModelFile("GhostfacePokemoncard.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val mesh = lib.getPreparePreviewMesh(100_000)
        assertNotNull(mesh); mesh!!
        assertTrue("Ghostface preview must have triangles", mesh.extruderIndices.isNotEmpty())

        val triCount = mesh.extruderIndices.size
        val span = previewSpanXYZ(mesh.trianglePositions)
        Log.i(
            "NativePreparePreviewTest",
            "Ghostface capped preview triangles=$triCount span=[${span[0]}, ${span[1]}, ${span[2]}]"
        )

        assertTrue(
            "Huge FLAT painted files should remain capped for responsiveness; got $triCount triangles",
            triCount < 200_000
        )
        assertTrue(
            "Ghostface preview bounds must stay sane after capped export, got span=" +
                "[${span[0]}, ${span[1]}, ${span[2]}]",
            span[0] < 500f && span[1] < 500f && span[2] < 500f
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

    @Test
    fun getPreparePreviewMesh_triangleCountRespectsCap_forDualColor3mf() {
        copyAssetToModelFile("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh(maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES)

        assertNotNull(preview)
        preview!!
        val triCount = preview.extruderIndices.size
        assertTrue(
            "Triangle count $triCount should be ≤ MAX_DECIMATED_TRIANGLES (${NativePreviewMesh.MAX_DECIMATED_TRIANGLES})",
            triCount <= NativePreviewMesh.MAX_DECIMATED_TRIANGLES
        )
        assertTrue(preview.trianglePositions.isNotEmpty())
        assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)
    }

    /**
     * B48 regression: H2C benchy (7 model colours → 4 physical extruders) must
     * preserve all 7 native paint state indices in the FULL preview mesh, and after
     * recolor with the expected colorMapping, index 5 triangles must be green.
     */
    @Test
    fun getPreparePreviewMesh_h2cBenchy_fullMesh_allSevenIndicesPresent() {
        copyAssetToModelFile("3DBenchy-H2C-Multi-Color.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()
        assertNotNull("H2C benchy preview mesh must not be null", preview)
        preview!!

        val indexCounts = mutableMapOf<Int, Int>()
        for (b in preview.extruderIndices) {
            val idx = b.toInt() and 0xFF
            indexCounts[idx] = (indexCounts[idx] ?: 0) + 1
        }
        Log.i("NativePreparePreviewTest", "H2C benchy FULL index distribution: $indexCounts")
        assertTrue(
            "Full mesh must have at least 7 distinct indices, got ${indexCounts.keys}",
            indexCounts.keys.size >= 7
        )
    }

    /**
     * B48 Part 2 regression: the DECIMATED preview mesh (matching the app's actual
     * rendering path) must preserve all 7 extruder indices AND produce green after
     * recolor.  The app calls getPreparePreviewMesh(MAX_DECIMATED_TRIANGLES).
     *
     * Red-green TDD: this test targets the actual rendering pipeline path.
     * If indices 4-6 are clamped to 0-3 during decimation, green (index 5)
     * will be missing — exactly matching the on-screen symptom.
     */
    @Test
    fun getPreparePreviewMesh_h2cBenchy_decimated_allSevenIndicesPreserved_andGreenVisible() {
        copyAssetToModelFile("3DBenchy-H2C-Multi-Color.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh(
            maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES
        )
        assertNotNull("Decimated H2C benchy preview must not be null", preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())
        assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)

        val triCount = preview.extruderIndices.size
        Log.i("NativePreparePreviewTest", "H2C benchy decimated triangles=$triCount")
        // MMU meshes emit all triangles interleaved (no decimation)
        // to preserve paint colour accuracy.  Check it's reasonable.
        assertTrue(
            "MMU mesh should have > 0 triangles",
            triCount > 0
        )

        // All 7 paint state indices (0-6) must survive decimation
        val indexCounts = mutableMapOf<Int, Int>()
        for (b in preview.extruderIndices) {
            val idx = b.toInt() and 0xFF
            indexCounts[idx] = (indexCounts[idx] ?: 0) + 1
        }
        Log.i("NativePreparePreviewTest", "H2C benchy DECIMATED index distribution: $indexCounts")
        assertTrue(
            "Decimated mesh must preserve all 7 extruder indices (got ${indexCounts.keys}). " +
                "If indices 4-6 are missing, native decimation is clamping to physical extruder count.",
            indexCounts.keys.size >= 7
        )

        // Index 5 must have enough triangles to be visible
        val index5Count = indexCounts[5] ?: 0
        assertTrue(
            "Decimated mesh index 5 (green) must have >100 triangles, got $index5Count",
            index5Count > 100
        )

        // Recolor and verify green at index 5
        val mesh = preview.toMeshData()
        assertNotNull(mesh)
        mesh!!

        val extruderColors = listOf(
            floatArrayOf(1f, 0f, 0f, 1f),  // slot 0: red
            floatArrayOf(0f, 1f, 0f, 1f),  // slot 1: green
            floatArrayOf(0f, 0f, 1f, 1f),  // slot 2: blue
            floatArrayOf(1f, 1f, 1f, 1f)   // slot 3: white
        )
        val colorMapping = listOf(2, 0, 3, 2, 0, 1, 0)
        val palette = colorMapping.map { slot -> extruderColors[slot] }

        mesh.recolor(palette)

        val indices = mesh.extruderIndices!!
        var greenFound = false
        for (tri in indices.indices) {
            if ((indices[tri].toInt() and 0xFF) == 5) {
                val rOffset = tri * 3 * MeshData.FLOATS_PER_VERTEX + 6
                assertEquals("Index 5 R must be 0 (green)", 0f, mesh.vertices.get(rOffset), 0.01f)
                assertEquals("Index 5 G must be 1 (green)", 1f, mesh.vertices.get(rOffset + 1), 0.01f)
                assertEquals("Index 5 B must be 0 (green)", 0f, mesh.vertices.get(rOffset + 2), 0.01f)
                greenFound = true
                break
            }
        }
        assertTrue("Must find index 5 (green) triangle in decimated mesh", greenFound)
    }

    /**
     * B51: H2C benchy paint state indices must be INTERLEAVED (round-robin) so
     * all 7 colours are proportionally represented in the first portion of the
     * buffer.  With sequential emission, green (index 5) only appears near the
     * end — if GL truncates or the VBO is partially uploaded, green disappears.
     *
     * This test checks that all 7 indices appear within the first 10% of
     * triangles.  Sequential emission fails this; round-robin passes.
     */
    @Test
    fun getPreparePreviewMesh_h2cBenchy_allSevenIndicesInFirstTenPercent() {
        copyAssetToModelFile("3DBenchy-H2C-Multi-Color.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()
        assertNotNull("H2C benchy preview mesh must not be null", preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())

        val totalTris = preview.extruderIndices.size
        val checkCount = maxOf(totalTris / 10, 1000)  // first 10%, min 1000
        val earlyIndices = mutableSetOf<Int>()
        for (i in 0 until minOf(checkCount, totalTris)) {
            earlyIndices.add(preview.extruderIndices[i].toInt() and 0xFF)
        }
        Log.i("NativePreparePreviewTest",
            "H2C benchy interleaving: total=$totalTris checked=$checkCount " +
                "earlyIndices=$earlyIndices")
        assertTrue(
            "First $checkCount of $totalTris triangles must contain all 7 indices " +
                "(round-robin interleaving), got $earlyIndices. " +
                "If <7, emission is sequential — green disappears visually.",
            earlyIndices.size >= 7
        )
    }

    /**
     * B51 regression: old.3mf Prepare preview is tiny and broken into two pieces.
     * The model is a full figure (~232mm tall at 1163 layers × 0.2mm).
     * After volume transform, the bounding box should span reasonable dimensions —
     * not a tiny sliver.
     */
    @Test
    fun getPreparePreviewMesh_old3mf_boundingBoxIsReasonableSize() {
        copyAssetToModelFile("old.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()
        assertNotNull("old.3mf preview mesh must not be null", preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())
        assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)

        val bounds = previewBounds3D(preview)
        val spanX = bounds[1] - bounds[0]
        val spanY = bounds[3] - bounds[2]
        val spanZ = bounds[5] - bounds[4]
        Log.i("NativePreparePreviewTest",
            "old.3mf bounds: X=[${bounds[0]},${bounds[1]}] Y=[${bounds[2]},${bounds[3]}] Z=[${bounds[4]},${bounds[5]}] " +
                "spans: ${spanX} x ${spanY} x ${spanZ}")

        // A full figure model should be at least 30mm in X and Y, and at least 50mm in Z.
        // The broken B48 mesh produces a tiny sliver.
        assertTrue(
            "old.3mf X span should be >= 30mm, got $spanX",
            spanX >= 30f
        )
        assertTrue(
            "old.3mf Y span should be >= 30mm, got $spanY",
            spanY >= 30f
        )
        assertTrue(
            "old.3mf Z span should be >= 50mm, got $spanZ",
            spanZ >= 50f
        )
    }

    /**
     * B72 regression: Prepare preview shows shattered/corrupted mesh after the user
     * scales a model, increases copy count, slices, then returns to the Prepare tab.
     *
     * Root cause: setModelInstances() is called during prepareSlicer() with N grid positions;
     * getPreparePreviewMesh() then returns all N copies baked into world-space coordinates.
     * The GL renderer also applies instancePositions for N copies → N×N corruption.
     *
     * Fix: reset to a single centred instance before fetching the preview mesh.
     * Without the reset, three copies at 40 mm spacing yield spanX ≈ 65 mm+;
     * with the reset, a single 1.5× calicube has spanX ≈ 15 mm.
     */
    @Test
    fun getPreparePreviewMesh_afterMultiInstanceSliceState_singleInstanceResetGivesCorrectBounds() {
        copyAssetToModelFile("calib-cube-10-dual-colour-merged.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        // Simulate prepareSlicer() side-effects: scale 1.5× then 3 instances at grid positions
        lib.setModelScale(1.5f, 1.5f, 1.5f)
        // 3 copies at 40 mm spacing — spans ~65 mm if baked into the preview mesh
        lib.setModelInstances(floatArrayOf(100f, 135f, 140f, 135f, 180f, 135f))

        // B72 fix: reset to a single centred instance before the preview fetch, exactly as
        // the fixed InlineModelPreview LaunchedEffect now does.
        lib.setModelInstances(floatArrayOf(135f, 135f))

        val preview = lib.getPreparePreviewMesh(NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
        assertNotNull("B72: preview must not be null after single-instance reset", preview)
        preview!!

        val bounds = previewBounds3D(preview)
        val spanX = bounds[1] - bounds[0]
        val spanY = bounds[3] - bounds[2]
        Log.i("NativePreparePreviewTest",
            "B72 calicube 1.5× single-instance bounds: X=[${bounds[0]},${bounds[1]}] " +
                "Y=[${bounds[2]},${bounds[3]}] spans: $spanX × $spanY")

        // Single calicube at 1.5× ≈ 15 mm × 15 mm.
        // Three un-reset copies would span ≈ 65 mm in X — well above the 50 mm threshold.
        assertTrue("B72: spanX should be <50 mm (single copy at 1.5×), got $spanX", spanX < 50f)
        assertTrue("B72: spanY should be <50 mm (single copy at 1.5×), got $spanY", spanY < 50f)
    }

    /**
     * B78 regression: Shashibo plate 5 Prepare preview shows the pyramid far too large
     * (~50-60% of the 270mm bed) and centred on bed, instead of the correct ~28% size
     * (77×82mm) in the upper-left quadrant. Reference asset: Shashibo-h2s-textured.3mf,
     * plate 5 "Small - H2D" (object_id=11, build transform scale=0.6).
     *
     * Root cause: the v1.5.65 B73 fix added `setModelScale(1f, 1f, 1f)` +
     * `setModelInstances(floatArrayOf(135f, 135f))` before every `getPreparePreviewMesh`
     * call. The scale reset overwrites the file's natural 0.6 scale (stored in the
     * build transform), so the mesh comes out at raw 128×137mm instead of 77×82mm.
     * The (135, 135) re-centre also shifts world.min to bed centre instead of the
     * plate's original XY.
     *
     * This test simulates the exact InlineModelPreview sequence used on initial load
     * (no prepareSlicer() side-effects yet) and asserts the preview mesh respects
     * the file's natural scale.
     */
    /**
     * Diagnostic companion to the B78 regression test: measures the mesh bounds
     * produced by `getPreparePreviewMesh` IMMEDIATELY after load, with no extra
     * setModelScale / setModelInstances calls.  Establishes the "natural" load-time
     * span that the fix should preserve.
     */
    @Test
    fun getPreparePreviewMesh_shashiboPlate5_naturalLoadBaseline() {
        copyAssetToModelFile("Shashibo-h2s-textured.3mf")
        val originalInfo = ThreeMfParser.parse(modelFile)
        val sanitized = BambuSanitizer.process(modelFile, workDir, isBambu = originalInfo.isBambu)
        val plateObjectIds = originalInfo.plates
            .find { it.plateId == 5 }
            ?.objectIds
            ?.toSet()
        val plateExtruderMap = originalInfo.objectExtruderMap
            .filterKeys { key -> plateObjectIds?.contains(key) == true }
        val rawPlateFile = BambuSanitizer.extractPlate(
            sanitized,
            5,
            workDir,
            hasPlateJsons = originalInfo.hasPlateJsons,
            plateObjectIds = plateObjectIds,
            objectExtruderMap = plateExtruderMap
        )
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, workDir)
        assertTrue(lib.loadModel(plateFile.absolutePath))

        val mi = lib.getModelInfo()
        val offsets = lib.getInstanceOffsets()
        Log.i("NativePreparePreviewTest",
            "B78 baseline model info: sizeX=${mi?.sizeX} sizeY=${mi?.sizeY} sizeZ=${mi?.sizeZ} " +
                "offsets=${offsets.toList()}")

        val preview = lib.getPreparePreviewMesh(NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
        assertNotNull(preview)
        preview!!
        val bounds = previewBounds3D(preview)
        val spanX = bounds[1] - bounds[0]
        val spanY = bounds[3] - bounds[2]
        val spanZ = bounds[5] - bounds[4]
        Log.i("NativePreparePreviewTest",
            "B78 baseline (natural load) bounds: X=[${bounds[0]},${bounds[1]}] " +
                "Y=[${bounds[2]},${bounds[3]}] Z=[${bounds[4]},${bounds[5]}] spans: $spanX × $spanY × $spanZ")
    }

    @Test
    fun getPreparePreviewMesh_shashiboPlate5_preservesFileNaturalScaleAndCentre() {
        copyAssetToModelFile("Shashibo-h2s-textured.3mf")
        val originalInfo = ThreeMfParser.parse(modelFile)
        val sanitized = BambuSanitizer.process(modelFile, workDir, isBambu = originalInfo.isBambu)
        val plateObjectIds = originalInfo.plates
            .find { it.plateId == 5 }
            ?.objectIds
            ?.toSet()
        val plateExtruderMap = originalInfo.objectExtruderMap
            .filterKeys { key -> plateObjectIds?.contains(key) == true }
        val rawPlateFile = BambuSanitizer.extractPlate(
            sanitized,
            5,
            workDir,
            hasPlateJsons = originalInfo.hasPlateJsons,
            plateObjectIds = plateObjectIds,
            objectExtruderMap = plateExtruderMap
        )
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, workDir)

        assertTrue(lib.loadModel(plateFile.absolutePath))

        // Simulate the FIXED InlineModelPreview LaunchedEffect sequence on initial load.
        // When nativeSliceStateDirty is false (no prior prepareSlicer), only setModelRotation
        // is called — the setModelScale(1,1,1) + setModelInstances reset is skipped so the
        // file's natural build-transform scale (0.6) and original plate position survive.
        lib.setModelRotation(0f, 0f, 0f)
        // NOTE: the buggy v1.5.65-v1.5.69 path would have called these two lines here:
        //   lib.setModelScale(1f, 1f, 1f)
        //   lib.setModelInstances(floatArrayOf(135f, 135f))
        // The fix skips them on fresh load.  The regression test below confirms that
        // the natural file state is what getPreparePreviewMesh emits.

        val preview = lib.getPreparePreviewMesh(NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
        assertNotNull("Shashibo plate 5 preview must not be null", preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())

        val bounds = previewBounds3D(preview)
        val spanX = bounds[1] - bounds[0]
        val spanY = bounds[3] - bounds[2]
        val centreX = (bounds[0] + bounds[1]) / 2f
        val centreY = (bounds[2] + bounds[3]) / 2f
        Log.i("NativePreparePreviewTest",
            "B78 Shashibo plate 5 bounds: X=[${bounds[0]},${bounds[1]}] " +
                "Y=[${bounds[2]},${bounds[3]}] spans: $spanX × $spanY centre=($centreX, $centreY)")

        // The 3MF stores plate 5's object with build transform scale = 0.6 (H2D plate).
        // With that scale preserved, the visible mesh fits comfortably under 110mm each axis
        // (actual physical size ≈ 100×87 mm after BambuSanitizer recentring).
        // Pre-fix (setModelScale(1,1,1) wipes 0.6): spanX/Y ≈ 167×145 mm.
        assertTrue(
            "B78: plate 5 X span should be < 110mm (file scale 0.6 preserved), got $spanX",
            spanX < 110f
        )
        assertTrue(
            "B78: plate 5 Y span should be < 110mm (file scale 0.6 preserved), got $spanY",
            spanY < 110f
        )
        // Natural load positions the mesh bbox centred on the plate-local (135, 135) origin
        // (BambuSanitizer.recenterGroupIfVirtual).  Pre-fix forced mesh lower-left to
        // (135, 135) instead — bbox centre sat >=40mm away from bed centre.
        assertTrue(
            "B78: plate 5 bbox centre X should be within 20mm of bed centre (135mm), got $centreX",
            kotlin.math.abs(centreX - 135f) < 20f
        )
        assertTrue(
            "B78: plate 5 bbox centre Y should be within 20mm of bed centre (135mm), got $centreY",
            kotlin.math.abs(centreY - 135f) < 20f
        )
    }

    /**
     * B78 companion: exercises the dirty-state path explicitly.  After a prepareSlicer()
     * style sequence (user scale + multi-copy setModelInstances), the fixed InlineModelPreview
     * sequence MUST restore a single instance at the load-time offset so the Prepare preview
     * doesn't show N shattered copies (B72) and the mesh does not double-scale (B73).
     */
    @Test
    fun getPreparePreviewMesh_shashiboPlate5_afterSliceState_restoresSingleInstance() {
        copyAssetToModelFile("Shashibo-h2s-textured.3mf")
        val originalInfo = ThreeMfParser.parse(modelFile)
        val sanitized = BambuSanitizer.process(modelFile, workDir, isBambu = originalInfo.isBambu)
        val plateObjectIds = originalInfo.plates
            .find { it.plateId == 5 }
            ?.objectIds
            ?.toSet()
        val plateExtruderMap = originalInfo.objectExtruderMap
            .filterKeys { key -> plateObjectIds?.contains(key) == true }
        val rawPlateFile = BambuSanitizer.extractPlate(
            sanitized, 5, workDir,
            hasPlateJsons = originalInfo.hasPlateJsons,
            plateObjectIds = plateObjectIds,
            objectExtruderMap = plateExtruderMap
        )
        val plateFile = BambuSanitizer.restructurePlateFile(rawPlateFile, workDir)

        assertTrue(lib.loadModel(plateFile.absolutePath))

        // Snapshot load-time offsets, exactly as SlicerViewModel does post-loadModel.
        val loadTimeOffsets = lib.getInstanceOffsets()
        Log.i("NativePreparePreviewTest", "B78 post-slice: load-time offsets=${loadTimeOffsets.toList()}")

        // Simulate prepareSlicer() side-effects with user scale + multi-copy grid.
        lib.setModelScale(1.5f, 1.5f, 1.5f)
        lib.setModelInstances(floatArrayOf(80f, 80f, 190f, 80f, 80f, 190f, 190f, 190f))

        // Now simulate the fixed InlineModelPreview dirty-path reset.
        lib.setModelRotation(0f, 0f, 0f)
        lib.setModelScale(1f, 1f, 1f)
        lib.setModelInstances(loadTimeOffsets)

        val preview = lib.getPreparePreviewMesh(NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
        assertNotNull(preview)
        preview!!
        val bounds = previewBounds3D(preview)
        val spanX = bounds[1] - bounds[0]
        val spanY = bounds[3] - bounds[2]
        Log.i("NativePreparePreviewTest",
            "B78 post-slice Shashibo plate 5 bounds: X=[${bounds[0]},${bounds[1]}] " +
                "Y=[${bounds[2]},${bounds[3]}] spans: $spanX × $spanY")

        // After dirty-path reset, mesh is a single copy; the 1.5× user scale was undone by
        // setModelScale(1,1,1). Span should be well under 200mm (multi-copy grid would be >200mm).
        assertTrue("B78 post-slice: spanX should be < 200mm (single copy), got $spanX", spanX < 200f)
        assertTrue("B78 post-slice: spanY should be < 200mm (single copy), got $spanY", spanY < 200f)
    }

    /**
     * B51 regression: Korok mask preview shows wrong orientation (standing upright
     * instead of lying flat on the bed).  The mask is a flat object — its Z-extent
     * should be much smaller than its XY footprint.  G-code shows 18 layers ≈ 3.6mm.
     */
    @Test
    fun getPreparePreviewMesh_korokMask_orientationIsFlat() {
        copyAssetToModelFile("PrusaSlicer-printables-Korok_mask_4colour.3mf")
        assertTrue(lib.loadModel(modelFile.absolutePath))

        val preview = lib.getPreparePreviewMesh()
        assertNotNull("Korok mask preview mesh must not be null", preview)
        preview!!
        assertTrue(preview.trianglePositions.isNotEmpty())
        assertTrue(preview.extruderIndices.size * 9 == preview.trianglePositions.size)

        val bounds = previewBounds3D(preview)
        val spanX = bounds[1] - bounds[0]
        val spanY = bounds[3] - bounds[2]
        val spanZ = bounds[5] - bounds[4]
        Log.i("NativePreparePreviewTest",
            "Korok mask bounds: X=[${bounds[0]},${bounds[1]}] Y=[${bounds[2]},${bounds[3]}] Z=[${bounds[4]},${bounds[5]}] " +
                "spans: ${spanX} x ${spanY} x ${spanZ}")

        // Korok mask is flat: Z should be < 20mm, and XY footprint should be > 50mm each.
        // If standing upright, Z would be >> XY — the opposite of correct orientation.
        assertTrue(
            "Korok mask Z span should be < 20mm (flat), got $spanZ",
            spanZ < 20f
        )
        assertTrue(
            "Korok mask X span should be >= 50mm, got $spanX",
            spanX >= 50f
        )
        assertTrue(
            "Korok mask Y span should be >= 50mm, got $spanY",
            spanY >= 50f
        )
    }
}

private val com.u1.slicer.viewer.MeshData.vertices: FloatArray get() {
    val buf = batches[0].geometry.duplicate()
    val arr = FloatArray(buf.capacity())
    buf.get(arr)
    return arr
}
private val com.u1.slicer.viewer.MeshData.extruderIndices: ByteArray get() {
    val buf = batches[0].materialIndices!!.duplicate()
    val arr = ByteArray(buf.capacity())
    buf.get(arr)
    return arr
}

