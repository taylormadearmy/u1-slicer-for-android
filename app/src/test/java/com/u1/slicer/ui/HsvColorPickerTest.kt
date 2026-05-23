package com.u1.slicer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure HSV ↔ hex color conversion helpers used by the colour picker (F64).
 */
class HsvColorPickerTest {

    @Test
    fun `hsvToHex red h=0 s=1 v=1 produces #FF0000`() {
        assertEquals("#FF0000", hsvToHex(0f, 1f, 1f))
    }

    @Test
    fun `hsvToHex green h=120 s=1 v=1 produces #00FF00`() {
        assertEquals("#00FF00", hsvToHex(120f, 1f, 1f))
    }

    @Test
    fun `hsvToHex blue h=240 s=1 v=1 produces #0000FF`() {
        assertEquals("#0000FF", hsvToHex(240f, 1f, 1f))
    }

    @Test
    fun `hsvToHex white s=0 v=1 produces #FFFFFF`() {
        assertEquals("#FFFFFF", hsvToHex(0f, 0f, 1f))
    }

    @Test
    fun `hsvToHex black v=0 produces #000000`() {
        assertEquals("#000000", hsvToHex(0f, 0f, 0f))
    }

    @Test
    fun `hexToHsv red round-trips correctly`() {
        val hsv = hexToHsv("#FF0000")
        assertEquals(0f, hsv[0], 1f)     // hue ≈ 0
        assertEquals(1f, hsv[1], 0.01f)  // sat = 1
        assertEquals(1f, hsv[2], 0.01f)  // val = 1
    }

    @Test
    fun `hexToHsv blue round-trips correctly`() {
        val hsv = hexToHsv("#0000FF")
        assertEquals(240f, hsv[0], 1f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)
    }

    @Test
    fun `hsvToHex and hexToHsv are inverse for arbitrary color`() {
        // Olive-ish: h=60, s=0.7, v=0.8
        val hex = hsvToHex(60f, 0.7f, 0.8f)
        val back = hexToHsv(hex)
        assertEquals(60f, back[0], 2f)
        assertEquals(0.7f, back[1], 0.02f)
        assertEquals(0.8f, back[2], 0.02f)
    }

    @Test
    fun `hexToHsv handles hash-less input`() {
        val hsv = hexToHsv("FF0000")
        assertEquals(1f, hsv[1], 0.01f)  // saturation = 1 (red)
    }

    // ---- F79 fixes ----

    @Test
    fun `f79 hue drag from achromatic state snaps saturation to 1`() {
        // When current sat is 0 (white/grey/black achromatic), changing hue
        // alone has no visible effect. Snap saturation so the user sees the
        // new hue.
        val (h, s, v) = HsvPickerSnap.snapOnHueChange(
            currentHue = 0f, currentSaturation = 0f, currentValue = 1f,
            newHue = 120f,
        )
        assertEquals(120f, h, 0.001f)
        assertEquals(1f, s, 0.001f)
        assertEquals(1f, v, 0.001f)
    }

    @Test
    fun `f79 hue drag from already-saturated state leaves saturation alone`() {
        val (h, s, v) = HsvPickerSnap.snapOnHueChange(
            currentHue = 0f, currentSaturation = 0.7f, currentValue = 0.8f,
            newHue = 240f,
        )
        assertEquals(240f, h, 0.001f)
        assertEquals(0.7f, s, 0.001f)
        assertEquals(0.8f, v, 0.001f)
    }

    @Test
    fun `f79 hue drag from very low saturation also snaps to 1`() {
        // Anything below 0.05 counts as achromatic for snap purposes.
        val (h, s, v) = HsvPickerSnap.snapOnHueChange(
            currentHue = 0f, currentSaturation = 0.02f, currentValue = 0.9f,
            newHue = 180f,
        )
        assertEquals(180f, h, 0.001f)
        assertEquals(1f, s, 0.001f)
        assertEquals(0.9f, v, 0.001f)
    }

    @Test
    fun `f79 hue drag from black snaps to 1 saturation but ALSO bumps value`() {
        // Pure black is sat=0, value=0. Snapping only saturation leaves the
        // colour black (since v=0 still produces black). Also bump value
        // to 1 so the picked hue is visible.
        val (h, s, v) = HsvPickerSnap.snapOnHueChange(
            currentHue = 0f, currentSaturation = 0f, currentValue = 0f,
            newHue = 60f,
        )
        assertEquals(60f, h, 0.001f)
        assertEquals(1f, s, 0.001f)
        assertEquals(1f, v, 0.001f)
    }
}
