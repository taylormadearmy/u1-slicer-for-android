package com.u1.slicer.machine

import com.u1.slicer.data.SliceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineConfigResolverTest {
    @Test
    fun `U1 profile preserves the released defaults and machine policy`() {
        val profile = profile("snapmaker_u1_resolved.json", MachineFamily.SNAPMAKER)

        val resolved = resolveMachineSliceConfig(profile, SliceConfig())

        assertEquals(270f, resolved.bedSizeX)
        assertEquals(270f, resolved.bedSizeY)
        assertEquals(270f, resolved.maxPrintHeight)
        assertEquals("SNAPMAKER_U1", resolved.machineTarget)
        assertEquals(170f, resolved.wipeTowerX)
        assertEquals(140f, resolved.wipeTowerY)
        assertEquals(profile.machineStartGcode, resolved.machineStartGcode)
        assertEquals(profile.machineEndGcode, resolved.machineEndGcode)
        assertEquals(profile.machineChangeFilamentGcode, resolved.machineChangeFilamentGcode)
    }

    @Test
    fun `generic profile supplies geometry and preserves an in-bounds wipe tower`() {
        val profile = profile("generic_klipper_300.json", MachineFamily.GENERIC)
        val base = SliceConfig(wipeTowerX = 120f, wipeTowerY = 130f)

        val resolved = resolveMachineSliceConfig(profile, base)

        assertEquals(300f, resolved.bedSizeX)
        assertEquals(300f, resolved.bedSizeY)
        assertEquals(350f, resolved.maxPrintHeight)
        assertEquals("GENERIC", resolved.machineTarget)
        assertEquals(120f, resolved.wipeTowerX)
        assertEquals(130f, resolved.wipeTowerY)
        assertEquals(profile.machineStartGcode, resolved.machineStartGcode)
        assertEquals(profile.machineEndGcode, resolved.machineEndGcode)
        assertEquals(profile.machineChangeFilamentGcode, resolved.machineChangeFilamentGcode)
    }

    @Test
    fun `generic profile clamps an out-of-bounds wipe tower`() {
        val profile = profile("generic_klipper_300.json", MachineFamily.GENERIC)
        val base = SliceConfig(wipeTowerX = 290f, wipeTowerY = 290f, wipeTowerWidth = 60f)

        val resolved = resolveMachineSliceConfig(profile, base)

        assertTrue(resolved.wipeTowerX <= 300f - resolved.wipeTowerWidth)
        assertTrue(resolved.wipeTowerY <= 300f - resolved.wipeTowerWidth)
    }

    private fun profile(name: String, family: MachineFamily): MachineProfile =
        requireNotNull(
            MachineProfile.fromResolvedOrcaJson(
                requireNotNull(javaClass.classLoader?.getResource("machine/$name")).readText(),
                family,
            )
        )
}
