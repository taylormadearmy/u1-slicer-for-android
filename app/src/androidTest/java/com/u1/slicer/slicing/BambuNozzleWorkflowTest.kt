package com.u1.slicer.slicing

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.*
import com.u1.slicer.slice.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class BambuNozzleWorkflowTest {
    @Test fun p1s02Stl() = runBlocking { verify(BambuModel.P1S, 0.2f, false) }
    @Test fun p1s02Imported3mf() = runBlocking { verify(BambuModel.P1S, 0.2f, true) }
    @Test fun p1s04Stl() = runBlocking { verify(BambuModel.P1S, 0.4f, false) }
    @Test fun p1s04Imported3mf() = runBlocking { verify(BambuModel.P1S, 0.4f, true) }
    @Test fun p1s06Stl() = runBlocking { verify(BambuModel.P1S, 0.6f, false) }
    @Test fun p1s06Imported3mf() = runBlocking { verify(BambuModel.P1S, 0.6f, true) }
    @Test fun p1s08Stl() = runBlocking { verify(BambuModel.P1S, 0.8f, false) }
    @Test fun p1s08Imported3mf() = runBlocking { verify(BambuModel.P1S, 0.8f, true) }
    @Test fun h2d02Stl() = runBlocking { verify(BambuModel.H2D, 0.2f, false) }
    @Test fun h2d02Imported3mf() = runBlocking { verify(BambuModel.H2D, 0.2f, true) }
    @Test fun h2d04Stl() = runBlocking { verify(BambuModel.H2D, 0.4f, false) }
    @Test fun h2d04Imported3mf() = runBlocking { verify(BambuModel.H2D, 0.4f, true) }
    @Test fun h2d06Stl() = runBlocking { verify(BambuModel.H2D, 0.6f, false) }
    @Test fun h2d06Imported3mf() = runBlocking { verify(BambuModel.H2D, 0.6f, true) }
    @Test fun h2d08Stl() = runBlocking { verify(BambuModel.H2D, 0.8f, false) }
    @Test fun h2d08Imported3mf() = runBlocking { verify(BambuModel.H2D, 0.8f, true) }

    @Test fun incompatibleImportedProcessShowsNozzleError() = runBlocking { verify(BambuModel.P1S, 0.2f, true, incompatibleProcess = true) }

    private suspend fun verify(model: BambuModel, diameter: Float, embedded: Boolean, incompatibleProcess: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as U1SlicerApplication
        val c = app.container
        val settings = c.settingsRepository
        val original = withTimeout(15000) { c.printersRepository.config.first { it != null } }!!
        val oldConfig = settings.sliceConfig.first()
        val oldOverrides = settings.slicingOverrides.first()
        val oldProcesses = c.processProfilesRepository.config.first()
        val initialJobs = c.sliceJobDao.getAll().first().map { it.id }.toSet()
        val printer = Printer("bambu-nozzle-fixture", "Offline Bambu nozzle", kind = PrinterKind.BAMBU_LAN,
            bambu = BambuConfig("192.0.2.5", "test", "TEST", model, diameter))
        val file = File(app.cacheDir, "bambu-nozzle-${model}-$diameter.${if (embedded) "3mf" else "stl"}")
        val exported = File(app.cacheDir, "bambu-nozzle-export.gcode.3mf")
        val rejected = File(app.cacheDir, "bambu-nozzle-rejected.gcode.3mf")
        val store = ViewModelStore()
        var vm: SlicerViewModel? = null
        try {
            settings.saveSliceConfig(SliceConfig())
            settings.saveSlicingOverrides(SlicingOverrides())
            c.processProfilesRepository.setActive(null)
            c.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
            assertEquals(diameter, c.printersRepository.config.first { it?.printers?.firstOrNull()?.bambu?.nozzleDiameter == diameter }!!.printers.first().bambu!!.nozzleDiameter)
            val viewModel = SlicerViewModel(app).also { vm = it; store.put("slicer", it) }
            withTimeout(15000) { viewModel.effectiveSliceTarget.first { it == if (model == BambuModel.H2D) SlicerTarget.BambuH2D else SlicerTarget.BambuP1S } }
            if (embedded) writeTetrahedronThreeMf(file, if (incompatibleProcess) 0.4f else diameter) else instrumentation.context.assets.open("tetrahedron.stl").use { input -> file.outputStream().use(input::copyTo) }
            viewModel.loadModelFromFile(file)
            val loaded = withTimeout(60000) { viewModel.state.first { it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error } }
            if (incompatibleProcess) {
                val error = if (loaded is SlicerViewModel.SlicerState.Error) loaded else {
                    viewModel.startSlicing()
                    withTimeout(60000) { viewModel.state.first { it is SlicerViewModel.SlicerState.Error || it is SlicerViewModel.SlicerState.SliceComplete } }
                }
                assertTrue("Incompatible imported process must fail: $error", error is SlicerViewModel.SlicerState.Error)
                assertTrue((error as SlicerViewModel.SlicerState.Error).message.contains("compatible process"))
                assertTrue(error.message.contains("0.2"))
                assertNull(viewModel.latestSliceArtifact.value)
                return
            }
            assertTrue("Load: $loaded", loaded is SlicerViewModel.SlicerState.ModelLoaded)
            withTimeout(15000) { viewModel.prepareConfig.first { it.getOrNull()?.nozzleDiameter == diameter } }
            viewModel.startSlicing()
            val done = withTimeout(180000) { viewModel.state.first { it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error } }
            assertTrue("Slice: $done", done is SlicerViewModel.SlicerState.SliceComplete)
            val artifact = withTimeout(30000) { viewModel.latestSliceArtifact.first { it != null } } as SliceArtifact.BambuProjectArtifact
            assertTrue(artifact.projectFile.name.endsWith(".gcode.3mf"))
            assertTrue(artifact.isCompatibleWith(printer))
            val changed = printer.copy(bambu = printer.bambu!!.copy(nozzleDiameter = if (diameter == 0.4f) 0.6f else 0.4f))
            assertFalse("Changing hardware requires reslicing", artifact.isCompatibleWith(changed))
            assertTrue(viewModel.suggestedGcodeFilename(file.name).endsWith(".gcode.3mf"))
            val sourceGcode = File((done as SlicerViewModel.SlicerState.SliceComplete).result.gcodePath)
            viewModel.saveGcodeTo(android.net.Uri.fromFile(exported))
            withTimeout(15000) {
                while (!exported.isFile || exported.length() != artifact.projectFile.length()) kotlinx.coroutines.delay(25)
            }
            assertArrayEquals(artifact.projectFile.readBytes(), exported.readBytes())
            ZipFile(exported).use { zip ->
                val config = JSONObject(zip.getInputStream(zip.getEntry("Metadata/project_settings.config")).reader().readText())
                assertEquals(diameter.toString(), config.getString("printer_variant"))
                val nozzles = config.getJSONArray("nozzle_diameter")
                assertEquals(if (model == BambuModel.H2D) 2 else 1, nozzles.length())
                repeat(nozzles.length()) { assertEquals(diameter.toDouble(), nozzles.getDouble(it), 0.00001) }
                if (model == BambuModel.H2D) {
                    assertEquals(if (diameter in listOf(0.2f, 0.8f)) 4 else 7, config.getJSONArray("printer_extruder_id").length())
                }
                val metadata = zip.getInputStream(zip.getEntry("Metadata/slice_info.config")).reader().readText()
                assertTrue(metadata.contains("nozzle_diameter=\"$diameter\""))
                val gcode = zip.getInputStream(zip.getEntry("Metadata/plate_1.gcode")).reader().readText()
                val expectedNozzles = if (model == BambuModel.H2D) "$diameter,$diameter" else "$diameter"
                assertTrue(gcode.contains("; nozzle_diameter = $expectedNozzles"))
                val heights = Regex("(?m)^; ?(?:LAYER_HEIGHT|HEIGHT): ?([0-9.]+)").findAll(gcode).map { it.groupValues[1].toFloat() }.toList()
                assertTrue("Actual toolpath heights present", heights.isNotEmpty())
                assertEquals(diameter / 2, heights.first(), 0.0001f)
                val width = Regex("(?m)^; external perimeters extrusion width = ([0-9.]+)mm").find(gcode)?.groupValues?.get(1)?.toFloat()
                assertNotNull("Native calculated wall flow", width)
                // The unchanged 0.4 mm Bambu baseline uses Orca's automatic 0.45 mm width.
                val expectedWidth = if (diameter == 0.4f) 0.45f else diameter * 1.05f
                assertEquals(expectedWidth, width!!, 0.0051f)
                assertTrue(gcode.lineSequence().any { it.startsWith("G1 ") && it.contains(" X") && it.contains(" E") })
            }
            c.printersRepository.replace(PrintersConfig(listOf(changed), changed.id))
            withTimeout(15000) { viewModel.prepareConfig.first { it.getOrNull()?.nozzleDiameter == changed.bambu!!.nozzleDiameter } }
            withTimeout(15000) { viewModel.sliceStale.first { it } }
            val error = assertThrows(IllegalArgumentException::class.java) {
                viewModel.prepareSliceExport(sourceGcode, rejected)
            }
            assertTrue(error.message!!.contains("Slice again"))
            assertFalse(rejected.exists())
        } finally {
            vm?.clearModel()
            instrumentation.runOnMainSync { store.clear() }
            c.sliceJobDao.getAll().first().filter { it.id !in initialJobs && it.modelName == file.name }.forEach {
                c.sliceJobDao.delete(it); File(it.gcodePath).delete(); it.sourcePath?.let { path -> File(path).delete() }
            }
            c.printersRepository.replace(original)
            c.processProfilesRepository.replace(oldProcesses)
            settings.saveSlicingOverrides(oldOverrides)
            settings.saveSliceConfig(oldConfig)
            file.delete(); exported.delete(); rejected.delete()
        }
    }
    private fun writeTetrahedronThreeMf(file: File, diameter: Float) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("_rels/.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""")
            entry("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""")
            entry("Metadata/project_settings.config", org.json.JSONObject()
                .put("nozzle_diameter", org.json.JSONArray(listOf("0.4")))
                .put("layer_height", (diameter / 2).toString())
                .put("initial_layer_print_height", (diameter / 2).toString()).toString())
            entry("3D/3dmodel.model", """<?xml version="1.0"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/></vertices><triangles><triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/><triangle v1="1" v2="2" v3="3"/><triangle v1="2" v2="0" v3="3"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>""")
        }
    }
}
