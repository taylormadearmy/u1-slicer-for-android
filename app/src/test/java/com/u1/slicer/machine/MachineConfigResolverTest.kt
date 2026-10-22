package com.u1.slicer.machine

import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget
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
        assertEquals("GENERIC_KLIPPER", resolved.machineTarget)
        assertEquals(120f, resolved.wipeTowerX)
        assertEquals(130f, resolved.wipeTowerY)
        assertEquals(profile.machineStartGcode, resolved.machineStartGcode)
        assertEquals(profile.machineEndGcode, resolved.machineEndGcode)
        assertEquals(profile.machineChangeFilamentGcode, resolved.machineChangeFilamentGcode)
    }

    @Test
    fun `generic profile disables U1 wipe tower`() {
        val profile = profile("generic_klipper_300.json", MachineFamily.GENERIC)
        val base = SliceConfig(wipeTowerX = 290f, wipeTowerY = 290f, wipeTowerWidth = 60f)

        val resolved = resolveMachineSliceConfig(profile, base)

        assertEquals(false, resolved.wipeTowerEnabled)
    }

    @Test
    fun `active generic profile drives the shared non-Bambu slice target`() {
        val profile = requireNotNull(
            MachineProfile.fromResolvedOrcaJson(
                """
                    {
                      "type": "machine",
                      "name": "Prusa CORE One 0.4 nozzle",
                      "printer_model": "Prusa CORE One",
                      "printer_variant": "0.4",
                      "printable_area": ["0x0", "250x0", "250x220", "0x220"],
                      "printable_height": "270",
                      "nozzle_diameter": ["0.4"],
                      "gcode_flavor": "marlin2",
                      "machine_start_gcode": ["M17\nG28"],
                      "machine_end_gcode": ["M104 S0\nM84"]
                    }
                """.trimIndent(),
                MachineFamily.GENERIC,
            )
        )

        val resolved = resolveActiveMachineSliceConfig(
            target = SlicerTarget.SnapmakerU1,
            profile = profile,
            base = SliceConfig(),
        )
        val rawModelGcode = machineGcodeForSlice(
            target = SlicerTarget.SnapmakerU1,
            profile = profile,
            hasEmbeddedProfile = false,
        )

        assertEquals(250f, resolved.bedSizeX)
        assertEquals(220f, resolved.bedSizeY)
        assertEquals(270f, resolved.maxPrintHeight)
        assertEquals("GENERIC_MARLIN", resolved.machineTarget)
        assertEquals("M17\nG28", rawModelGcode.first)
        assertEquals("M104 S0\nM84", rawModelGcode.second)
    }

    @Test
    fun `embedded generic model cannot replace selected machine G-code`() {
        val profile = profile("generic_klipper_300.json", MachineFamily.GENERIC)

        assertEquals(
            profile.machineStartGcode to profile.machineEndGcode,
            machineGcodeForSlice(
                target = SlicerTarget.SnapmakerU1,
                profile = profile,
                hasEmbeddedProfile = true,
            )
        )
    }

    private fun profile(name: String, family: MachineFamily): MachineProfile =
        requireNotNull(
            MachineProfile.fromResolvedOrcaJson(
                requireNotNull(javaClass.classLoader?.getResource("machine/$name")).readText(),
                family,
            )
        )
}
