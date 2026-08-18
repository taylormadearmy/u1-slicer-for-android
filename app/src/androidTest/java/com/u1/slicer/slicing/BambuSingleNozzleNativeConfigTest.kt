package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BambuSingleNozzleNativeConfigTest {

    private lateinit var native: NativeLibrary
    private lateinit var model: File

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
        model = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "bambu-single-nozzle-target-test.stl",
        )
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("tetrahedron.stl")
            .use { input -> model.outputStream().use(input::copyTo) }
    }

    @After
    fun tearDown() {
        native.clearModel()
        model.delete()
    }

    @Test
    fun allSingleNozzleTargetsProduceTheirOfficialMachineIdentityAndLimits() {
        val targets = listOf(
            MachineExpectation(
                SlicerTarget.BambuX1C,
                "Bambu Lab X1 Carbon",
                "Bambu Lab X1 Carbon 0.4 nozzle",
                hasFrontLeftExclusion = true,
                printableHeight = "250",
                zSpeed = "20,20",
                zAcceleration = "500,500",
            ),
            MachineExpectation(
                SlicerTarget.BambuX1E,
                "Bambu Lab X1E",
                "Bambu Lab X1E 0.4 nozzle",
                hasFrontLeftExclusion = true,
                printableHeight = "250",
                zSpeed = "20,20",
                zAcceleration = "500,500",
            ),
            MachineExpectation(
                SlicerTarget.BambuP1S,
                "Bambu Lab P1S",
                "Bambu Lab P1S 0.4 nozzle",
                hasFrontLeftExclusion = true,
                printableHeight = "250",
                zSpeed = "20,20",
                zAcceleration = "500,500",
            ),
            MachineExpectation(
                SlicerTarget.BambuP1P,
                "Bambu Lab P1P",
                "Bambu Lab P1P 0.4 nozzle",
                hasFrontLeftExclusion = true,
                printableHeight = "250",
                zSpeed = "20,20",
                zAcceleration = "500,500",
            ),
            MachineExpectation(
                SlicerTarget.BambuP2S,
                "Bambu Lab P2S",
                "Bambu Lab P2S 0.4 nozzle",
                hasFrontLeftExclusion = false,
                printableHeight = "256",
                zSpeed = "20,20,20",
                zAcceleration = "500,500,500",
            ),
            MachineExpectation(
                SlicerTarget.BambuA1,
                "Bambu Lab A1",
                "Bambu Lab A1 0.4 nozzle",
                hasFrontLeftExclusion = false,
                printableHeight = "256",
                zSpeed = "30,30",
                zAcceleration = "1500,1500",
            ),
        )

        targets.forEach { expected ->
            native.clearModel()
            assertTrue("load ${expected.target}", native.loadModel(model.absolutePath))
            val config = resolveTargetedSliceConfig(
                target = expected.target,
                base = SliceConfig(extruderCount = 1, nozzleTemp = 220, bedTemp = 60),
            )

            val result = native.slice(config)
            assertNotNull(expected.target.name, result)
            assertTrue("${expected.target}: ${result!!.errorMessage}", result.success)
            val gcode = File(result.gcodePath).readText()

            assertTrue(gcode.contains("; printable_area = 0x0,256x0,256x256,0x256"))
            assertTrue(
                "${expected.target}: ${gcode.lineSequence().firstOrNull { it.startsWith("; printable_height =") }}",
                gcode.contains("; printable_height = ${expected.printableHeight}"),
            )
            assertTrue(gcode.contains("; printer_model = ${expected.model}"))
            assertTrue(gcode.contains("; printer_settings_id = ${expected.settingsId}"))
            assertTrue(gcode.contains("; machine_max_speed_z = ${expected.zSpeed}"))
            assertTrue(gcode.contains("; machine_max_acceleration_z = ${expected.zAcceleration}"))
            assertTrue(gcode.contains("; gcode_flavor = marlin"))
            assertTrue(gcode.contains("; single_extruder_multi_material = 1"))
            assertTrue(gcode.contains("; initial_layer_print_height = 0.2"))
            assertTrue(gcode.contains("; elefant_foot_compensation = 0.15"))
            assertTrue(
                "${expected.target}: ${gcode.lineSequence().firstOrNull { it.startsWith("; curr_bed_type =") }}",
                gcode.contains("; curr_bed_type = Textured PEI Plate"),
            )
            assertTrue(gcode.contains("; textured_plate_temp = 55"))
            assertTrue(gcode.contains("; filament_flow_ratio = 0.98"))
            assertTrue(gcode.contains("; reduce_fan_stop_start_freq = 1"))
            assertTrue(gcode.contains("; fan_cooling_layer_time = 100"))
            assertTrue(gcode.contains("M620 S[next_extruder]A"))
            assertTrue(gcode.contains("M621 S[next_extruder]A"))
            if (expected.hasFrontLeftExclusion) {
                assertTrue(gcode.contains("; bed_exclude_area = 0x0,18x0,18x28,0x28"))
            } else {
                assertFalse(gcode.contains("; bed_exclude_area = 0x0,18x0,18x28,0x28"))
            }
            assertFalse(gcode.contains("PRINT_START"))
            assertFalse(gcode.contains("PRINT_END"))
        }
    }

    private data class MachineExpectation(
        val target: SlicerTarget,
        val model: String,
        val settingsId: String,
        val hasFrontLeftExclusion: Boolean,
        val printableHeight: String,
        val zSpeed: String,
        val zAcceleration: String,
    )
}
