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
