package com.u1.slicer.data

import com.u1.slicer.SlicerViewModel
import com.u1.slicer.viewer.ModelRenderer
import org.junit.Assert.*
import org.junit.Test

class DataClassesTest {

    // --- FilamentProfile ---

    @Test
    fun `FilamentProfile default values`() {
        val profile = FilamentProfile(
            name = "Test PLA",
            material = "PLA",
            nozzleTemp = 210,
            bedTemp = 60,
            printSpeed = 60f,
            retractLength = 0.8f,
            retractSpeed = 45f
        )
        assertEquals(0L, profile.id) // autoGenerate default
        assertEquals("#808080", profile.color)
        assertEquals(1.24f, profile.density, 0.001f)
        assertFalse(profile.isDefault)
    }

    @Test
    fun `FilamentProfile copy with custom color`() {
        val profile = FilamentProfile(
            name = "Red PLA",
            material = "PLA",
            nozzleTemp = 210,
            bedTemp = 60,
            printSpeed = 60f,
            retractLength = 0.8f,
            retractSpeed = 45f,
            color = "#FF0000"
        )
        assertEquals("#FF0000", profile.color)
    }

    @Test
    fun `FilamentProfile equality`() {
        val a = FilamentProfile(id = 1, name = "PLA", material = "PLA", nozzleTemp = 210, bedTemp = 60, printSpeed = 60f, retractLength = 0.8f, retractSpeed = 45f)
        val b = FilamentProfile(id = 1, name = "PLA", material = "PLA", nozzleTemp = 210, bedTemp = 60, printSpeed = 60f, retractLength = 0.8f, retractSpeed = 45f)
        assertEquals(a, b)
    }

    // --- SliceJob ---

    @Test
    fun `SliceJob creation`() {
        val job = SliceJob(
            modelName = "benchy.stl",
            gcodePath = "/data/output.gcode",
            totalLayers = 150,
            estimatedTimeSeconds = 3600f,
            estimatedFilamentMm = 5000f,
            layerHeight = 0.2f,
            fillDensity = 0.15f,
            nozzleTemp = 210,
            bedTemp = 60,
            supportEnabled = false,
            filamentType = "PLA"
        )
        assertEquals("benchy.stl", job.modelName)
        assertEquals(150, job.totalLayers)
        assertTrue(job.timestamp > 0)
    }

    @Test
    fun `SliceJob timestamp defaults to current time`() {
        val before = System.currentTimeMillis()
        val job = SliceJob(
            modelName = "test.stl",
            gcodePath = "/out.gcode",
            totalLayers = 10,
            estimatedTimeSeconds = 100f,
            estimatedFilamentMm = 500f,
            layerHeight = 0.2f,
            fillDensity = 0.2f,
            nozzleTemp = 200,
            bedTemp = 55,
            supportEnabled = true,
            filamentType = "PETG"
        )
        val after = System.currentTimeMillis()
        assertTrue(job.timestamp in before..after)
    }

    // --- GcodeLayer data classes ---

    @Test
    fun `GcodeMove data class`() {
        val move = com.u1.slicer.gcode.GcodeMove(
            type = com.u1.slicer.gcode.MoveType.EXTRUDE,
            x0 = 0f, y0 = 0f,
            x1 = 10f, y1 = 20f,
            extruder = 1
        )
        assertEquals(10f, move.x1, 0.001f)
        assertEquals(1, move.extruder)
    }

    @Test
    fun `GcodeMove default extruder is 0`() {
        val move = com.u1.slicer.gcode.GcodeMove(
            type = com.u1.slicer.gcode.MoveType.TRAVEL,
            x0 = 0f, y0 = 0f, x1 = 5f, y1 = 5f
        )
        assertEquals(0, move.extruder)
    }

    @Test
    fun `ParsedGcode default bed dimensions`() {
        val gcode = com.u1.slicer.gcode.ParsedGcode(layers = emptyList())
        assertEquals(270f, gcode.bedWidth, 0.001f)
        assertEquals(270f, gcode.bedHeight, 0.001f)
    }

    // --- ModelInfo ---

    // --- ModelRenderer.WipeTowerInfo ---

    @Test
    fun `WipeTowerInfo stores fields correctly`() {
        val info = ModelRenderer.WipeTowerInfo(x = 170f, y = 140f, width = 60f, depth = 60f)
        assertEquals(170f, info.x, 0.001f)
        assertEquals(140f, info.y, 0.001f)
        assertEquals(60f, info.width, 0.001f)
        assertEquals(60f, info.depth, 0.001f)
    }

    @Test
    fun `WipeTowerInfo equality and copy`() {
        val a = ModelRenderer.WipeTowerInfo(x = 10f, y = 20f, width = 30f, depth = 40f)
        val b = a.copy()
        assertEquals(a, b)
        val c = a.copy(x = 50f)
        assertNotEquals(a, c)
        assertEquals(50f, c.x, 0.001f)
        assertEquals(20f, c.y, 0.001f)
    }

    @Test
    fun `WipeTowerInfo hashCode consistent with equals`() {
        val a = ModelRenderer.WipeTowerInfo(1f, 2f, 3f, 4f)
        val b = ModelRenderer.WipeTowerInfo(1f, 2f, 3f, 4f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // --- SlicerViewModel.ModelScale ---

    @Test
    fun `ModelScale defaults to 1x uniform`() {
        val scale = SlicerViewModel.ModelScale()
        assertEquals(1f, scale.x, 0.001f)
        assertEquals(1f, scale.y, 0.001f)
        assertEquals(1f, scale.z, 0.001f)
        assertTrue(scale.isUniform)
        assertEquals(1f, scale.uniform, 0.001f)
    }

    @Test
    fun `ModelScale isUniform true when all axes equal`() {
        assertTrue(SlicerViewModel.ModelScale(1.5f, 1.5f, 1.5f).isUniform)
        assertTrue(SlicerViewModel.ModelScale(0.5f, 0.5f, 0.5f).isUniform)
    }

    @Test
    fun `ModelScale isUniform false when axes differ`() {
        assertFalse(SlicerViewModel.ModelScale(1f, 2f, 1f).isUniform)
        assertFalse(SlicerViewModel.ModelScale(1f, 1f, 2f).isUniform)
        assertFalse(SlicerViewModel.ModelScale(2f, 1f, 1f).isUniform)
    }

    @Test
    fun `ModelScale uniform returns x value`() {
        val scale = SlicerViewModel.ModelScale(2f, 2f, 2f)
        assertEquals(2f, scale.uniform, 0.001f)
    }

    @Test
    fun `ModelScale equality and copy`() {
        val a = SlicerViewModel.ModelScale(1.5f, 2f, 0.5f)
        val b = SlicerViewModel.ModelScale(1.5f, 2f, 0.5f)
        assertEquals(a, b)
        val c = a.copy(z = 3f)
        assertEquals(1.5f, c.x, 0.001f)
        assertEquals(3f, c.z, 0.001f)
    }

    // --- ModelInfo ---

    @Test
    fun `ModelInfo creation`() {
        val info = ModelInfo(
            filename = "cube.stl",
            format = "STL",
            sizeX = 20f, sizeY = 20f, sizeZ = 20f,
            triangleCount = 12,
            volumeCount = 1,
            isManifold = true
        )
        assertEquals("cube.stl", info.filename)
        assertEquals(12, info.triangleCount)
        assertTrue(info.isManifold)
    }
}
