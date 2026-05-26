package com.u1.slicer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-05-26 — freezer guard for the printer send/upload paths.
 *
 * Same root cause as the 0-byte save (see [SaveGcodeStateResilienceTest]):
 * sending a sliced G-code to the printer is TWO long, unprotected background
 * operations chained together —
 *   1. the canonical→physical T-index remap (~80 s for a 272 MB H2C plate),
 *      kicked off in MainActivity's `sendActionScope.launch`, and
 *   2. the network upload itself (minutes over WAN) in
 *      `PrinterViewModel.sendAndPrint` / `sendUploadOnly`.
 *
 * If the user backgrounds the app during either (very likely during a
 * multi-minute upload), Android's cached-app freezer suspends the process and
 * the transfer truncates — leaving a corrupt/partial G-code on the printer's
 * storage (Upload Only) or a never-started print (Send & Print). Both steps
 * must run inside a LongOpService foreground service.
 *
 * Source-grep guard: a behavioural test would need a live printer + device.
 */
class SendUploadForegroundServiceTest {

    private val printerVm: String by lazy {
        File("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt").readText()
    }
    private val mainActivity: String by lazy {
        File("src/main/java/com/u1/slicer/MainActivity.kt").readText()
    }

    @Test
    fun sendAndPrintHoldsForegroundServiceAcrossUpload() {
        val start = printerVm.indexOf("fun sendAndPrint(")
        assertTrue("sendAndPrint must exist", start > 0)
        val end = printerVm.indexOf("\n    fun ", start + 1)
        val body = printerVm.substring(start, if (end > 0) end else printerVm.length)
        assertTrue(
            "sendAndPrint must start a LongOpService foreground service before uploading.",
            body.contains("LongOpService.start(")
        )
        assertTrue(
            "sendAndPrint must stop the foreground service in a finally block.",
            Regex("""finally\s*\{[^}]*LongOpService\.stop\(""").containsMatchIn(body)
        )
    }

    @Test
    fun sendUploadOnlyHoldsForegroundServiceAcrossUpload() {
        val start = printerVm.indexOf("fun sendUploadOnly(")
        assertTrue("sendUploadOnly must exist", start > 0)
        val end = printerVm.indexOf("\n    fun ", start + 1)
        val body = printerVm.substring(start, if (end > 0) end else printerVm.length)
        assertTrue(
            "sendUploadOnly must start a LongOpService foreground service before uploading.",
            body.contains("LongOpService.start(")
        )
        assertTrue(
            "sendUploadOnly must stop the foreground service in a finally block.",
            Regex("""finally\s*\{[^}]*LongOpService\.stop\(""").containsMatchIn(body)
        )
    }

    @Test
    fun sendRemapCoroutinesHoldForegroundService() {
        // MainActivity dispatches the canonical→physical remap on sendActionScope
        // before handing the physical file to the printer ViewModel. Both the
        // canonical and the Absent-canonical paths must hold a foreground service
        // across the remap/copy. There are two `sendActionScope.launch` sites;
        // each must contain a LongOpService.start("Preparing G-code") paired with
        // a stop in finally.
        val launches = Regex("""sendActionScope\.launch""").findAll(mainActivity).count()
        assertTrue("Expected the two send-dispatch coroutines in MainActivity, found $launches", launches >= 2)
        val starts = Regex("""LongOpService\.start\(\s*toastContext\s*,\s*"Preparing G-code"\s*\)""")
            .findAll(mainActivity).count()
        assertTrue(
            "Both send-remap coroutines must start a 'Preparing G-code' foreground service; found $starts.",
            starts >= 2
        )
        val stops = Regex("""finally\s*\{\s*LongOpService\.stop\(\s*toastContext\s*\)""")
            .findAll(mainActivity).count()
        assertTrue(
            "Both send-remap coroutines must stop the foreground service in finally; found $stops.",
            stops >= 2
        )
    }
}
