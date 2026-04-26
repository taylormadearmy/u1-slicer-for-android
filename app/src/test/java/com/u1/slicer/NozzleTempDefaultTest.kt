package com.u1.slicer

import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * nozzleTempDefaultForMaterial: returns a sensible nozzle temp for a slot
 * that has no linked FilamentProfile (e.g. after a Sync-from-Printer clears
 * the profile link).
 *
 * Rules:
 *  - Each named material maps to its standard temp
 *  - Lookup is case-insensitive
 *  - Unknown / unrecognised material falls back to 220 (PLA default)
 */
class NozzleTempDefaultTest {

    @Test fun `PLA returns 220`()  { assertEquals(220, nozzleTempDefaultForMaterial("PLA")) }
    @Test fun `PETG returns 235`() { assertEquals(235, nozzleTempDefaultForMaterial("PETG")) }
    @Test fun `ABS returns 270`()  { assertEquals(270, nozzleTempDefaultForMaterial("ABS")) }
    @Test fun `ASA returns 260`()  { assertEquals(260, nozzleTempDefaultForMaterial("ASA")) }
    @Test fun `PA returns 260`()   { assertEquals(260, nozzleTempDefaultForMaterial("PA")) }
    @Test fun `TPU returns 225`()  { assertEquals(225, nozzleTempDefaultForMaterial("TPU")) }
    @Test fun `PVA returns 210`()  { assertEquals(210, nozzleTempDefaultForMaterial("PVA")) }

    @Test fun `lowercase petg returns 235`() { assertEquals(235, nozzleTempDefaultForMaterial("petg")) }
    @Test fun `mixed-case Abs returns 270`() { assertEquals(270, nozzleTempDefaultForMaterial("Abs")) }

    @Test fun `unknown material falls back to 220`() { assertEquals(220, nozzleTempDefaultForMaterial("PC")) }
    @Test fun `empty string falls back to 220`()     { assertEquals(220, nozzleTempDefaultForMaterial("")) }
}

/**
 * computeFreshSlotTemps: regression tests for the stale-extruderTemps bug (v1.5.63).
 *
 * extruderTemps is set at model-load time. If the user changes a preset after loading,
 * the stored value goes stale. computeFreshSlotTemps() must be called at slice time
 * to recompute from current presets — this is the actual source of nozzle temperature
 * passed to the native slicer via JNI (applyConfigToPrusa reads extruder_temps directly;
 * the nozzle_temperature key in the embedded profile is not in profile_keys[] and is ignored).
 */
class ComputeFreshExtruderTempsTest {

    private fun preset(index: Int, material: String, profileId: Long? = null) =
        ExtruderPreset(index = index, materialType = material, filamentProfileId = profileId)

    private fun filament(id: Long, material: String, nozzleTemp: Int) =
        FilamentProfile(id = id, name = material, material = material,
            nozzleTemp = nozzleTemp, bedTemp = 60, retractLength = 0.8f, retractSpeed = 45f)

    @Test
    fun `single PLA slot returns 220`() {
        val result = computeFreshSlotTemps(1, null, listOf(preset(0, "PLA")), emptyList())
        assertArrayEquals(intArrayOf(220), result)
    }

    @Test
    fun `single PETG slot returns 235`() {
        val result = computeFreshSlotTemps(1, null, listOf(preset(0, "PETG")), emptyList())
        assertArrayEquals(intArrayOf(235), result)
    }

    @Test
    fun `linked filament profile nozzleTemp wins over materialType default`() {
        // Profile says 245 for PETG — custom profile should win over the 235 default
        val result = computeFreshSlotTemps(
            slotCount = 1,
            usedSlots = null,
            presets = listOf(preset(0, "PETG", profileId = 7L)),
            filaments = listOf(filament(7L, "PETG", 245))
        )
        assertArrayEquals(intArrayOf(245), result)
    }

    @Test
    fun `multi-colour four slots each get correct temp`() {
        val result = computeFreshSlotTemps(
            slotCount = 4,
            usedSlots = null,
            presets = listOf(
                preset(0, "PLA"),
                preset(1, "PETG"),
                preset(2, "ABS"),
                preset(3, "TPU")
            ),
            filaments = emptyList()
        )
        assertArrayEquals(intArrayOf(220, 235, 270, 225), result)
    }

    @Test
    fun `usedSlots remapping resolves physical slot correctly`() {
        // 2-colour model using physical slots E1+E4 (indices 0 and 3)
        val result = computeFreshSlotTemps(
            slotCount = 2,
            usedSlots = listOf(0, 3),
            presets = listOf(preset(0, "PLA"), preset(3, "PETG")),
            filaments = emptyList()
        )
        assertArrayEquals(intArrayOf(220, 235), result)
    }

    @Test
    fun `missing preset falls back to PLA default`() {
        // No preset configured for slot 0
        val result = computeFreshSlotTemps(1, null, emptyList(), emptyList())
        assertArrayEquals(intArrayOf(220), result)
    }

    @Test
    fun `stale-config regression - changing preset after load is reflected at slice time`() {
        // Simulates: model loaded with PLA preset, user switches to PETG before slicing.
        // Old bug: extruderTemps was fixed at load time (220); fix: recompute at slice time.
        val plaPreset = listOf(preset(0, "PLA"))
        val petgPreset = listOf(preset(0, "PETG"))

        val atLoadTime = computeFreshSlotTemps(1, null, plaPreset, emptyList())
        val atSliceTime = computeFreshSlotTemps(1, null, petgPreset, emptyList())

        assertArrayEquals(intArrayOf(220), atLoadTime)
        assertArrayEquals(intArrayOf(235), atSliceTime)  // must reflect the changed preset
    }
}
