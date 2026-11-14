package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.gcode.U1TopCover
import com.u1.slicer.gcode.applyPrintTimeRemap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class U1TopCoverInstrumentedTest {
    @Test fun nativeStlOutputGetsPurifierBeforeHoming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = File.createTempFile("cover-model", ".stl", instrumentation.targetContext.cacheDir)
        val lib = com.u1.slicer.NativeLibrary()
        try {
            instrumentation.context.assets.open("tetrahedron.stl").use { input ->
                model.outputStream().use { input.copyTo(it) }
            }
            val profile = org.json.JSONObject(instrumentation.targetContext.assets
                .open("orca_profiles/printer/snapmaker_u1.json").bufferedReader().use { it.readText() })
            lib.clearModel()
            assertTrue(lib.loadModel(model.absolutePath))
            val result = requireNotNull(lib.slice(com.u1.slicer.data.SliceConfig(
                machineStartGcode = profile.getString("machine_start_gcode"),
                machineEndGcode = profile.getString("machine_end_gcode"),
            )))
            assertTrue(result.errorMessage, result.success)
            val gcode = File(result.gcodePath)
            assertTrue(U1TopCover.apply(gcode, listOf("PLA")))
            val text = gcode.readText()
            assertTrue(text.indexOf("SET_PURIFIER_MODE") < text.indexOf("G28 X Y"))
            assertTrue(text.contains("MODE=1 DESIRE_TEMP=42 ALARM_TEMP=48 FAN_SPEED=0.6 DELAY_OFF=0"))
        } finally {
            lib.clearModel()
            model.delete()
        }
    }

    @Test fun generatedJobPolicySurvivesUploadRemapping() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("cover-source", ".gcode", cache)
        val output = File.createTempFile("cover-remap", ".gcode", cache)
        try {
            for (material in listOf("PLA", "PETG", "ABS")) {
                source.writeText("PRINT_START\nG28\nT0\nG1 X10 E1\nPRINT_END\n; filament used [mm] = 10\n")
                assertTrue(U1TopCover.apply(source, listOf(material)))
                applyPrintTimeRemap(source.absolutePath, output.absolutePath, listOf(3))
                val lines = output.readLines()
                assertEquals(U1TopCover.command(listOf(material)), lines[1])
                assertTrue(lines.contains("T3"))
                assertTrue(lines.contains("PRINT_END"))
            }
        } finally {
            source.delete()
            output.delete()
        }
    }
}
