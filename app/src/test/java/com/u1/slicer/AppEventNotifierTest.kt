package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Test

class AppEventNotifierTest {

    @Test
    fun `slice complete title and body`() {
        assertEquals("Slice complete", AppEventNotifier.titleFor(AppEventNotifier.Event.SliceComplete("model.3mf")))
        assertEquals("model.3mf is ready to send to printer",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.SliceComplete("model.3mf")))
    }

    @Test
    fun `slice failed title and body`() {
        assertEquals("Slice failed", AppEventNotifier.titleFor(AppEventNotifier.Event.SliceFailed("Out of memory")))
        assertEquals("Out of memory", AppEventNotifier.bodyFor(AppEventNotifier.Event.SliceFailed("Out of memory")))
    }

    @Test
    fun `slice failed truncates long error message`() {
        val longMsg = "A".repeat(150)
        assertEquals(100, AppEventNotifier.bodyFor(AppEventNotifier.Event.SliceFailed(longMsg)).length)
    }

    @Test
    fun `model loaded title and body`() {
        assertEquals("Model ready", AppEventNotifier.titleFor(AppEventNotifier.Event.ModelLoaded("dragon.stl")))
        assertEquals("dragon.stl loaded and ready to slice",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.ModelLoaded("dragon.stl")))
    }

    @Test
    fun `upload complete title and body`() {
        assertEquals("Upload complete", AppEventNotifier.titleFor(AppEventNotifier.Event.UploadComplete("print.gcode")))
        assertEquals("print.gcode sent to printer",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.UploadComplete("print.gcode")))
    }

    @Test
    fun `print started title and body`() {
        assertEquals("Print started", AppEventNotifier.titleFor(AppEventNotifier.Event.PrintStarted("print.gcode")))
        assertEquals("print.gcode", AppEventNotifier.bodyFor(AppEventNotifier.Event.PrintStarted("print.gcode")))
    }

    @Test
    fun `print paused title and body`() {
        assertEquals("Print paused", AppEventNotifier.titleFor(AppEventNotifier.Event.PrintPaused("print.gcode", 42)))
        assertEquals("print.gcode paused at 42%",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.PrintPaused("print.gcode", 42)))
    }

    @Test
    fun `print complete title and body`() {
        assertEquals("Print complete", AppEventNotifier.titleFor(AppEventNotifier.Event.PrintComplete("print.gcode")))
        assertEquals("print.gcode finished",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.PrintComplete("print.gcode")))
    }

    @Test
    fun `print failed title and body`() {
        assertEquals("Print stopped", AppEventNotifier.titleFor(AppEventNotifier.Event.PrintFailed("print.gcode")))
        assertEquals("print.gcode was cancelled or failed",
            AppEventNotifier.bodyFor(AppEventNotifier.Event.PrintFailed("print.gcode")))
    }

    @Test
    fun `printer offline title and body`() {
        assertEquals("Printer offline", AppEventNotifier.titleFor(AppEventNotifier.Event.PrinterOffline))
        assertEquals("Lost connection during print", AppEventNotifier.bodyFor(AppEventNotifier.Event.PrinterOffline))
    }

    @Test
    fun `navigate target for slice complete is preview`() {
        assertEquals("preview", AppEventNotifier.navigateTargetFor(AppEventNotifier.Event.SliceComplete("x")))
    }

    @Test
    fun `navigate target for print paused is printer`() {
        assertEquals("printer", AppEventNotifier.navigateTargetFor(AppEventNotifier.Event.PrintPaused("x", 0)))
    }

    @Test
    fun `navigate target for model loaded is null`() {
        assertEquals(null, AppEventNotifier.navigateTargetFor(AppEventNotifier.Event.ModelLoaded("x")))
    }
}
