package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.machine.U1NozzlePolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Offline native slices: no printer connection, upload, or physical printing. */
@RunWith(AndroidJUnit4::class)
class U1NozzleSlicingTest {
    private lateinit var native: NativeLibrary
    private lateinit var directory: File

    @Before fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "u1-nozzle-slicing").also { it.mkdirs() }
    }

    @After fun tearDown() {
        native.clearModel()
        directory.deleteRecursively()
    }

    @Test fun stl02() = sliceAndVerify(0.2f, embedded = false)
    @Test fun stl04() = sliceAndVerify(0.4f, embedded = false)
    @Test fun stl06() = sliceAndVerify(0.6f, embedded = false)
    @Test fun stl08() = sliceAndVerify(0.8f, embedded = false)
    @Test fun embeddedThreeMf02() = sliceAndVerify(0.2f, embedded = true)
    @Test fun embeddedThreeMf04() = sliceAndVerify(0.4f, embedded = true)
    @Test fun embeddedThreeMf06() = sliceAndVerify(0.6f, embedded = true)
    @Test fun embeddedThreeMf08() = sliceAndVerify(0.8f, embedded = true)

    private fun sliceAndVerify(diameter: Float, embedded: Boolean) {
        val defaults = U1NozzlePolicy.defaults(diameter)
        val height = defaults.getValue("layer_height").toString().toFloat()
        val firstHeight = defaults.getValue("initial_layer_print_height").toString().toFloat()
        val model = if (embedded) {
            val source = tetrahedronThreeMf()
            val info = ThreeMfParser.parse(source)
            val embedder = ProfileEmbedder(InstrumentationRegistry.getInstrumentation().targetContext)
            val config = embedder.buildConfig(info, nozzleDiameter = diameter)
            embedder.embed(source, config, directory, info, nozzleDiameter = diameter)
        } else {
            File(directory, "tetrahedron.stl").also { target ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("tetrahedron.stl")
                    .use { source -> target.outputStream().use(source::copyTo) }
            }
        }
        assertTrue("Model should load", native.loadModel(model.absolutePath))
        assertTrue(native.setModelInstances(floatArrayOf(100f, 100f)))
        val result = native.slice(SliceConfig(
            nozzleDiameter = diameter,
            layerHeight = height,
            firstLayerHeight = firstHeight,
            perimeters = 2,
            topSolidLayers = 2,
            bottomSolidLayers = 2,
        ))
        assertTrue("$diameter mm: ${result?.errorMessage}", result?.success == true)
        val output = File(result!!.gcodePath)
        try {
            val gcode = output.readText()
            assertEquals(diameter, setting(gcode, "nozzle_diameter"), 0.0001f)
            assertEquals(height, setting(gcode, "layer_height"), 0.0001f)
            assertEquals(firstHeight, setting(gcode, "initial_layer_print_height"), 0.0001f)
            assertEquals(diameter * 1.05f, setting(gcode, "outer_wall_line_width"), 0.0001f)
            // Native flow calculation must consume the chosen width, in addition to
            // reporting it in the config footer. This header is rounded to 2 decimals.
            val flowWidth = Regex("(?m)^; external perimeters extrusion width = ([0-9.]+)mm$")
                .find(gcode)?.groupValues?.get(1)?.toFloat()
            assertTrue("Native wall flow width must be present", flowWidth != null)
            assertEquals(diameter * 1.05f, flowWidth!!, 0.0051f)
            assertTrue("Slice must contain extrusion moves", gcode.lineSequence().any {
                it.startsWith("G1 ") && it.contains(" X") && it.contains(" E")
            })
            val layerHeights = Regex("(?m)^; ?(?:LAYER_HEIGHT|HEIGHT): ?([0-9.]+)")
                .findAll(gcode).map { it.groupValues[1].toFloat() }.toList()
            assertTrue("Slice must contain layer annotations", layerHeights.isNotEmpty())
            assertEquals("Actual first layer height", firstHeight, layerHeights.first(), 0.0001f)
            assertTrue("Actual layer heights must use the selected process", layerHeights.drop(1).any {
                kotlin.math.abs(it - height) < 0.0001f
            })
        } finally {
            output.delete()
        }
    }

    private fun setting(gcode: String, key: String): Float {
        val value = Regex("(?m)^; ${Regex.escape(key)} = ([^\\r\\n]+)")
            .find(gcode)?.groupValues?.get(1)
        assertTrue("Missing $key in G-code", value != null)
        return value!!.substringBefore(',').trim().toFloat()
    }

    private fun tetrahedronThreeMf(): File = File(directory, "tetrahedron.3mf").also { file ->
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
