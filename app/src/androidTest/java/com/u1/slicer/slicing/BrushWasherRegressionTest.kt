package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrushWasherRegressionTest {
    @Test fun projectWithUnusedAsaLoadsAndSlicesWithoutChamberHeating() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as U1SlicerApplication
        val original = withTimeout(15_000) { app.container.printersRepository.config.first { it != null } }!!
        val printer = Printer("brush-washer-fixture", "Brush washer fixture")
        app.container.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
        val vm = SlicerViewModel(app)
        val file = File(app.cacheDir, "brush-washer_v2.3mf")
        try {
            withTimeout(15_000) { vm.effectiveSliceTarget.first { it == SlicerTarget.SnapmakerU1 } }
            instrumentation.context.assets.open("regression/brush-washer_v2.3mf").use { input -> file.outputStream().use(input::copyTo) }
            vm.loadModelFromFile(file)
            withTimeout(60_000) { vm.showPlateSelector.first { it } }
            vm.selectPlate(1)
            val loaded = withTimeout(120_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue(loaded.toString(), loaded is SlicerViewModel.SlicerState.ModelLoaded)
            vm.startSlicing()
            val sliced = withTimeout(240_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue(sliced.toString(), sliced is SlicerViewModel.SlicerState.SliceComplete)
            val gcode = File((sliced as SlicerViewModel.SlicerState.SliceComplete).result.gcodePath).readText()
            assertFalse(Regex("(?m)^M(?:141|191)\\s+S65(?:\\s|$)").containsMatchIn(gcode))
            assertTrue(gcode.contains("PRINT_START"))
        } finally {
            vm.clearModel()
            app.container.printersRepository.replace(original)
            file.delete()
        }
    }
}
