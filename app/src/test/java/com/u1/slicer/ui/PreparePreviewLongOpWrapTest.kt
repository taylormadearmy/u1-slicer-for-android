package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F90 follow-up (v2.7.1): structural guard for the foreground-service wrap around
 * the InlineModelPreview LaunchedEffect that starts the preview long-op stage.
 *
 * Why a source-grep test instead of a runtime test:
 *  - The contract is "the wrap is in the right shape", not "the wrap returns X".
 *  - The crash this wrap prevents is `ForegroundServiceDidNotStartInTimeException`,
 *    which only fires under real Android Service lifecycle (no JVM unit-test path).
 *  - The Compose harness is not on the production classpath; the previous F90
 *    structural tests follow this same source-grep pattern for the same reason.
 *
 * What we assert:
 *  1. The LaunchedEffect that starts the preview long-op must wrap the native work
 *     with `LongOpService.start(..., "Preparing preview")` ... `try { ... } finally {
 *     LongOpService.stop(...) }`.
 *  2. The `start(...)` call must come AFTER the 300 ms debounce.
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
     * Find the LaunchedEffect body that contains the preview-stage LongOpService start.
     * Anchoring on the explicit start call avoids grabbing earlier comments / overlay
     * strings that also mention "preview".
     */
    private fun previewMeshLaunchedEffectBody(): String {
        val src = mainActivitySource()
        val stageStartAt = src.indexOf("LongOpService.start(previewPrepContext, \"Preparing preview\")")
        require(stageStartAt >= 0) {
            "LongOpService.start(previewPrepContext, \"Preparing preview\") not found â€” " +
                "InlineModelPreview has been restructured. Re-read the LaunchedEffect " +
                "that fetches the native preview mesh and update this test."
        }

        val launchedEffectAt = src.lastIndexOf("LaunchedEffect(", stageStartAt)
        require(launchedEffectAt >= 0) {
            "LaunchedEffect( not found before the preview start at offset $stageStartAt"
        }

        val argsClose = src.indexOf(") {", launchedEffectAt)
        require(argsClose >= 0 && argsClose < stageStartAt) {
            "could not find arg-list close for LaunchedEffect at $launchedEffectAt"
        }

        val bodyOpen = argsClose + 2
        var depth = 0
        var i = bodyOpen
        var bodyClose = -1
        while (i < src.length) {
            when (src[i]) {
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
        val delayAt = body.indexOf("kotlinx.coroutines.delay(300)")
        val startAt = body.indexOf("LongOpService.start(")
        require(delayAt >= 0) {
            "the debounce delay was removed from InlineModelPreview â€” this test is now " +
                "obsolete (or the debounce moved). Re-read the LaunchedEffect and decide " +
                "what the new ordering contract should be before deleting this assertion."
        }
        require(startAt >= 0) {
            "LongOpService.start(...) missing â€” covered by the previous test, but this " +
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
