package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import com.u1.slicer.machine.MachineProfile
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SliceArtifact
import com.u1.slicer.slice.SlicerTarget
import com.u1.slicer.slice.isLocalSliceAvailable
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyMachineBindingIntegrationTest {
    private val application get() = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as U1SlicerApplication

    private suspend fun withLegacyBinding(test: suspend (MachineProfile, Printer) -> Unit) {
        val container = application.container
        val repository = container.printersRepository
        val original = withTimeout(15_000) { repository.config.first { it != null } }!!
        val snapshot = GenericMachineProfile.fromJson(JSONObject(GenericMachineProfile.centauriCarbon().resolvedJson).apply {
            put("name", "Legacy cache integration fixture")
            put("vendor", "Test")
            put("printer_model", "Legacy cache integration fixture")
            put("machine_start_gcode", "G28\n;LEGACY_BINDING_FIXTURE\nM190 S[bed_temperature_initial_layer_single]\nM109 S[nozzle_temperature_initial_layer]")
            put("machine_end_gcode", "M104 S0\nM140 S0")
        }.toString())
        val profile = MachineProfile.fromSnapshot(snapshot)
        val cacheFile = File(application.filesDir, "machine_profiles/${profile.id}.json")
        val previousCache = cacheFile.takeIf { it.isFile }?.readText()
        val legacy = Printer("legacy-binding-fixture", "Legacy catalogue fixture", machineProfileId = profile.id)
        try {
            container.machineProfileRepository.save(profile)
            repository.replace(PrintersConfig(listOf(legacy, Printer("offline-u1-fixture", "Offline U1")), legacy.id))
            test(profile, legacy)
        } finally {
            repository.replace(original)
            if (previousCache == null) cacheFile.delete() else cacheFile.writeText(previousCache)
        }
    }

    @Test fun cachedBindingSwitchesBedsAndSlicesWithTheSelectedMachine() = runBlocking {
        withLegacyBinding { profile, legacy ->
            val repository = application.container.printersRepository
            val restored = withTimeout(15_000) { repository.activePrinter.first { it?.machineProfile != null } }!!
            assertTrue(isLocalSliceAvailable(restored))
            val viewModel = SlicerViewModel(application)
            val model = File(application.cacheDir, "legacy-binding-tetrahedron.stl")
            try {
                withTimeout(15_000) { viewModel.activeMachineProfile.first { it.id == profile.id } }
                assertEquals(257f, viewModel.activeMachineProfile.value.bedSizeX)
                assertEquals(1, viewModel.activeMachineProfile.value.extruderCount)
                repository.setActive("offline-u1-fixture")
                withTimeout(15_000) {
                    viewModel.activeMachineProfile.first { it.id == "snapmaker-u1-0.4" }
                    viewModel.effectiveSliceTarget.first { it == SlicerTarget.SnapmakerU1 }
                }
                assertEquals(270f, viewModel.activeMachineProfile.value.bedSizeX)
                assertEquals(4, viewModel.activeMachineProfile.value.extruderCount)
                repository.setActive(legacy.id)
                withTimeout(15_000) {
                    viewModel.activeMachineProfile.first { it.id == profile.id }
                    viewModel.effectiveSliceTarget.first { it == SlicerTarget.GenericKlipper }
                }
                InstrumentationRegistry.getInstrumentation().context.assets.open("tetrahedron.stl").use { input ->
                    model.outputStream().use(input::copyTo)
                }
                viewModel.loadModelFromFile(model)
                val loaded = withTimeout(60_000) { viewModel.state.first {
                    it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error
                } }
                assertTrue("Model load failed: $loaded", loaded is SlicerViewModel.SlicerState.ModelLoaded)
                viewModel.startSlicing()
                val sliced = withTimeout(90_000) { viewModel.state.first {
                    it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error
                } }
                assertTrue("Legacy binding slice failed: $sliced", sliced is SlicerViewModel.SlicerState.SliceComplete)
                val artifact = withTimeout(15_000) {
                    viewModel.latestSliceArtifact.first { it != null }
                } as SliceArtifact.MoonrakerGcodeArtifact
                assertEquals(SlicerTarget.GenericKlipper, artifact.target)
                assertEquals(restored.machineProfile?.fingerprint, artifact.machineProfileFingerprint)
                assertTrue(artifact.gcodeFile.readText().contains(";LEGACY_BINDING_FIXTURE"))
            } finally {
                viewModel.clearModel()
                model.delete()
            }
        }
    }

    @Test fun startupMigrationPersistsCachedSnapshotBeforeTheCacheDisappears() = runBlocking {
        withLegacyBinding { profile, legacy ->
            val container = application.container
            container.printersRepository.runMigrationIfNeeded(container.settingsRepository)
            assertTrue(File(application.filesDir, "machine_profiles/${profile.id}.json").delete())
            val reloaded = withTimeout(15_000) {
                PrintersRepository(application).activePrinter.first { it?.id == legacy.id }
            }!!
            assertTrue(isLocalSliceAvailable(reloaded))
            assertEquals(GenericMachineProfile.fromJson(profile.machineJson).fingerprint, reloaded.machineProfile?.fingerprint)
            assertEquals(257f, container.machineProfileRepository.activeFor(reloaded).bedSizeX)
        }
    }
}
