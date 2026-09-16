package com.u1.slicer.migration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.HostType
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfileRepository
import com.u1.slicer.machine.resolveMachineSliceConfig
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies v4 data after candidate install/restart and exercises a durable generic profile. */
@RunWith(AndroidJUnit4::class)
class V5UpgradeMigrationVerificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private lateinit var native: NativeLibrary

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
    }

    @After
    fun tearDown() {
        native.clearModel()
    }

    @Test
    fun verifyV4PrintersConfigAndLegacyU1SliceAfterUpgrade() = runBlocking {
        val config = requireNotNull(PrintersRepository(context).config.first())
        assertEquals(listOf("u1-primary-v4", "u1-secondary-v4", "bambu-a1-v4"), config.printers.map { it.id })
        assertEquals("u1-secondary-v4", config.activeId)
        assertEquals("Spare U1", config.active.nickname)
        assertEquals("u1-cam-secondary", config.active.selectedWebcamUid)
        assertEquals(listOf(0, 2), config.active.extruderPresets.map(ExtruderPreset::index))

        val bambu = config.printers.single { it.kind == PrinterKind.BAMBU_LAN }
        assertEquals(HostType.BAMBU_LAN, bambu.hostType)
        assertEquals("127.0.0.1", bambu.bambu?.ip)
        assertEquals("fixture-a1-serial", bambu.bambu?.serial)

        val repository = MachineProfileRepository.fromContext(context)
        val u1 = repository.activeFor(config.printers.first())
        assertEquals(MachineFamily.SNAPMAKER, u1.family)
        assertEquals(270f, u1.bedSizeX)
        assertEquals(270f, u1.bedSizeY)
        assertEquals(270f, u1.maxPrintHeight)
        assertEquals(4, u1.extruderCount)
        assertU1NativeSlice(u1)
    }

    @Test
    fun seedAndVerifyGenericProfileSurvivesCandidateReinstall() = runBlocking {
        val machineJson = instrumentation.context.assets
            .open("machine/generic_klipper_300.json")
            .bufferedReader()
            .use { it.readText() }
        val profile = requireNotNull(
            com.u1.slicer.machine.MachineProfile.fromResolvedOrcaJson(machineJson, MachineFamily.GENERIC)
        )
        val repository = MachineProfileRepository.fromContext(context)
        repository.save(profile)

        val current = requireNotNull(PrintersRepository(context).config.first())
        val generic = Printer(
            id = "generic-klipper-soak",
            nickname = "Generic Klipper Soak",
            moonrakerUrl = "http://127.0.0.1:7127",
            extruderPresets = listOf(ExtruderPreset(0, "#FFFFFF", "PLA")),
            machineProfileId = profile.id,
        )
        // Keep the legacy active selection unchanged while adding a durable profile entry.
        val updated = PrintersConfig(current.printers + generic, current.activeId)
        PrintersRepository(context).replace(updated)

        val afterWrite = requireNotNull(PrintersRepository(context).config.first {
            it?.printers?.any { printer -> printer.id == generic.id } == true
        })
        val resolved = repository.activeFor(afterWrite.printers.single { it.id == generic.id })
        assertEquals(profile.id, resolved.id)
        assertEquals(300f, resolved.bedSizeX)
        assertEquals(300f, resolved.bedSizeY)
        assertEquals(350f, resolved.maxPrintHeight)
    }

    @Test
    fun verifyGenericProfileSurvivesCandidateReinstall() = runBlocking {
        val config = requireNotNull(PrintersRepository(context).config.first())
        val generic = config.printers.single { it.id == "generic-klipper-soak" }
        val resolved = MachineProfileRepository.fromContext(context).activeFor(generic)
        assertEquals("generic-klipper-0.4", resolved.id)
        assertEquals(300f, resolved.bedSizeX)
        assertEquals(300f, resolved.bedSizeY)
        assertEquals(350f, resolved.maxPrintHeight)
        assertEquals(config.activeId, "u1-secondary-v4")
    }

    private fun assertU1NativeSlice(profile: com.u1.slicer.machine.MachineProfile) {
        val model = File(context.cacheDir, "phase7-upgrade-3DBenchy.stl")
        instrumentation.context.assets.open("3DBenchy.stl").use { input ->
            model.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue("Benchy must load", native.loadModel(model.absolutePath))
        val info = requireNotNull(native.getModelInfo())
        assertTrue(native.setModelInstances(floatArrayOf(
            (profile.bedSizeX - info.sizeX) / 2f,
            (profile.bedSizeY - info.sizeY) / 2f,
        )))
        val result = requireNotNull(native.slice(resolveMachineSliceConfig(profile, SliceConfig(
            layerHeight = 0.2f,
            firstLayerHeight = 0.2f,
            nozzleTemp = 210,
            bedTemp = 60,
            nozzleDiameter = profile.nozzleDiameter,
            extruderCount = 1,
            extruderTemps = intArrayOf(210),
            wipeTowerEnabled = false,
        ))))
        assertTrue("U1 slice failed: ${result.errorMessage}", result.success)
    }
}
