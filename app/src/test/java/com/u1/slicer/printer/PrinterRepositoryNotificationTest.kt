package com.u1.slicer.printer

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
}
