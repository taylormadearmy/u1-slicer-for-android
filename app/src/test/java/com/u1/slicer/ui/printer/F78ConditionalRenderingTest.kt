package com.u1.slicer.ui.printer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F78 source-grep guards for Compose UI conditional-rendering contracts.
 *
 * These tests read source files to verify that the F78 conditional-rendering
 * contracts are in place — similar in pattern to [com.u1.slicer.ui.InlineModelPreviewRotationKeysTest].
 * The intent is to catch silent regressions where the visibility predicate is removed
 * or restructured in a way that breaks the single-printer UX or the nickname subtitle.
 *
 * None of these tests use a Compose UI harness — they grep the source text. The
 * underlying logic is trivially simple (a boolean guard), so a source-text pin is
 * the right tradeoff: it's cheap, deterministic, and doesn't require the Compose
 * test infrastructure that is absent from this project.
 */
class F78ConditionalRenderingTest {

    // The gradle test runner's working directory is the `app/` module root when
    // tests are run via `./gradlew testDebugUnitTest`. The same multi-candidate
    // pattern used in InlineModelPreviewRotationKeysTest is followed here so the
    // tests also pass when invoked from the project root.

    private fun readSource(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/$relative"),
            File("../app/src/main/java/com/u1/slicer/$relative"),
            File("src/main/java/com/u1/slicer/$relative"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("Source file not found: $relative (searched from ${File(".").absolutePath})")
        return f.readText()
    }

    // ---- Gap 4a: ActivePrinterChip is hidden for single-printer users ----

    @Test
    fun activePrinterChip_earlyReturns_whenPrinterCountIsOneOrLess() {
        val src = readSource("ui/printer/ActivePrinterChip.kt")

        assertTrue(
            "F78: ActivePrinterChip must early-return when printerCount <= 1 so single-printer " +
                "users never see the chip. Expected: `if (printerCount <= 1) return`.",
            src.contains("if (printerCount <= 1) return"),
        )
    }

    // ---- Gap 4b: FilamentMappingDialog gates the subtitle on showNicknameInTitle ----

    @Test
    fun filamentMappingDialog_gatesSendToSubtitle_onShowNicknameInTitle() {
        val src = readSource("ui/FilamentMappingDialog.kt")

        assertTrue(
            "F78: FilamentMappingDialog must declare a `showNicknameInTitle` parameter so callers " +
                "can opt in to the 'Send to <nickname>' subtitle in multi-printer scenarios.",
            src.contains("showNicknameInTitle"),
        )
        assertTrue(
            "F78: FilamentMappingDialog must gate the subtitle on `activeNickname.isNotBlank()` " +
                "so an empty nickname never produces a bare 'Send to ' label.",
            src.contains("activeNickname.isNotBlank()"),
        )
        assertTrue(
            "F78: FilamentMappingDialog must render a 'Send to ' string as the subtitle text " +
                "when the gate condition is met.",
            src.contains("Send to "),
        )
        // Belt-and-suspenders: verify the gate is a conjunction of both conditions, not just one.
        assertTrue(
            "F78: The 'Send to' subtitle must be gated by `showNicknameInTitle && activeNickname.isNotBlank()` " +
                "(both conditions together) to prevent showing a blank or unwanted subtitle.",
            src.contains("showNicknameInTitle") && src.contains("activeNickname.isNotBlank()"),
        )
    }

    // ---- Gap 4c: PrinterScreen passes both printerCount and activeNickname to the chip ----

    @Test
    fun printerScreen_passesRequiredParametersToActivePrinterChip() {
        val src = readSource("ui/PrinterScreen.kt")

        assertTrue(
            "F78: PrinterScreen must call ActivePrinterChip — if it was removed the chip is unreachable.",
            src.contains("ActivePrinterChip("),
        )
        assertTrue(
            "F78: PrinterScreen must pass `printerCount = printerCount` to ActivePrinterChip " +
                "so the chip can evaluate the printerCount <= 1 guard with the live count.",
            src.contains("printerCount = printerCount"),
        )
        assertTrue(
            "F78: PrinterScreen must pass `activeNickname = activeNickname` to ActivePrinterChip " +
                "so the chip can display the correct printer name.",
            src.contains("activeNickname = activeNickname"),
        )
    }
}
