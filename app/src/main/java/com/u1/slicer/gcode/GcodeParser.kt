package com.u1.slicer.gcode

import java.io.BufferedReader
import java.io.File
import java.io.FileReader

object GcodeParser {

    fun parse(file: File): ParsedGcode {
        val layers = mutableListOf<GcodeLayer>()
        val currentMoves = ArrayList<GcodeMove>(512) // reused across layers, avoids re-allocation
        var currentZ = 0f
        var layerIndex = 0

        var x = 0f
        var y = 0f
        var currentExtruder = 0
        var lastE = 0f
        var absoluteE = true

        BufferedReader(FileReader(file)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                val len = l.length

                // Find first non-space character (avoids trim() String allocation)
                var start = 0
                while (start < len && l[start] == ' ') start++
                if (start >= len) continue

                // Comment-only line
                if (l[start] == ';') {
                    if (startsWithAt(l, start, ";LAYER_CHANGE") || startsWithAt(l, start, "; layer_change")) {
                        if (currentMoves.isNotEmpty()) {
                            layers.add(GcodeLayer(layerIndex++, currentZ, currentMoves.toList()))
                            currentMoves.clear()
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
                            if (currentMoves.isNotEmpty()) {
                                layers.add(GcodeLayer(layerIndex++, currentZ, currentMoves.toList()))
                                currentMoves.clear()
                            }
                            currentZ = newZ
                        }

                        val hasE = !newE.isNaN()
                        val isExtrude = hasE && if (absoluteE) newE > lastE else newE > 0f
                        if (hasE) lastE = newE

                        if (newX != x || newY != y) {
                            currentMoves.add(GcodeMove(
                                type = if (isExtrude) MoveType.EXTRUDE else MoveType.TRAVEL,
                                x0 = x, y0 = y, x1 = newX, y1 = newY, extruder = currentExtruder
                            ))
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

                // T0–T9 — tool change
                if (c0 == 'T' && cmdLen == 2 && l[start + 1] in '0'..'9') {
                    currentExtruder = (l[start + 1] - '0').coerceIn(0, 3)
                }
            }
        }

        if (currentMoves.isNotEmpty()) {
            layers.add(GcodeLayer(layerIndex, currentZ, currentMoves.toList()))
        }

        return ParsedGcode(layers = layers)
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
