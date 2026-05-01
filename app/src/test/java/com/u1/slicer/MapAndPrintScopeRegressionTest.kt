package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the 2026-05-01 "Map & Print / Map & Upload silently
 * does nothing" bug.
 *
 * **Symptom:** after slicing, tapping "Map & Print" or "Map & Upload" opened
 * the Filament Mapping dialog correctly and the dialog dismissed on Confirm,
 * but the print was never sent. No remapped G-code was written, no HTTP
 * request to the printer fired, no error in logs, no SendingState transition.
 *
 * **Root cause:** `rememberCoroutineScope()` was declared INSIDE the
 * `pendingMappingSend?.let { pending -> ... }` block. The dialog's
 * `onConfirm` lambda did:
 * ```
 *   pendingMappingSend = null         // triggers recomposition
 *   navigateTab(Routes.PRINTER)        // synchronous
 *   scope.launch(IO) { applyPrintTimeRemap(); sendAndPrint() }
 * ```
 * Setting `pendingMappingSend = null` causes the `?.let` block to leave the
 * composition tree on the next frame. Compose then cancels the
 * `rememberCoroutineScope()` tied to that block. The `scope.launch`
 * dispatched into a dying scope and the IO coroutine was silently killed
 * before any line executed — print never sent.
 *
 * **Fix:** hoist `rememberCoroutineScope()` to the parent composable
 * (which lives across the dialog's lifecycle). Declared as
 * `sendActionScope` outside the gate.
 *
 * This test enforces three structural invariants on `MainActivity.kt`:
 *  1. A `sendActionScope` declaration exists at the parent-composable level.
 *  2. It is declared BEFORE the `pendingMappingSend?.let` gate (so it
 *     survives the dialog dismissal).
 *  3. The gate body uses `sendActionScope.launch` — not a freshly-allocated
 *     `rememberCoroutineScope()` declared inside the gate.
 *
 * The third invariant is enforced via line-based scanning rather than a
 * full Kotlin parser: from the `pendingMappingSend?.let` line forward,
 * any `rememberCoroutineScope()` declaration we encounter before the
 * outer composable returns would re-introduce the bug.
 */
class MapAndPrintScopeRegressionTest {

    private val mainActivityPath =
        File("src/main/java/com/u1/slicer/MainActivity.kt")

    private val mainActivitySource: String by lazy {
        require(mainActivityPath.exists()) {
            "MainActivity.kt not found at ${mainActivityPath.absolutePath} — " +
                "test must run from the app/ module"
        }
        mainActivityPath.readText()
    }

    @Test
    fun sendActionScope_isDeclared() {
        val src = mainActivitySource
        val decl = "val sendActionScope = rememberCoroutineScope()"
        assertTrue(
            "MainActivity.kt must declare `$decl` to use for the dialog's " +
                "onConfirm IO coroutine. The pre-fix shape declared " +
                "`val scope = rememberCoroutineScope()` INSIDE the " +
                "`pendingMappingSend?.let` block, which got cancelled when " +
                "the dialog dismissed and silently killed the print/upload.",
            src.contains(decl)
        )
    }

    @Test
    fun sendActionScope_declaredBeforePendingMappingSendGate() {
        val src = mainActivitySource
        val sendActionScopeIdx = src.indexOf("val sendActionScope = rememberCoroutineScope()")
        val gateIdx = src.indexOf("pendingMappingSend?.let { pending ->")
        require(sendActionScopeIdx >= 0) {
            "sendActionScope declaration not found — see " +
                "sendActionScope_isDeclared() for context."
        }
        require(gateIdx >= 0) {
            "MainActivity.kt no longer contains `pendingMappingSend?.let` " +
                "block — if the dialog gate was renamed or restructured, " +
                "update this test."
        }
        assertTrue(
            "sendActionScope must be declared BEFORE the " +
                "pendingMappingSend?.let { ... } gate (so it survives the " +
                "dialog dismissal). Found sendActionScope at " +
                "$sendActionScopeIdx, gate at $gateIdx.",
            sendActionScopeIdx < gateIdx
        )
    }

    @Test
    fun gateBody_doesNotShadowScope_withFreshlyAllocatedRememberCoroutineScope() {
        val src = mainActivitySource
        val gateIdx = src.indexOf("pendingMappingSend?.let { pending ->")
        require(gateIdx >= 0) {
            "MainActivity.kt no longer contains `pendingMappingSend?.let` block."
        }
        // From the gate line onward, scan to the end of the file and look for
        // any line that declares `val <name> = rememberCoroutineScope()`. If
        // any exist they're either:
        //   - inside the gate (the bug we fixed), or
        //   - in a sibling composable later in the file (irrelevant to
        //     this gate but still risky if a future refactor moves the
        //     gate down — flag it for review).
        // Production has exactly one rememberCoroutineScope() in MainActivity
        // (the hoisted sendActionScope). After the gate appears, the count
        // must be 0.
        val afterGate = src.substring(gateIdx)
        val rememberDecls = Regex("""val\s+\w+\s*=\s*rememberCoroutineScope\s*\(\s*\)""")
            .findAll(afterGate)
            .map { it.value }
            .toList()
        assertTrue(
            "After the `pendingMappingSend?.let` gate begins, no `val X = " +
                "rememberCoroutineScope()` declaration should appear. Any " +
                "such declaration is at risk of being scoped to the gate's " +
                "lifecycle, which gets cancelled when the dialog dismisses " +
                "(setting pendingMappingSend = null) — silently killing the " +
                "IO coroutine. Found: $rememberDecls",
            rememberDecls.isEmpty()
        )
    }

    @Test
    fun gateBody_referencesSendActionScope_forIoLaunch() {
        val src = mainActivitySource
        val gateIdx = src.indexOf("pendingMappingSend?.let { pending ->")
        require(gateIdx >= 0) {
            "MainActivity.kt no longer contains `pendingMappingSend?.let` block."
        }
        // Scan the rest of the file for `sendActionScope.launch(`. The gate
        // contains exactly two such launches in the current shape (the
        // CanonicalLookup.Present onConfirm path + the CanonicalLookup.Absent
        // fallback path); both must use the hoisted scope.
        val afterGate = src.substring(gateIdx)
        val sendActionLaunches = Regex("""sendActionScope\s*\.launch\s*\(""")
            .findAll(afterGate)
            .toList()
        assertTrue(
            "Expected at least 2 `sendActionScope.launch(` calls in the " +
                "pendingMappingSend gate (CanonicalLookup.Present onConfirm " +
                "+ CanonicalLookup.Absent fallback). Found " +
                "${sendActionLaunches.size}. If the gate's structure changed " +
                "such that the launch count differs, update this test — but " +
                "verify the new shape still uses a hoisted scope.",
            sendActionLaunches.size >= 2
        )
    }
}
