package com.u1.slicer.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2.4 — instrumented coverage of [autoSuggestMapping].
 *
 * Lives in androidTest because the underlying [findClosestExtruder] uses
 * `android.graphics.Color.parseColor` for RGB parsing, which is unavailable
 * in JVM-only tests.
 */
@RunWith(AndroidJUnit4::class)
class FilamentMappingDialogInstrumentedTest {

    private val redPreset = ExtruderPreset(0, "#FF0000", "PLA")
    private val greenPreset = ExtruderPreset(1, "#00FF00", "PLA")
    private val bluePreset = ExtruderPreset(2, "#0000FF", "PLA")
    private val whitePreset = ExtruderPreset(3, "#FFFFFF", "PLA")
    private val presets = listOf(redPreset, greenPreset, bluePreset, whitePreset)

    @Test
    fun singleColour_mapsToClosestExtruder() {
        val list = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0011", "PLA", FilamentSource.FILE_COLOUR)
            )
        )
        assertEquals(listOf(0), autoSuggestMapping(list, presets))  // closest to red
    }

    @Test
    fun fourColourIdentityMatching_producesIdentityMapping() {
        val list = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#0000FF", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(3, "#FFFFFF", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        assertEquals(listOf(0, 1, 2, 3), autoSuggestMapping(list, presets))
    }

    @Test
    fun permutedColours_mapToCorrespondingPresets() {
        val list = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FFFFFF", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#00FF00", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(3, "#0000FF", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        assertEquals(listOf(3, 0, 1, 2), autoSuggestMapping(list, presets))
    }

    @Test
    fun moreFileColoursThanPresets_duplicatesAllowed() {
        val list = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#FF1010", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#00FF00", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(3, "#0000FF", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(4, "#FFFFFF", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        val mapping = autoSuggestMapping(list, presets)
        assertEquals(5, mapping.size)
        assertEquals(0, mapping[0])  // both red entries → slot 0 (overlap)
        assertEquals(0, mapping[1])
        assertEquals(1, mapping[2])
        assertEquals(2, mapping[3])
        assertEquals(3, mapping[4])
    }

    @Test
    fun emptyPresetList_defaultsAllEntriesToSlot0() {
        val list = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        assertEquals(listOf(0, 0), autoSuggestMapping(list, emptyList()))
    }

    @Test
    fun stlSyntheticEntry_mapsToClosestPreset() {
        val list = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FFFFFF", "PLA", FilamentSource.STL_DEFAULT)
            )
        )
        assertEquals(listOf(3), autoSuggestMapping(list, presets))  // white → E4
    }
}
