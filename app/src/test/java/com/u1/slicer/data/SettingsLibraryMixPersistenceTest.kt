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
    fun `decodeLibraryMixes tolerates malformed input`() {
        // Corrupt DataStore values (e.g. partial writes) must not crash.
        assertEquals(emptyList<MixedFilamentRow>(), SettingsRepository.decodeLibraryMixes("not-json"))
    }
}
