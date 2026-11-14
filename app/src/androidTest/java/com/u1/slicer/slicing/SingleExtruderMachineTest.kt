package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfile
import com.u1.slicer.machine.resolveMachineSliceConfig
import com.u1.slicer.slice.GenericMachineProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** End-to-end native gate for a generic machine with one physical extruder. */
@RunWith(AndroidJUnit4::class)
class SingleExtruderMachineTest {

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
        outDir = File(cacheDir, "single_extruder_machine_test").also { it.mkdirs() }
        embedder = ProfileEmbedder(context)
    }

    @After
    fun tearDown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    @Test
    fun genericOneExtruderProfile_emitsSingleToolGcode() {
        val profile = requireNotNull(
            MachineProfile.fromResolvedOrcaJson(GENERIC_ONE_EXTRUDER_PROFILE, MachineFamily.GENERIC)
        )
        assertEquals(1, profile.extruderCount)
        assertEquals("G28\nPRINT_START", profile.machineStartGcode)

        val input = asset("colored_3DBenchy (1).3mf")
        val info = ThreeMfParser.parse(input)
        assertTrue("Fixture must exercise painted multi-colour input", info.hasPaintData)
        assertTrue("Fixture must contain multiple source colours", info.detectedColors.size > 1)

        val processed = BambuSanitizer.process(input, outDir)
        val sourceTuning = embedder.buildConfig(
            info = info,
            targetExtruderCount = profile.extruderCount,
            filamentSettings = mapOf(
                "nozzle_temperature" to mutableListOf("217"),
                "nozzle_temperature_initial_layer" to mutableListOf("217"),
                "hot_plate_temp" to mutableListOf("61"),
                "hot_plate_temp_initial_layer" to mutableListOf("61"),
            ),
            machineProfile = profile,
        )
        // Match the production generic path: the source supplies tuning only.
        val embeddedConfig = GenericMachineProfile.fromJson(profile.machineJson).compose(sourceTuning)
        assertEquals(listOf("0x0"), embeddedConfig["extruder_offset"])
        assertEquals("0", embeddedConfig["single_extruder_multi_material"])

        val embedded = embedder.embed(processed, embeddedConfig, outDir, info)
        assertTrue("Generic one-extruder model must load", lib.loadModel(embedded.absolutePath))
        val selected = File(outDir, "selected-machine.json")
        selected.writeText(embedder.serializeConfig(embeddedConfig))
        assertTrue("Selected machine must load separately from the source", lib.loadProfile(selected.absolutePath))

        val config = resolveMachineSliceConfig(
            profile,
            SliceConfig(
                layerHeight = 0.2f,
                firstLayerHeight = 0.2f,
                perimeters = 2,
                topSolidLayers = 5,
                bottomSolidLayers = 4,
                fillDensity = 0.15f,
                fillPattern = "gyroid",
                printSpeed = 150f,
                travelSpeed = 200f,
                firstLayerSpeed = 50f,
                nozzleTemp = 217,
                bedTemp = 61,
                nozzleDiameter = profile.nozzleDiameter,
                filamentDiameter = 1.75f,
                retractLength = 0.8f,
                retractSpeed = 45f,
                extruderCount = profile.extruderCount,
                extruderTemps = intArrayOf(217),
                wipeTowerEnabled = true,
                wipeTowerX = 170f,
                wipeTowerY = 140f,
                wipeTowerWidth = 60f,
            )
        )
        val result = lib.slice(config)
        assertNotNull("slice() must return a result", result)
        result!!
        assertTrue("Generic one-extruder slice must succeed: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        val nonZeroToolCommands = Regex("(?m)^T[1-9][0-9]*(?:\\s.*)?$").findAll(gcode).toList()
        assertTrue("Single-extruder G-code must not contain T1+: $nonZeroToolCommands", nonZeroToolCommands.isEmpty())
        assertFalse("Single-extruder G-code must not contain toolchange sections", gcode.contains("; CP TOOLCHANGE"))
        assertFalse("Single-extruder G-code must not contain a prime tower", gcode.contains(";TYPE:Prime tower"))
        assertEquals(listOf("0"), headerValues(gcode, "enable_prime_tower"))
        assertEquals(listOf("217"), headerValues(gcode, "nozzle_temperature"))
        assertEquals(listOf("217"), headerValues(gcode, "nozzle_temperature_initial_layer"))
        assertEquals(listOf("61"), headerValues(gcode, "hot_plate_temp"))
        assertEquals(listOf("61"), headerValues(gcode, "hot_plate_temp_initial_layer"))
    }

    private fun asset(name: String): File {
        val file = File(cacheDir, name.replace("/", "_"))
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            input.copyTo(file.outputStream())
        }
        return file
    }

    private fun headerValues(gcode: String, key: String): List<String> = gcode.lineSequence()
        .filter { it.trimStart().startsWith("; $key = ") }
        .map { it.substringAfter("=").trim() }
        .toList()

    companion object {
        private val GENERIC_ONE_EXTRUDER_PROFILE = """
            {
              "type": "machine",
              "name": "Generic Klipper 300",
              "printer_model": "Generic Klipper",
              "printer_variant": "0.4",
              "printable_area": ["0x0", "300x0", "300x300", "0x300"],
              "printable_height": "350",
              "nozzle_diameter": ["0.4"],
              "extruder_offset": ["0x0"],
              "gcode_flavor": "klipper",
              "single_extruder_multi_material": "0",
              "machine_start_gcode": ["G28\nPRINT_START"],
              "machine_end_gcode": ["END_PRINT"],
              "machine_change_filament_gcode": "",
              "machine_pause_gcode": "PAUSE"
            }
        """.trimIndent()
    }
}
