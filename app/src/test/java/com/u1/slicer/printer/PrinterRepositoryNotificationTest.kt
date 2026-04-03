package com.u1.slicer.printer

import com.u1.slicer.AppEventNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrinterRepositoryNotificationTest {

    @Test
    fun `idle to printing is PrintStarted`() {
        val event = PrinterRepository.detectTransition("idle", "printing", "job.gcode", 10)
        assertEquals("PrintStarted", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to paused is PrintPaused`() {
        val event = PrinterRepository.detectTransition("printing", "paused", "job.gcode", 45)
        assertEquals("PrintPaused", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to complete is PrintComplete`() {
        val event = PrinterRepository.detectTransition("printing", "complete", "job.gcode", 100)
        assertEquals("PrintComplete", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to error is PrintFailed`() {
        val event = PrinterRepository.detectTransition("printing", "error", "job.gcode", 50)
        assertEquals("PrintFailed", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to cancelled is PrintFailed`() {
        val event = PrinterRepository.detectTransition("printing", "cancelled", "job.gcode", 50)
        assertEquals("PrintFailed", event?.javaClass?.simpleName)
    }

    @Test
    fun `printing to disconnected is PrinterOffline`() {
        val event = PrinterRepository.detectTransition("printing", "disconnected", "job.gcode", 50)
        assertEquals("PrinterOffline", event?.javaClass?.simpleName)
    }

    @Test
    fun `paused to disconnected is PrinterOffline`() {
        val event = PrinterRepository.detectTransition("paused", "disconnected", "job.gcode", 50)
        assertEquals("PrinterOffline", event?.javaClass?.simpleName)
    }

    @Test
    fun `idle to idle is null`() {
        assertNull(PrinterRepository.detectTransition("idle", "idle", "", 0))
    }

    @Test
    fun `disconnected to printing is PrintStarted`() {
        val event = PrinterRepository.detectTransition("disconnected", "printing", "job.gcode", 0)
        assertEquals("PrintStarted", event?.javaClass?.simpleName)
    }

    // --- Grace-period (B39) tests ---

    @Test
    fun `single disconnected failure does not fire PrinterOffline`() {
        // Start from printing, then one failure
        var prevState = "printing"
        var failures = 0
        val (effectiveState, newFailures) = PrinterRepository.applyGracePeriod("disconnected", prevState, failures)
        failures = newFailures
        val event = PrinterRepository.detectTransition(prevState, effectiveState, "job.gcode", 50)
        assertNull(event)
    }

    @Test
    fun `two consecutive failures do not fire PrinterOffline`() {
        var prevState = "printing"
        var failures = 0
        repeat(2) {
            val (effectiveState, newFailures) = PrinterRepository.applyGracePeriod("disconnected", prevState, failures)
            failures = newFailures
            val event = PrinterRepository.detectTransition(prevState, effectiveState, "job.gcode", 50)
            assertNull(event)
            prevState = effectiveState
        }
    }

    @Test
    fun `three consecutive failures fire PrinterOffline`() {
        var prevState = "printing"
        var failures = 0
        var lastEvent: AppEventNotifier.Event? = null
        repeat(3) {
            val (effectiveState, newFailures) = PrinterRepository.applyGracePeriod("disconnected", prevState, failures)
            failures = newFailures
            lastEvent = PrinterRepository.detectTransition(prevState, effectiveState, "job.gcode", 50)
            prevState = effectiveState
        }
        assertEquals("PrinterOffline", lastEvent?.javaClass?.simpleName)
    }

    @Test
    fun `success after two failures resets counter and next single failure does not fire`() {
        var prevState = "printing"
        var failures = 0
        // Two failures
        repeat(2) {
            val (effectiveState, newFailures) = PrinterRepository.applyGracePeriod("disconnected", prevState, failures)
            failures = newFailures
            prevState = effectiveState
        }
        // Recovery
        val (recoveredState, resetFailures) = PrinterRepository.applyGracePeriod("printing", prevState, failures)
        failures = resetFailures
        prevState = recoveredState
        assertEquals(0, failures)
        // One more failure should not fire
        val (effectiveState, newFailures) = PrinterRepository.applyGracePeriod("disconnected", prevState, failures)
        failures = newFailures
        val event = PrinterRepository.detectTransition(prevState, effectiveState, "job.gcode", 50)
        assertNull(event)
    }
}
