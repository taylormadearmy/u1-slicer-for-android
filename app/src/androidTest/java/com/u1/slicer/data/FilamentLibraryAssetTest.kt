package com.u1.slicer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The bundled filament library asset is packaged in the APK and parses at runtime. */
@RunWith(AndroidJUnit4::class)
class FilamentLibraryAssetTest {

    private fun load(): FilamentLibrary {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val text = ctx.assets.open(FilamentLibraryRepository.ASSET_NAME)
            .bufferedReader().use { it.readText() }
        return FilamentLibrary.parse(text)
    }

    @Test
    fun assetPackagedAndParses_withExpectedScale() {
        val lib = load()
        assertTrue("expected >10000 entries, got ${lib.entries.size}", lib.entries.size > 10000)
        assertEquals(lib.snapshot.count, lib.entries.size)
    }

    @Test
    fun knownEntryPresent() {
        val azure = load().entry("prusament-pla-azure-blue")!!
        assertEquals("Prusament", azure.brand)
        assertEquals("#008FBE", azure.hex)
    }
}
