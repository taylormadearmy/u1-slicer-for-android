package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FilamentLibraryRecentsTest {
    @Test
    fun `new slug goes first`() {
        assertEquals(listOf("c", "a", "b"), updateRecents(listOf("a", "b"), "c"))
    }

    @Test
    fun `existing slug moves to front without duplicate`() {
        assertEquals(listOf("b", "a", "c"), updateRecents(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `capped at MAX_RECENTS`() {
        val full = (1..FilamentLibraryRepository.MAX_RECENTS).map { "s$it" }
        val out = updateRecents(full, "new")
        assertEquals(FilamentLibraryRepository.MAX_RECENTS, out.size)
        assertEquals("new", out.first())
        assertEquals(false, out.contains("s${FilamentLibraryRepository.MAX_RECENTS}"))
    }
}
