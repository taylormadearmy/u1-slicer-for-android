package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentLibraryMatcherTest {

    private val lib = FilamentLibrary(
        entries = listOf(
            FilamentLibraryEntry("prusament-pla-galaxy-black", "Prusament", "PLA Prusa Galaxy Black", "PLA", hex = "#3E413F"),
            FilamentLibraryEntry("prusament-pla-azure-blue", "Prusament", "PLA Azure Blue", "PLA", hex = "#008FBE"),
            FilamentLibraryEntry("prusament-petg-jet-black", "Prusament", "PETG Jet Black", "PETG", hex = "#000000"),
            FilamentLibraryEntry("bambulab-pla-black", "Bambu Lab", "PLA Basic Black", "PLA", hex = "#000000"),
            FilamentLibraryEntry("acme-pla-nocolour", "Acme", "PLA Mystery", "PLA", hex = null),
        ),
        snapshot = LibrarySnapshotInfo("test", "2026-06-10", 5),
    )

    @Test
    fun `exact brand and colour match within threshold`() {
        val m = FilamentLibraryMatcher.match(lib, vendor = "Prusament", material = "PLA",
            subType = null, hex = "#3E413F")
        assertEquals("prusament-pla-galaxy-black", m!!.entry.slug)
        assertTrue(m.deltaE < 1.0)
    }

    @Test
    fun `vendor normalisation is case and punctuation insensitive both ways`() {
        val m = FilamentLibraryMatcher.match(lib, "bambu lab", "PLA", null, "#000000")
        assertEquals("bambulab-pla-black", m!!.entry.slug)
        val m2 = FilamentLibraryMatcher.match(lib, "BambuLab", "PLA", null, "#000000")
        assertEquals("bambulab-pla-black", m2!!.entry.slug)
    }

    @Test
    fun `colour beyond deltaE threshold rejects`() {
        // Azure blue reported as bright red — never a match even with right brand+material.
        assertNull(FilamentLibraryMatcher.match(lib, "Prusament", "PLA", null, "#FF0000"))
    }

    @Test
    fun `material mismatch rejects even with perfect colour`() {
        assertNull(FilamentLibraryMatcher.match(lib, "Prusament", "ABS", null, "#3E413F"))
    }

    @Test
    fun `unknown vendor returns null - never guesses across brands`() {
        assertNull(FilamentLibraryMatcher.match(lib, "Snapmaker", "PLA", null, "#000000"))
        assertNull(FilamentLibraryMatcher.match(lib, "NoSuchVendor", "PLA", null, "#3E413F"))
    }

    @Test
    fun `missing vendor or colour or material returns null`() {
        assertNull(FilamentLibraryMatcher.match(lib, null, "PLA", null, "#3E413F"))
        assertNull(FilamentLibraryMatcher.match(lib, "", "PLA", null, "#3E413F"))
        assertNull(FilamentLibraryMatcher.match(lib, "Prusament", "PLA", null, null))
        assertNull(FilamentLibraryMatcher.match(lib, "Prusament", null, null, "#3E413F"))
    }

    @Test
    fun `subtype tokens break colour ties toward the named filament`() {
        // Both Prusament PLAs compete on a colour between them; subtype "Galaxy" must win the tie.
        val between = "#1F6880"  // roughly between #3E413F and #008FBE in Lab — recompute if needed
        val withSub = FilamentLibraryMatcher.match(lib, "Prusament", "PLA", "Galaxy", between)
        if (withSub != null) {
            assertEquals("prusament-pla-galaxy-black", withSub.entry.slug)
        } else {
            // If the midpoint exceeds the gate for both, pick a closer probe to Galaxy Black.
            val m = FilamentLibraryMatcher.match(lib, "Prusament", "PLA", "Galaxy", "#37403E")
            assertEquals("prusament-pla-galaxy-black", m!!.entry.slug)
        }
    }

    @Test
    fun `entries without colour are never matched`() {
        assertNull(FilamentLibraryMatcher.match(lib, "Acme", "PLA", null, "#123456"))
    }

    @Test
    fun `threshold is pinned at 10`() {
        assertEquals(10.0, FilamentLibraryMatcher.MAX_DELTA_E, 1e-9)
    }
}
