package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeValidator
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native JNI geometry regression coverage for non-U1 rectangular beds. */
@RunWith(AndroidJUnit4::class)
class MachineBedSizeTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    }

    @After
    fun tearDown() {
        lib.clearModel()
    }

    @Test
    fun generic300mmBed_preservesBenchyPlacedBeyondU1Bed() {
        val gcode = sliceBenchyAtCentre(
            SlicingIntegrationTest.DEFAULT_CONFIG.copy(
                bedSizeX = 300f,
                bedSizeY = 300f,
                maxPrintHeight = 350f,
                machineTarget = "GENERIC",
            )
        )

        val bounds = GcodeValidator.checkBedBounds(gcode, bedMaxX = 300.0, bedMaxY = 300.0)
        assertTrue("300 mm G-code must stay within the configured bed: $bounds", bounds.withinBounds)
        assertTrue(
            "Benchy centred at X=260 on a 300 mm bed must retain motion beyond X=270; " +
                "got maxX=${bounds.maxX}",
            bounds.maxX > 270.0,
        )
    }

    @Test
    fun u1Bed_autoCentresSameOutOfBoundsPlacement() {
        val gcode = sliceBenchyAtCentre(SlicingIntegrationTest.DEFAULT_CONFIG)
        val bounds = GcodeValidator.checkBedBounds(gcode, bedMaxX = 270.0, bedMaxY = 270.0)
        assertTrue("U1 auto-centred G-code must remain within 270 mm: $bounds", bounds.withinBounds)
        assertTrue(
            "The U1 regression pin must not preserve the near-right-edge placement; got maxX=${bounds.maxX}",
            bounds.maxX <= 270.0,
        )
    }

    private fun sliceBenchyAtCentre(config: SliceConfig): String {
        val file = File(cacheDir, "MachineBedSizeTest-3DBenchy.stl")
        InstrumentationRegistry.getInstrumentation().context.assets.open("3DBenchy.stl").use { input ->
            input.copyTo(file.outputStream())
        }
        assertTrue("3DBenchy fixture must load", lib.loadModel(file.absolutePath))
        if (config.machineTarget == "GENERIC") {
            val selected = File(cacheDir, "MachineBedSizeTest-machine.json")
            val machine = com.u1.slicer.slice.GenericMachineProfile.fromJson(org.json.JSONObject().apply {
                put("name", "Machine bed fixture")
                put("gcode_flavor", "klipper")
                put("nozzle_diameter", org.json.JSONArray(List(config.extruderCount) { "0.4" }))
                put("printable_area", org.json.JSONArray(listOf("0x0", "300x0", "300x300", "0x300")))
                put("printable_height", "350")
                put("machine_start_gcode", "G28")
                put("machine_end_gcode", "M104 S0")
                put("single_extruder_multi_material", "0")
            }.toString())
            selected.writeText(com.u1.slicer.bambu.ProfileEmbedder(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).serializeConfig(machine.compose()))
            assertTrue("Selected machine must load", lib.loadProfile(selected.absolutePath))
        }
        val info = lib.getModelInfo()
        assertNotNull("3DBenchy model info is required", info)
        info!!
        assertTrue(
            "Placement must use the native lower-left convention",
            // 3DBenchy is about 60 mm wide: its centre must stay at X=260 (not 280)
            // to be inside a 300 mm bed while still exercising motion beyond U1's X=270.
            lib.setModelInstances(floatArrayOf(260f - info.sizeX / 2f, 150f - info.sizeY / 2f)),
        )
        val result = lib.slice(if (config.machineTarget == "GENERIC") {
            config.copy(machineStartGcode = "G28", machineEndGcode = "M104 S0")
        } else config)
        assertNotNull("Slice result must be returned", result)
        assertTrue("Benchy slice must succeed: ${result!!.errorMessage}", result.success)
        return File(result.gcodePath).readText()
    }
}
