package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixColourMatcherTest {
    // 4 loaded filaments (1-based indices map to these): red, green, blue, white
    private val loaded = listOf("#e23b3b", "#46c46a", "#3b6fe2", "#ffffff")

    @Test fun recoversKnownMix() {
        // Predict a known 2-colour mix, then ask the matcher to hit that predicted colour.
        val target = FilamentMixPredictor.predict(listOf(loaded[0], loaded[2]), listOf(60, 40))
        val s = MixColourMatcher.bestMix(target, loaded, count = 2)
        assertEquals(2, s.componentIndices.size)
        assertTrue("should recover red+blue (got ${s.componentIndices})", s.componentIndices.toSet() == setOf(1, 3))
        assertTrue("close match expected (ΔE=${s.deltaE})", s.deltaE < 3.0)
        assertEquals(100, s.weights.sum())
    }

    @Test fun respectsCount() {
        val s3 = MixColourMatcher.bestMix("#8a7f6a", loaded, count = 3)
        assertEquals(3, s3.componentIndices.size)
        assertEquals(3, s3.weights.size)
        assertEquals(100, s3.weights.sum())
    }

    @Test fun capsCountToLoaded() {
        val s = MixColourMatcher.bestMix("#888888", loaded.take(2), count = 4) // only 2 loaded
        assertTrue("count capped to loaded", s.componentIndices.size <= 2)
    }

    @Test fun closestSingleFilament_picksNearest() {
        val (idx, dE) = MixColourMatcher.closestSingleFilament("#e63c3c", loaded)
        assertEquals(1, idx) // nearest to red
        assertTrue(dE < 5.0)
    }

    @Test fun isFastEnough() {
        val start = System.nanoTime()
        repeat(10) { MixColourMatcher.bestMix("#a85b9c", loaded, count = 3) }
        val msPer = (System.nanoTime() - start) / 1e6 / 10.0
        assertTrue("avg ${msPer}ms/query should be < 250ms", msPer < 250.0)
    }

    @Test fun emptyLoaded_bestMix_throwsClearError() {
        val ex = try {
            MixColourMatcher.bestMix("#888888", emptyList(), count = 2); null
        } catch (e: IllegalArgumentException) { e }
        assertTrue("expected a clear empty-loaded error", ex != null && (ex!!.message ?: "").contains("loaded", ignoreCase = true))
    }

    @Test fun emptyLoaded_closestSingle_returnsSentinel() {
        val (idx, dE) = MixColourMatcher.closestSingleFilament("#888888", emptyList())
        assertEquals(0, idx) // 0 = "no filament" sentinel (indices are otherwise 1-based)
        assertEquals(Double.MAX_VALUE, dE, 0.0)
    }

    @Test fun count1_returnsSingleComponent() {
        val s = MixColourMatcher.bestMix(loaded[1], loaded, count = 1)
        assertEquals(1, s.componentIndices.size)
        assertEquals(listOf(100), s.weights)
        assertTrue("count=1 should pick the exact loaded colour (ΔE=${s.deltaE})", s.deltaE < 1.0)
    }
}
