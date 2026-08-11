package com.u1.slicer.bambu

import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.slice.SlicerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BambuTargetedConfigResolverTest {

    @Test
    fun `every supported bambu model has a matching slicer target and machine profile`() {
        BambuModel.entries.forEach { model ->
            val target = SlicerTarget.forBambuModel(model)
            assertTrue("Missing machine profile for $model", target in BAMBU_MACHINE_PROFILES)
        }
    }

    @Test
    fun `a1 mini swaps in its envelope and engine matched complete machine gcode`() {
        val base = SliceConfig(
            bedSizeX = 270f,
            bedSizeY = 270f,
            maxPrintHeight = 270f,
            machineStartGcode = "PRINT_START",
            machineEndGcode = "PRINT_END",
        )

        val profile = BAMBU_MACHINE_PROFILES.getValue(SlicerTarget.BambuA1Mini)
        val resolved = resolveTargetedSliceConfig(SlicerTarget.BambuA1Mini, base)

        assertEquals(profile.bedSizeX, resolved.bedSizeX)
        assertEquals(profile.bedSizeY, resolved.bedSizeY)
        assertEquals(profile.maxPrintHeight, resolved.maxPrintHeight)
        assertEquals("BAMBU_A1_MINI", resolved.machineTarget)
        assertTrue(resolved.machineStartGcode.contains("date: 20240620"))
        assertTrue(resolved.machineEndGcode.contains("date: 20231229"))
        assertTrue(resolved.machineChangeFilamentGcode.contains("date: 20240913"))
        assertTrue(resolved.machineChangeFilamentGcode.contains("M620 S[next_extruder]A"))
        assertTrue(resolved.machineChangeFilamentGcode.contains("M621 S[next_extruder]A"))
        assertFalse(resolved.machineStartGcode.lineSequence().any { it.trimStart().startsWith("=") })
    }

    @Test
    fun `all bambu targets have complete engine matched identities and templates`() {
        val expectedModelIds = mapOf(
            SlicerTarget.BambuX1C to "BL-P001",
            SlicerTarget.BambuX1E to "C13",
            SlicerTarget.BambuP1S to "C12",
            SlicerTarget.BambuP1P to "C11",
            SlicerTarget.BambuA1 to "N2S",
            SlicerTarget.BambuA1Mini to "N1",
            SlicerTarget.BambuH2D to "O1D",
        )

        expectedModelIds.forEach { (target, modelId) ->
            val profile = BAMBU_MACHINE_PROFILES.getValue(target)
            val resolved = resolveTargetedSliceConfig(target, SliceConfig())
            assertTrue(target.supportsLocalSlicing)
            assertEquals(modelId, profile.printerModelId)
            assertTrue(profile.printerModel.isNotBlank())
            assertTrue(profile.printerSettingsId.endsWith("0.4 nozzle"))
            assertTrue(profile.defaultPlaFilamentSettingsId.startsWith("Bambu PLA Basic @BBL"))
            assertTrue(resolved.machineStartGcode.isNotBlank())
            assertTrue(resolved.machineEndGcode.isNotBlank())
            assertTrue(resolved.machineChangeFilamentGcode.isNotBlank())
            assertTrue(resolved.machineChangeFilamentGcode.contains("M620"))
            assertTrue(resolved.machineChangeFilamentGcode.contains("M621"))
        }
    }

    @Test
    fun `x and p targets use official 250 millimetre printable height`() {
        listOf(
            SlicerTarget.BambuX1C,
            SlicerTarget.BambuX1E,
            SlicerTarget.BambuP1S,
            SlicerTarget.BambuP1P,
        ).forEach { target ->
            assertEquals(250f, BAMBU_MACHINE_PROFILES.getValue(target).maxPrintHeight)
            assertEquals(250f, resolveTargetedSliceConfig(target, SliceConfig()).maxPrintHeight)
        }
        assertEquals(256f, BAMBU_MACHINE_PROFILES.getValue(SlicerTarget.BambuA1).maxPrintHeight)
    }

    @Test
    fun `h2d uses official union bed dual nozzle reach and compatible machine templates`() {
        val profile = BAMBU_MACHINE_PROFILES.getValue(SlicerTarget.BambuH2D)
        val resolved = resolveTargetedSliceConfig(
            SlicerTarget.BambuH2D,
            SliceConfig(wipeTowerX = 340f, wipeTowerY = 300f),
        )

        assertEquals(350f, resolved.bedSizeX)
        assertEquals(320f, resolved.bedSizeY)
        assertEquals(325f, resolved.maxPrintHeight)
        assertEquals(60f, resolved.wipeTowerWidth)
        assertEquals(262f, resolved.wipeTowerX)
        assertEquals(257f, resolved.wipeTowerY)
        assertEquals("BAMBU_H2D", resolved.machineTarget)
        assertEquals("O1D", profile.printerModelId)
        assertEquals(2, profile.nozzleDiameters.size)
        assertEquals(BambuPrintBounds(0.0, 0.0, 325.0, 320.0), profile.nozzlePrintableAreas[0])
        assertEquals(BambuPrintBounds(25.0, 0.0, 350.0, 320.0), profile.nozzlePrintableAreas[1])
        assertEquals(1, profile.masterNozzle)
        assertFalse(profile.supportsFilamentTrackSwitch)
        assertTrue(resolved.machineStartGcode.contains("machine: H2D"))
        assertTrue(resolved.machineEndGcode.contains(";===== H2D"))
        assertTrue(resolved.machineChangeFilamentGcode.contains("M620 S[next_extruder]A"))
    }

    @Test
    fun `single nozzle prime tower uses official width and stays inside brim safe bed bounds`() {
        val resolved = resolveTargetedSliceConfig(
            SlicerTarget.BambuA1Mini,
            SliceConfig(wipeTowerX = 340f, wipeTowerY = 300f, wipeTowerWidth = 60f),
        )

        assertEquals(35f, resolved.wipeTowerWidth)
        assertEquals(142f, resolved.wipeTowerX)
        assertEquals(142f, resolved.wipeTowerY)
    }

    @Test
    fun `snapmaker target leaves the config unchanged`() {
        val base = SliceConfig(
            bedSizeX = 270f,
            bedSizeY = 270f,
            maxPrintHeight = 270f,
            machineStartGcode = "PRINT_START",
            machineEndGcode = "PRINT_END",
            machineTarget = "SNAPMAKER_U1",
        )

        val resolved = resolveTargetedSliceConfig(
            target = SlicerTarget.SnapmakerU1,
            base = base,
        )

        assertEquals(base, resolved)
    }

    @Test
    fun `switching a resolved a1 config back to snapmaker removes bambu templates`() {
        val a1 = resolveTargetedSliceConfig(SlicerTarget.BambuA1Mini, SliceConfig())
        val snapmaker = resolveTargetedSliceConfig(SlicerTarget.SnapmakerU1, a1)

        assertEquals("SNAPMAKER_U1", snapmaker.machineTarget)
        assertTrue(snapmaker.machineStartGcode.isBlank())
        assertTrue(snapmaker.machineEndGcode.isBlank())
        assertTrue(snapmaker.machineChangeFilamentGcode.isBlank())
    }
}
