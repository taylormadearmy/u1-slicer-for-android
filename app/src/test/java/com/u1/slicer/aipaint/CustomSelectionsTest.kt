package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomSelectionsTest {

    @Test
    fun `empty list builds no custom-selections group`() {
        val node = CustomSelections.buildGroup(emptyList())
        assertNull(node)
    }

    @Test
    fun `single selection builds a parent with one child`() {
        val selections = listOf(
            CustomSelection(id = 0, slot = 1, triangleIds = intArrayOf(1, 2, 3, 4, 5)),
        )
        val group = CustomSelections.buildGroup(selections)
        assertNotNull(group)
        assertEquals(1, group!!.children.size)
        assertEquals("Custom selection · 5 tri", group.children[0].region.label)
        assertEquals(1, group.children[0].region.slot)
    }

    @Test
    fun `multiple selections build siblings`() {
        val selections = listOf(
            CustomSelection(id = 0, slot = 0, triangleIds = intArrayOf(1, 2)),
            CustomSelection(id = 1, slot = 2, triangleIds = intArrayOf(3, 4, 5)),
        )
        val group = CustomSelections.buildGroup(selections)!!
        assertEquals(2, group.children.size)
        assertEquals("Custom selection · 2 tri", group.children[0].region.label)
        assertEquals("Custom selection · 3 tri", group.children[1].region.label)
    }

    @Test
    fun `group parent uses Custom selections label and BRUSH source`() {
        val sel = listOf(CustomSelection(id = 0, slot = 0, triangleIds = intArrayOf(1)))
        val group = CustomSelections.buildGroup(sel)!!
        assertEquals("Custom selections", group.region.label)
        assertEquals(SegmentationSource.BRUSH, group.nodeSource)
    }
}
