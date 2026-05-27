package com.u1.slicer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F90: pure-JVM tests for LongOpService's stack-mutation companion. Exercises
 * `startPure`, `updatePure`, `stopPure`, `currentStageOrNull` — no Android Service,
 * no Intent construction.
 */
class LongOpServiceStackTest {

    @Before
    fun setUp() {
        LongOpService.resetForTest()
    }

    @After
    fun tearDown() {
        LongOpService.resetForTest()
    }

    @Test
    fun `startPure pushes stage and current top is the pushed stage`() {
        val payload = LongOpService.startPure("Loading model")
        assertEquals(LongOpService.ACTION_UPDATE, payload.action)
        assertEquals("Loading model", payload.stage)
        assertEquals(0, payload.progress)
        assertEquals("Loading model", LongOpService.currentStageOrNull())
    }

    @Test
    fun `multiple startPure calls stack and last push is current top`() {
        LongOpService.startPure("Loading model")
        val inner = LongOpService.startPure("Preparing model")
        assertEquals("Preparing model", inner.stage)
        assertEquals("Preparing model", LongOpService.currentStageOrNull())
    }

    @Test
    fun `updatePure with stage replaces the top of stack`() {
        LongOpService.startPure("Slicing")
        val tick = LongOpService.updatePure(progress = 47, stage = "Generating layers")
        assertEquals(LongOpService.ACTION_UPDATE, tick.action)
        assertEquals("Generating layers", tick.stage)
        assertEquals(47, tick.progress)
        // Stack depth must remain 1, not 2 — the rename replaced rather than pushed.
        LongOpService.stopPure()
        assertNull(LongOpService.currentStageOrNull())
    }

    @Test
    fun `updatePure with null stage carries progress without mutating stack`() {
        LongOpService.startPure("Slicing")
        val tick = LongOpService.updatePure(progress = 33, stage = null)
        assertEquals("Slicing", tick.stage)
        assertEquals(33, tick.progress)
        assertEquals("Slicing", LongOpService.currentStageOrNull())
    }

    @Test
    fun `updatePure on empty stack pushes the new stage`() {
        val tick = LongOpService.updatePure(progress = 10, stage = "Slicing")
        assertEquals("Slicing", tick.stage)
        assertEquals("Slicing", LongOpService.currentStageOrNull())
    }

    @Test
    fun `stopPure pops the top frame revealing the parent stage`() {
        LongOpService.startPure("Loading model")
        LongOpService.startPure("Preparing model")
        val pop = LongOpService.stopPure()
        assertEquals(LongOpService.ACTION_UPDATE, pop.action)
        assertEquals("Loading model", pop.stage)
        assertEquals("Loading model", LongOpService.currentStageOrNull())
    }

    @Test
    fun `stopPure on last frame emits ACTION_STOP`() {
        LongOpService.startPure("Loading model")
        val pop = LongOpService.stopPure()
        assertEquals(LongOpService.ACTION_STOP, pop.action)
        assertNull(pop.stage)
        assertNull(LongOpService.currentStageOrNull())
    }

    @Test
    fun `stopPure on empty stack does not crash and still emits ACTION_STOP`() {
        val pop = LongOpService.stopPure()
        assertEquals(LongOpService.ACTION_STOP, pop.action)
        assertNull(pop.stage)
    }

    @Test
    fun `concurrent pushes serialize under lock without throwing`() {
        val threads = (0 until 16).map { i ->
            Thread {
                repeat(50) {
                    LongOpService.startPure("Stage-$i-$it")
                    LongOpService.stopPure()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // All pushes were paired with pops — stack must be empty.
        assertNull(LongOpService.currentStageOrNull())
    }

    /**
     * B130 (#158) structural guard: Android force-crashes the app with
     * ForegroundServiceDidNotStartInTimeException if an `onStartCommand` (reached
     * via `startForegroundService()`) returns without calling `startForeground()`
     * within 5s — EVEN on the stop / empty-stage paths. So `onStartCommand` must
     * promote to foreground BEFORE any `stopForeground()`/`stopSelf()`. A unit
     * test can't drive the real Service, so this greps the source.
     */
    @Test
    fun `onStartCommand promotes to foreground before any stop`() {
        val src = java.io.File("src/main/java/com/u1/slicer/LongOpService.kt").readText()
        val start = src.indexOf("fun onStartCommand")
        assertTrue("onStartCommand must exist", start >= 0)
        val after = src.indexOf("\n    private fun", start + 1)
        val body = src.substring(start, if (after > start) after else src.length)
        val firstStartFg = body.indexOf("startForeground(")
        val firstStop = minOf(
            body.indexOf("stopForeground(").let { if (it < 0) Int.MAX_VALUE else it },
            body.indexOf("stopSelf(").let { if (it < 0) Int.MAX_VALUE else it },
        )
        assertTrue("onStartCommand must call startForeground()", firstStartFg >= 0)
        assertTrue(
            "B130: startForeground() must be called BEFORE any stopForeground()/stopSelf() " +
                "in onStartCommand so the foreground-service watchdog is satisfied on the " +
                "ACTION_STOP and empty-stage paths too (not just the normal path).",
            firstStop == Int.MAX_VALUE || firstStartFg < firstStop,
        )
    }
}
