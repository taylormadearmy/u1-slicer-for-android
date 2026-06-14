package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLibraryMixPersistenceTest {

    @Test
    fun `encodeLibraryMixes round-trips empty list to empty string`() {
        val encoded = SettingsRepository.encodeLibraryMixes(emptyList())
        assertEquals("", encoded)
        assertEquals(emptyList<MixedFilamentRow>(), SettingsRepository.decodeLibraryMixes(""))
    }

    @Test
    fun `encodeLibraryMixes round-trips multiple rows`() {
        val rows = listOf(
            MixedFilamentRow.fromLegacy(
                id = 1L, componentA = 1, componentB = 2, mixBPercent = 50,
                distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                label = "E1+E2 @ 50%", inLibrary = true,
            ),
            MixedFilamentRow.fromLegacy(
                id = 2L, componentA = 2, componentB = 4, mixBPercent = 33,
                distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
                label = "My Sage", inLibrary = true,
            ),
        )
        val encoded = SettingsRepository.encodeLibraryMixes(rows)
        val decoded = SettingsRepository.decodeLibraryMixes(encoded)
        assertEquals(rows, decoded)
    }

    @Test
    fun `encodeLibraryMixes round-trips top-surface settings`() {
        val rows = listOf(
            MixedFilamentRow.fromLegacy(
                id = 7L, componentA = 1, componentB = 3, mixBPercent = 40,
                distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                label = "Glazed", inLibrary = true,
            ).copy(
                topMixMode = MixedFilamentRow.TopMixMode.DITHER,
                fineTopLines = true,
                ironingGlaze = true,
            ),
            MixedFilamentRow.fromLegacy(
                id = 8L, componentA = 2, componentB = 4, mixBPercent = 60,
                distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                label = "Off", inLibrary = true,
            ).copy(topMixMode = MixedFilamentRow.TopMixMode.OFF),
        )
        val decoded = SettingsRepository.decodeLibraryMixes(SettingsRepository.encodeLibraryMixes(rows))
        assertEquals(rows, decoded)
        assertEquals(MixedFilamentRow.TopMixMode.DITHER, decoded[0].topMixMode)
        assertEquals(true, decoded[0].fineTopLines)
        assertEquals(true, decoded[0].ironingGlaze)
        assertEquals(MixedFilamentRow.TopMixMode.OFF, decoded[1].topMixMode)
    }

    @Test
    fun `decodeLibraryMixes defaults top-surface settings when keys absent (old save)`() {
        // Encode a row, then strip the new keys to simulate a pre-feature save.
        val rows = listOf(
            MixedFilamentRow.fromLegacy(
                id = 8L, componentA = 1, componentB = 2, mixBPercent = 50,
                distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                label = "Old", inLibrary = true,
            ),
        )
        val arr = org.json.JSONArray(SettingsRepository.encodeLibraryMixes(rows))
        val obj = arr.getJSONObject(0)
        obj.remove("topMixMode"); obj.remove("fineTopLines"); obj.remove("ironingGlaze")
        val decoded = SettingsRepository.decodeLibraryMixes(arr.toString())
        assertEquals(MixedFilamentRow.TopMixMode.STRIPES, decoded[0].topMixMode)
        assertEquals(false, decoded[0].fineTopLines)
        assertEquals(false, decoded[0].ironingGlaze)
    }

    @Test
    fun `decodeLibraryMixes tolerates malformed input`() {
        // Corrupt DataStore values (e.g. partial writes) must not crash.
        assertEquals(emptyList<MixedFilamentRow>(), SettingsRepository.decodeLibraryMixes("not-json"))
    }
}
