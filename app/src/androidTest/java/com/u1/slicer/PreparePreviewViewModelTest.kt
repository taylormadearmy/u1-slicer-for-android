package com.u1.slicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PreparePreviewViewModelTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun dragonPlate3_selectPlate_keepsThreeVisiblePrepareColours_andSliceOutput() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("Dragon Scale infinity.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("plate selector visible") {
                viewModel.showPlateSelector.value
            }

            viewModel.selectPlate(3)

            waitUntil("plate 3 loaded with color mapping", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            val info = viewModel.threeMfInfo.value
            val mapping = viewModel.colorMapping.value

            assertNotNull("Plate 3 info should be available after selection", info)
            assertNotNull("Plate 3 color mapping should be available after selection", mapping)

            info!!
            mapping!!

            assertTrue(
                "Dragon plate 3 should keep at least 3 detected colors in Prepare state, got ${info.detectedColors}",
                info.detectedColors.size >= 3
            )
            assertTrue(
                "Dragon plate 3 should keep at least 3 visible slots in Prepare state, got $mapping",
                mapping.distinct().size >= 3
            )
            assertTrue(
                "Expected at least 3 non-blank active extruder colors, got ${viewModel.activeExtruderColors.value}",
                viewModel.activeExtruderColors.value.count { it.isNotBlank() } >= 3
            )

            val preview = NativeLibrary().getPreparePreviewMesh()
            assertNotNull("Native prepare preview mesh should be available after Dragon plate 3 load", preview)
            preview!!
            val distinctIndices = preview.extruderIndices.map { it.toInt() and 0xFF }.toSet().sorted()
            assertTrue(
                "Dragon plate 3 native preview indices should preserve at least 3 colors, got $distinctIndices",
                distinctIndices.size >= 3
            )
            assertTrue(
                "Dragon plate 3 native preview indices must be compact 0..N-1 for Android recolor, got $distinctIndices",
                distinctIndices == distinctIndices.indices.toList()
            )

            viewModel.startSlicing()

            waitUntil("dragon plate 3 slice complete", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete
            }

            val state = viewModel.state.value as SlicerViewModel.SlicerState.SliceComplete
            val gcode = File(state.result.gcodePath).readText()
            val toolChangeRegex = Regex("""(?m)^T([0-3])\b""")
            val toolLines = toolChangeRegex.findAll(gcode).map { it.groupValues[1].toInt() }.toList()
            val usedTools = toolLines.toSet().size

            assertTrue(
                "Dragon plate 3 slice should keep at least 3 extruders/tools in output, got $usedTools",
                usedTools >= 3
            )
            assertTrue(
                "Dragon plate 3 slice should contain explicit tool change commands, got ${toolLines.take(12)}",
                toolLines.isNotEmpty()
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    @Test
    fun slipSlidePlate3_selectPlate_keepsFourVisiblePrepareColours() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("slip slide spin fidget.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("plate selector visible") {
                viewModel.showPlateSelector.value
            }

            viewModel.selectPlate(3)

            waitUntil("slip slide plate 3 loaded with color mapping", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            val info = viewModel.threeMfInfo.value
            val mapping = viewModel.colorMapping.value

            assertNotNull("Slip/slide plate 3 info should be available", info)
            assertNotNull("Slip/slide plate 3 color mapping should be available", mapping)

            info!!
            mapping!!

            assertTrue(
                "Slip/slide plate 3 should keep at least 4 detected colours in Prepare state, got ${info.detectedColors}",
                info.detectedColors.size >= 4
            )
            assertTrue(
                "Slip/slide plate 3 should keep at least 4 visible slots in Prepare state, got $mapping",
                mapping.distinct().size >= 4
            )
            assertTrue(
                "Slip/slide plate 3 should expose at least 4 non-blank active extruder colours, got ${viewModel.activeExtruderColors.value}",
                viewModel.activeExtruderColors.value.count { it.isNotBlank() } >= 4
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    @Test
    fun flippy_layerToolOnly_hasSegments_andRecolorByZBandsProducesMultipleColours() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("flippy+flappy+mini.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("flippy plate selector visible") {
                viewModel.showPlateSelector.value
            }

            viewModel.selectPlate(4)

            waitUntil("flippy loaded with layer-tool mapping", timeoutMs = 60_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.layerToolOnly.value
            }

            val info = viewModel.threeMfInfo.value
            assertNotNull("threeMfInfo should be non-null after flippy load", info)
            info!!

            assertTrue("flippy should have hasLayerToolChanges=true", info.hasLayerToolChanges)
            assertNotNull("flippy layerToolSegments should survive merge into threeMfInfo", info.layerToolSegments)
            assertTrue(
                "flippy layerToolSegments should be non-empty, got ${info.layerToolSegments?.size}",
                (info.layerToolSegments?.size ?: 0) >= 2
            )

            assertTrue("layerToolOnly StateFlow should be true for flippy", viewModel.layerToolOnly.value)

            val mapping = viewModel.colorMapping.value
            val colors = viewModel.activeExtruderColors.value
            assertNotNull("colorMapping should be non-null for flippy", mapping)
            assertTrue("activeExtruderColors should be non-empty for flippy", colors.isNotEmpty())

            // Verify recolorByZBands would produce distinct colours on the native preview
            val lib = NativeLibrary()
            assertTrue(lib.loadModel(modelFile.absolutePath))
            val preview = lib.getPreparePreviewMesh()
            assertNotNull("Native preview mesh should be available for flippy", preview)
            preview!!
            val meshData = preview.toMeshData()
            assertNotNull("MeshData conversion should succeed", meshData)
            meshData!!

            // Build a deterministic 2-colour palette: extruder 1 (base) = red, extruder 2 = green
            // so we can assert distinct colours regardless of user filament presets.
            val testPalette = listOf(
                floatArrayOf(1f, 0f, 0f, 1f),  // palette[0]: red (extruderBambu=1, base)
                floatArrayOf(0f, 1f, 0f, 1f)   // palette[1]: green (extruderBambu=2)
            )

            meshData.recolorByZBands(info.layerToolSegments!!, testPalette)

            val buf = meshData.vertices
            val triCount = meshData.vertexCount / 3
            // Check G channel: red has G=0, green has G=1 — should see both
            val gValues = mutableSetOf<Float>()
            for (tri in 0 until triCount) {
                val gOffset = tri * 3 * 10 + 7  // G is at offset 7 per vertex
                gValues.add(buf.get(gOffset))
                if (gValues.size >= 2) break
            }
            assertTrue(
                "recolorByZBands through full ViewModel path should produce at least 2 distinct G values for flippy (red base + green band), got $gValues",
                gValues.size >= 2
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    private fun copyAssetToCache(assetName: String): File {
        val outFile = File(targetContext.cacheDir, assetName.replace("/", "_"))
        assetContext.assets.open(assetName).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }

    /**
     * B48 regression: H2C benchy (7 model colours → 4 physical extruders) must
     * produce green in both the Prepare preview vertex buffer AND the sliced G-code.
     *
     * This test exercises the full ViewModel flow: load → auto color mapping →
     * preview mesh → recolor using the REAL colorMapping + activeExtruderColors
     * (not hardcoded test values) → verify green RGBA in vertex buffer → slice →
     * verify T1 > 0 in G-code.
     *
     * Red-green TDD: catches regressions in the mapping pipeline that data-layer
     * tests (NativePreparePreviewTest, SemmSlicingTest) would miss.
     */
    @Test
    fun h2cBenchy_fullPipeline_greenVisibleInPreview_andAllToolsInGcode() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("3DBenchy-H2C-Multi-Color.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("H2C benchy loaded with color mapping", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            val info = viewModel.threeMfInfo.value
            val mapping = viewModel.colorMapping.value
            val colors = viewModel.activeExtruderColors.value

            assertNotNull("threeMfInfo should be non-null after H2C benchy load", info)
            assertNotNull("colorMapping should be non-null after H2C benchy load", mapping)
            info!!
            mapping!!

            assertTrue("H2C benchy must have hasPaintData=true", info.hasPaintData)
            assertTrue(
                "H2C benchy must detect 7 model colours, got ${info.detectedColors.size}",
                info.detectedColors.size >= 7
            )
            assertTrue(
                "H2C benchy colorMapping must have 7 entries, got ${mapping.size}",
                mapping.size >= 7
            )
            // Slot 1 (green) must appear in the mapping
            assertTrue(
                "colorMapping must include slot 1 (green), got distinct=${mapping.distinct()}",
                mapping.contains(1)
            )
            // activeExtruderColors must include green at slot 1
            assertTrue(
                "activeExtruderColors[1] must be non-blank (green), got $colors",
                colors.size > 1 && colors[1].isNotBlank()
            )

            // Get native preview mesh and recolor using the REAL mapping + colors
            val preview = NativeLibrary().getPreparePreviewMesh()
            assertNotNull("Native preview mesh should be available for H2C benchy", preview)
            preview!!
            val mesh = preview.toMeshData()
            assertNotNull("MeshData conversion should succeed", mesh)
            mesh!!

            // Build palette using the same logic as MainActivity's InlineModelPreview
            val palette = mapping.map { slot ->
                SlicerViewModel.staticHexColorToFloatArray(colors.getOrElse(slot) { "" })
            }
            assertTrue("Palette must have 7 entries (one per model colour)", palette.size >= 7)

            mesh.recolor(palette)

            // Find green: any triangle with index 5 should have G ≈ 1.0
            val indices = mesh.extruderIndices!!
            var greenFound = false
            for (tri in indices.indices) {
                val idx = indices[tri].toInt() and 0xFF
                if (idx == 5) {
                    val gOffset = tri * 3 * com.u1.slicer.viewer.MeshData.FLOATS_PER_VERTEX + 7
                    val g = mesh.vertices.get(gOffset)
                    if (g > 0.9f) {
                        greenFound = true
                        break
                    }
                }
            }
            assertTrue(
                "H2C benchy Prepare preview must contain green (G>0.9) at index 5 triangles " +
                    "after recolor with real colorMapping=$mapping, colors=$colors",
                greenFound
            )

            // Now slice and verify T1 > 0
            viewModel.startSlicing()

            waitUntil("H2C benchy slice complete", timeoutMs = 180_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete
            }

            val state = viewModel.state.value as SlicerViewModel.SlicerState.SliceComplete
            val gcode = java.io.File(state.result.gcodePath).readText()
            val t1Count = gcode.lines().count { it.trim() == "T1" }
            assertTrue(
                "H2C benchy G-code must have T1 tool changes (green), got $t1Count",
                t1Count > 0
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    private fun waitUntil(label: String, timeoutMs: Long = 30_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label")
    }
}
