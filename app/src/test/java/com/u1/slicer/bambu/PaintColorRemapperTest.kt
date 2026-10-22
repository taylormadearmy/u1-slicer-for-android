package com.u1.slicer.bambu

import org.junit.Assert.assertEquals
import org.junit.Test

class PaintColorRemapperTest {
    @Test fun `rewrites leaf while retaining split tree`() {
        val original = "481" // split node with two direct leaves
        val remapped = PaintColorRemapper.remap(original, mapOf(1 to 5, 2 to 3))!!
        assertEquals(setOf(3, 5), PaintColorDecoder.decodeStates(remapped))
        // One direct leaf expands to an extended encoding; decoded split leaves remain intact.
    }

    @Test fun `rewrites extended leaf`() {
        val remapped = PaintColorRemapper.remap("8C", mapOf(11 to 5))!!
        assertEquals(setOf(5), PaintColorDecoder.decodeStates(remapped))
    }
}
