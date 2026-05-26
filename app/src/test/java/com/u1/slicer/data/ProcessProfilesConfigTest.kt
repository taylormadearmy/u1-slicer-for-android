package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessProfilesConfigTest {

    private val sample = ProcessProfile(
        id = "id-1",
        name = "Fine 0.16",
        sourceName = "0.16mm Fine @Snapmaker U1",
        keys = mapOf(
            "layer_height" to "0.16",
            "wall_loops" to "3",
            "thumbnails" to listOf("50x50", "100x100"),
        ),
    )

    @Test
    fun roundTrip_preservesScalarsAndArrays() {
        val cfg = ProcessProfilesConfig(profiles = listOf(sample), activeId = "id-1")
        val json = ProcessProfilesConfig.toJson(cfg)
        val parsed = ProcessProfilesConfig.fromJson(json)
        assertEquals(1, parsed.profiles.size)
        val p = parsed.profiles[0]
        assertEquals(sample.id, p.id)
        assertEquals(sample.name, p.name)
        assertEquals(sample.sourceName, p.sourceName)
        assertEquals("0.16", p.keys["layer_height"])
        assertEquals("3", p.keys["wall_loops"])
        val thumbs = p.keys["thumbnails"]
        assertTrue(thumbs is List<*>)
        assertEquals(listOf("50x50", "100x100"), thumbs as List<*>)
        assertEquals("id-1", parsed.activeId)
    }

    @Test
    fun applyAdd_appendsToList() {
        val a = ProcessProfilesConfig()
        val b = ProcessProfilesRepository.applyAdd(a, sample)
        assertEquals(1, b.profiles.size)
        assertNull("first imported profile must not auto-activate", b.activeId)
    }

    @Test
    fun applyRename_updatesNameOnlyForMatchingId() {
        val other = sample.copy(id = "id-2", name = "Standard")
        val cfg = ProcessProfilesConfig(profiles = listOf(sample, other), activeId = "id-2")
        val next = ProcessProfilesRepository.applyRename(cfg, "id-2", "Renamed")
        assertEquals("Fine 0.16", next.profiles[0].name)
        assertEquals("Renamed", next.profiles[1].name)
        assertEquals("id-2", next.activeId)
    }

    @Test
    fun applyRename_ignoresBlankName() {
        val cfg = ProcessProfilesConfig(profiles = listOf(sample), activeId = "id-1")
        val next = ProcessProfilesRepository.applyRename(cfg, "id-1", "   ")
        assertEquals(sample.name, next.profiles[0].name)
    }

    @Test
    fun applyDelete_removesAndClearsActiveIfMatched() {
        val cfg = ProcessProfilesConfig(profiles = listOf(sample), activeId = "id-1")
        val next = ProcessProfilesRepository.applyDelete(cfg, "id-1")
        assertTrue(next.profiles.isEmpty())
        assertNull("activeId is cleared when active profile is deleted", next.activeId)
    }

    @Test
    fun applyDelete_keepsActiveWhenOtherProfileDeleted() {
        val other = sample.copy(id = "id-2", name = "Standard")
        val cfg = ProcessProfilesConfig(profiles = listOf(sample, other), activeId = "id-1")
        val next = ProcessProfilesRepository.applyDelete(cfg, "id-2")
        assertEquals(1, next.profiles.size)
        assertEquals("id-1", next.activeId)
    }

    @Test
    fun applySetActive_setsAndClears() {
        val cfg = ProcessProfilesConfig(profiles = listOf(sample))
        val activated = ProcessProfilesRepository.applySetActive(cfg, "id-1")
        assertEquals("id-1", activated.activeId)
        val cleared = ProcessProfilesRepository.applySetActive(activated, null)
        assertNull(cleared.activeId)
    }

    @Test
    fun applySetActive_ignoresUnknownId() {
        val cfg = ProcessProfilesConfig(profiles = listOf(sample), activeId = "id-1")
        val next = ProcessProfilesRepository.applySetActive(cfg, "does-not-exist")
        assertEquals("id-1", next.activeId)
    }

    @Test
    fun fromJson_garbage_returnsDefault() {
        val parsed = ProcessProfilesConfig.fromJson("not json")
        assertTrue(parsed.profiles.isEmpty())
        assertNull(parsed.activeId)
    }
}
