package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.computePlateFileIndices
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.data.SessionStateRepository
import com.u1.slicer.slice.CosmosMachineProfile
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original user projects, through the same plate selection and re-embed path as Prepare. */
@RunWith(AndroidJUnit4::class)
class CosmosSourceFilamentRegressionTest {
    private enum class Scenario { LOAD, SWITCH, RESTORE, EDIT }

    @Test fun ruggedDrawerPlateSevenLoadsAndSlicesSourceFilamentTwo() =
        verifyPlate("6x4_Rugged_Drawers_ALL.3mf", 7, "4", 2, "#3E3E3F", 55)

    @Test fun topCoverPlateOneLoadsAndSlicesSourceFilamentThree() =
        verifyPlate("walmart-u1-top-cover.3mf", 1, "17", 3, "#F9C564", 65)

    @Test fun ruggedDrawerPlateSwitchRetainsSourceFilament() =
        verifyPlate("6x4_Rugged_Drawers_ALL.3mf", 7, "4", 2, "#3E3E3F", 55, Scenario.SWITCH)

    @Test fun topCoverPlateSwitchRetainsSourceFilament() =
        verifyPlate("walmart-u1-top-cover.3mf", 1, "17", 3, "#F9C564", 65, Scenario.SWITCH)

    @Test fun ruggedDrawerSessionRestoreRetainsSourceFilament() =
        verifyPlate("6x4_Rugged_Drawers_ALL.3mf", 7, "4", 2, "#3E3E3F", 55, Scenario.RESTORE)

    @Test fun topCoverSessionRestoreRetainsSourceFilament() =
        verifyPlate("walmart-u1-top-cover.3mf", 1, "17", 3, "#F9C564", 65, Scenario.RESTORE)

    @Test fun ruggedDrawerFilamentEditsReachRemappedNozzle() =
        verifyPlate("6x4_Rugged_Drawers_ALL.3mf", 7, "4", 2, "#3E3E3F", 55, Scenario.EDIT)

    @Test fun topCoverFilamentEditsReachRemappedNozzle() =
        verifyPlate("walmart-u1-top-cover.3mf", 1, "17", 3, "#F9C564", 65, Scenario.EDIT)

    private suspend fun awaitLoaded(vm: SlicerViewModel) {
        val loaded = withTimeout(120_000) { vm.state.first {
            it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error
        } }
        assertTrue("Load failed: $loaded", loaded is SlicerViewModel.SlicerState.ModelLoaded)
    }

    private fun verifyPlate(name: String, plateId: Int, objectId: String, sourceTool: Int, colour: String, bed: Int,
        scenario: Scenario = Scenario.LOAD): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as U1SlicerApplication
        val original = withTimeout(15_000) { app.container.printersRepository.config.first { it != null } }!!
        val originalOverrides = app.container.settingsRepository.slicingOverrides.first()
        val sessions = SessionStateRepository(app)
        val originalSession = sessions.read()
        sessions.clear()
        app.container.settingsRepository.saveSlicingOverrides(SlicingOverrides())
        val printer = Printer("source-filament-cosmos", "COSMOS regression", machine = MachineKind.GENERIC_KLIPPER,
            machineProfile = CosmosMachineProfile.profile, extruderPresets = listOf(ExtruderPreset(0)))
        app.container.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
        val store = ViewModelStore()
        var vm = SlicerViewModel(app).also { store.put("slicer", it) }
        val source = File(app.cacheDir, name)
        val native = NativeLibrary()
        try {
            withTimeout(15_000) { vm.effectiveSliceTarget.first { it == SlicerTarget.GenericKlipper } }
            instrumentation.context.assets.open("regression/$name").use { input -> source.outputStream().use(input::copyTo) }
            val parsed = ThreeMfParser.parse(source)
            assertFalse(parsed.hasPaintData)
            assertFalse(parsed.hasLayerToolChanges)
            assertEquals(listOf(objectId), parsed.plates.single { it.plateId == plateId }.objectIds)
            assertEquals(sourceTool, parsed.objectExtruderMap[objectId])
            vm.loadModelFromFile(source)
            withTimeout(90_000) { vm.showPlateSelector.first { it } }
            vm.selectPlate(plateId)
            awaitLoaded(vm)
            if (scenario == Scenario.SWITCH) {
                vm.selectPlate(if (plateId == 7) 8 else 2)
                awaitLoaded(vm)
                vm.selectPlate(plateId)
                awaitLoaded(vm)
            }
            if (scenario == Scenario.RESTORE) {
                withTimeout(15_000) { sessions.state.first { it?.selectedPlateId == plateId && !it.wasSliceComplete } }
                store.clear()
                NativeLibrary.previewMutex.withLock { native.clearModel() }
                vm = SlicerViewModel(app).also { store.put("slicer", it) }
                withTimeout(15_000) {
                    vm.effectiveSliceTarget.first { it == SlicerTarget.GenericKlipper }
                    vm.sessionResumeOffer.first { it?.plateId == plateId }
                }
                vm.acceptSessionResume()
                awaitLoaded(vm)
                assertEquals(plateId, vm.currentPlateId.value)
            }
            assertEquals(1, native.nativeGetObjectCount())
            assertEquals(setOf(1), NativePlateState.parseVolumeMapJson(native.nativeGetAllVolumeExtruders()).usedExtruders)
            assertEquals(setOf(1), vm.threeMfInfo.value!!.usedExtruderIndices)
            assertEquals(listOf(colour), vm.threeMfInfo.value!!.detectedColors)
            assertEquals(listOf(sourceTool - 1), vm.displayedFilamentFileIndices())
            assertEquals(setOf(sourceTool), vm.threeMfInfo.value!!.plates.single().filamentIndices)
            withTimeout(15_000) {
                vm.canonicalFilamentColors.first { it == listOf(colour) }
                vm.meshAlignedFilamentColors.first { it == listOf(colour) }
            }
            val outputColour = if (scenario == Scenario.EDIT) "#123ABC" else colour
            val outputMaterial = if (scenario == Scenario.EDIT) "PETG" else "PLA"
            val outputTemperature = if (scenario == Scenario.EDIT) 235 else 220
            if (scenario == Scenario.EDIT) {
                vm.setDisplayedFilamentColorOverride(0, outputColour)
                vm.setDisplayedFilamentMaterialOverride(0, outputMaterial)
                assertEquals(outputMaterial, vm.filamentOverrides.value[sourceTool - 1]?.materialType)
                withTimeout(15_000) {
                    vm.displayedFilamentMaterials.first { it.getOrNull(sourceTool - 1) == (outputMaterial to outputTemperature) }
                    vm.resolvedFilamentColors.first { it == listOf(outputColour) }
                    vm.canonicalFilamentColors.first { it == listOf(outputColour) }
                    vm.meshAlignedFilamentColors.first { it == listOf(outputColour) }
                }
            }

            // Changing a process setting forces the source project through embedding again.
            vm.saveSlicingOverrides(SlicingOverrides(layerHeight = OverrideValue(OverrideMode.OVERRIDE, 0.24f)))
            withTimeout(15_000) { vm.slicingOverrides.first { it.layerHeight.value == 0.24f } }
            vm.startSlicing()
            val sliced = withTimeout(300_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue("Slice failed: $sliced", sliced is SlicerViewModel.SlicerState.SliceComplete)
            val result = (sliced as SlicerViewModel.SlicerState.SliceComplete).result
            val preview = withTimeout(30_000) { vm.parsedGcode.first { it != null } }!!
            assertEquals(listOf(sourceTool - 1), computePlateFileIndices(vm.threeMfInfo.value, plateId,
                vm.canonicalFilamentList.value!!.size, perExtruderFilamentMm = preview.perExtruderFilamentMm))
            assertTrue(preview.perExtruderFilamentMm[sourceTool - 1] > 0f)
            assertEquals(1, preview.perExtruderFilamentMm.count { it > 0f })
            assertTrue(result.totalLayers > 10)
            assertTrue(result.estimatedFilamentMm > 0f)
            val output = File(result.gcodePath)
            val gcode = output.readText()
            assertTrue("Expected EXTRUDER=$outputTemperature BED=$bed for $scenario; actual ${gcode.lineSequence().firstOrNull { it.startsWith("PRINT_START ") }}",
                gcode.contains("PRINT_START EXTRUDER=$outputTemperature BED=$bed"))
            assertTrue(gcode.contains("PRINT_END"))
            assertTrue(gcode.contains("; printer_settings_id = ${CosmosMachineProfile.NAME}"))
            assertTrue("Wrong colour for $scenario", gcode.contains("; filament_colour = $outputColour", ignoreCase = true))
            assertTrue("Wrong material for $scenario", gcode.contains("; filament_type = $outputMaterial"))
            assertTrue(gcode.contains("; nozzle_temperature = $outputTemperature"))
            assertTrue("Wrong source flow for $scenario", gcode.contains("; filament_flow_ratio = ${if (sourceTool == 2) "0.95" else "0.966"}"))
            assertTrue("Wrong source speed limit for $scenario", gcode.contains("; filament_max_volumetric_speed = ${if (sourceTool == 2) "15" else "20"}"))
            assertTrue(gcode.contains("; layer_height = 0.24"))
            assertTrue(Regex("(?m)^G1 .*E[0-9]").containsMatchIn(gcode))
            assertFalse(Regex("(?m)^T[1-9][0-9]*(?:\\s|$)").containsMatchIn(gcode))
            assertFalse(Regex("(?m)^M(?:729|8213)(?:\\s|$)").containsMatchIn(gcode))
            assertFalse(gcode.contains("SM_PRINT"))
            assertEquals(setOf(1), NativePlateState.parseVolumeMapJson(native.nativeGetAllVolumeExtruders()).usedExtruders)
            val evidence = File(app.getExternalFilesDir(null), "cosmos-source-filament-regression").apply { mkdirs() }
            output.copyTo(File(evidence, "$name-plate$plateId-${scenario.name.lowercase()}.gcode"), overwrite = true)
        } finally {
            vm.clearModel()
            store.clear()
            app.container.printersRepository.replace(original)
            app.container.settingsRepository.saveSlicingOverrides(originalOverrides)
            if (originalSession != null) sessions.write(originalSession) else sessions.clear()
            source.delete()
        }
    }
}
