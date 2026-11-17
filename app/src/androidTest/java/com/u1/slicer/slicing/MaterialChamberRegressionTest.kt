package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaterialChamberRegressionTest {
    @Test fun implicitPlaSlotMaterialDoesNotInheritTheSourceAsaChamber() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as U1SlicerApplication
        val original = withTimeout(15_000) { app.container.printersRepository.config.first { it != null } }!!
        val printer = Printer("chamber-material-fixture", "PLA slot fixture")
        app.container.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
        val vm = SlicerViewModel(app)
        val file = File(app.cacheDir, "single-asa-chamber-source.3mf")
        try {
            withTimeout(15_000) { vm.effectiveSliceTarget.first { it == SlicerTarget.SnapmakerU1 } }
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                entry("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""")
                entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""")
                entry("3D/3dmodel.model", """<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/></vertices><triangles><triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/><triangle v1="1" v2="2" v3="3"/><triangle v1="2" v2="0" v3="3"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>""")
                entry("Metadata/project_settings.config", """{"filament_type":["ASA"],"filament_colour":["#ffffff"],"nozzle_temperature":["270"],"nozzle_temperature_initial_layer":["270"],"chamber_temperature":["65"]}""")
            }
            vm.loadModelFromFile(file)
            val loaded = withTimeout(60_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue(loaded.toString(), loaded is SlicerViewModel.SlicerState.ModelLoaded)
            vm.startSlicing()
            val sliced = withTimeout(90_000) { vm.state.first {
                it is SlicerViewModel.SlicerState.SliceComplete || it is SlicerViewModel.SlicerState.Error
            } }
            assertTrue(sliced.toString(), sliced is SlicerViewModel.SlicerState.SliceComplete)
            val gcode = File((sliced as SlicerViewModel.SlicerState.SliceComplete).result.gcodePath).readText()
            assertTrue(gcode.contains("; filament_type = PLA"))
            assertTrue(gcode.contains("; chamber_temperature = 0"))
        } finally {
            vm.clearModel()
            file.delete()
            app.container.printersRepository.replace(original)
        }
    }
}
