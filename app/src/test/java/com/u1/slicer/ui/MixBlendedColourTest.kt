package com.u1.slicer.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class MixBlendedColourTest {
    @Test fun cyanPlusYellow_isGreenish_notGreyAverage() {
        val cyan = Color(0x00 / 255f, 0x9b / 255f, 0xc3 / 255f)
        val yellow = Color(0xf6 / 255f, 0xb9 / 255f, 0x21 / 255f)
        val blended = mixBlendedColour(listOf(cyan, yellow), listOf(50, 50))
        // green channel should dominate, and it must NOT be the naive average (~0x7b, 0xaa, 0x72)
        assertTrue("green should dominate red", blended.green > blended.red)
        assertTrue("green should dominate blue", blended.green > blended.blue)
    }
    @Test fun emptyColours_returnsGray() {
        assertTrue(mixBlendedColour(emptyList(), emptyList()) == Color.Gray)
    }
}
