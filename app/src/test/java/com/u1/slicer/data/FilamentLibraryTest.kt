package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentLibraryTest {

    private fun lib() = FilamentLibrary.parse(
        """
        {"schema":1,"source":"OpenPrintTag/openprinttag-database","commit":"abc1234","date":"2026-06-10","count":5,
         "entries":[
          {"s":"acme-pla-red","b":"Acme","n":"PLA Red","m":"PLA","h":"#FF0000","td":2.0,"d":1.24,"nl":205,"nh":225,"bl":40,"bh":60},
          {"s":"acme-pla-blue","b":"Acme","n":"PLA Blue","m":"PLA","h":"#0000FF"},
          {"s":"acme-petg-red","b":"Acme","n":"PETG Red","m":"PETG","h":"#EE0000"},
          {"s":"bolt-pa6-nat","b":"Bolt","n":"PA6 Natural","m":"PA","mr":"PA6","d":1.14},
          {"s":"bolt-pla-red","b":"Bolt","n":"PLA Cherry Red","m":"PLA","h":"#F10505","ri":1.46}
         ]}
        """.trimIndent()
    )

    @Test
    fun `parse exposes snapshot info and entries`() {
        val l = lib()
        assertEquals(5, l.entries.size)
        assertEquals("abc1234", l.snapshot.commit)
        assertEquals("2026-06-10", l.snapshot.date)
        assertEquals(5, l.snapshot.count)
    }

    @Test
    fun `parse maps optional fields, nulls when absent`() {
        val red = lib().entry("acme-pla-red")!!
        assertEquals("Acme", red.brand)
        assertEquals("#FF0000", red.hex)
        assertEquals(2.0, red.td!!, 1e-9)
        assertEquals(205, red.minNozzle)
        assertEquals(225, red.maxNozzle)
        assertEquals(40, red.minBed)
        assertEquals(60, red.maxBed)
        assertNull(red.ri)
        val nat = lib().entry("bolt-pa6-nat")!!
        assertNull(nat.hex)
        assertEquals("PA", nat.material)
        assertEquals("PA6", nat.materialRaw)
        assertNull(nat.td)
        assertNull(nat.minNozzle)
    }

    @Test
    fun `entry returns null for unknown slug`() {
        assertNull(lib().entry("nope"))
    }

    @Test
    fun `blank query lists favourites then recents then rest alphabetical`() {
        val res = lib().search(
            "", material = null,
            favourites = setOf("bolt-pla-red"),
            recents = listOf("acme-petg-red", "bolt-pla-red"),
        )
        assertEquals("bolt-pla-red", res[0].slug)        // favourite first
        assertEquals("acme-petg-red", res[1].slug)       // recent (favourites not repeated)
        // rest alphabetical by brand+name
        assertEquals(listOf("acme-pla-blue", "acme-pla-red", "bolt-pa6-nat"), res.drop(2).map { it.slug })
    }

    @Test
    fun `query matches across brand name material, all tokens must match`() {
        val res = lib().search("acme red")
        assertEquals(setOf("acme-pla-red", "acme-petg-red"), res.map { it.slug }.toSet())
        assertTrue(lib().search("acme red petg").map { it.slug } == listOf("acme-petg-red"))
    }

    @Test
    fun `query is case-insensitive`() {
        assertEquals(listOf("bolt-pla-red"), lib().search("CHERRY").map { it.slug })
    }

    @Test
    fun `material filter applies to blank and non-blank queries`() {
        assertEquals(setOf("acme-pla-red", "acme-pla-blue", "bolt-pla-red"),
            lib().search("", material = "PLA").map { it.slug }.toSet())
        assertEquals(listOf("acme-petg-red"), lib().search("red", material = "PETG").map { it.slug })
    }

    @Test
    fun `favourites rank first on non-blank query`() {
        val res = lib().search("red", favourites = setOf("bolt-pla-red"))
        assertEquals("bolt-pla-red", res[0].slug)
    }

    @Test
    fun `limit caps results`() {
        assertEquals(2, lib().search("", limit = 2).size)
    }

    @Test
    fun `parse rejects malformed json`() {
        try {
            FilamentLibrary.parse("{not json")
            org.junit.Assert.fail("expected exception")
        } catch (_: Exception) { /* expected */ }
    }

    @Test
    fun `displayName is brand plus name`() {
        assertEquals("Acme PLA Red", lib().entry("acme-pla-red")!!.displayName)
    }
}
