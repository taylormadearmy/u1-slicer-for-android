package com.u1.slicer.ui

import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import com.u1.slicer.network.NozzleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H2DNozzleAssignmentUiTest {

    @Test
    fun `assignment state is source-wide and missing rows default to auto`() {
        assertEquals(listOf(2, 0, 0), normalizeH2DNozzleAssignments(listOf(2), 3))
        assertEquals(
            listOf(2, 1, 0),
            updateH2DNozzleAssignment(
                assignments = listOf(2),
                filamentCount = 3,
                filamentIndex = 1,
                assignment = 1,
            ),
        )
    }

    @Test
    fun `fixed routes enforce side while switchable and unknown topology remain compatible`() {
        val leftFixed = BambuSlotNozzleRoute(NozzleSide.LEFT, switchable = false)
        val leftFts = BambuSlotNozzleRoute(NozzleSide.LEFT, switchable = true)

        assertTrue(isBambuSlotCompatibleWithNozzle(NozzleSide.LEFT, leftFixed))
        assertFalse(isBambuSlotCompatibleWithNozzle(NozzleSide.RIGHT, leftFixed))
        assertTrue(isBambuSlotCompatibleWithNozzle(NozzleSide.RIGHT, leftFts))
        assertTrue(
            isBambuSlotCompatibleWithNozzle(
                NozzleSide.RIGHT,
                BambuSlotNozzleRoute(NozzleSide.UNKNOWN),
            ),
        )
        assertTrue(isBambuSlotCompatibleWithNozzle(NozzleSide.RIGHT, route = null))
    }

    @Test
    fun `auto suggestion colour matches within required nozzle side`() {
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PETG", FilamentSource.FILE_COLOUR),
            ),
        )
        val presets = listOf(
            ExtruderPreset(index = 10, color = "#0000FF", displayLabel = "Left AMS"),
            ExtruderPreset(index = 20, color = "#FF0000", displayLabel = "Right AMS"),
            ExtruderPreset(index = 21, color = "#00FF00", displayLabel = "Right external"),
        )
        val routes = mapOf(
            10 to BambuSlotNozzleRoute(NozzleSide.LEFT),
            20 to BambuSlotNozzleRoute(NozzleSide.RIGHT),
            21 to BambuSlotNozzleRoute(NozzleSide.RIGHT),
        )

        assertEquals(
            listOf(10, 21),
            autoSuggestSideAwareMapping(
                canonicalList = canonical,
                extruderPresets = presets,
                requiredNozzleSides = listOf(NozzleSide.LEFT, NozzleSide.RIGHT),
                slotNozzleRoutes = routes,
            ),
        )
    }
}
