package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.CosmosMachineProfile
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SlicerTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Offline slicing only: no connection, heater command or print is sent to a physical printer. */
@RunWith(AndroidJUnit4::class)
class CosmosNativeConfigTest {
    @Test fun rawStlEmitsCosmosMacroParametersAndNeverTheEmergencyStopCommands() {
        verifySlice(emptyMap(), 210, 60)
    }

    @Test fun cosmosMacroUsesTheSelectedFilamentFirstLayerTemperatures() {
        verifySlice(mapOf("nozzle_temperature" to listOf("240"), "nozzle_temperature_initial_layer" to listOf("245"),
            "hot_plate_temp" to listOf("75"), "hot_plate_temp_initial_layer" to listOf("80"),
            "textured_plate_temp" to listOf("75"), "textured_plate_temp_initial_layer" to listOf("80")), 245, 80)
    }

    private fun verifySlice(tuning: Map<String, Any>, nozzle: Int, bed: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val native = NativeLibrary()
        val model = File(context.cacheDir, "cosmos-tetrahedron.stl")
        val profileFile = File(context.cacheDir, "cosmos-profile.json")
        try {
            native.clearModel()
            InstrumentationRegistry.getInstrumentation().context.assets.open("tetrahedron.stl").use { source ->
                model.outputStream().use(source::copyTo)
            }
            assertTrue(native.loadModel(model.absolutePath))
            assertTrue(native.setModelInstances(floatArrayOf(100f, 100f)))
            val profile = CosmosMachineProfile.profile
            profileFile.writeText(ProfileEmbedder(context).serializeConfig(profile.compose(
                GenericMachineProfile.centauriCarbon().settings, overrides = tuning)))
            assertTrue(native.loadProfile(profileFile.absolutePath))
            val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), profile))
            assertTrue(result?.errorMessage, result?.success == true)
            val commands = File(result!!.gcodePath).readLines().map { it.substringBefore(';').trim() }.filter { it.isNotBlank() }
            val start = commands.single { it.startsWith("PRINT_START ") }
            assertEquals("PRINT_START EXTRUDER=$nozzle BED=$bed CHAMBER=0", start)
            assertEquals(1, commands.count { it == "PRINT_END" })
            assertFalse(commands.any { Regex("^M(?:729|8213)(?:\\s|$)").containsMatchIn(it) })
            assertFalse(commands.any { it.startsWith("SM_PRINT") || it.startsWith("MOVE_TO_") })
            assertFalse(commands.any { it.contains("[") || it.contains("]") })
        } finally {
            native.clearModel()
            model.delete()
            profileFile.delete()
        }
    }
}
