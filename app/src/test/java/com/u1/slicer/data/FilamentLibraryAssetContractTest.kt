package com.u1.slicer.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentLibraryAssetContractTest {

    private val asset = File("src/main/assets/filament_library.json")

    @Test
    fun `bundled asset exists and parses with expected scale`() {
        assertTrue("asset missing — run tools/openprinttag-convert", asset.exists())
        val lib = FilamentLibrary.parse(asset.readText())
        assertTrue("expected >10000 FFF entries, got ${lib.entries.size}", lib.entries.size > 10000)
        assertEquals(lib.snapshot.count, lib.entries.size)
        assertTrue(lib.snapshot.commit.isNotBlank())
        assertTrue(lib.snapshot.date.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        assertTrue("expected >=100 brands", lib.entries.map { it.brand }.toSet().size >= 100)
    }

    @Test
    fun `every entry has slug brand name and well-formed hex when present`() {
        val lib = FilamentLibrary.parse(asset.readText())
        lib.entries.forEach { e ->
            assertTrue(e.slug.isNotBlank()); assertTrue(e.brand.isNotBlank()); assertTrue(e.name.isNotBlank())
            e.hex?.let { assertTrue("bad hex $it on ${e.slug}", it.matches(Regex("#[0-9A-F]{6}"))) }
        }
    }

    @Test
    fun `known prusament entry round-trips`() {
        val lib = FilamentLibrary.parse(asset.readText())
        val azure = lib.entry("prusament-pla-azure-blue")!!
        assertEquals("Prusament", azure.brand)
        assertEquals("PLA", azure.material)
        assertEquals("#008FBE", azure.hex)
    }
}
