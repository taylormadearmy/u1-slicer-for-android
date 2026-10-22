package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual preset -> ViewModel -> native path without contacting a printer. */
@RunWith(AndroidJUnit4::class)
class GenericPrinterSliceViewModelTest {
    @Test fun selectedLibraryFilamentControlsHeatingAndFirstLayerHeaders() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as U1SlicerApplication
        val container = application.container
        val original = withTimeout(15_000) { container.printersRepository.config.first { it != null } }!!
        val filament = FilamentProfile(name = "Fixture PETG", material = "PETG", nozzleTemp = 245, bedTemp = 80,
            retractLength = 0.8f, retractSpeed = 30f, nozzleTempInitialLayer = 250)
        val profileId = container.filamentDao.insert(filament)
        val machine = GenericMachineProfile.fromJson(JSONObject(GenericMachineProfile.centauriCarbon().resolvedJson).apply {
            put("machine_start_gcode", "G28\nM190 S[bed_temperature_initial_layer_single]\nM109 S[nozzle_temperature_initial_layer]")
            put("machine_end_gcode", "M104 S0\nM140 S0")
        }.toString())
        val printer = Printer("fixture-generic", "Fixture Klipper", machine = MachineKind.GENERIC_KLIPPER,
            machineProfile = machine, extruderPresets = listOf(ExtruderPreset(0, "#123456", "PETG", profileId)))
        container.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
        val viewModel = SlicerViewModel(application)
        val model = File(application.cacheDir, "generic-viewmodel-tetrahedron.stl")
        try {
            withTimeout(15_000) {
                viewModel.effectiveSliceTarget.first { it == SlicerTarget.GenericKlipper }
                viewModel.extruderPresets.first { it.firstOrNull()?.filamentProfileId == profileId }
                viewModel.filaments.first { rows -> rows.any { it.id == profileId } }
            }
            instrumentation.context.assets.open("tetrahedron.stl").use { input -> model.outputStream().use(input::copyTo) }
            viewModel.loadModelFromFile(model)
            val loaded = withTimeout(60_000) { viewModel.state.first {
                it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue("Model load failed: $loaded", loaded is SlicerViewModel.SlicerState.ModelLoaded)
            viewModel.startSlicing()
            val sliced = withTimeout(90_000) { viewModel.state.first {
                it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue("Slice failed: $sliced", sliced is SlicerViewModel.SlicerState.SliceComplete)
            val gcode = File((sliced as SlicerViewModel.SlicerState.SliceComplete).result.gcodePath).readText()
            assertTrue(gcode.lineSequence().any { it.startsWith("M109 S250") })
            assertTrue(gcode.lineSequence().any { it.startsWith("M190 S80") })
            assertTrue(gcode.contains("; nozzle_temperature = 245"))
            assertTrue(gcode.contains("; nozzle_temperature_initial_layer = 250"))
            assertTrue(gcode.contains("; filament_type = PETG"))
        } finally {
            viewModel.clearModel()
            model.delete()
            container.printersRepository.replace(original)
            container.filamentDao.delete(filament.copy(id = profileId))
        }
    }
}
