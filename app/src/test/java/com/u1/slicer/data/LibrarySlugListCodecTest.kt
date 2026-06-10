package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySlugListCodecTest {
    @Test
    fun `round trip preserves order and content`() {
        val slugs = listOf("a-1", "b-2", "c-3")
        assertEquals(slugs, decodeSlugList(encodeSlugList(slugs)))
    }

    @Test
    fun `empty and blank decode to empty list`() {
        assertEquals(emptyList<String>(), decodeSlugList(""))
        assertEquals(emptyList<String>(), decodeSlugList("   "))
    }

    @Test
    fun `malformed json decodes to empty list`() {
        assertEquals(emptyList<String>(), decodeSlugList("{broken"))
    }
}
