package com.u1.slicer.slicing

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.*
import com.u1.slicer.machine.U1SliceNozzles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrollNozzleRegressionTest {
    @Test fun suppliedTrollOnE4With02NozzleSlicesAndExports() = runBlocking { verify(false, "file-remap") }
    @Test fun suppliedTrollOnE4With02NozzleSupportsAdaptiveLayers() = runBlocking { verify(true, "file-remap") }

    @Test fun suppliedTrollObjectAssignedToE4SlicesAndExports() = runBlocking { verify(false, "object") }
    @Test fun suppliedTrollFilamentMappedToE4SlicesAndExports() = runBlocking { verify(false, "filament") }

    @Test fun suppliedTrollOrganicSupportsExplainAdaptiveRestriction() = runBlocking { verify(true, "organic") }

    private suspend fun verify(adaptive: Boolean, route: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as U1SlicerApplication
        val container = app.container
        val originalPrinters = withTimeout(15_000) { container.printersRepository.config.first { it != null } }!!
        val originalConfig = container.settingsRepository.sliceConfig.first()
        val originalOverrides = container.settingsRepository.slicingOverrides.first()
        val originalProcesses = container.processProfilesRepository.config.first()
        val originalJobs = container.sliceJobDao.getAll().first().map { it.id }.toSet()
        val model = File(app.cacheDir, "troll-nozzle-regression.3mf")
        val export = File(app.cacheDir, "troll-nozzle-export.gcode")
        val store = ViewModelStore()
        var vm: SlicerViewModel? = null
        var expectedJobName = model.name
        val scale = InstrumentationRegistry.getArguments().getString("trollScale")?.toFloatOrNull() ?: 0.15f
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val printer = Printer("troll-nozzle-fixture", "Offline troll fixture",
                u1Nozzles = U1NozzleConfiguration().withDiameter(3, 0.2f))
            val overrides = SlicingOverrides(primeTower = OverrideValue(OverrideMode.OVERRIDE, false)).let {
                if (adaptive && route != "organic") it.copy(supports = OverrideValue(OverrideMode.OVERRIDE, false)) else it
            }
            container.settingsRepository.saveSliceConfig(SliceConfig())
            container.settingsRepository.saveSlicingOverrides(overrides)
            container.processProfilesRepository.setActive(null)
            container.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
            val viewModel = SlicerViewModel(app).also { vm = it; store.put("troll", it) }
            progressScope.launch {
                viewModel.state.collect { state ->
                    if (state is SlicerViewModel.SlicerState.Slicing) {
                        android.util.Log.i("TrollRegression", "$route progress=${state.progress} ${state.stage}")
                    }
                }
            }
            withTimeout(15_000) { viewModel.slicingOverrides.first { it == overrides } }
            instrumentation.context.assets.open("troll_last123.3mf").use { input ->
                model.outputStream().use(input::copyTo)
            }
            viewModel.loadModelFromFile(model)
            withTimeout(90_000) { viewModel.showPlateSelector.first { it } }
            viewModel.selectPlate(1)
            val loaded = withTimeout(90_000) { viewModel.state.first {
                it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue("Plate load failed: $loaded", loaded is SlicerViewModel.SlicerState.ModelLoaded)
            expectedJobName = viewModel.modelFileName.value
            when (route) {
                "file-remap", "organic" -> viewModel.setCanonicalColourRemaps(listOf(CanonicalColourRemap(0, CanonicalColourDestination.PhysicalSlot(3))))
                "object" -> viewModel.setObjectFilament(0, 4)
                else -> viewModel.applyMultiColorAssignments(listOf(3), viewModel.extruderPresets.value, viewModel.filaments.first())
            }
            viewModel.setModelScale(SlicerViewModel.ModelScale(scale, scale, scale))
            withTimeout(15_000) { viewModel.prepareConfig.first { it.getOrNull()?.nozzleDiameter == 0.2f } }
            if (route == "organic") {
                val message = withTimeout(30_000) {
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { viewModel.toastEvents.first() }
                    viewModel.setAdaptiveLayerHeight(AdaptiveLayerHeightState(AdaptiveLayerHeightState.Mode.ADAPTIVE))
                    pending.await()
                }
                assertTrue(message, message.contains("Organic supports"))
                assertEquals(AdaptiveLayerHeightState.Mode.USE_FILE, viewModel.adaptiveLayerHeight.value.mode)
                return
            }
            viewModel.setAdaptiveLayerHeight(AdaptiveLayerHeightState(
                if (adaptive) AdaptiveLayerHeightState.Mode.ADAPTIVE else AdaptiveLayerHeightState.Mode.OFF))
            withTimeout(30_000) { viewModel.adaptiveLayerHeight.first {
                it.mode == if (adaptive) AdaptiveLayerHeightState.Mode.ADAPTIVE else AdaptiveLayerHeightState.Mode.OFF
            } }
            viewModel.startSlicing()
            val result = withTimeout(if (scale > 0.15f) 1_200_000L else 300_000L) { viewModel.state.first {
                it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error
            } }
            File(app.filesDir, "transient").walkTopDown().filter { it.extension == "gcode" }.forEach { output ->
                val tools = output.useLines { lines -> lines.filter { Regex("^T[0-9]+(?:\\s.*)?$").matches(it) }.toSet() }
                val usage = output.useLines { lines -> lines.filter { it.startsWith("; filament used [mm]") }.toList() }
                android.util.Log.i("TrollRegression", "route=$route tools=$tools usage=$usage")
            }
            assertTrue("Slice result: $result; mapping=${viewModel.colorMapping.value}; export=${viewModel.resolveExportMapping()}; config tools=${viewModel.config.value.extruderCount}",
                result is SlicerViewModel.SlicerState.SliceComplete)
            val job = withTimeout(30_000) { container.sliceJobDao.getAll().first { jobs ->
                jobs.any { it.id !in originalJobs && it.modelName == expectedJobName }
            }.first { it.id !in originalJobs && it.modelName == expectedJobName } }
            assertTrue(viewModel.prepareExportableGcode(File(job.gcodePath), export))
            val nozzles = requireNotNull(U1SliceNozzles.read(export))
            assertEquals(0.2f, nozzles.diameterMm)
            assertEquals(listOf(3), nozzles.tools)
            nozzles.validate(printer.u1Nozzles, null)
            if (adaptive) {
                val range = requireNotNull(viewModel.adaptiveLayerHeightRange.value)
                assertTrue("Adaptive layers must fit the installed 0.2 mm nozzle", range[1] <= 0.16001f)
            }
        } finally {
            progressScope.cancel()
            vm?.let { modelView ->
                modelView.cancelSlicing()
                kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    modelView.state.first { it !is SlicerViewModel.SlicerState.Slicing && it !is SlicerViewModel.SlicerState.Cancelling }
                }
                modelView.clearModel()
            }
            instrumentation.runOnMainSync { store.clear() }
            container.sliceJobDao.getAll().first().filter { it.id !in originalJobs && it.modelName == expectedJobName }.forEach {
                container.sliceJobDao.delete(it); File(it.gcodePath).delete(); it.sourcePath?.let { path -> File(path).delete() }
            }
            model.delete(); export.delete()
            container.printersRepository.replace(originalPrinters)
            container.processProfilesRepository.replace(originalProcesses)
            container.settingsRepository.saveSlicingOverrides(originalOverrides)
            container.settingsRepository.saveSliceConfig(originalConfig)
        }
    }
}
