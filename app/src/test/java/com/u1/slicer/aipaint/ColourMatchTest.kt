package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Test

class ColourMatchTest {
    @Test fun naiveBlend_endpoints_andMidpoint() {
        assertEquals("#0000FF", ColourMatch.naiveBlendHex("#0000FF", "#FFFF00", 0))
        assertEquals("#FFFF00", ColourMatch.naiveBlendHex("#0000FF", "#FFFF00", 100))
        assertEquals("#808080", ColourMatch.naiveBlendHex("#0000FF", "#FFFF00", 50))
    }

    @Test fun closestSlot_picksNearestByDeltaE() {
        val palette = listOf("#0000FF", "#FFFF00", "#808040")
        assertEquals(1, ColourMatch.closestSlot("#FFEE10", palette))
        assertEquals(0, ColourMatch.closestSlot("#1010EE", palette))
    }

    @Test fun closestSlot_emptyPaletteReturnsZero() {
        assertEquals(0, ColourMatch.closestSlot("#123456", emptyList()))
    }

    @Test fun deltaE_identicalIsZero() {
        assertEquals(0.0, ColourMatch.deltaE76("#3FA34D", "#3FA34D"), 1e-6)
    }
}
