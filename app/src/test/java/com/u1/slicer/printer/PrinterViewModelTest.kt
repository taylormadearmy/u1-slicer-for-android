package com.u1.slicer.printer

import org.junit.Assert.assertFalse
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
}
