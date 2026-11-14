package com.u1.slicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebcamSelectionTest {
    private val display = WebcamSource("display", "Printer display", "screen", "mjpeg", listOf("http://display"))
    private val physical = WebcamSource("physical", "Physical camera", "printer", "mjpeg", listOf("http://physical"))

    @Test
    fun `preferred camera wins regardless of Moonraker list order`() {
        val selection = WebcamSelection.resolve(listOf(display, physical), preferredUid = "physical")

        assertEquals("physical", selection.selected?.uid)
        assertFalse(selection.preferredSourceUnavailable)
    }

    @Test
    fun `missing preferred camera uses default without forgetting saved preference`() {
        val selection = WebcamSelection.resolve(listOf(display), preferredUid = "physical")

        assertEquals("display", selection.selected?.uid)
        assertTrue(selection.preferredSourceUnavailable)
    }

    @Test
    fun `empty discovery has no selected camera`() {
        val selection = WebcamSelection.resolve(emptyList(), preferredUid = "physical")

        assertNull(selection.selected)
        assertTrue(selection.preferredSourceUnavailable)
    }
}
