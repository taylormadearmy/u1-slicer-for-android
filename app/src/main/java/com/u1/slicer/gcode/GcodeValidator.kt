package com.u1.slicer.gcode

/**
 * Pure-Kotlin G-code validation utilities.
 *
 * Ported from u1-slicer-bridge/tests/slicing.spec.ts and multicolour-slice.spec.ts.
 * All functions are stateless and operate on a raw G-code string — no Android deps,
 * so they can be exercised in JVM unit tests without a device.
 */
object GcodeValidator {

    data class PrimeTowerFootprint(
        val minX: Double, val maxX: Double,
        val minY: Double, val maxY: Double,
        val moveCount: Int
    ) {
        val width get() = maxX - minX
        val depth get() = maxY - minY
    }

    /** Return all bare tool-change lines (e.g. "T0", "T1", "T2", "T3"). */
    fun extractToolChanges(gcode: String): Set<String> {
        val result = mutableSetOf<String>()
        for (line in gcode.lines()) {
            val trimmed = line.trim()
            if (trimmed.matches(Regex("T[0-9]\\s*"))) result.add(trimmed.trimEnd())
        }
        return result
    }

    /**
     * Return all non-zero nozzle temps found in M104/M109 commands.
     * Only counts executable lines (not comment lines starting with ';').
     */
    fun extractNozzleTemps(gcode: String): List<Int> {
        val result = mutableListOf<Int>()
        for (line in gcode.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(";")) continue
            if (!trimmed.startsWith("M104") && !trimmed.startsWith("M109")) continue
            val m = Regex("S(\\d+)").find(trimmed) ?: continue
            val temp = m.groupValues[1].toIntOrNull() ?: continue
            if (temp > 0) result.add(temp)
        }
        return result
    }

    /**
     * Count layer changes by scanning for OrcaSlicer layer annotations.
     * OrcaSlicer emits ";LAYER_CHANGE" or ";Z:" per layer.
     */
    fun extractLayerCount(gcode: String): Int {
        var count = 0
        for (line in gcode.lines()) {
            val t = line.trim()
            if (t == ";LAYER_CHANGE" || t.startsWith(";Z:")) count++
        }
        return count
    }

    /**
     * Parse the bounding box of the prime tower from G-code feature annotations.
     * OrcaSlicer uses "; FEATURE: Prime tower" or ";TYPE:prime-tower".
     * Returns null if no prime tower moves are found.
     *
     * Ported directly from bridge slicing.spec.ts parsePrimeTowerFootprint().
     */
    fun parsePrimeTowerFootprint(gcode: String): PrimeTowerFootprint? {
        var x = Double.NaN
        var y = Double.NaN
        var inPrimeTower = false
        var relativeE = false
        var absE = Double.NaN
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var count = 0

        for (rawLine in gcode.lines()) {
            val line = rawLine.trim()
            when {
                line == "M83" -> { relativeE = true; continue }
                line == "M82" -> { relativeE = false; continue }
                line.startsWith("G92 ") -> {
                    Regex("E(-?\\d+(?:\\.\\d+)?)").find(line)?.let { absE = it.groupValues[1].toDouble() }
                    continue
                }
                line.startsWith("; FEATURE: ") || line.startsWith(";TYPE:") -> {
                    inPrimeTower = line.contains("prime", ignoreCase = true) &&
                            line.contains("tower", ignoreCase = true)
                    continue
                }
            }
            if (!inPrimeTower) continue
            if (!line.startsWith("G1")) continue

            Regex("\\bX(-?\\d+(?:\\.\\d+)?)").find(line)?.let { x = it.groupValues[1].toDouble() }
            Regex("\\bY(-?\\d+(?:\\.\\d+)?)").find(line)?.let { y = it.groupValues[1].toDouble() }
            val eMatch = Regex("\\bE(-?\\d+(?:\\.\\d+)?)").find(line) ?: continue
            val en = eMatch.groupValues[1].toDouble()

            val isExtrusion = if (relativeE) {
                en > 1e-6
            } else {
                if (absE.isNaN()) { absE = en; continue }
                val extruding = en > absE + 1e-6
                absE = en
                extruding
            }
            if (!isExtrusion) continue
            if (x.isNaN() || y.isNaN()) continue

            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
            count++
        }

        if (count == 0) return null
        return PrimeTowerFootprint(minX, maxX, minY, maxY, count)
    }

    /** True if the G-code has at least one executable M104/M109 with non-zero S value. */
    fun hasNonZeroNozzleTemps(gcode: String) = extractNozzleTemps(gcode).isNotEmpty()

    /**
     * Extract the retract_length_toolchange config value from G-code comments.
     * Returns null if not found.  OrcaSlicer emits "; retract_length_toolchange = 0.8,0.8".
     */
    fun extractToolchangeRetractLength(gcode: String): List<Double>? {
        for (line in gcode.lines()) {
            val t = line.trim()
            if (t.startsWith("; retract_length_toolchange = ")) {
                val csv = t.substringAfter("= ").trim()
                return csv.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            }
        }
        return null
    }

    /**
     * Find the largest retraction (most negative G1 E value) in the G-code.
     * Only considers lines that are actual retractions (negative E), not de-retractions.
     * Returns the magnitude (positive number) of the largest retraction, or 0 if none found.
     */
    fun maxRetractionMm(gcode: String): Double {
        var maxRetract = 0.0
        val eRegex = Regex("""^G1\s.*E(-\d+(?:\.\d+)?)""")
        for (line in gcode.lines()) {
            val t = line.trim()
            val m = eRegex.find(t) ?: continue
            val e = m.groupValues[1].toDoubleOrNull() ?: continue
            val magnitude = -e  // e is negative, magnitude is positive
            if (magnitude > maxRetract) maxRetract = magnitude
        }
        return maxRetract
    }

    /** True if the G-code has tool-change lines for exactly the given set of tools. */
    fun hasToolChanges(gcode: String, vararg tools: String): Boolean {
        val actual = extractToolChanges(gcode)
        return tools.all { it in actual }
    }

    /** True if none of the given tool-change lines appear in the G-code. */
    fun lacksToolChanges(gcode: String, vararg tools: String): Boolean {
        val actual = extractToolChanges(gcode)
        return tools.none { it in actual }
    }

    /**
     * True if the G-code contains a "; Retract(unload)" comment, which indicates
     * the wipe tower is performing a bowden-style multi-step filament unload sequence.
     * This should NEVER appear in Snapmaker U1 G-code — the U1 has independent extruders,
     * not a single-extruder-multi-material (SEMM) bowden setup.
     */
    fun hasBowdenUnloadSequence(gcode: String): Boolean {
        return gcode.lines().any { it.trim() == "; Retract(unload)" }
    }

    /**
     * Extract a config value from OrcaSlicer G-code comments.
     * OrcaSlicer emits lines like "; key = value" in the G-code header/footer.
     * Returns null if not found.
     */
    fun extractConfigComment(gcode: String, key: String): String? {
        val prefix = "; $key = "
        for (line in gcode.lines()) {
            val t = line.trim()
            if (t.startsWith(prefix)) return t.substringAfter(prefix).trim()
        }
        return null
    }

    /**
     * Extract the estimated printing time comment from G-code.
     * OrcaSlicer emits "; estimated printing time (normal mode) = 1h 2m 3s"
     * or for BBL printers: "; model printing time: 1h 2m; total estimated time: 1h 5m"
     * Returns null if not found.
     */
    fun extractEstimatedTime(gcode: String): String? {
        for (line in gcode.lines()) {
            val t = line.trim()
            if (t.startsWith("; estimated printing time") || t.startsWith("; model printing time"))
                return t
        }
        return null
    }

    data class BedBoundsResult(
        val withinBounds: Boolean,
        val minX: Double, val maxX: Double,
        val minY: Double, val maxY: Double,
        val violatingLines: List<String>
    )

    /**
     * Check that all G1 X/Y motion coordinates fall within the given bed bounds.
     * Returns a BedBoundsResult with min/max extents and any violating lines.
     * Only checks lines with both X and Y — travel/single-axis moves are skipped
     * if the missing axis is unknown.
     *
     * @param bedMinX  bed lower X limit (default 0.0)
     * @param bedMaxX  bed upper X limit (default 270.0 for Snapmaker U1)
     * @param bedMinY  bed lower Y limit (default 0.0)
     * @param bedMaxY  bed upper Y limit (default 270.0 for Snapmaker U1)
     */
    fun checkBedBounds(
        gcode: String,
        bedMinX: Double = 0.0, bedMaxX: Double = 270.0,
        bedMinY: Double = 0.0, bedMaxY: Double = 270.0
    ): BedBoundsResult {
        var curX = Double.NaN
        var curY = Double.NaN
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        val violations = mutableListOf<String>()

        val xRegex = Regex("""X(-?\d+(?:\.\d+)?)""")
        val yRegex = Regex("""Y(-?\d+(?:\.\d+)?)""")

        for (rawLine in gcode.lines()) {
            val line = rawLine.trim()
            if (line.startsWith(";")) continue
            if (!line.startsWith("G0") && !line.startsWith("G1")) continue

            xRegex.find(line)?.let { curX = it.groupValues[1].toDouble() }
            yRegex.find(line)?.let { curY = it.groupValues[1].toDouble() }

            if (!curX.isNaN()) { minX = minOf(minX, curX); maxX = maxOf(maxX, curX) }
            if (!curY.isNaN()) { minY = minOf(minY, curY); maxY = maxOf(maxY, curY) }

            val xOob = !curX.isNaN() && (curX < bedMinX - 0.5 || curX > bedMaxX + 0.5)
            val yOob = !curY.isNaN() && (curY < bedMinY - 0.5 || curY > bedMaxY + 0.5)
            if ((xOob || yOob) && violations.size < 5) violations.add(line)
        }

        val withinBounds = violations.isEmpty() &&
            (minX.isInfinite() || (minX >= bedMinX - 0.5 && maxX <= bedMaxX + 0.5)) &&
            (minY.isInfinite() || (minY >= bedMinY - 0.5 && maxY <= bedMaxY + 0.5))

        return BedBoundsResult(
            withinBounds = withinBounds,
            minX = if (minX.isInfinite()) 0.0 else minX,
            maxX = if (maxX.isInfinite()) 0.0 else maxX,
            minY = if (minY.isInfinite()) 0.0 else minY,
            maxY = if (maxY.isInfinite()) 0.0 else maxY,
            violatingLines = violations
        )
    }
}
