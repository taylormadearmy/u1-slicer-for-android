package com.u1.slicer.gcode

import java.io.BufferedReader
import java.io.File
import java.io.FileReader

object GcodeParser {

    /**
     * Default move cap for [parse].  2 million moves ≈ 120 MB of GcodeMove objects
     * on ART — comfortably within Android heap limits while covering most real-world
     * G-code files.  The citystep model (3.7 M moves, 115 MB file) exceeded this and
     * OOMed before B52 introduced the cap.
     */
    const val DEFAULT_MAX_MOVES = 2_000_000

    /**
     * @param colorSegmentsByPausePrint When true, assigns extruder indices 0–3 by counting
     *   `; PAUSE_PRINT` markers (layer-change manual swaps) instead of `T` commands.
     *   Used for Hueforge-style models sliced as single-filament G-code.
     * @param maxMoves Maximum number of moves to store.  Files exceeding this are
     *   stride-sampled so moves are distributed evenly across all layers.
     */
    @JvmOverloads
    fun parse(
        file: File,
        colorSegmentsByPausePrint: Boolean = false,
        maxMoves: Int = DEFAULT_MAX_MOVES
    ): ParsedGcode {
        // Estimate total moves from file size.  Use 16 bytes/line (conservative lower
        // bound) so we overestimate the move count — this produces a higher stride,
        // which is the safe direction (stores fewer moves, stays well under maxMoves).
        val estimatedMoves = file.length() / 16
        val stride = if (estimatedMoves > maxMoves) {
            ((estimatedMoves + maxMoves - 1) / maxMoves).toInt().coerceAtLeast(2)
        } else 1
        val useStride = stride > 1

        val layers = mutableListOf<GcodeLayer>()
        val currentMoves = ArrayList<GcodeMove>(512) // reused across layers, avoids re-allocation
        var currentZ = 0f
        var layerIndex = 0
        var totalMovesCount = 0
        var storedMovesCount = 0
        var hasUnflushedMoves = false

        var x = 0f
        var y = 0f
        var currentExtruder = 0
        var pauseSegmentExtruder = 0
        var pausePrintSegmentsBumped = 0
        var lastE = 0f
        var absoluteE = true
        var perExtruderMm = emptyList<Float>()
        // Phase 2 — multi-filament files (paint segmentation, MMU) can have
        // more than 4 distinct T-indices in the G-code (H2C benchy: 7,
        // Buzz plate 9: 11). FloatArray(4) silently truncated; we now grow
        // the buffer on demand. Cap at 32 — well above any realistic file.
        var computedPerExtruderMm = FloatArray(4)
        val computedExtruderOrder = mutableListOf<Int>()
        fun ensureExtruderCapacity(idx: Int) {
            if (idx < computedPerExtruderMm.size) return
            val needed = (idx + 1).coerceAtMost(32)
            val grown = FloatArray(needed)
            System.arraycopy(computedPerExtruderMm, 0,
                grown, 0, computedPerExtruderMm.size)
            computedPerExtruderMm = grown
        }
        var currentFeatureType: Byte = FeatureType.OTHER
        var currentFeatureLabel: String = "OTHER"
        var wipeTowerE = 0f      // total E extruded in prime/wipe tower regions
        var wipeTowerEStart = Float.NaN  // E value at entry to prime tower region
        var lineNumber = 0

        BufferedReader(FileReader(file)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                lineNumber++
                val l = line!!
                val len = l.length

                // Find first non-space character (avoids trim() String allocation)
                var start = 0
                while (start < len && l[start] == ' ') start++
                if (start >= len) continue

                // Comment-only line
                if (l[start] == ';') {
                    if (colorSegmentsByPausePrint && startsWithAt(l, start, "; PAUSE_PRINT")) {
                        val afterPauseToken = start + "; PAUSE_PRINT".length
                        if (afterPauseToken >= len ||
                            l[afterPauseToken] == ' ' || l[afterPauseToken] == '\t'
                        ) {
                            pauseSegmentExtruder = (pauseSegmentExtruder + 1).coerceAtMost(3)
                            pausePrintSegmentsBumped++
                        }
                    }
                    if (startsWithAt(l, start, ";LAYER_CHANGE") || startsWithAt(l, start, "; layer_change")) {
                        if (currentMoves.isNotEmpty() || hasUnflushedMoves) {
                            layers.add(GcodeLayer(layerIndex++, currentZ, currentMoves.toList()))
                            currentMoves.clear()
                            hasUnflushedMoves = false
                        }
                    }
                    if (perExtruderMm.isEmpty() && startsWithAt(l, start, "; filament used [mm]")) {
                        val eqIdx = l.indexOf('=', start)
                        if (eqIdx >= 0) {
                            val valStr = l.substring(eqIdx + 1).trim()
                            perExtruderMm = valStr.split(',').mapNotNull { it.trim().toFloatOrNull() }
                        }
                    }
                    // ;TYPE: feature type annotations from OrcaSlicer
                    if (startsWithAt(l, start, ";TYPE:")) {
                        val typeName = l.substring(start + 6).trim()
                        val prevFeature = currentFeatureType
                        currentFeatureLabel = typeName
                        currentFeatureType = when {
                            typeName.startsWith("Outer wall")            -> FeatureType.OUTER_WALL
                            typeName.startsWith("Inner wall")            -> FeatureType.INNER_WALL
                            typeName.startsWith("Sparse infill")         -> FeatureType.SPARSE_INFILL
                            typeName.startsWith("Internal solid infill") -> FeatureType.SOLID_INFILL
                            typeName.startsWith("Solid infill")          -> FeatureType.SOLID_INFILL
                            typeName.startsWith("Top surface")           -> FeatureType.TOP_SURFACE
                            typeName.startsWith("Bottom surface")        -> FeatureType.BOTTOM_SURFACE
                            typeName.startsWith("Support interface")     -> FeatureType.SUPPORT_INTERFACE
                            typeName.startsWith("Support")               -> FeatureType.SUPPORT
                            typeName.startsWith("Prime tower")           -> FeatureType.PRIME_TOWER
                            typeName.startsWith("Wipe tower")            -> FeatureType.PRIME_TOWER
                            typeName.startsWith("Bridge")                -> FeatureType.BRIDGE
                            typeName.startsWith("Skirt")                 -> FeatureType.SKIRT
                            typeName.startsWith("Brim")                  -> FeatureType.SKIRT
                            typeName.startsWith("Gap infill")            -> FeatureType.SPARSE_INFILL
                            else                                         -> FeatureType.OTHER
                        }
                        // Track wipe tower E boundaries for waste estimation
                        if (currentFeatureType == FeatureType.PRIME_TOWER && prevFeature != FeatureType.PRIME_TOWER) {
                            wipeTowerEStart = lastE
                        } else if (prevFeature == FeatureType.PRIME_TOWER && currentFeatureType != FeatureType.PRIME_TOWER) {
                            if (!wipeTowerEStart.isNaN() && absoluteE) {
                                wipeTowerE += (lastE - wipeTowerEStart).coerceAtLeast(0f)
                            }
                            wipeTowerEStart = Float.NaN
                        }
                    }
                    continue
                }

                // Find end of command token (avoids split() List allocation)
                var cmdEnd = start
                while (cmdEnd < len && l[cmdEnd] != ' ' && l[cmdEnd] != ';') cmdEnd++
                val cmdLen = cmdEnd - start
                if (cmdLen == 0) continue

                val c0 = l[start]

                // G0 / G1 — hot path (vast majority of G-code lines)
                if (c0 == 'G' && cmdLen <= 3) {
                    val gn = when (cmdLen) {
                        2 -> l[start + 1] - '0'
                        3 -> (l[start + 1] - '0') * 10 + (l[start + 2] - '0')
                        else -> -1
                    }
                    if (gn == 0 || gn == 1) {
                        var newX = x; var newY = y; var newZ = currentZ; var newE = Float.NaN
                        var pos = cmdEnd
                        while (pos < len) {
                            while (pos < len && l[pos] == ' ') pos++
                            if (pos >= len || l[pos] == ';') break
                            val letter = l[pos++]
                            val valStart = pos
                            while (pos < len && l[pos] != ' ' && l[pos] != ';') pos++
                            if (pos == valStart) continue
                            val v = parseGFloat(l, valStart, pos)
                            when (letter) {
                                'X' -> newX = v
                                'Y' -> newY = v
                                'Z' -> newZ = v
                                'E' -> newE = v
                            }
                        }

                        if (newZ != currentZ) {
                            if (currentMoves.isNotEmpty() || hasUnflushedMoves) {
                                layers.add(GcodeLayer(layerIndex++, currentZ, currentMoves.toList()))
                                currentMoves.clear()
                                hasUnflushedMoves = false
                            }
                            currentZ = newZ
                        }

                        val hasE = !newE.isNaN()
                        val eBefore = lastE
                        val isExtrude = hasE && if (absoluteE) newE > eBefore else newE > 0f
                        if (hasE) lastE = newE

                        if (newX != x || newY != y) {
                            val moveExtruder =
                                if (colorSegmentsByPausePrint) pauseSegmentExtruder else currentExtruder
                            if (isExtrude) {
                                val extrudedMm = if (absoluteE) {
                                    (newE - eBefore).coerceAtLeast(0f)
                                } else {
                                    newE.coerceAtLeast(0f)
                                }
                                if (moveExtruder in 0..31 && extrudedMm > 0f) {
                                    ensureExtruderCapacity(moveExtruder)
                                    if (computedPerExtruderMm[moveExtruder] <= 0f) {
                                        computedExtruderOrder += moveExtruder
                                    }
                                    computedPerExtruderMm[moveExtruder] += extrudedMm
                                }
                            }
                            totalMovesCount++
                            hasUnflushedMoves = true
                            val shouldStore = if (useStride) {
                                totalMovesCount % stride == 0 && storedMovesCount < maxMoves
                            } else {
                                storedMovesCount < maxMoves
                            }
                            if (shouldStore) {
                                storedMovesCount++
                                currentMoves.add(GcodeMove(
                                    type = if (isExtrude) MoveType.EXTRUDE else MoveType.TRAVEL,
                                    x0 = x, y0 = y, x1 = newX, y1 = newY,
                                    extruder = moveExtruder,
                                    featureType = currentFeatureType,
                                    lineNumber = lineNumber,
                                    featureLabel = currentFeatureLabel
                                ))
                            }
                        }
                        x = newX; y = newY
                        continue
                    }
                }

                // G92 — reset E position
                if (c0 == 'G' && cmdLen == 3 && l[start + 1] == '9' && l[start + 2] == '2') {
                    var pos = cmdEnd
                    while (pos < len) {
                        while (pos < len && l[pos] == ' ') pos++
                        if (pos >= len || l[pos] == ';') break
                        val letter = l[pos++]
                        val valStart = pos
                        while (pos < len && l[pos] != ' ' && l[pos] != ';') pos++
                        if (letter == 'E') lastE = parseGFloat(l, valStart, pos)
                    }
                    continue
                }

                // M82 / M83 — absolute / relative E
                if (c0 == 'M' && cmdLen == 3 && l[start + 1] == '8') {
                    when (l[start + 2]) {
                        '2' -> absoluteE = true
                        '3' -> absoluteE = false
                    }
                    continue
                }

                // Tool change. Phase 2 (2026-04-28, post-adversarial-review)
                // — parses multi-digit T-indices, not just T0..T9. Buzz
                // plate 9 emits T10 + T11 in canonical-fileIndex space;
                // the prior `cmdLen == 2` check skipped them so any
                // extrusion after `T10` was attributed to the previous
                // tool, breaking per-filament usage summaries and
                // gcode-preview colouring.
                if (c0 == 'T' && cmdLen >= 2 && l[start + 1] in '0'..'9') {
                    var raw = 0
                    var i = start + 1
                    val end = start + cmdLen
                    while (i < end && l[i] in '0'..'9') {
                        raw = raw * 10 + (l[i] - '0')
                        i++
                    }
                    currentExtruder = raw.coerceIn(0, 31)  // safety cap
                    ensureExtruderCapacity(currentExtruder)
                }
            }
        }

        // Close any open wipe tower region at EOF
        if (currentFeatureType == FeatureType.PRIME_TOWER && !wipeTowerEStart.isNaN() && absoluteE) {
            wipeTowerE += (lastE - wipeTowerEStart).coerceAtLeast(0f)
        }

        if (currentMoves.isNotEmpty() || hasUnflushedMoves) {
            layers.add(GcodeLayer(layerIndex, currentZ, currentMoves.toList()))
        }

        val hasComputedExtrusion = computedPerExtruderMm.any { it > 0f }
        // B67: use SORTED tool order (0,1,2,3) for compact array, not first-appearance
        // order. First-appearance reordering caused the per-extruder summary to swap
        // E1/E2 values when T1 appeared before T0 in the G-code.
        val compactComputedPerExtruderMm = computedExtruderOrder.sorted().map { idx ->
            computedPerExtruderMm[idx]
        }
        // v2.0.0 systematic fix (Border Collie + Buzz plate 1 reports): the
        // footer line `; filament used [mm] = a, b, c, ...` is the slicer's
        // authoritative output — it is in CANONICAL fileIdx order, sized to
        // canonical, with 0.0 for unused entries. Pre-fix the parser preferred
        // `compactComputedPerExtruderMm` (compact T-order, sparse) for multi-
        // tool jobs to avoid "phantom footer zeros creating fake preview
        // slots". That trade-off was wrong: it discarded canonical alignment
        // and made downstream UI label chips by T-index instead of fileIdx
        // (Border Collie 2 chips labelled "1, 2" instead of "2, 3"; Buzz
        // plate 1 4 chips labelled "1, 2, 3, 4" instead of "1, 2, 6, 9").
        // The fix: always prefer the raw canonical-wide footer line. UI
        // surfaces filter by mm > 0 to hide phantom zeros.
        val resolvedPerExtruderMm = when {
            // Pause-segment mode: footer comments don't reflect post-injected tool splits.
            colorSegmentsByPausePrint && hasComputedExtrusion -> compactComputedPerExtruderMm
            // Always prefer the raw canonical-wide footer line when present.
            perExtruderMm.isNotEmpty() -> perExtruderMm
            // No footer line → fall back to computed (compact T-order).
            hasComputedExtrusion -> compactComputedPerExtruderMm
            else -> emptyList()
        }
        // Phase 2 — pass the full per-extruder list through. Display layer
        // (SliceCompleteSummaryCard / buildPerExtruderDisplaySlots) handles
        // user-facing slot mapping; clamping at 4 here drops legitimate
        // multi-filament data for files with > 4 distinct T-indices
        // (H2C benchy, Buzz plate 9, etc.).
        val finalPerExtruderMm = resolvedPerExtruderMm

        return ParsedGcode(
            layers = layers,
            perExtruderFilamentMm = finalPerExtruderMm,
            wipeTowerFilamentMm = wipeTowerE,
            _totalMoves = totalMovesCount,
            isPreviewSimplified = useStride
        )
    }

    private fun startsWithAt(s: String, offset: Int, prefix: String): Boolean {
        if (offset + prefix.length > s.length) return false
        for (i in prefix.indices) if (s[offset + i] != prefix[i]) return false
        return true
    }

    /** Parse a G-code float in s[start..<end] with no String allocation. */
    private fun parseGFloat(s: String, start: Int, end: Int): Float {
        var i = start
        var neg = false
        if (i < end && s[i] == '-') { neg = true; i++ }
        var intPart = 0L; var fracPart = 0L; var fracDiv = 1L; var inFrac = false
        while (i < end) {
            when (val c = s[i]) {
                in '0'..'9' -> {
                    val d = c - '0'
                    if (inFrac) { fracPart = fracPart * 10 + d; fracDiv *= 10 }
                    else intPart = intPart * 10 + d
                }
                '.' -> inFrac = true
                else -> break
            }
            i++
        }
        val result = intPart.toFloat() + fracPart.toFloat() / fracDiv.toFloat()
        return if (neg) -result else result
    }
}
