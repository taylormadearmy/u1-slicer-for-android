package com.u1.slicer.slicing

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.data.U1NozzleConfiguration
import com.u1.slicer.data.defaultExtruderPresets
import com.u1.slicer.gcode.GcodeToolSpace
import com.u1.slicer.machine.U1SliceNozzles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real app pipeline and saved-job export preparation, without any printer network calls. */
@RunWith(AndroidJUnit4::class)
class U1NozzleViewModelTest {
    @Test fun selectedE4With02NozzleShows01BeforeSlicing() = runBlocking {
        verifySlice(0.2f, slot = 3)
    }
    @Test fun selectedE3With02NozzleSlicesWhileUnusedHeadsRemain04() = runBlocking {
        verifySlice(0.2f, slot = 2)
    }

    @Test fun selected06NozzleUsesMatchingProcessAndHistory() = runBlocking {
        verifySlice(0.6f, slot = 0)
    }

    @Test fun selected08NozzleUsesMatchingProcessAndHistory() = runBlocking {
        verifySlice(0.8f, slot = 0)
    }

    @Test fun embeddedThreeMfSelectedE3With02NozzleUses01FirstLayer() = runBlocking {
        verifySlice(0.2f, slot = 2, embedded = true)
    }

    @Test fun selected02E3Rejects04E2SupportBeforeSlicing() = runBlocking {
        verifySlice(0.2f, slot = 2, incompatibleSupport = true)
    }

    private suspend fun verifySlice(diameter: Float, slot: Int, embedded: Boolean = false, incompatibleSupport: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as U1SlicerApplication
        val container = application.container
        val settings = container.settingsRepository
        val originalPrinters = withTimeout(15_000) { container.printersRepository.config.first { it != null } }!!
        val originalConfig = settings.sliceConfig.first()
        val originalOverrides = settings.slicingOverrides.first()
        val originalProcesses = container.processProfilesRepository.config.first()
        val initialJobIds = container.sliceJobDao.getAll().first().map { it.id }.toSet()
        val printer = Printer("fixture-u1-nozzle", "Offline nozzle fixture",
            moonrakerUrl = "http://192.0.2.2",
            u1Nozzles = U1NozzleConfiguration().withDiameter(slot, diameter),
            extruderPresets = defaultExtruderPresets().map {
                it.copy(color = if (it.index == 2) "#234567" else it.color)
            })
        val model = File(application.cacheDir, "u1-vm-nozzle-$diameter.${if (embedded) "3mf" else "stl"}")
        val exported = File(application.cacheDir, "u1-vm-nozzle-$diameter-export.gcode")
        val rejected = File(application.cacheDir, "u1-vm-nozzle-$diameter-rejected.gcode")
        val store = ViewModelStore()
        val reopenedStore = ViewModelStore()
        var viewModel: SlicerViewModel? = null
        try {
            val overrides = if (incompatibleSupport) SlicingOverrides(
                supports = OverrideValue(OverrideMode.OVERRIDE, true),
                supportFilament = OverrideValue(OverrideMode.OVERRIDE, 2),
                supportInterfaceFilament = OverrideValue(OverrideMode.OVERRIDE, 2),
            ) else SlicingOverrides()
            settings.saveSliceConfig(SliceConfig())
            settings.saveSlicingOverrides(overrides)
            container.processProfilesRepository.setActive(null)
            container.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
            val vm = SlicerViewModel(application).also {
                viewModel = it
                store.put("slicer", it)
            }
            withTimeout(15_000) {
                vm.extruderPresets.first { it.getOrNull(2)?.color == "#234567" }
                vm.slicingOverrides.first { it == overrides }
            }
            if (embedded) writeTetrahedronThreeMf(model) else {
                instrumentation.context.assets.open("tetrahedron.stl")
                    .use { input -> model.outputStream().use(input::copyTo) }
            }
            vm.loadModelFromFile(model)
            val loaded = withTimeout(60_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue("Load failed: $loaded", loaded is SlicerViewModel.SlicerState.ModelLoaded)
            vm.setSelectedExtruder(slot)
            assertEquals(slot, vm.selectedExtruder.value)
            if (!incompatibleSupport) {
                val prepared = withTimeout(15_000) { vm.prepareConfig.first {
                    it.getOrNull()?.nozzleDiameter == diameter
                } }.getOrThrow()
                assertEquals("Prepare must show the layer height before Slice is tapped", diameter / 2, prepared.layerHeight)
            }
            vm.startSlicing()
            val sliced = withTimeout(120_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error
            } }
            if (incompatibleSupport) {
                assertTrue("Different physical support nozzle should be rejected: $sliced", sliced is SlicerViewModel.SlicerState.Error)
                assertTrue((sliced as SlicerViewModel.SlicerState.Error).message.contains("same nozzle size"))
                assertTrue("Rejected jobs must not be recorded", container.sliceJobDao.getAll().first().none {
                    it.id !in initialJobIds && it.modelName == model.name
                })
                return
            }
            assertTrue("Slice failed: $sliced", sliced is SlicerViewModel.SlicerState.SliceComplete)
            val job = withTimeout(30_000) {
                container.sliceJobDao.getAll().first { rows -> rows.any {
                    it.id !in initialJobIds && it.modelName == model.name &&
                        !File(it.gcodePath).canonicalPath.startsWith(application.cacheDir.canonicalPath)
                } }.first { it.id !in initialJobIds && it.modelName == model.name }
            }
            val durable = File(job.gcodePath)
            assertTrue("History G-code must survive cache cleanup", durable.isFile)
            val gcode = durable.readText()
            assertSetting(gcode, "nozzle_diameter", diameter)
            assertSetting(gcode, "layer_height", diameter / 2)
            assertSetting(gcode, "initial_layer_print_height", diameter / 2)
            assertSetting(gcode, "outer_wall_line_width", diameter * 1.05f)
            val heights = Regex("(?m)^; ?(?:LAYER_HEIGHT|HEIGHT): ?([0-9.]+)")
                .findAll(gcode).map { it.groupValues[1].toFloat() }.toList()
            assertTrue("Sliced paths must have layer-height annotations", heights.isNotEmpty())
            assertEquals("Actual first extruded layer", diameter / 2, heights.first(), 0.0001f)
            assertTrue("Subsequent layers must use the selected nozzle process", heights.drop(1).any {
                kotlin.math.abs(it - diameter / 2) < 0.0001f
            })
            val flowWidth = Regex("(?m)^; external perimeters extrusion width = ([0-9.]+)mm$")
                .find(gcode)?.groupValues?.get(1)?.toFloat()
            assertNotNull("Native calculated flow must be present", flowWidth)
            assertEquals("Actual native wall flow", diameter * 1.05f, flowWidth!!, 0.0051f)
            assertTrue("Output must contain extruded toolpaths", gcode.lineSequence().any {
                it.startsWith("G1 ") && it.contains(" X") && it.contains(" E")
            })
            assertEquals("History must show actual sliced layer height", diameter / 2, job.layerHeight, 0.0001f)
            assertEquals("History preserves selected physical toolhead", slot, job.selectedExtruderAtSlice)
            val requirement = requireNotNull(U1SliceNozzles.read(durable))
            assertEquals(diameter, requirement.diameterMm, 0f)
            requirement.validate(printer.u1Nozzles, listOf(slot))

            // A fresh ViewModel must retain the saved file's hardware contract.
            vm.clearModel()
            val reopened = SlicerViewModel(application).also { reopenedStore.put("slicer", it) }
            withTimeout(15_000) { reopened.extruderPresets.first { it.getOrNull(2)?.color == "#234567" } }
            val opened = CompletableDeferred<Boolean>()
            reopened.loadJobGcodeForViewer(job) { opened.complete(it) }
            assertTrue("Historical G-code should load", withTimeout(15_000) { opened.await() })
            assertTrue(reopened.prepareExportableGcodeWithMapping(durable, exported, listOf(slot)))
            val exportedRequirement = requireNotNull(U1SliceNozzles.read(exported))
            assertEquals(GcodeToolSpace.PHYSICAL, exportedRequirement.toolSpace)
            assertEquals(listOf(slot), exportedRequirement.tools)
            val otherSlot = if (slot == 0) 1 else 0
            assertThrows(IllegalArgumentException::class.java) {
                reopened.prepareExportableGcodeWithMapping(durable, rejected, listOf(otherSlot))
            }
            assertFalse("Incompatible remap must fail before creating upload/export data", rejected.exists())
            val legacy = File(application.cacheDir, "u1-legacy-nozzle.gcode")
            try {
                legacy.writeText("T0\nG1 X1 E1\n; nozzle_diameter = 0.4\n")
                assertThrows(IllegalArgumentException::class.java) {
                    reopened.prepareExportableGcodeWithMapping(legacy, rejected, listOf(slot))
                }
                assertFalse("Unknown older G-code needs reslicing after a nozzle change", rejected.exists())
            } finally {
                legacy.delete()
            }
            reopened.clearModel()
        } finally {
            viewModel?.clearModel()
            instrumentation.runOnMainSync { store.clear(); reopenedStore.clear() }
            val fixtureJobs = container.sliceJobDao.getAll().first().filter { it.id !in initialJobIds && it.modelName == model.name }
            for (job in fixtureJobs) {
                container.sliceJobDao.delete(job)
                File(job.gcodePath).delete()
                job.sourcePath?.let { File(it).delete() }
            }
            model.delete()
            exported.delete()
            rejected.delete()
            container.printersRepository.replace(originalPrinters)
            container.processProfilesRepository.replace(originalProcesses)
            settings.saveSlicingOverrides(originalOverrides)
            settings.saveSliceConfig(originalConfig)
        }
    }

    private fun assertSetting(gcode: String, key: String, expected: Float) {
        val value = Regex("(?m)^; ${Regex.escape(key)} = ([^\\r\\n]+)")
            .find(gcode)?.groupValues?.get(1)?.substringBefore(',')?.trim()?.toFloat()
        assertNotNull("Missing $key in actual G-code", value)
        assertEquals(key, expected, value!!, 0.0001f)
    }

    private fun writeTetrahedronThreeMf(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("_rels/.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""")
            entry("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""")
            entry("3D/3dmodel.model", """<?xml version="1.0"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/></vertices><triangles><triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/><triangle v1="1" v2="2" v3="3"/><triangle v1="2" v2="0" v3="3"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>""")
        }
    }
}
