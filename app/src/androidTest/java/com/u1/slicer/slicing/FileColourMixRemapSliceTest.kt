package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.CanonicalColourDestination
import com.u1.slicer.data.CanonicalColourRemap
import com.u1.slicer.data.MixedFilamentRow
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F99: a normal multi-colour 3MF may map one source colour to an in-app ColorMix. */
@RunWith(AndroidJUnit4::class)
class FileColourMixRemapSliceTest {

    private lateinit var viewModel: SlicerViewModel
    private lateinit var fixture: File

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        viewModel = SlicerViewModel(targetContext.applicationContext as U1SlicerApplication)
        fixture = File(targetContext.cacheDir, "f99_mix_${System.currentTimeMillis()}.3mf")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("calib-cube-10-dual-colour-merged.3mf")
            .use { input -> fixture.outputStream().use(input::copyTo) }
    }

    @After
    fun tearDown() {
        runCatching { viewModel.clearModel() }
        runCatching {
            viewModel.mixedFilamentManager.projectMixes.value.forEach {
                viewModel.mixedFilamentManager.delete(it.id)
            }
        }
        fixture.delete()
    }

    private fun waitUntil(label: String, timeoutMs: Long = 300_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label. State=${viewModel.state.value}")
    }

    @Test
    fun fileColourMappedToColorMix_slicesWithBothComponentTools() {
        val mix = viewModel.mixedFilamentManager.add(
            componentA = 1,
            componentB = 2,
            mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        )
        viewModel.loadModelFromFile(fixture)
        waitUntil("multi-colour fixture to load") {
            viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded ||
                viewModel.state.value is SlicerViewModel.SlicerState.Error
        }
        assertTrue("Fixture must load before applying the file-colour remap. State=${viewModel.state.value}",
            viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded)

        // The UI identifies source colours by canonical file index. Remap the first source
        // colour to the mix while every other source colour retains the physical default.
        viewModel.setCanonicalColourRemaps(
            listOf(CanonicalColourRemap(0, CanonicalColourDestination.Mix(mix.id))),
        )
        viewModel.startSlicing()
        waitUntil("file-colour ColorMix slice to finish") {
            viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete ||
                viewModel.state.value is SlicerViewModel.SlicerState.Error
        }
        val state = viewModel.state.value
        assertTrue("F99 file-colour mix slice must complete. State=$state",
            state is SlicerViewModel.SlicerState.SliceComplete)

        val gcode = File((state as SlicerViewModel.SlicerState.SliceComplete).result.gcodePath).readText()
        assertTrue("ColorMix must emit E1's physical tool", Regex("(?m)^T0\\b").containsMatchIn(gcode))
        assertTrue("ColorMix must emit E2's physical tool", Regex("(?m)^T1\\b").containsMatchIn(gcode))
    }

    /**
     * B149/F99: a virtual mix id is 5, which collides with a normal file's fifth canonical
     * colour. The staged 3MF assigns source E3 to mix id 5; reloading must preserve that
     * virtual id instead of treating it as source colour index 4 and mapping it to E1.
     */
    @Test
    fun fifthCanonicalColourFile_mappedE3ColorMix_keepsBothComponentTools() {
        val fiveColourFixture = File(targetContext.cacheDir, "f99_five_colours_${System.currentTimeMillis()}.3mf")
        writeFiveColourE3Fixture(fiveColourFixture)
        try {
            val mix = viewModel.mixedFilamentManager.add(
                componentA = 1,
                componentB = 2,
                mixBPercent = 50,
                distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            )
            viewModel.loadModelFromFile(fiveColourFixture)
            waitUntil("five-colour fixture to load") {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            assertTrue("Five-colour fixture must load. State=${viewModel.state.value}",
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded)

            // The model_settings assignment is E3, so its canonical source identity is index 2.
            viewModel.setCanonicalColourRemaps(
                listOf(CanonicalColourRemap(2, CanonicalColourDestination.Mix(mix.id))),
            )
            viewModel.startSlicing()
            waitUntil("five-colour F99 ColorMix slice to finish") {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            val state = viewModel.state.value
            assertTrue("F99 five-colour mix slice must complete. State=$state",
                state is SlicerViewModel.SlicerState.SliceComplete)
            val gcode = File((state as SlicerViewModel.SlicerState.SliceComplete).result.gcodePath).readText()
            assertTrue("F99 collision regression must emit E1", Regex("(?m)^T0\\b").containsMatchIn(gcode))
            assertTrue("F99 collision regression must emit E2", Regex("(?m)^T1\\b").containsMatchIn(gcode))
        } finally {
            fiveColourFixture.delete()
        }
    }

    private fun writeFiveColourE3Fixture(file: File) {
        val model = """
            <?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
              <resources><object id="2" type="model"><mesh><vertices>
                <vertex x="0" y="0" z="0"/><vertex x="12" y="0" z="0"/>
                <vertex x="12" y="12" z="0"/><vertex x="0" y="12" z="0"/>
                <vertex x="0" y="0" z="8"/><vertex x="12" y="0" z="8"/>
                <vertex x="12" y="12" z="8"/><vertex x="0" y="12" z="8"/>
              </vertices><triangles>
                <triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="3" v3="2"/>
                <triangle v1="4" v2="5" v3="6"/><triangle v1="4" v2="6" v3="7"/>
                <triangle v1="0" v2="1" v3="5"/><triangle v1="0" v2="5" v3="4"/>
                <triangle v1="1" v2="2" v3="6"/><triangle v1="1" v2="6" v3="5"/>
                <triangle v1="2" v2="3" v3="7"/><triangle v1="2" v2="7" v3="6"/>
                <triangle v1="3" v2="0" v3="4"/><triangle v1="3" v2="4" v3="7"/>
              </triangles></mesh></object></resources><build><item objectid="2"/></build>
            </model>
        """.trimIndent()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""")
            put("3D/3dmodel.model", model)
            put("Metadata/model_settings.config", """<config><object id="2"><metadata type="object" key="extruder" value="3"/><volume firstid="0" lastid="11"><metadata type="volume" key="extruder" value="3"/></volume></object></config>""")
            put("Metadata/project_settings.config", """{"filament_colour":["#05BDFA","#FEF552","#D1D3D5","#FFFFFF","#000000"],"filament_type":["PLA","PLA","PLA","PLA","PLA"],"filament_count":"5"}""")
            put("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""")
        }
    }
}
