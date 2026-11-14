package com.u1.slicer.ui.printer

import com.u1.slicer.network.PrinterStatus
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintCompletionEstimateTest {
    private val now = Instant.parse("2026-11-14T13:15:00Z")
    private val utc = ZoneId.of("UTC")

    @Test fun `halfway through a two hour print finishes in one hour`() {
        assertEquals(now.plusSeconds(3600), estimatePrintCompletion(PrinterStatus("printing", 0.5f, printDuration = 3600f), now))
    }

    @Test fun `new progress updates revise the estimate`() {
        val status = PrinterStatus("printing", 0.25f, printDuration = 3600f)
        assertEquals(now.plusSeconds(10800), estimatePrintCompletion(status, now))
        assertEquals(now.plusSeconds(5400), estimatePrintCompletion(status.copy(progress = 0.5f, printDuration = 5400f), now))
    }

    @Test fun `paused finished failed and disconnected jobs have no estimate`() {
        listOf("paused", "complete", "standby", "error", "cancelled", "disconnected").forEach { state ->
            assertNull(state, estimatePrintCompletion(PrinterStatus(state, 0.5f, printDuration = 3600f), now))
        }
    }

    @Test fun `unknown invalid or startup progress has no estimate`() {
        listOf(0f, -1f, 0.001f, 1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { progress ->
            assertNull("progress=$progress", estimatePrintCompletion(PrinterStatus("printing", progress, printDuration = 3600f), now))
        }
    }

    @Test fun `missing invalid or short elapsed duration has no estimate`() {
        listOf(0f, -1f, 59f, Float.NaN, Float.POSITIVE_INFINITY).forEach { elapsed ->
            assertNull("elapsed=$elapsed", estimatePrintCompletion(PrinterStatus("printing", 0.5f, printDuration = elapsed), now))
        }
    }

    @Test fun `first useful sample produces an estimate`() {
        assertEquals(now.plusSeconds(5940), estimatePrintCompletion(PrinterStatus("printing", 0.01f, printDuration = 60f), now))
    }

    @Test fun `unrepresentable finish time has no estimate`() {
        assertNull(estimatePrintCompletion(PrinterStatus("printing", 0.01f, printDuration = Float.MAX_VALUE), now))
    }

    @Test fun `same day uses selected clock format`() {
        val finish = Instant.parse("2026-11-14T15:30:00Z")
        assertEquals("15:30", formatPrintCompletion(finish, now, utc, true, Locale.UK))
        assertEquals("3:30 PM", formatPrintCompletion(finish, now, utc, false, Locale.US))
    }

    @Test fun `overnight finish says tomorrow`() {
        val finish = Instant.parse("2026-11-15T01:30:00Z")
        assertEquals("Tomorrow, 01:30", formatPrintCompletion(finish, now, utc, true, Locale.UK))
    }

    @Test fun `multi day finish includes a date`() {
        val finish = Instant.parse("2026-11-16T15:30:00Z")
        assertEquals("16/11/2026, 15:30", formatPrintCompletion(finish, now, utc, true, Locale.UK))
    }

    @Test fun `day labels follow the phone timezone`() {
        val finish = Instant.parse("2026-11-15T01:30:00Z")
        assertEquals("17:30", formatPrintCompletion(finish, now, ZoneId.of("America/Los_Angeles"), true, Locale.UK))
    }

    @Test fun `spring clock change preserves the actual remaining duration`() {
        val beforeClockChange = Instant.parse("2026-03-29T00:30:00Z")
        val finish = estimatePrintCompletion(PrinterStatus("printing", 0.5f, printDuration = 3600f), beforeClockChange)!!
        assertEquals("02:30", formatPrintCompletion(finish, beforeClockChange, ZoneId.of("Europe/London"), true, Locale.UK))
    }
}
