package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F90 follow-up (v2.7.1): structural guard for the foreground-service wrap around
 * the InlineModelPreview LaunchedEffect that calls `lib.getPreparePreviewMesh(...)`.
 *
 * Why a source-grep test instead of a runtime test:
 *  - The contract is "the wrap is in the right shape", not "the wrap returns X".
 *  - The crash this wrap prevents is `ForegroundServiceDidNotStartInTimeException`,
 *    which only fires under real Android Service lifecycle (no JVM unit-test path).
 *  - The Compose harness is not on the production classpath; the previous F90
 *    structural tests (`InlineModelPreviewRotationKeysTest`) follow this same
 *    source-grep pattern for the same reason.
 *
 * What we assert (the contract Kevin's naive attempt violated and that Android
 * 14+ punishes with a hard FATAL):
 *  1. The LaunchedEffect that calls `lib.getPreparePreviewMesh(` must wrap that
 *     call with `LongOpService.start(..., "Preparing preview")` ... `try { ... }
 *     finally { LongOpService.stop(...) }`.
 *  2. The `start(...)` call must come AFTER the 300 ms debounce. Putting it
 *     above the debounce causes Android to throw `ForegroundServiceDidNotStartInTimeException`
 *     under rotation-slider drag, because every cancelled-mid-debounce LaunchedEffect
 *     still fires `startForegroundService` and Android's per-call 5-second watchdog
 *     can't be satisfied during rapid cancel/restart churn.
 */
class PreparePreviewLongOpWrapTest {

    private fun mainActivitySource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/MainActivity.kt"),
            File("../app/src/main/java/com/u1/slicer/MainActivity.kt"),
            File("src/main/java/com/u1/slicer/MainActivity.kt")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("MainActivity.kt not found from ${File(".").absolutePath}")
        return f.readText()
    }

    /**
     * Find the LaunchedEffect body that contains the call to `lib.getPreparePreviewMesh(`.
     * Anchoring on the explicit native call (not bare `getPreparePreviewMesh`) avoids
     * grabbing earlier comments / overlay strings that also mention "preview".
     */
    private fun previewMeshLaunchedEffectBody(): String {
        val src = mainActivitySource()
        val nativeCallAt = src.indexOf("lib.getPreparePreviewMesh(")
        require(nativeCallAt >= 0) {
            "lib.getPreparePreviewMesh( not found — InlineModelPreview has been " +
                "restructured. Find the LaunchedEffect that fetches the native preview " +
                "mesh and update this test."
        }
        // Walk back to the enclosing LaunchedEffect( ... ) { ... } body.
        val launchedEffectAt = src.lastIndexOf("LaunchedEffect(", nativeCallAt)
        require(launchedEffectAt >= 0) {
            "LaunchedEffect( not found before lib.getPreparePreviewMesh( at offset $nativeCallAt"
        }
        // Find the opening brace of the body: ") {" after the arg list.
        val argsClose = src.indexOf(") {", launchedEffectAt)
        require(argsClose >= 0 && argsClose < nativeCallAt) {
            "could not find arg-list close for LaunchedEffect at $launchedEffectAt"
        }
        val bodyOpen = argsClose + 2
        // Brace-match to find the body close.
        var depth = 0
        var i = bodyOpen
        var bodyClose = -1
        while (i < src.length) {
            val c = src[i]
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        bodyClose = i
                        break
                    }
                }
            }
            i++
        }
        require(bodyClose > bodyOpen) { "could not brace-match LaunchedEffect body" }
        return src.substring(bodyOpen, bodyClose + 1)
    }

    @Test
    fun previewMeshLaunchedEffect_pushesLongOpStartForPreparingPreview() {
        val body = previewMeshLaunchedEffectBody()
        assertTrue(
            "F90 follow-up: the InlineModelPreview LaunchedEffect that fetches the " +
                "preview mesh must call LongOpService.start(..., \"Preparing preview\") " +
                "so the foreground notification keeps Android from killing the process " +
                "during the 30+ second native call. Body was:\n$body",
            body.contains("LongOpService.start(") &&
                body.contains("\"Preparing preview\"")
        )
    }

    @Test
    fun previewMeshLaunchedEffect_pairsStopInFinally() {
        val body = previewMeshLaunchedEffectBody()
        assertTrue(
            "F90 follow-up: the InlineModelPreview LaunchedEffect's LongOpService.start " +
                "must be paired with LongOpService.stop in a finally block so cancellation " +
                "and exception both pop the stage off the LongOpService stack. Body was:\n$body",
            body.contains("finally") && body.contains("LongOpService.stop(")
        )
    }

    @Test
    fun previewMeshLaunchedEffect_startIsAfterDebounce() {
        val body = previewMeshLaunchedEffectBody()
        // The 300ms debounce sits inside the !isInitialFetch branch. The crash Kevin hit
        // happened because start() was placed BEFORE the delay(300) — so a rotation drag
        // that cancelled mid-debounce still fired startForegroundService, accumulating
        // unfulfilled 5-second watchdogs that Android resolves with a FATAL exception.
        //
        // Correct order: kotlinx.coroutines.delay(300) appears in the source BEFORE the
        // LongOpService.start(...) call.
        val delayAt = body.indexOf("kotlinx.coroutines.delay(300)")
        val startAt = body.indexOf("LongOpService.start(")
        require(delayAt >= 0) {
            "the debounce delay was removed from InlineModelPreview — this test is now " +
                "obsolete (or the debounce moved). Re-read the LaunchedEffect and decide " +
                "what the new ordering contract should be before deleting this assertion."
        }
        require(startAt >= 0) {
            "LongOpService.start(...) missing — covered by the previous test, but this " +
                "test relies on its presence too."
        }
        assertTrue(
            "F90 follow-up: LongOpService.start(\"Preparing preview\") must appear AFTER " +
                "the debounce `kotlinx.coroutines.delay(300)`, not before. Putting it before " +
                "causes ForegroundServiceDidNotStartInTimeException on rotation drag.\n" +
                "  delay(300) at offset $delayAt\n  start( at offset $startAt",
            startAt > delayAt
        )
    }
}
