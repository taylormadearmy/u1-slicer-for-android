package com.u1.slicer.gcode

import java.io.File

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
 * The four input cases:
 *   1. Full canonical mapping confirmed by user → returned (truncated
 *      to canonical size if stale and longer).
 *   2. Plate-narrowed mapping (smaller than canonical) → expanded with
 *      mod-4 fallback for out-of-plate canonical indices.
 *   3. Single-colour with selected slot → `[selectedExtruder]` clamped
 *      to U1's 0..3 range.
 *   4. No canonical context → `null` (identity copy).
 *
 * Edge case: multi-colour file loaded but user hasn't confirmed a
 * mapping yet (`confirmedMapping == null` and `canonicalSize > 1`).
 * Falls back to identity-mod-4 so accidental Save/Share still produces
 * a printable file.
 *
 * Spec: docs/superpowers/specs/2026-04-28-canonical-export-mapping-helper-design.md
 */
fun resolveCanonicalExportMapping(
    canonicalSize: Int,
    confirmedMapping: List<Int>?,
    selectedExtruder: Int,
): List<Int>? {
    if (canonicalSize == 0) return null

    if (!confirmedMapping.isNullOrEmpty()) {
        return if (confirmedMapping.size >= canonicalSize) {
            confirmedMapping.take(canonicalSize)
        } else {
            List(canonicalSize) { i ->
                if (i < confirmedMapping.size) confirmedMapping[i] else (i % 4)
            }
        }
    }

    if (canonicalSize == 1) {
        return listOf(selectedExtruder.coerceIn(0, 3))
    }

    return List(canonicalSize) { it % 4 }
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
