package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SliceArtifact
import com.u1.slicer.slice.SlicerTarget
import com.u1.slicer.slice.buildSliceArtifact
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BambuH2DNativeConfigTest {

    private lateinit var native: NativeLibrary
    private lateinit var model: File

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
        model = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "h2d-target-test.stl",
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
    fun h2dTargetSlicesAndPackagesHotendAwareProject() {
        assertTrue(native.loadModel(model.absolutePath))
        // Keep the fixture entirely inside the left nozzle's exclusive reach
        // so the inferred physical-nozzle assignment is deterministic.
        assertTrue(native.setModelInstances(floatArrayOf(15f, 15f)))
        val config = resolveTargetedSliceConfig(
            target = SlicerTarget.BambuH2D,
            base = SliceConfig(
                extruderCount = 1,
                nozzleTemp = 220,
                bedTemp = 60,
            ),
        )

        val result = native.slice(config)
        assertNotNull(result)
        assertTrue(result!!.errorMessage, result.success)
        val rawGcode = File(result.gcodePath)
        val rawText = rawGcode.readText()
        assertTrue(rawText.contains(";===== machine: H2D"))
        assertTrue(rawText.contains("; printable_area = 0x0,350x0,350x320,0x320"))
        assertTrue(rawText.contains("; printable_height = 325"))
        assertTrue(rawText.contains("; printer_model = Bambu Lab H2D"))
        assertTrue(rawText.contains("; printer_settings_id = Bambu Lab H2D 0.4 nozzle"))

        val artifact = buildSliceArtifact(
            target = SlicerTarget.BambuH2D,
            sourceModelName = model.name,
            gcodeFile = rawGcode,
            workingDir = model.parentFile!!,
            plateId = 1,
            filamentColours = listOf("#FFFFFF"),
            filamentTypes = listOf("PLA"),
        ) as SliceArtifact.BambuProjectArtifact

        ZipFile(artifact.projectFile).use { zip ->
            val projectSettings = JSONObject(
                zip.getInputStream(zip.getEntry("Metadata/project_settings.config")).reader().readText(),
            )
            assertEquals("O1D", projectSettings.getString("printer_model_id"))
            assertEquals(1, projectSettings.getJSONArray("filament_map").getInt(0))
            val packagedGcode = zip.getInputStream(zip.getEntry("Metadata/plate_1.gcode"))
                .reader().readText()
            assertTrue(packagedGcode.contains(" H0"))
        }
    }
}
