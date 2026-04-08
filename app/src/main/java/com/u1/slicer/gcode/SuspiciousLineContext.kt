package com.u1.slicer.gcode

import java.io.File

/**
 * Streams a G-code file line-by-line to extract ±2-line context windows around
 * a small set of suspicious move line numbers.
 *
 * B52: The previous implementation used [File.readLines] which loaded the entire
 * file into a List<String>.  For the citystep model (115 MB, 3.7 M lines) this
 * OOMed on Android.  This version only keeps a small rolling buffer.
 */
internal fun buildSuspiciousModelLineContexts(
    gcodeFile: File,
    samples: List<GcodeValidator.MoveSample>
): List<Map<String, Any?>> {
    if (samples.isEmpty() || !gcodeFile.exists()) return emptyList()

    val targetLines = samples
        .mapNotNull { it.lineNumber.takeIf { line -> line > 0 } }
        .distinct()
        .take(3)
    if (targetLines.isEmpty()) return emptyList()

    // Build the set of all line numbers we need to capture (each target ±2).
    val neededLines = mutableSetOf<Int>()
    for (target in targetLines) {
        for (offset in -2..2) {
            val ln = target + offset
            if (ln >= 1) neededLines.add(ln)
        }
    }

    // Stream the file, only keeping lines whose number is in neededLines.
    val captured = mutableMapOf<Int, String>() // lineNumber → text
    val maxNeeded = neededLines.max()
    var lineNumber = 0
    gcodeFile.bufferedReader().use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            lineNumber++
            if (lineNumber in neededLines) {
                captured[lineNumber] = line!!
            }
            if (lineNumber > maxNeeded) break // no need to read further
        }
    }

    // Assemble context windows in the same format as the old implementation.
    val contexts = mutableListOf<Map<String, Any?>>()
    for (target in targetLines) {
        val start = maxOf(1, target - 2)
        val end = minOf(lineNumber, target + 2)
        if (!captured.containsKey(target)) continue
        contexts += mapOf(
            "lineNumber" to target,
            "windowStart" to start,
            "windowEnd" to end,
            "lines" to (start..end).mapNotNull { ln ->
                captured[ln]?.let { text ->
                    mapOf("lineNumber" to ln, "text" to text)
                }
            }
        )
    }
    return contexts
}
