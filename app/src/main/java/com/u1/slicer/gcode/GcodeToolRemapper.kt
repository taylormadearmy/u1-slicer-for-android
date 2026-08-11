package com.u1.slicer.gcode

import com.u1.slicer.bambu.BambuFilamentConfigCompactor
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
    // Snapmaker SM_ commands: SM_PRINT_AUTO_FEED EXTRUDER=N, SM_PRINT_FLOW_CALIBRATE INDEX=N, etc.
    private val SM_PARAM_RE = Regex("""((?:EXTRUDER|INDEX)=)(\d+)""")
    private val BAMBU_AMS_S_RE = Regex("""(\bS)(\d+)(A\b)""")
    private val BAMBU_RETRACT_I_RE = Regex("""(\bI)(\d+)(?=\s|$)""")
    private val FILAMENT_VECTOR_PREFIXES = listOf(
        "; filament used [mm] = ",
        "; filament used [cm3] = ",
        "; filament used [g] = ",
        "; filament cost = ",
    )

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
        remapFile(src, tmp, toolMap, emptyList())
        // Atomic replace; fall back to copy+delete if rename fails (cross-device)
        if (!tmp.renameTo(src)) {
            tmp.copyTo(src, overwrite = true)
            tmp.delete()
        }
    }

    /** Write a remapped copy while leaving the source slice untouched. */
    internal fun remapCopy(
        sourcePath: String,
        outputPath: String,
        toolMap: Map<Int, Int>,
        filamentIndices: List<Int> = emptyList(),
    ) {
        val output = File(outputPath)
        output.parentFile?.mkdirs()
        remapFile(File(sourcePath), output, toolMap, filamentIndices)
    }

    private fun remapFile(
        src: File,
        output: File,
        toolMap: Map<Int, Int>,
        filamentIndices: List<Int>,
    ) {
        output.bufferedWriter().use { out ->
            src.bufferedReader().use { inp ->
                for (line in inp.lineSequence()) {
                    val remapped = remapLine(line.trimEnd(), toolMap)
                    out.write(compactFilamentVector(remapped, filamentIndices))
                    out.write("\n")
                }
            }
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
        if ((line.startsWith("M620") || line.startsWith("M621")) && BAMBU_AMS_S_RE.containsMatchIn(line)) {
            return BAMBU_AMS_S_RE.replace(line) { mr ->
                val tool = mr.groupValues[2].toIntOrNull() ?: return@replace mr.value
                val remapped = toolMap[tool] ?: tool
                "${mr.groupValues[1]}$remapped${mr.groupValues[3]}"
            }
        }
        if (line.startsWith("M620.11") && BAMBU_RETRACT_I_RE.containsMatchIn(line)) {
            return BAMBU_RETRACT_I_RE.replace(line) { mr ->
                val tool = mr.groupValues[2].toIntOrNull() ?: return@replace mr.value
                val remapped = toolMap[tool] ?: tool
                "${mr.groupValues[1]}$remapped"
            }
        }
        // Snapmaker SM_ commands with EXTRUDER=N or INDEX=N
        if (line.startsWith("SM_") && SM_PARAM_RE.containsMatchIn(line)) {
            return SM_PARAM_RE.replace(line) { mr ->
                val compact = mr.groupValues[2].toIntOrNull() ?: return@replace mr.value
                val actual = toolMap[compact] ?: compact
                "${mr.groupValues[1]}$actual"
            }
        }
        return line
    }

    private fun compactFilamentVector(line: String, filamentIndices: List<Int>): String {
        if (filamentIndices.isEmpty()) return line
        val footerPrefix = FILAMENT_VECTOR_PREFIXES.firstOrNull(line::startsWith)
        if (footerPrefix != null) {
            val values = line.removePrefix(footerPrefix).split(',')
            val compacted = filamentIndices.mapNotNull(values::getOrNull)
            return if (compacted.size == filamentIndices.size) {
                footerPrefix + compacted.joinToString(",") { it.trim() }
            } else {
                line
            }
        }
        if (!line.startsWith("; ")) return line
        val separator = line.indexOf(" = ")
        if (separator <= 2) return line
        val key = line.substring(2, separator)
        val value = line.substring(separator + 3)
        val compacted = BambuFilamentConfigCompactor.compact(key, value, filamentIndices) ?: return line
        // Orca's human-readable header uses semicolons for colour/type
        // vectors, while its CONFIG_BLOCK generally uses commas. Preserve the
        // source delimiter so downstream Bambu metadata readers see the same
        // compact filament count as plate_*.json and slice_info.config.
        val delimiter = if (';' in value) ";" else ","
        return "; $key = ${compacted.joinToString(delimiter)}"
    }
}
