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
class BambuA1MiniNativeConfigTest {

    private lateinit var native: NativeLibrary
    private lateinit var model: File

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
        model = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "a1-mini-target-test.stl",
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
    fun a1MiniTargetProducesBambuMachineGcodeWithoutU1Commands() {
        assertTrue(native.loadModel(model.absolutePath))
        val config = resolveTargetedSliceConfig(
            target = SlicerTarget.BambuA1Mini,
            base = SliceConfig(
                extruderCount = 1,
                nozzleTemp = 220,
                bedTemp = 60,
            ),
        )

        val result = native.slice(config)
        assertNotNull(result)
        assertTrue(result!!.errorMessage, result.success)
        val gcode = File(result.gcodePath).readText()

        assertTrue(gcode.contains(";===== machine: A1 mini"))
        assertTrue(gcode.contains("; change_filament_gcode = ;===== machine: A1 mini"))
        assertTrue(gcode.contains("date: 20240913"))
        assertTrue(gcode.contains("M620 S[next_extruder]A"))
        assertTrue(gcode.contains("M621 S[next_extruder]A"))
        assertTrue(gcode.contains("; printable_area = 0x0,180x0,180x180,0x180"))
        assertTrue(gcode.contains("; printable_height = 180"))
        assertTrue(gcode.contains("; printer_model = Bambu Lab A1 mini"))
        assertTrue(gcode.contains("; printer_settings_id = Bambu Lab A1 mini 0.4 nozzle"))
        assertTrue(gcode.contains("; gcode_flavor = marlin"))
        assertTrue(gcode.contains("; single_extruder_multi_material = 1"))
        assertTrue(gcode.contains("; exclude_object = 0"))
        assertFalse(gcode.contains("EXCLUDE_OBJECT_DEFINE"))
        assertFalse(gcode.contains("EXCLUDE_OBJECT_START"))
        assertFalse(gcode.contains("EXCLUDE_OBJECT_END"))
    }
}
