package com.u1.slicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.ExtruderPreset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reproduces DC15's reported v1.6.10 issues for triage — not a pass/fail assertion,
 * a diagnostic dump.
 *
 * DC15's device state (from Discord screenshots):
 *   E1 = PINK  (#FF69B4 used as proxy; true hex unknown)
 *   E2 = BLUE  (#0000FF)
 *   E3 = WHITE (#FFFFFF)
 *   E4 = BLACK (#000000 — inferred from plate 9 Prepare Setup "E4 ●")
 *
 * Plate 8 report: Slice summary shows E1 (pink) for the painted stripes (correct),
 * but the G-code 3D viewer renders the stripes BLUE (E2's loaded filament).
 *
 * Plate 9 report: Prepare shows 2 distinct colours (peach+white painted, per our
 * B90 investigation); after slicing only ONE extruder appears in the summary (E3)
 * and the 3D viewer renders the whole model ORANGE (GcodeRenderer's default slot 0
 * colour when no override fires).
 *
 * This test dumps everything needed to distinguish:
 *   - Is semmColorPermutation actually being set? (would land in G-code remap)
 *   - Is slicerColorOrder being computed for DC15's preset state?
 *   - Which T-indices are actually present in the sliced G-code?
 *   - What does normalizeGcodePreviewColors output for this state?
 */
@RunWith(AndroidJUnit4::class)
class DC15BuzzRepro {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun copyAssetToCache(assetName: String): File {
        val outFile = File(targetContext.cacheDir, assetName.replace("/", "_"))
        assetContext.assets.open(assetName).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private fun waitUntil(label: String, timeoutMs: Long = 60_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    @Test
    fun dc15_buzzPlate8_diagnosticDump() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val settingsRepo = application.container.settingsRepository
        val viewModel = SlicerViewModel(application)

        // Set DC15-like presets.
        runBlocking {
            settingsRepo.saveExtruderPresets(listOf(
                ExtruderPreset(index = 0, color = "#FF69B4"),  // E1 PINK
                ExtruderPreset(index = 1, color = "#0000FF"),  // E2 BLUE
                ExtruderPreset(index = 2, color = "#FFFFFF"),  // E3 WHITE
                ExtruderPreset(index = 3, color = "#000000"),  // E4 BLACK
            ))
        }
        // Give DataStore a moment to propagate.
        Thread.sleep(1_000)

        val modelFile = copyAssetToCache("Buzz_Multipart_3MF_Bambu.3mf")
        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("buzz plate selector visible", timeoutMs = 240_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            (viewModel.state.value as? SlicerViewModel.SlicerState.Error)?.let {
                throw AssertionError("Buzz load failed: ${it.message}")
            }

            viewModel.selectPlate(8)
            waitUntil("plate 8 loaded", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }
            Thread.sleep(600)

            val info = viewModel.threeMfInfo.value!!
            val colorMapping = viewModel.colorMapping.value!!
            val extruderColors = viewModel.activeExtruderColors.value.toList()
            val slicerColorOrder = viewModel.slicerColorOrder.value
            val semmColorPermutation = viewModel.semmColorPermutationFlow.value

            android.util.Log.i("DC15Repro",
                "PLATE 8 STATE\n" +
                "  detectedColors=${info.detectedColors}\n" +
                "  usedExtruderIndices=${info.usedExtruderIndices}\n" +
                "  objectExtruderMap=${info.objectExtruderMap}\n" +
                "  colorMapping=$colorMapping\n" +
                "  activeExtruderColors=$extruderColors\n" +
                "  slicerColorOrder=$slicerColorOrder\n" +
                "  semmColorPermutation=$semmColorPermutation"
            )

            viewModel.startSlicing()
            waitUntil("slice complete", timeoutMs = 300_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            (viewModel.state.value as? SlicerViewModel.SlicerState.Error)?.let {
                throw AssertionError("Slice failed: ${it.message}")
            }

            val result = (viewModel.state.value as SlicerViewModel.SlicerState.SliceComplete).result
            val gcode = File(result.gcodePath).readText()
            val lines = gcode.lines()
            val toolCounts = (0..3).map { t -> lines.count { it.trim() == "T$t" } }
            val filamentColourHeader = lines.firstOrNull { it.startsWith("; filament_colour") }

            val previewColors = normalizeGcodePreviewColors(
                extruderColors = extruderColors,
                colorMapping = colorMapping,
                semmColorPermutation = semmColorPermutation,
                slicerColorOrder = slicerColorOrder
            )

            android.util.Log.i("DC15Repro",
                "PLATE 8 G-CODE + PREVIEW\n" +
                "  T0=${toolCounts[0]} T1=${toolCounts[1]} T2=${toolCounts[2]} T3=${toolCounts[3]}\n" +
                "  filament_colour_header=$filamentColourHeader\n" +
                "  previewColors=$previewColors\n" +
                "  => T0 renders=${previewColors.getOrNull(0)}\n" +
                "  => T1 renders=${previewColors.getOrNull(1)}\n" +
                "  => T2 renders=${previewColors.getOrNull(2)}\n" +
                "  => T3 renders=${previewColors.getOrNull(3)}"
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
            // Restore default presets so other tests aren't polluted.
            runBlocking {
                settingsRepo.saveExtruderPresets(com.u1.slicer.data.defaultExtruderPresets())
            }
        }
    }

    /**
     * Force DC15's exact colorMapping = [0, 2] (Color 1 brown → E1 PINK,
     * Color 2 white → E3 WHITE) via applyMultiColorAssignments, bypassing
     * auto-match. This matches DC15's v1.6.10 screenshot exactly.
     */
    @Test
    fun dc15_buzzPlate8_forcedMappingDump() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val settingsRepo = application.container.settingsRepository
        val viewModel = SlicerViewModel(application)

        runBlocking {
            settingsRepo.saveExtruderPresets(listOf(
                ExtruderPreset(index = 0, color = "#FF69B4"),
                ExtruderPreset(index = 1, color = "#0000FF"),
                ExtruderPreset(index = 2, color = "#FFFFFF"),
                ExtruderPreset(index = 3, color = "#000000"),
            ))
        }
        Thread.sleep(1_000)

        val modelFile = copyAssetToCache("Buzz_Multipart_3MF_Bambu.3mf")
        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("buzz plate selector visible", timeoutMs = 240_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            (viewModel.state.value as? SlicerViewModel.SlicerState.Error)?.let {
                throw AssertionError("Buzz load failed: ${it.message}")
            }

            viewModel.selectPlate(8)
            waitUntil("plate 8 loaded", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }
            Thread.sleep(600)

            // Force DC15's mapping: Color 1 (brown) → E1 PINK (slot 0), Color 2 (white) → E3 WHITE (slot 2)
            val dc15Mapping = listOf(0, 2)
            val presets = viewModel.extruderPresets.value
            val filaments = viewModel.filaments.value
            viewModel.applyMultiColorAssignments(dc15Mapping, presets, filaments)
            Thread.sleep(1_000)

            val info = viewModel.threeMfInfo.value!!
            val colorMapping = viewModel.colorMapping.value!!
            val extruderColors = viewModel.activeExtruderColors.value.toList()
            val slicerColorOrder = viewModel.slicerColorOrder.value
            val semmColorPermutation = viewModel.semmColorPermutationFlow.value

            android.util.Log.i("DC15Repro",
                "PLATE 8 FORCED-MAPPING STATE\n" +
                "  detectedColors=${info.detectedColors}\n" +
                "  usedExtruderIndices=${info.usedExtruderIndices}\n" +
                "  objectExtruderMap=${info.objectExtruderMap}\n" +
                "  colorMapping=$colorMapping\n" +
                "  activeExtruderColors=$extruderColors\n" +
                "  slicerColorOrder=$slicerColorOrder\n" +
                "  semmColorPermutation=$semmColorPermutation"
            )

            viewModel.startSlicing()
            waitUntil("slice complete", timeoutMs = 300_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            (viewModel.state.value as? SlicerViewModel.SlicerState.Error)?.let {
                throw AssertionError("Slice failed: ${it.message}")
            }

            val result = (viewModel.state.value as SlicerViewModel.SlicerState.SliceComplete).result
            val gcode = File(result.gcodePath).readText()
            val lines = gcode.lines()
            val toolCounts = (0..3).map { t -> lines.count { it.trim() == "T$t" } }
            val filamentColourHeader = lines.firstOrNull { it.startsWith("; filament_colour") }

            val previewColors = normalizeGcodePreviewColors(
                extruderColors = extruderColors,
                colorMapping = colorMapping,
                semmColorPermutation = semmColorPermutation,
                slicerColorOrder = slicerColorOrder
            )

            android.util.Log.i("DC15Repro",
                "PLATE 8 FORCED-MAPPING G-CODE + PREVIEW\n" +
                "  T0=${toolCounts[0]} T1=${toolCounts[1]} T2=${toolCounts[2]} T3=${toolCounts[3]}\n" +
                "  filament_colour_header=$filamentColourHeader\n" +
                "  previewColors=$previewColors\n" +
                "  => T0 renders=${previewColors.getOrNull(0)}\n" +
                "  => T1 renders=${previewColors.getOrNull(1)}\n" +
                "  => T2 renders=${previewColors.getOrNull(2)}\n" +
                "  => T3 renders=${previewColors.getOrNull(3)}"
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
            runBlocking {
                settingsRepo.saveExtruderPresets(com.u1.slicer.data.defaultExtruderPresets())
            }
        }
    }

    @Test
    fun dc15_buzzPlate9_diagnosticDump() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val settingsRepo = application.container.settingsRepository
        val viewModel = SlicerViewModel(application)

        runBlocking {
            settingsRepo.saveExtruderPresets(listOf(
                ExtruderPreset(index = 0, color = "#FF69B4"),
                ExtruderPreset(index = 1, color = "#0000FF"),
                ExtruderPreset(index = 2, color = "#FFFFFF"),
                ExtruderPreset(index = 3, color = "#000000"),
            ))
        }
        Thread.sleep(1_000)

        val modelFile = copyAssetToCache("Buzz_Multipart_3MF_Bambu.3mf")
        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("buzz plate selector visible", timeoutMs = 240_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            (viewModel.state.value as? SlicerViewModel.SlicerState.Error)?.let {
                throw AssertionError("Buzz load failed: ${it.message}")
            }

            viewModel.selectPlate(9)
            waitUntil("plate 9 loaded", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }
            Thread.sleep(600)

            val info = viewModel.threeMfInfo.value!!
            val colorMapping = viewModel.colorMapping.value!!
            val extruderColors = viewModel.activeExtruderColors.value.toList()
            val slicerColorOrder = viewModel.slicerColorOrder.value
            val semmColorPermutation = viewModel.semmColorPermutationFlow.value

            android.util.Log.i("DC15Repro",
                "PLATE 9 STATE\n" +
                "  detectedColors=${info.detectedColors}\n" +
                "  usedExtruderIndices=${info.usedExtruderIndices}\n" +
                "  objectExtruderMap=${info.objectExtruderMap}\n" +
                "  colorMapping=$colorMapping\n" +
                "  activeExtruderColors=$extruderColors\n" +
                "  slicerColorOrder=$slicerColorOrder\n" +
                "  semmColorPermutation=$semmColorPermutation"
            )

            viewModel.startSlicing()
            waitUntil("slice complete", timeoutMs = 600_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            (viewModel.state.value as? SlicerViewModel.SlicerState.Error)?.let {
                throw AssertionError("Slice failed: ${it.message}")
            }

            val result = (viewModel.state.value as SlicerViewModel.SlicerState.SliceComplete).result
            val gcode = File(result.gcodePath).readText()
            val lines = gcode.lines()
            val toolCounts = (0..3).map { t -> lines.count { it.trim() == "T$t" } }
            val filamentColourHeader = lines.firstOrNull { it.startsWith("; filament_colour") }

            val previewColors = normalizeGcodePreviewColors(
                extruderColors = extruderColors,
                colorMapping = colorMapping,
                semmColorPermutation = semmColorPermutation,
                slicerColorOrder = slicerColorOrder
            )

            android.util.Log.i("DC15Repro",
                "PLATE 9 G-CODE + PREVIEW\n" +
                "  T0=${toolCounts[0]} T1=${toolCounts[1]} T2=${toolCounts[2]} T3=${toolCounts[3]}\n" +
                "  filament_colour_header=$filamentColourHeader\n" +
                "  previewColors=$previewColors"
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
            runBlocking {
                settingsRepo.saveExtruderPresets(com.u1.slicer.data.defaultExtruderPresets())
            }
        }
    }
}
