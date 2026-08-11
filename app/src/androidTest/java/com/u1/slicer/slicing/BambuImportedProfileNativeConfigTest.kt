package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuImportedConfigComposer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BambuImportedProfileNativeConfigTest {
    private lateinit var native: NativeLibrary
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
        cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    }

    @After
    fun tearDown() {
        native.clearModel()
    }

    @Test
    fun dragonScaleP1sX1cSettingsSurviveH2dRetargetButMachineFieldsDoNot() {
        val source = readJsonAsset("dragon_scale_imported_profile.json")
        val composed = BambuImportedConfigComposer.compose(SlicerTarget.BambuH2D, sourceConfig = source)
        val threeMf = writeTetrahedronThreeMf(composed.config)

        assertTrue(native.loadModel(threeMf.absolutePath))
        val config = resolveTargetedSliceConfig(
            SlicerTarget.BambuH2D,
            SliceConfig(extruderCount = 2),
        ).copy(layerHeight = 0f)
        val result = native.slice(config)
        assertTrue(result?.errorMessage, result?.success == true)
        val gcode = File(result!!.gcodePath).readText()

        assertTrue(gcode.contains("; elefant_foot_compensation = 0.15"))
        assertTrue(gcode.contains("; initial_layer_print_height = 0.2"))
        assertTrue(gcode.contains("; curr_bed_type = Textured PEI Plate"))
        assertTrue(gcode.contains("; textured_plate_temp = 55,55"))
        assertTrue(gcode.contains("; filament_flow_ratio = 0.98,0.985"))
        assertTrue(gcode.contains("; reduce_fan_stop_start_freq = 1,1"))
        assertTrue(gcode.contains("; fan_cooling_layer_time = 100,100"))
        assertTrue(gcode.contains("; printer_model = Bambu Lab H2D"))
        assertTrue(gcode.contains("; printable_area = 0x0,350x0,350x320,0x320"))
        assertFalse(gcode.contains("fixture sentinel"))
        assertFalse(gcode.contains("printer_model = Bambu Lab P1S"))
    }

    @Test
    fun explicitImportedOverrideWinsAfterSource() {
        val composed = BambuImportedConfigComposer.compose(
            target = SlicerTarget.BambuH2D,
            sourceConfig = readJsonAsset("dragon_scale_imported_profile.json"),
            explicitOverrides = mapOf("initial_layer_print_height" to "0.28"),
        )
        val threeMf = writeTetrahedronThreeMf(composed.config)

        assertTrue(native.loadModel(threeMf.absolutePath))
        val result = native.slice(
            resolveTargetedSliceConfig(SlicerTarget.BambuH2D, SliceConfig(extruderCount = 2))
                .copy(layerHeight = 0f)
        )
        assertTrue(result?.errorMessage, result?.success == true)
        assertTrue(File(result!!.gcodePath).readText().contains("; initial_layer_print_height = 0.28"))
    }

    private fun readJsonAsset(name: String): Map<String, Any> {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).bufferedReader().use { it.readText() }
        return jsonObjectToMap(JSONObject(text))
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any> = json.keys().asSequence()
        .associateWith { key -> jsonValue(json.get(key)) }

    private fun jsonValue(value: Any): Any = when (value) {
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }

    private fun writeTetrahedronThreeMf(config: Map<String, Any>): File {
        val file = File(cacheDir, "bambu-imported-${System.nanoTime()}.3mf")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>""".toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(TETRAHEDRON_MODEL.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Metadata/project_settings.config"))
            zip.write(ProfileEmbedder(InstrumentationRegistry.getInstrumentation().targetContext)
                .serializeConfig(config).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>""".toByteArray(),
            )
            zip.closeEntry()
        }
        return file
    }

    companion object {
        private const val TETRAHEDRON_MODEL = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
  <resources><object id="1" type="model"><mesh><vertices>
    <vertex x="10" y="10" z="0"/><vertex x="30" y="10" z="0"/>
    <vertex x="20" y="30" z="0"/><vertex x="20" y="20" z="20"/>
  </vertices><triangles>
    <triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/>
    <triangle v1="1" v2="2" v3="3"/><triangle v1="2" v2="0" v3="3"/>
  </triangles></mesh></object></resources><build><item objectid="1"/></build>
</model>"""
    }
}
