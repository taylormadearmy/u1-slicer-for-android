package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentMixPredictorTest {
    private fun dE(a: String, b: String) = ColourMatch.deltaE76(a, b)

    @Test fun referenceVectors_matchWithinDeltaE1() {
        val cases = listOf(
            Triple("#009bc3" to "#f6b921", 50 to 50, "#519e5f"),
            Triple("#c9378c" to "#f6b921", 50 to 50, "#cc6545"),
            Triple("#009bc3" to "#c9378c", 50 to 50, "#4a5e94"),
            Triple("#252e2e" to "#e4e4e5", 50 to 50, "#636363"),
            Triple("#007a9d" to "#b32326", 50 to 50, "#434446"),
            Triple("#007a9d" to "#f6b921", 75 to 25, "#1e7c6f"),
            Triple("#347644" to "#e2deda", 75 to 25, "#4f7e5a"),
        )
        for ((colours, weights, expected) in cases) {
            val out = FilamentMixPredictor.predict(listOf(colours.first, colours.second), listOf(weights.first, weights.second))
            assertTrue("predict(${colours.first}+${colours.second} ${weights}) = $out, expected ~$expected (ΔE ${dE(out, expected)})",
                dE(out, expected) < 1.0)
        }
    }

    @Test fun endpoint_returnsThatColour() {
        assertEquals("#007a9d", FilamentMixPredictor.predict(listOf("#007a9d"), listOf(100)).lowercase())
        assertEquals("#007a9d", FilamentMixPredictor.predict(listOf("#007a9d", "#ffffff"), listOf(10000, 0)).lowercase())
    }

    @Test fun blueYellow_isGreenish_notGrey() {
        val out = FilamentMixPredictor.predict(listOf("#009bc3", "#f6b921"), listOf(50, 50))
        assertTrue("blue+yellow should be greenish ($out)", dE(out, "#519e5f") < 3.0)
    }

    @Test fun threeWay_returnsMidtone() {
        val out = FilamentMixPredictor.predict(listOf("#009bc3", "#c9378c", "#f6b921"), listOf(34, 33, 33))
        val v = out.removePrefix("#").toLong(16)
        val sum = ((v shr 16) and 0xFF) + ((v shr 8) and 0xFF) + (v and 0xFF)
        assertTrue("3-way midtone sum=$sum", sum in 200..550)
    }

    @Test fun predictor_differsFromNaiveAverage_forSubtractivePair() {
        val naive = ColourMatch.naiveBlendHexMulti(listOf("#009bc3", "#f6b921"), listOf(50, 50))
        val pred = FilamentMixPredictor.predict(listOf("#009bc3", "#f6b921"), listOf(50, 50))
        org.junit.Assert.assertNotEquals("predictor must differ from the naive sRGB average", naive, pred)
    }
}
