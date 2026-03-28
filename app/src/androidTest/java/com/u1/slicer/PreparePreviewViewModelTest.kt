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

    private fun copyAssetToCache(assetName: String): File {
        val outFile = File(targetContext.cacheDir, assetName.replace("/", "_"))
        assetContext.assets.open(assetName).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
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
