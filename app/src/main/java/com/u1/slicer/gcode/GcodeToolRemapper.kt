package com.u1.slicer.gcode

import java.io.File

/**
 * Post-processes G-code to remap compact tool indices to actual printer slot indices.
 *
 * OrcaSlicer emits T0, T1, … (one per unique colour in the 3MF).  When the user assigns
 * model colours to non-zero slots (e.g. E3+E4 → physical slots [2,3]) we rewrite:
 *   T0 → T2,  T1 → T3
 *   M104 … T0 → M104 … T2   (set temperature, no wait)
 *   M109 … T0 → M109 … T2   (set temperature + wait)
 *
 * Mirrors bridge slicer.py remap_compacted_tools().
 */
object GcodeToolRemapper {

    private val TOOL_LINE_RE = Regex("""^T(\d+)\s*(?:;.*)?$""")
    // Captures the M104/M109 prefix up to (but not including) the T parameter
    private val MTEMP_T_RE = Regex("""(M10[49]\b.*?)\bT(\d+)""")

    /**
     * Rewrite [gcodePath] in-place, replacing compact tool indices with [targetSlots].
     *
     * @param gcodePath  Absolute path to the G-code file to modify.
     * @param targetSlots  Mapping from compact index → physical slot index.
     *                     E.g. [2, 3] means T0→T2, T1→T3.
     *                     If this is already the identity (0,1,…) nothing is written.
     */
    fun remap(gcodePath: String, targetSlots: List<Int>) {
        if (targetSlots.isEmpty()) return
        val toolMap = targetSlots.mapIndexed { compact, actual -> compact to actual }.toMap()
        val src = File(gcodePath)
        val tmp = File("$gcodePath.remap.tmp")
        tmp.bufferedWriter().use { out ->
            src.bufferedReader().use { inp ->
                for (line in inp.lineSequence()) {
                    out.write(remapLine(line.trimEnd(), toolMap))
                    out.write("\n")
                }
            }
        }
        // Atomic replace; fall back to copy+delete if rename fails (cross-device)
        if (!tmp.renameTo(src)) {
            tmp.copyTo(src, overwrite = true)
            tmp.delete()
        }
    }

    /** Remap a single G-code line. Visible for testing. */
    internal fun remapLine(line: String, toolMap: Map<Int, Int>): String {
        // Standalone tool change: "T0", "T1 ; comment", …
        val tMatch = TOOL_LINE_RE.matchEntire(line)
        if (tMatch != null) {
            val compact = tMatch.groupValues[1].toIntOrNull() ?: return line
            val actual = toolMap[compact] ?: compact
            return "T$actual"
        }
        // M104/M109 with T parameter anywhere on the line
        if (MTEMP_T_RE.containsMatchIn(line)) {
            return MTEMP_T_RE.replace(line) { mr ->
                val compact = mr.groupValues[2].toIntOrNull() ?: return@replace mr.value
                val actual = toolMap[compact] ?: compact
                "${mr.groupValues[1]}T$actual"
            }
        }
        return line
    }
}
