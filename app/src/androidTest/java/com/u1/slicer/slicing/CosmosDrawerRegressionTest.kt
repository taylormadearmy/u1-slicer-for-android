package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.slice.CosmosMachineProfile
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CosmosDrawerRegressionTest {
    @Test fun drawerPlateFiveLoadsAndSlicesWithOnlyTheCosmosTool() = verifyPlate(5, true)

    @Test fun drawerPlateFourStillRejectsUnavailableTools() = verifyPlate(4, false)

    private fun verifyPlate(plateId: Int, shouldSlice: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as U1SlicerApplication
        val original = withTimeout(15_000) { app.container.printersRepository.config.first { it != null } }!!
        val printer = Printer("drawer-cosmos", "COSMOS regression", machine = MachineKind.GENERIC_KLIPPER,
            machineProfile = CosmosMachineProfile.profile)
        app.container.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
        val vm = SlicerViewModel(app)
        val file = File(app.cacheDir, "4x4_Drawer_ALL.3mf")
        try {
            withTimeout(15_000) { vm.effectiveSliceTarget.first { it == SlicerTarget.GenericKlipper } }
            instrumentation.context.assets.open("regression/4x4_Drawer_ALL.3mf").use { input -> file.outputStream().use(input::copyTo) }
            val parsed = ThreeMfParser.parse(file)
            assertEquals(setOf(1, 2), parsed.usedExtruderIndices)
            assertEquals(listOf("6"), parsed.plates.single { it.plateId == 5 }.objectIds)
            vm.loadModelFromFile(file)
            withTimeout(90_000) { vm.showPlateSelector.first { it } }
            vm.selectPlate(plateId)
            val loaded = withTimeout(120_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error
            } }
            if (!shouldSlice) {
                assertTrue(loaded.toString(), loaded is SlicerViewModel.SlicerState.Error)
                assertTrue((loaded as SlicerViewModel.SlicerState.Error).message.contains("tools not available"))
                return@runBlocking
            }
            assertTrue(loaded.toString(), loaded is SlicerViewModel.SlicerState.ModelLoaded)
            vm.startSlicing()
            val sliced = withTimeout(240_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue(sliced.toString(), sliced is SlicerViewModel.SlicerState.SliceComplete)
            val gcode = File((sliced as SlicerViewModel.SlicerState.SliceComplete).result.gcodePath).readText()
            assertTrue(gcode.contains("PRINT_START EXTRUDER="))
            assertFalse(Regex("(?m)^T[1-9][0-9]*(?:\\s|$)").containsMatchIn(gcode))
            assertFalse(Regex("(?m)^M(?:729|8213)(?:\\s|$)").containsMatchIn(gcode))
            assertFalse(gcode.contains("SM_PRINT"))
            assertTrue(gcode.contains("; printer_settings_id = ${CosmosMachineProfile.NAME}"))
        } finally {
            vm.clearModel()
            app.container.printersRepository.replace(original)
            file.delete()
        }
    }
}
