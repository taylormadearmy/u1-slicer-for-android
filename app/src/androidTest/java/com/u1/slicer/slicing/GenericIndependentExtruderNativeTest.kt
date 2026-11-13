package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SlicerTarget
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Selected machine, assigned volumes, and executable extrusion must agree. No printer is contacted. */
@RunWith(AndroidJUnit4::class)
class GenericIndependentExtruderNativeTest {
    private lateinit var native: NativeLibrary
    private lateinit var workspace: File
    private lateinit var embedder: ProfileEmbedder

    @Before fun setUp() {
        assertTrue(NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workspace = File(context.cacheDir, "generic-independent-extruder-test").apply { mkdirs() }
        embedder = ProfileEmbedder(context)
    }

    @After fun tearDown() {
        native.clearModel()
        workspace.deleteRecursively()
    }

    @Test fun klipperAssignedIndependentToolsProduceExtrusionWithSelectedOffsetsAndHeat() {
        verifyIndependentTools("klipper", SlicerTarget.GenericKlipper)
    }

    @Test fun marlinAssignedIndependentToolsProduceExtrusionWithSelectedOffsetsAndHeat() {
        verifyIndependentTools("marlin", SlicerTarget.GenericMarlin)
    }

    @Test fun extrusionOracleRecognizesLeadingDotMovesButNotRetractionOrResets() {
        val commands = listOf("M83", "T0", "G1 X.5 Y-.2 E.013", "T1", "G1 X10 E-.8", "G92 E0", "G1 E.8")
        assertEquals(setOf(0), extrudingTools(commands))
        assertEquals(setOf(0, 1), extrudingTools(commands + "G1 X10 Y20 E.05721"))
        assertEquals(emptySet<Int>(), extrudingTools(listOf("M82", "T0", "G92 E10", "G1 X1 E9", "G92 E0")))
        assertEquals(setOf(1), extrudingTools(listOf("M82", "T1", "G92 E10", "G1 X1 E10.1")))
    }

    private fun verifyIndependentTools(flavor: String, target: SlicerTarget) {
        val source = File(workspace, "calib-cube-10-dual-colour-merged.3mf")
        InstrumentationRegistry.getInstrumentation().context.assets.open(source.name).use { input ->
            source.outputStream().use(input::copyTo)
        }
        val sanitized = BambuSanitizer.process(source, workspace)
        for (toolCount in listOf(2, 4)) {
            native.clearModel()
            assertTrue(native.loadModel(sanitized.absolutePath))
            assertEquals("Fixture must be one assembled object", 1, native.nativeGetObjectCount())
            assertEquals("Fixture must have two printable volumes", 2, native.nativeGetVolumeCount(0))
            if (toolCount == 4) assertEquals(1, native.nativeDuplicateObject(0))
            val positions = if (toolCount == 2) floatArrayOf(80f, 100f)
                else floatArrayOf(80f, 100f, 140f, 100f)
            assertTrue(native.setObjectPositions(positions))
            for (objectIndex in 0 until native.nativeGetObjectCount()) {
                for (volumeIndex in 0 until 2) {
                    val slot = objectIndex * 2 + volumeIndex + 1
                    assertTrue(native.nativeSetVolumeExtruder(objectIndex, volumeIndex, slot))
                    assertEquals(slot, native.nativeGetVolumeExtruder(objectIndex, volumeIndex))
                }
            }

            val temperatures = listOf(215, 245, 220, 240).take(toolCount)
            val offsets = listOf("0x0", "12x-3", "-8x4", "6x7").take(toolCount)
            val start = "G28\nM83\nM190 S60\n" + temperatures.indices.joinToString("\n") {
                "M109 T$it S{nozzle_temperature_initial_layer[$it]}"
            } + "\n; SELECTED_INDEPENDENT_MACHINE"
            val profile = GenericMachineProfile.fromJson(JSONObject().apply {
                put("name", "Independent $flavor $toolCount")
                put("gcode_flavor", flavor)
                put("nozzle_diameter", JSONArray(List(toolCount) { "0.4" }))
                put("extruder_offset", JSONArray(offsets))
                put("printable_area", JSONArray(listOf("0x0", "300x0", "300x300", "0x300")))
                put("printable_height", "300")
                put("single_extruder_multi_material", "0")
                put("machine_start_gcode", start)
                put("machine_end_gcode", "M104 S0\nM140 S0")
                put("use_relative_e_distances", "1")
            }.toString())
            val selected = File(workspace, "selected-$flavor-$toolCount.json")
            selected.writeText(embedder.serializeConfig(profile.compose(overrides = mapOf(
                "nozzle_temperature" to temperatures.map(Int::toString),
                "nozzle_temperature_initial_layer" to temperatures.map(Int::toString),
                "filament_colour" to listOf("#FF0000", "#0000FF", "#00FF00", "#FFFFFF").take(toolCount),
                "filament_type" to List(toolCount) { "PLA" },
                "skirt_loops" to "0",
                "brim_width" to "0",
            ))))
            assertTrue(native.loadProfile(selected.absolutePath))
            val result = native.slice(resolveTargetedSliceConfig(target, SliceConfig(), profile))
            assertTrue("$flavor $toolCount tools: ${result?.errorMessage}", result?.success == true)
            val gcode = File(result!!.gcodePath).readText()
            assertEquals(offsets.joinToString(","), header(gcode, "extruder_offset"))
            assertEquals(temperatures.joinToString(","), header(gcode, "nozzle_temperature_initial_layer"))
            assertEquals("0", header(gcode, "single_extruder_multi_material"))
            assertTrue(gcode.contains("SELECTED_INDEPENDENT_MACHINE"))
            assertFalse(gcode.contains("SM_PRINT"))
            assertFalse(gcode.contains("PRINT_START"))
            assertFalse(gcode.contains("EXCLUDE_OBJECT_"))
            val commands = gcode.lineSequence().map { it.substringBefore(';').trim() }.toList()
            temperatures.forEachIndexed { tool, temperature ->
                assertTrue("Tool $tool must heat to its selected temperature", commands.any {
                    it.matches(Regex("M109\\s+T$tool\\s+S$temperature(?:\\.0+)?"))
                })
            }
            val toolCommands = commands.mapNotNull { Regex("^T(\\d+)$").matchEntire(it)?.groupValues?.get(1)?.toInt() }.toSet()
            val expectedTools = (0 until toolCount).toSet()
            assertEquals("Only selected tools may be commanded", expectedTools, toolCommands)
            assertEquals("Each assigned physical tool must actually extrude", expectedTools, extrudingTools(commands))
            val bounds = GcodeValidator.checkBedBounds(gcode, bedMaxX = 300.0, bedMaxY = 300.0)
            assertTrue("All output must remain in the selected bed: $bounds", bounds.withinBounds)
        }
    }

    private fun header(gcode: String, key: String): String = gcode.lineSequence()
        .first { it.startsWith("; $key = ") }.substringAfter(" = ").trim()

    private fun extrudingTools(commands: List<String>): Set<Int> {
        var activeTool = 0
        var relative = false
        val extrusionPositions = mutableMapOf<Int, Double>()
        val extruded = mutableSetOf<Int>()
        for (command in commands) {
            Regex("^T(\\d+)$").matchEntire(command)?.let { activeTool = it.groupValues[1].toInt() }
            if (command == "M83") relative = true
            if (command == "M82") relative = false
            val amount = Regex("(?:^|\\s)E(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))").find(command)?.groupValues?.get(1)?.toDouble() ?: continue
            if (command.startsWith("G92 ")) {
                extrusionPositions[activeTool] = amount
            } else if (command.startsWith("G0 ") || command.startsWith("G1 ")) {
                val delta = if (relative) amount else amount - (extrusionPositions[activeTool] ?: 0.0)
                if (!relative) extrusionPositions[activeTool] = amount
                if (delta > 0 && Regex("(?:^|\\s)[XY]-?(?:\\d|\\.\\d)").containsMatchIn(command)) extruded += activeTool
            }
        }
        return extruded
    }
}
