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

    @Ignore("Phase 2 refactor — §4 Step 3: ProfileEmbedder.normalizePerFilamentArrays no truncation")
    @Test
    fun profileEmbedder_normalizePerFilamentArrays_doesNotTruncate() {
        val src = readSource("bambu/ProfileEmbedder.kt")
        val truncates = src.contains("while (list.size > targetCount)") ||
            src.contains("list.removeAt(list.lastIndex)")
        assertTrue(
            "ProfileEmbedder.normalizePerFilamentArrays must NOT truncate per-" +
                "filament arrays when source > target. Drops override material " +
                "for files with N > 4. Handoff §1 table site 13.",
            !truncates
        )
    }

    @Ignore("Phase 2 refactor — §4 Step 2: extCount parameter retirement")
    @Test
    fun slicerViewModel_doesNotPassExtCount_toFileFilamentSpaceFunctions() {
        val src = readSource("SlicerViewModel.kt")
        // After §4 Step 2 retires `extCount` as a parameter name (split into
        // `filamentCount` and `slotCount`), the symbol should not appear
        // outside the SliceConfig field name. Permit the SliceConfig field
        // (cfg.extruderCount → val extCount = ...) but flag passes as
        // function args.
        val violations = Regex("""\bextCount\s*[,)]""").findAll(src).toList()
        assertTrue(
            "SlicerViewModel must not pass `extCount` as a function argument — " +
                "the parameter conflates file-filament-space and slot-space, " +
                "which is the root smell behind the §1 bug class. Architecture " +
                "review §4 Step 2. Found ${violations.size} occurrences.",
            violations.isEmpty()
        )
    }
}
