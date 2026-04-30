package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 2 (post-v2.0.0-validation) — structural guards against the
 * "canonical-space leak" bug class identified during v2.0.0 manual
 * verification.
 *
 * The class: any export path (Send / Save / Share / Jobs share) that
 * reads `_colorMapping.value` (live, plate-narrowed for multi-plate
 * Bambu) or `decodedColorMapping(job)` (decoded from stored CSV) and
 * passes it positionally to `applyPrintTimeRemap` without first
 * routing through `resolveCanonicalExportMapping` will leak canonical-
 * fileIndex T-values (T4-T9+) to the printer-bound output for
 * multi-plate files whose plate filaments don't start at canonical
 * fileIdx 0. The U1 firmware only understands T0-T3 so the print
 * either fails or produces wrong-colour output.
 *
 * Three siblings were caught during v2.0.0 validation:
 *   1. Send dialog: pre-fix used `_colorMapping` directly. Fixed by
 *      computing `plateFileIndices` and expanding to canonical-wide
 *      before `applyPrintTimeRemap`.
 *   2. Slice Summary chip strip: pre-fix indexed canonical list
 *      positionally by perExtruderFilamentMm row. Fixed by passing
 *      `effectivePlateIndices` for fileIdx translation.
 *   3. Jobs Share (`shareJobGcode`): pre-fix decoded plate-narrowed
 *      stored CSV and applied positionally. Fixed by routing the
 *      slice-time persist through `resolveExportMapping()` (canonical-
 *      wide CSV) and the share-time decode through
 *      `resolveCanonicalExportMapping`.
 *
 * The grep-based guards below are pattern matches against
 * `app/src/main`. Pattern follows the existing `HardcodedExtruderCapTest`
 * structural guards.
 */
class CanonicalExportLeakGuardTest {

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

    private fun readSourceTree(): Map<String, String> {
        val roots = listOf(
            File("app/src/main/java/com/u1/slicer"),
            File("../app/src/main/java/com/u1/slicer"),
            File("src/main/java/com/u1/slicer"),
        )
        val root = roots.firstOrNull { it.exists() }
            ?: error("source tree not found from ${File(".").absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path.replace('\\', '/') to it.readText() }
    }

    /**
     * Bug 2 class — `applyPrintTimeRemap(` is the canonical→physical
     * write boundary. Every callsite must be reviewed when added; the
     * known-good ones live in two files.
     *
     * Allow-list:
     *   - `gcode/PrintTimeRemap.kt` — the function definitions (2 overloads).
     *   - `MainActivity.kt` — Send dialog onConfirm (uses `expanded`
     *     built from `plateFileIndices`, plus the doc-comment `//
     *     applyPrintTimeRemap to a temp file ...`).
     *   - `SlicerViewModel.kt` — `prepareExportableGcodeWithMapping`,
     *     the resolver-driven layer-2 helper used by Save / Share /
     *     Jobs Share.
     *
     * Any new file containing `applyPrintTimeRemap(` is a structural
     * red flag — it likely bypasses the resolver. New callers MUST
     * either route through `prepareExportableGcodeWithMapping` or
     * compute their mapping via `resolveCanonicalExportMapping`.
     */
    @Test
    fun applyPrintTimeRemap_callSites_areConfinedToReviewedFiles() {
        val tree = readSourceTree()
        val knownFiles = setOf(
            "gcode/PrintTimeRemap.kt",
            "MainActivity.kt",
            "SlicerViewModel.kt",
        )
        // Match a function call: `applyPrintTimeRemap(` (paren immediately
        // after, no space between identifier and paren). Excludes
        // doc-comment mentions like `// applyPrintTimeRemap to ...`.
        val callRegex = Regex("""\bapplyPrintTimeRemap\(""")
        val violations = tree.entries
            .filter { (path, _) -> path !in knownFiles }
            .filter { (_, src) -> callRegex.containsMatchIn(src) }
            .map { it.key }
        assertTrue(
            "applyPrintTimeRemap() callers must be confined to the reviewed " +
                "set (PrintTimeRemap.kt definitions, MainActivity.kt Send " +
                "dialog, SlicerViewModel.kt prepareExportableGcodeWithMapping). " +
                "New callers are likely Bug 2 class siblings — they must " +
                "compute their mapping via resolveCanonicalExportMapping or " +
                "route through prepareExportableGcodeWithMapping. Found " +
                "${violations.size} unexpected files: $violations",
            violations.isEmpty()
        )
    }

    /**
     * Bug 2 class — `decodedColorMapping(job)` returns a List<Int>
     * decoded from the stored CSV. If the CSV was written by a
     * pre-fix slicer, that list could be plate-narrowed (size <
     * canonicalListSize). Consumers must NOT pass it positionally
     * to `applyPrintTimeRemap` — they must route through
     * `resolveCanonicalExportMapping` so the resolver handles the
     * three known shapes (canonical-wide / plate-narrowed / null).
     *
     * Allow-list:
     *   - `data/SliceJob.kt` — function definition.
     *   - `SlicerViewModel.kt` — single consumer in `shareJobGcode`,
     *     which feeds the result to `resolveCanonicalExportMapping`.
     */
    @Test
    fun decodedColorMapping_consumers_routeThroughResolver() {
        val tree = readSourceTree()
        val callRegex = Regex("""\bdecodedColorMapping\(""")
        val violations = tree.entries
            .filter { (path, _) -> path != "data/SliceJob.kt" }
            .filter { (_, src) -> callRegex.containsMatchIn(src) }
            .filter { (_, src) ->
                // Allow only when paired with resolveCanonicalExportMapping
                // in the same file (pragmatic check; a per-callsite static
                // analysis would need a real Kotlin parser).
                !src.contains("resolveCanonicalExportMapping(")
            }
            .map { it.key }
        assertTrue(
            "decodedColorMapping(job) consumers must pair with " +
                "resolveCanonicalExportMapping in the same file (Bug 2 " +
                "class — bare positional use leaks canonical-fileIndex " +
                "T-values to the printer for multi-plate jobs whose plate " +
                "filaments don't start at canonical fileIdx 0). Found " +
                "${violations.size} unpaired consumers: $violations",
            violations.isEmpty()
        )
    }

    /**
     * Bug 2 class — slice-time `colorMappingCsv` persistence must use
     * `resolveExportMapping()` (canonical-wide) rather than
     * `_colorMapping.value` (plate-narrowed for multi-plate Bambu).
     *
     * The slice-time persist site lives in SlicerViewModel.kt. Since
     * Kotlin is hard to statically check at this granularity, we
     * pattern-match the assignment: the line setting `colorMappingCsv =`
     * must mention `resolveExportMapping`.
     */
    @Test
    fun sliceTimeColorMappingCsv_isPersistedFromResolver() {
        val src = readSource("SlicerViewModel.kt")
        val assignRegex = Regex("""colorMappingCsv\s*=\s*([^\n]+)""")
        val matches = assignRegex.findAll(src).toList()
        assertEquals(
            "Expected exactly one colorMappingCsv assignment (slice-time " +
                "persist site). Found ${matches.size}.",
            1, matches.size
        )
        val nextLines = src.substring(matches.single().range.first).take(300)
        assertTrue(
            "colorMappingCsv at slice time must derive from " +
                "resolveExportMapping() (canonical-wide), not " +
                "_colorMapping.value (plate-narrowed for multi-plate). " +
                "Bug 2 class — pre-fix shipped canonical-T G-code to the " +
                "printer for jobs whose plate filaments don't start at " +
                "canonical fileIdx 0. Surrounding source: ${nextLines.take(120)}",
            nextLines.contains("resolveExportMapping()")
        )
    }

    /**
     * Bug 3 class — Slice Summary chip strip indexes the canonical
     * filament list by row position in `perExtruderFilamentMm`. With the
     * v2.0.0 systematic fix, the chip strip iterates `visibleEntries`
     * (a list of `ChipEntry(fileIdx, plateNarrowedPosition, mm)` triples)
     * derived from `plateFileIndices`. plateFileIndices itself is
     * gcode-driven (non-zero perExtruderFilamentMm canonical fileIdx
     * positions) when post-slice gcode is available.
     *
     * Guard: the SliceSummary composable must consume `plateFileIndices`
     * AND iterate `visibleEntries` so chip labels use the true canonical
     * fileIdx (not positional T-index).
     */
    @Test
    fun sliceSummary_indexesCanonicalListThroughPlateFileIndices() {
        val src = readSource("MainActivity.kt")
        // Match the Composable parameter declaration block.
        val composableSignature = "perExtruderFilamentMm: List<Float>"
        assertTrue(
            "Slice Summary composable must accept perExtruderFilamentMm. " +
                "If renamed, update this guard.",
            src.contains(composableSignature)
        )
        assertTrue(
            "Slice Summary composable must thread plateFileIndices and " +
                "build visibleEntries for canonical-fileIdx-aware chip labels " +
                "(Bug 3 class). Pre-systematic-fix the chip strip iterated " +
                "perExtruderFilamentMm positionally and labelled chips by " +
                "T-index (off by one for sparse canonical files like Border " +
                "Collie / Buzz plate 1).",
            src.contains("plateFileIndices: List<Int>?") &&
                src.contains("visibleEntries")
        )
    }

    /**
     * Bug 1 class extension — gcode preview palette must be CANONICAL-
     * fileIndex-aligned (file-wide), not plate-narrowed. The renderer
     * queries palette[T] where T is a canonical fileIdx emitted by the
     * slicer; for multi-plate Bambu files whose plate filaments don't
     * start at canonical fileIdx 0 (e.g. Dragon plate 1 using fileIdx
     * 1+2 of 13), a plate-narrowed palette mis-indexes:
     *   - palette[1] returns plate filament 1's colour (correct fileIdx
     *     would be filament 2's)
     *   - palette[2] falls back to slot preset
     *
     * The post-fix wiring uses `canonicalFilamentColors` (file-wide
     * canonical, override-applied) for gcode preview composables.
     * `resolvedFilamentColors` (plate-narrowed) stays for chip-strip
     * surfaces only.
     *
     * Guard: SlicerViewModel must declare `canonicalFilamentColors`
     * StateFlow, and both gcode preview entry points (InlineGcodePreview
     * + GcodeViewer3DScreen) must consume it.
     */
    @Test
    fun gcodePreviewPalette_consumesCanonicalFilamentColorsNotPlateNarrowed() {
        val vmSrc = readSource("SlicerViewModel.kt")
        assertTrue(
            "SlicerViewModel must declare `canonicalFilamentColors: " +
                "StateFlow<List<String>>` — file-wide canonical-fileIndex-" +
                "aligned palette for the gcode preview body. Pre-fix the " +
                "preview consumed plate-narrowed `resolvedFilamentColors` " +
                "and mis-indexed for multi-plate fileIdx > 0 " +
                "(Dragon plate 1 surfaced this regression).",
            vmSrc.contains("val canonicalFilamentColors: StateFlow<List<String>>")
        )
        val mainSrc = readSource("MainActivity.kt")
        assertTrue(
            "MainActivity must collect canonicalFilamentColors via " +
                "`viewModel.canonicalFilamentColors.collectAsState()` and " +
                "pass it to InlineGcodePreview as the palette source. " +
                "Pre-fix the wiring used resolvedFilamentColors which is " +
                "plate-narrowed for multi-plate Bambu files.",
            mainSrc.contains("viewModel.canonicalFilamentColors.collectAsState()") &&
                mainSrc.contains("canonicalFilamentColors")
        )
        val navSrc = readSource("navigation/NavGraph.kt")
        assertTrue(
            "navigation/NavGraph.kt must collect and pass " +
                "canonicalFilamentColors to GcodeViewer3DScreen — the " +
                "fullscreen gcode preview also needs canonical-aligned " +
                "palette indexing.",
            navSrc.contains("viewModel.canonicalFilamentColors.collectAsState()") &&
                navSrc.contains("canonicalFilamentColors")
        )
    }

    /**
     * Bug 1 class — gcode preview palette construction must prefer
     * file colour over slot preset colour. The pre-fix
     * `normalizeGcodePreviewColors` did the opposite (slot first,
     * file fallback) — Dragon plate 1 with default presets showed E1
     * red instead of the file's blue.
     *
     * Guard: the helper's ordering in MainActivity.kt must check
     * `fileColor` BEFORE `slotColor`.
     */
    @Test
    fun gcodePreviewPalette_prefersFileColorOverSlotPreset() {
        val src = readSource("MainActivity.kt")
        // Locate the helper body and verify ordering.
        val header = "internal fun normalizeGcodePreviewColors("
        val start = src.indexOf(header)
        require(start >= 0) { "normalizeGcodePreviewColors not found" }
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
        require(bodyEnd > 0) { "normalizeGcodePreviewColors body not terminated" }
        val body = src.substring(bodyStart, bodyEnd)
        val fileColorIdx = body.indexOf("!fileColor.isNullOrBlank()")
        val slotColorIdx = body.indexOf("!slotColor.isNullOrBlank()")
        assertTrue(
            "normalizeGcodePreviewColors must check !fileColor.isNullOrBlank() " +
                "before !slotColor.isNullOrBlank(). Bug 1 class — slot-first " +
                "ordering shows the wrong colour when both are non-blank " +
                "(Dragon plate 1: blue file colour overridden by red E1 preset).",
            fileColorIdx in 0 until slotColorIdx
        )
    }
}
