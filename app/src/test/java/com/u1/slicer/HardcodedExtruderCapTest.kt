package com.u1.slicer

import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Phase 2 — structural guards against the "hardcoded ≤4 extruders" bug
 * class identified in the architecture review at
 * `docs/superpowers/reviews/2026-04-26-phase2-architecture-review.md`.
 *
 * Two kinds of assertion live here:
 *
 * **Currently-green guards** — invariants already in tree (e.g. GcodeParser's
 * grow-on-demand buffer per B95 / `ensureExtruderCapacity`). These run on
 * every build and fail on regression.
 *
 * **Currently-red guards** — `@Ignore`d targets for the Phase 2 refactor's
 * remaining work (display-only `coerceIn(0, 3)`, `ProfileEmbedder` truncation,
 * `extCount` parameter retirement). Each `@Ignore` references the §4 step in
 * the architecture review where the fix is scheduled. Un-ignore as the
 * refactor progresses; the test then enforces the fix doesn't regress.
 *
 * Pattern matches the existing source-grep tests in this codebase
 * (`ModelInfoDialogScrollTest`, `FilamentTypeHeaderPatchTest`).
 */
class HardcodedExtruderCapTest {

    private fun readSource(relPath: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/$relPath"),
            File("../app/src/main/java/com/u1/slicer/$relPath"),
            File("src/main/java/com/u1/slicer/$relPath"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("$relPath not found from ${File(".").absolutePath}")
        return f.readText()
    }

    // ─── Currently-green guards ──────────────────────────────────────────

    @Test
    fun gcodeParser_perExtruderArray_growsBeyondFour() {
        val src = readSource("gcode/GcodeParser.kt")
        assertTrue(
            "GcodeParser must define ensureExtruderCapacity() so per-extruder " +
                "buffers grow on demand for paint-segmentation files (H2C: 7, " +
                "Buzz plate 9: 11). Regression of B95 / handoff §1 bug 1.",
            src.contains("fun ensureExtruderCapacity(")
        )
    }

    @Test
    fun gcodeParser_doesNotSilentlyCapTIndexAtThree() {
        val src = readSource("gcode/GcodeParser.kt")
        val violations = Regex("""coerceIn\(\s*0\s*,\s*3\s*\)""").findAll(src).toList()
        assertTrue(
            "GcodeParser must NOT coerceIn(0, 3) on T-index parsing — silently " +
                "collapses T6 → T3 and loses data for >4-filament files. " +
                "Handoff §1 bug 1, GcodeParser.kt:249 in the original report. " +
                "Found ${violations.size} violations.",
            violations.isEmpty()
        )
    }

    @Test
    fun gcodeParser_doesNotTakeFour_onPerExtruderArrays() {
        val src = readSource("gcode/GcodeParser.kt")
        val violations = Regex("""\.take\(4\)""").findAll(src).toList()
        assertTrue(
            "GcodeParser must NOT use .take(4) on per-extruder arrays — caps " +
                "filament count at 4 silently for paint-segmentation files. " +
                "Handoff §1 table sites 3-5. Found ${violations.size} occurrences.",
            violations.isEmpty()
        )
    }

    // ─── Currently-red guards (Phase 2 refactor TODOs) ───────────────────

    /**
     * Display-side cap: `MainActivity.buildPerExtruderDisplaySlots` and the
     * preview palette colours ≤4 paths still coerce / truncate. Slicer
     * correctness is unaffected (the slicer side is filament-indexed) but
     * the user sees only 4 chips for a 7-filament file.
     *
     * Fix scheduled for §4 Step 6 (display + preview palette N-indexed).
     */
    @Ignore("Phase 2 refactor — §4 Step 6: display palette N-indexed")
    @Test
    fun mainActivity_displaySlots_doNotCoerceToThree() {
        val src = readSource("MainActivity.kt")
        val violations = Regex("""coerceIn\(\s*0\s*,\s*3\s*\)""").findAll(src).toList()
        assertTrue(
            "MainActivity must NOT coerceIn(0, 3) on file-filament indices in " +
                "display paths. Handoff §1 table sites 9-10. Found " +
                "${violations.size} occurrences.",
            violations.isEmpty()
        )
    }

    @Ignore("Phase 2 refactor — §4 Step 6: display palette N-indexed")
    @Test
    fun mainActivity_displayPaths_doNotTakeFour_onColorArrays() {
        val src = readSource("MainActivity.kt")
        // Permit `.take(4)` only on lines that explicitly carry an opt-out
        // comment. Today there are no such comments — every site is a
        // display-side filament-count cap that should grow with N.
        val violations = src.lines()
            .withIndex()
            .filter { (_, line) ->
                line.contains(".take(4)") && !line.contains("// slot-space")
            }
        assertTrue(
            "MainActivity must NOT .take(4) on file-filament arrays in display " +
                "paths (colour palette, job-list inline preview, G-code preview " +
                "palette). Handoff §1 table sites 11-12. Annotate slot-space " +
                "callers with `// slot-space` to opt out. Found " +
                "${violations.size} unannotated occurrences: " +
                "${violations.take(3).map { (i, _) -> "line ${i + 1}" }}",
            violations.isEmpty()
        )
    }

    @Test
    fun profileEmbedder_normalizePerFilamentArrays_doesNotTruncate() {
        val src = readSource("bambu/ProfileEmbedder.kt")
        // Locate the function body and search only that scope, so the
        // adjacent `normalizeMatrix` (which legitimately truncates the
        // flush_volumes matrix to its NxN size) does not register.
        val header = "private fun normalizePerFilamentArrays("
        val start = src.indexOf(header)
        require(start >= 0) { "normalizePerFilamentArrays not found" }
        val bodyStart = src.indexOf('{', src.indexOf(')', start))
        var depth = 0
        var i = bodyStart
        var bodyEnd = -1
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { bodyEnd = i + 1; break } }
            }
            i++
        }
        require(bodyEnd > 0) { "normalizePerFilamentArrays body not terminated" }
        val body = src.substring(bodyStart, bodyEnd)
        val truncates = body.contains("list.size > targetCount") ||
            body.contains("list.removeAt(list.lastIndex)")
        assertTrue(
            "ProfileEmbedder.normalizePerFilamentArrays must NOT truncate per-" +
                "filament arrays when source > target — drops override material " +
                "for files with N > 4 (handoff §1 table site 13). Caller-side " +
                "guarantee in SlicerViewModel.embedProfile bumps targetCount " +
                "to >= canonical.size.",
            !truncates
        )
    }

    @Test
    fun slicerViewModel_doesNotPassExtCount_toFileFilamentSpaceFunctions() {
        val src = readSource("SlicerViewModel.kt")
        // §4 Step 2 retired `extCount` as a parameter name (split into
        // `slotCount` and `filamentCount`). The diagnostics map key
        // `"extCount" to slotCount` survives as a string literal for log
        // backwards-compatibility — it is not a function argument and the
        // regex below excludes string-literal contexts.
        val violations = src.lines()
            .withIndex()
            .filter { (_, line) ->
                val match = Regex("""\bextCount\s*[,)=]""").find(line) ?: return@filter false
                // Exclude `"extCount"` string-literal context (the diagnostics key).
                val pre = line.substring(0, match.range.first)
                !pre.endsWith("\"")
            }
        assertTrue(
            "SlicerViewModel must not pass `extCount` as a function argument — " +
                "the parameter conflates file-filament-space and slot-space, " +
                "which is the root smell behind the §1 bug class. Architecture " +
                "review §4 Step 2. Found ${violations.size} occurrences: " +
                "${violations.take(3).map { (i, _) -> "line ${i + 1}" }}",
            violations.isEmpty()
        )
    }
}
