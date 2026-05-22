package com.u1.slicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterViewModelTest {
    @Test
    fun `shouldStartCameraKeepalive returns false when job already active`() {
        assertFalse(PrinterViewModel.shouldStartCameraKeepalive(hasActiveJob = true))
    }

    @Test
    fun `shouldStartCameraKeepalive returns true when no active job exists`() {
        assertTrue(PrinterViewModel.shouldStartCameraKeepalive(hasActiveJob = false))
    }

    @Test
    fun `shouldPollLedOnConnectionEdge returns true only on rising connection edge`() {
        assertTrue(PrinterViewModel.shouldPollLedOnConnectionEdge(wasConnected = false, isConnected = true))
        assertFalse(PrinterViewModel.shouldPollLedOnConnectionEdge(wasConnected = true, isConnected = true))
        assertFalse(PrinterViewModel.shouldPollLedOnConnectionEdge(wasConnected = true, isConnected = false))
    }

    @Test
    fun `shouldPollLedOnConnectionEdge ignores disconnected steady state`() {
        assertFalse(PrinterViewModel.shouldPollLedOnConnectionEdge(wasConnected = false, isConnected = false))
    }

    // ---- F82: idle-state controls ----

    @Test
    fun `f82 sanitizeCustomGcode trims whitespace`() {
        assertEquals("M104 S0", PrinterViewModel.sanitizeCustomGcode("  M104 S0  "))
        assertEquals("G28 X", PrinterViewModel.sanitizeCustomGcode("\tG28 X\n"))
    }

    @Test
    fun `f82 sanitizeCustomGcode rejects empty and whitespace-only input`() {
        assertNull(PrinterViewModel.sanitizeCustomGcode(""))
        assertNull(PrinterViewModel.sanitizeCustomGcode("   "))
        assertNull(PrinterViewModel.sanitizeCustomGcode("\t\n"))
    }

    @Test
    fun `f82 sanitizeCustomGcode passes through normal commands unchanged`() {
        assertEquals("M104 S210 T0", PrinterViewModel.sanitizeCustomGcode("M104 S210 T0"))
        assertEquals("TURN_OFF_HEATERS", PrinterViewModel.sanitizeCustomGcode("TURN_OFF_HEATERS"))
        assertEquals("SET_PRESSURE_ADVANCE ADVANCE=0.05",
            PrinterViewModel.sanitizeCustomGcode("SET_PRESSURE_ADVANCE ADVANCE=0.05"))
    }
}
