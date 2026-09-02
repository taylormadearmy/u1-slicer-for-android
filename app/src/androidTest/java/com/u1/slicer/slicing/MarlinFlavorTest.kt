package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.machine.GcodeFlavor
import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfile
import com.u1.slicer.machine.resolveMachineSliceConfig
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native end-to-end gate for authored Marlin machine profiles. */
@RunWith(AndroidJUnit4::class)
class MarlinFlavorTest {
    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = context.cacheDir
        outDir = File(cacheDir, "marlin_flavor_test").also { it.mkdirs() }
        embedder = ProfileEmbedder(context)
    }

    @After
    fun tearDown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    @Test
    fun authoredMarlinProfile_emitsMarlinTemperatureCommandsOnly() {
        val profile = requireNotNull(
            MachineProfile.fromResolvedOrcaJson(
                testAsset("generic_marlin_machine.json").readText(),
                MachineFamily.GENERIC,
            ),
        )
        assertEquals(GcodeFlavor.MARLIN, profile.gcodeFlavor)

        val input = testAsset("colored_3DBenchy (1).3mf")
        val info = ThreeMfParser.parse(input)
        val processed = BambuSanitizer.process(input, outDir)
        val embeddedConfig = embedder.buildConfig(
            info = info,
            targetExtruderCount = profile.extruderCount,
            filamentSettings = mapOf(
                "nozzle_temperature" to mutableListOf("215"),
                "nozzle_temperature_initial_layer" to mutableListOf("215"),
                "hot_plate_temp" to mutableListOf("60"),
                "hot_plate_temp_initial_layer" to mutableListOf("60"),
            ),
            machineProfile = profile,
        )
        val embedded = embedder.embed(processed, embeddedConfig, outDir, info)
        assertTrue("Authored Marlin Benchy must load", lib.loadModel(embedded.absolutePath))

        val config = resolveMachineSliceConfig(profile, marlinSliceConfig(profile))
        val result = lib.slice(config)
        assertNotNull("slice() must return a result", result)
        result!!
        assertTrue("Marlin slice must succeed: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        assertTrue("Marlin body must contain executable M104", executable(gcode, "M104"))
        assertTrue("Marlin body must contain executable M109", executable(gcode, "M109"))
        assertFalse("Marlin must not contain EXCLUDE_OBJECT_DEFINE", gcode.contains("EXCLUDE_OBJECT_DEFINE"))
        assertFalse("Marlin must not contain any EXCLUDE_OBJECT command", executable(gcode, "EXCLUDE_OBJECT_"))
        assertFalse("Marlin must not contain SET_HEATER_TEMPERATURE", executable(gcode, "SET_HEATER_TEMPERATURE"))
        assertFalse("Marlin must not contain SET_VELOCITY_LIMIT", executable(gcode, "SET_VELOCITY_LIMIT"))
        assertFalse("Marlin must not contain Snapmaker SM_ macros", executable(gcode, "SM_"))
        assertFalse("Marlin must not invoke PRINT_START", executable(gcode, "PRINT_START"))
        assertFalse("Marlin must not invoke END_PRINT", executable(gcode, "END_PRINT"))
    }

    private fun marlinSliceConfig(profile: MachineProfile): SliceConfig = SliceConfig(
        layerHeight = 0.2f,
        firstLayerHeight = 0.2f,
        perimeters = 2,
        topSolidLayers = 5,
        bottomSolidLayers = 4,
        fillDensity = 0.15f,
        fillPattern = "gyroid",
        printSpeed = 100f,
        travelSpeed = 150f,
        firstLayerSpeed = 40f,
        nozzleTemp = 215,
        bedTemp = 60,
        nozzleDiameter = profile.nozzleDiameter,
        filamentDiameter = 1.75f,
        retractLength = 0.8f,
        retractSpeed = 40f,
        extruderCount = profile.extruderCount,
        extruderTemps = intArrayOf(215),
        wipeTowerEnabled = false,
    )

    private fun executable(gcode: String, prefix: String): Boolean = gcode.lineSequence()
        .map(String::trim)
        .filterNot { it.startsWith(';') }
        .any { it.startsWith(prefix) }

    private fun testAsset(name: String): File {
        val file = File(cacheDir, "MarlinFlavorTest-${name.replace('/', '_')}")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            input.copyTo(file.outputStream())
        }
        return file
    }
}
