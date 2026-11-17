package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.machine.MachineProfile
import com.u1.slicer.machine.NativeMachineProfileValidator
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericInsetPlacementTest {
    @Test fun profileValidationPreservesTheOpenModelAndItsGcode() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val native = com.u1.slicer.NativeLibrary()
        val model = File(context.cacheDir, "validation-preservation.stl")
        try {
            native.clearModel()
            instrumentation.context.assets.open("tetrahedron.stl").use { input -> model.outputStream().use(input::copyTo) }
            assertTrue(native.loadModel(model.absolutePath))
            assertTrue(native.setModelInstances(floatArrayOf(40f, 50f)))
            val beforeInfo = native.getModelInfo()!!
            val beforePositions = native.getInstanceOffsets()
            val sliced = native.slice(com.u1.slicer.data.SliceConfig())!!
            assertTrue(sliced.errorMessage, sliced.success)
            val beforeGcode = File(sliced.gcodePath).readBytes()
            val machine = profile(listOf("100x100", "200x100", "200x200", "100x200"))
            val result = NativeMachineProfileValidator(context).validate(MachineProfile.fromSnapshot(machine))
            assertTrue(result.errorMessage, result.isValid)
            assertEquals(beforeInfo.triangleCount, native.getModelInfo()!!.triangleCount)
            assertArrayEquals(beforePositions, native.getInstanceOffsets(), 0.001f)
            assertArrayEquals(beforeGcode, File(sliced.gcodePath).readBytes())
        } finally {
            native.clearModel()
            model.delete()
        }
    }
    private fun profile(polygon: List<String>) = GenericMachineProfile.fromJson(JSONObject(GenericMachineProfile.centauriCarbon().resolvedJson).apply {
        put("name", "Inset regression")
        put("printable_area", JSONArray(polygon))
        put("bed_exclude_area", JSONArray())
        put("machine_start_gcode", "G28")
        put("machine_end_gcode", "M104 S0\nM140 S0")
    }.toString())

    @Test fun profileValidationPlacesItsCubeInsideALargeInset() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val machine = profile(listOf("100x100", "200x100", "200x200", "100x200"))
        val result = NativeMachineProfileValidator(context).validate(MachineProfile.fromSnapshot(machine))
        assertTrue(result.errorMessage, result.isValid)
    }

    @Test fun fullWidthModelCentersInsideTheActualPrintableRectangle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as U1SlicerApplication
        val original = withTimeout(15_000) { app.container.printersRepository.config.first { it != null } }!!
        val machine = profile(listOf("0.5x1", "270.5x1", "270.5x271", "0.5x271"))
        val printer = Printer("inset-fixture", "Inset fixture", machine = MachineKind.GENERIC_KLIPPER, machineProfile = machine)
        app.container.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
        val vm = SlicerViewModel(app)
        val file = File(app.cacheDir, "inset-placement.stl")
        try {
            withTimeout(15_000) { vm.effectiveSliceTarget.first { it == SlicerTarget.GenericKlipper } }
            instrumentation.context.assets.open("tetrahedron.stl").use { input -> file.outputStream().use(input::copyTo) }
            vm.loadModelFromFile(file)
            val loaded = withTimeout(60_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue(loaded.toString(), loaded is SlicerViewModel.SlicerState.ModelLoaded)
            val info = (loaded as SlicerViewModel.SlicerState.ModelLoaded).info
            vm.setModelScale(SlicerViewModel.ModelScale(270f / info.sizeX, 270f / info.sizeY, 0.2f / info.sizeZ))
            val positions = vm.getPlacementPositions()
            assertEquals(0.5f, positions[0], 0.01f)
            assertEquals(1f, positions[1], 0.01f)
            vm.startSlicing()
            val sliced = withTimeout(120_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue(sliced.toString(), sliced is SlicerViewModel.SlicerState.SliceComplete)
        } finally {
            vm.clearModel()
            app.container.printersRepository.replace(original)
            file.delete()
        }
    }
}
