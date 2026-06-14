package com.u1.slicer.gcode

import java.io.File

enum class GcodeToolSpace {
    CANONICAL,
    PHYSICAL,
}

fun gcodeToolSpaceFromDb(value: String?): GcodeToolSpace =
    when (value) {
        GcodeToolSpace.PHYSICAL.name -> GcodeToolSpace.PHYSICAL
        else -> GcodeToolSpace.CANONICAL
    }

fun gcodeToolSpaceToDb(value: GcodeToolSpace): String = value.name

/**
 * Phase 2.3 — non-destructive print-time T-index rewrite.
 *
 * Reads [sourceGcodePath] line-by-line, rewriting T-indices,
 * `M104/M109 ... Tn`, and Snapmaker `SM_ ... EXTRUDER=n` / `INDEX=n`
 * patterns according to [colorMapping], writes the result to [outputPath].
 *
 * Why a new function vs reusing [GcodeToolRemapper.remap]: the existing
 * `remap` overwrites in place. With print-time mapping (Phase 2 design),
 * the user can change their filament-to-slot mapping in the Filament
 * mapping dialog after slicing has finished. Each change re-applies the
 * remap to the *original* sliced G-code — so the source must stay intact.
 *
 * @param sourceGcodePath The canonical sliced G-code emitted by the
 *   slicer with `T<fileIndex>` references. Never modified.
 * @param outputPath The destination for the remapped G-code (e.g.
 *   "${sourceGcodePath}.remapped"). Overwritten if it exists.
 * @param colorMapping Mapping from file-index → physical slot. Index N
 *   in this list is the slot to use for the file's filament N. Empty list
 *   = no rewrite (output is a verbatim copy of the source).
 */
/**
 * Phase 2 (2026-04-28) — derives a canonical-fileIndex → physical-slot
 * mapping for the export boundary (Send / Save / Share / Jobs share).
 *
 * Returned list: index = canonical fileIndex, value = physical slot
 * (0..3 for U1). [applyPrintTimeRemap] consumes this list directly.
 *
 * Returns `null` when there's no canonical context (legacy file, STL
 * with no canonical list yet, etc.) — caller treats null as "identity
 * copy, skip remap".
 *
 * The five input cases:
 *   1. Full canonical mapping confirmed by user (size == canonicalSize)
 *      → returned as-is (clamped to 0..3 defensively).
 *   2. Plate-narrowed mapping with explicit fileIndex keys (Phase 2
 *      post-round-2-review fix) → built into a sparse map and expanded
 *      to canonical width with mod-4 fallback for unmapped canonical
 *      indices.
 *   3. Plate-narrowed mapping without fileIndex keys (legacy / cache-
 *      stale) → positional expansion with mod-4 fallback. Best-effort
 *      and known-imperfect for multi-plate files where the plate-
 *      narrowed mapping doesn't start at canonical fileIndex 0.
 *   4. Single-colour with selected slot → `[selectedExtruder]` clamped
 *      to U1's 0..3 range.
 *   5. No canonical context → `null` (identity copy).
 *
 * Edge case: multi-colour file loaded but user hasn't confirmed a
 * mapping yet (`confirmedMapping == null` and `canonicalSize > 1`).
 * Falls back to identity-mod-4 so accidental Save/Share still produces
 * a printable file.
 *
 * @param canonicalSize Number of canonical fileIndices addressable by
 *   the slicer (matches `CanonicalFilamentList.size`).
 * @param confirmedMapping User's slot picks, in either canonical
 *   fileIndex order (when [confirmedMappingFileIndices] is null) or in
 *   parallel with [confirmedMappingFileIndices].
 * @param confirmedMappingFileIndices Optional. When non-null,
 *   `confirmedMappingFileIndices[i]` is the 0-indexed canonical
 *   fileIndex that `confirmedMapping[i]` is the slot for. Used when
 *   the mapping is plate-narrowed and the plate's filaments don't
 *   correspond to canonical fileIndices 0..size-1. Reviewer 3's P1
 *   "resolver invents wrong slots" finding requires this to avoid
 *   silently misclassifying T-indices on multi-plate sparse files.
 * @param selectedExtruder Physical slot 0..3 to use for single-colour
 *   files where [confirmedMapping] is null.
 *
 * Spec: docs/superpowers/specs/2026-04-28-canonical-export-mapping-helper-design.md
 */
fun resolveCanonicalExportMapping(
    canonicalSize: Int,
    confirmedMapping: List<Int>?,
    selectedExtruder: Int,
    confirmedMappingFileIndices: List<Int>? = null,
): List<Int>? {
    if (canonicalSize == 0) {
        // B106: non-canonical single-colour (raw STL). Still apply the user's
        // physical slot so T0 in the sliced G-code is rewritten to the selected
        // extruder at send time. Without this, STL sliced with E3 selected stays
        // as T0 and the wrong extruder is used by the printer.
        val slot = selectedExtruder.coerceIn(0, 3)
        return if (slot != 0) listOf(slot) else null
    }

    // Phase 2 (post-round-2-review, Reviewer 1 hardening) — clamp every
    // confirmedMapping entry to the U1's physical-slot range. Defends
    // against malformed mapping entering state/DB and producing
    // out-of-range T<n> on the printer-bound output.
    fun Int.clamp(): Int = this.coerceIn(0, 3)

    if (!confirmedMapping.isNullOrEmpty()) {
        // Phase 2 (post-round-2-review, Reviewer 3 P1) — when the
        // caller supplies fileIndex keys, build a sparse fileIdx →
        // slot map. Otherwise fall back to positional interpretation
        // (legacy / cache-stale path).
        if (!confirmedMappingFileIndices.isNullOrEmpty()) {
            val byFileIdx = mutableMapOf<Int, Int>()
            confirmedMappingFileIndices.zip(confirmedMapping)
                .forEach { (fileIdx, slot) ->
                    if (fileIdx in 0 until canonicalSize) {
                        byFileIdx[fileIdx] = slot.clamp()
                    }
                }
            return List(canonicalSize) { i ->
                byFileIdx[i] ?: (i % 4)
            }
        }
        return if (confirmedMapping.size >= canonicalSize) {
            confirmedMapping.take(canonicalSize).map { it.clamp() }
        } else {
            List(canonicalSize) { i ->
                if (i < confirmedMapping.size) confirmedMapping[i].clamp() else (i % 4)
            }
        }
    }

    if (canonicalSize == 1) {
        return listOf(selectedExtruder.clamp())
    }

    return List(canonicalSize) { it % 4 }
}

fun resolveExportMappingForToolSpace(
    toolSpace: GcodeToolSpace,
    canonicalSize: Int,
    confirmedMapping: List<Int>?,
    selectedExtruder: Int,
    confirmedMappingFileIndices: List<Int>? = null,
): List<Int>? {
    if (toolSpace == GcodeToolSpace.PHYSICAL) return null
    return resolveCanonicalExportMapping(
        canonicalSize = canonicalSize,
        confirmedMapping = confirmedMapping,
        selectedExtruder = selectedExtruder,
        confirmedMappingFileIndices = confirmedMappingFileIndices,
    )
}

fun applyPrintTimeRemap(
    sourceGcodePath: String,
    outputPath: String,
    colorMapping: List<Int>,
) {
    val src = File(sourceGcodePath)
    val out = File(outputPath)
    if (colorMapping.isEmpty()) {
        src.copyTo(out, overwrite = true)
        return
    }
    val toolMap = colorMapping.mapIndexed { fileIdx, slot -> fileIdx to slot }.toMap()
    src.bufferedReader().use { inp ->
        out.bufferedWriter().use { writer ->
            for (line in inp.lineSequence()) {
                writer.write(GcodeToolRemapper.remapLine(line.trimEnd(), toolMap))
                writer.write("\n")
            }
        }
    }
}

/**
 * Phase 2 B.1 (2026-04-28) — type-safe overload of [applyPrintTimeRemap].
 * Consumes a [CanonicalGcodePath] and produces a [PhysicalGcodePath]; only
 * functions that send G-code to the printer accept the latter, so callers
 * that forget to remap fail at compile time.
 *
 * When [colorMapping] is empty the input is copied verbatim to the output
 * — used for legacy / single-colour cases where the source is already in
 * physical-slot space. The return value is still typed
 * [PhysicalGcodePath] because the caller has asserted (by handing in
 * an empty mapping) that the source is print-ready.
 */
fun applyPrintTimeRemap(
    source: CanonicalGcodePath,
    output: PhysicalGcodePath,
    colorMapping: List<Int>,
): PhysicalGcodePath {
    applyPrintTimeRemap(source.absolutePath, output.absolutePath, colorMapping)
    return output
}

/**
 * Internal-memory wrong-nozzle fix (2026-06-03) — chooses the [colorMapping]
 * fed to [applyPrintTimeRemap] based on how the file will be started.
 *
 * The physical T-index remap is only correct for a verbatim API print
 * (Map & Print). When a file is uploaded to hold and later started from the
 * printer's own internal memory, the printer applies its Filament Setup
 * (canonical→physical) mapping to the body. If the body is already in
 * physical-slot space, the two maps compose → double remap → the first
 * colour prints on the wrong nozzle.
 *
 * So for the Upload-Only / send-to-hold path with a canonical source we return
 * an EMPTY mapping (verbatim copy = canonical body) and let the printer map
 * once via Filament Setup. When the source is already physical-slot G-code
 * (ColorMix component-tool output), the caller can keep a physical mapping so
 * the upload remains an E-slot body rather than being treated as canonical.
 * Map & Print returns the resolved [physicalMapping] so its body is print-ready
 * for the verbatim API start.
 *
 * @param uploadOnly true for the send-to-hold / Upload-Only action.
 * @param physicalMapping the resolved canonical-fileIndex → physical-slot
 *   mapping, interpreted in [sourceToolSpace].
 */
fun sendRemapForAction(
    uploadOnly: Boolean,
    physicalMapping: List<Int>,
    sourceToolSpace: GcodeToolSpace = GcodeToolSpace.CANONICAL,
): List<Int> =
    if (uploadOnly && sourceToolSpace == GcodeToolSpace.CANONICAL) emptyList() else physicalMapping
