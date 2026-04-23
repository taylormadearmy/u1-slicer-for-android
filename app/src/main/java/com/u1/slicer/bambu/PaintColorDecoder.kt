package com.u1.slicer.bambu

/**
 * B95 (#102) — Faithful Kotlin port of OrcaSlicer's `TriangleSelector::deserialize` for
 * extracting the set of leaf paint states encoded in a single triangle's `paint_color`
 * (or `mmu_segmentation`) hex attribute.
 *
 * The hex string is read RIGHT-TO-LEFT (matching `FacetsAnnotation::set_triangle_from_string`
 * in `Model.cpp:3463-3486` which iterates `str.crbegin()` → `str.crend()`).  Each hex char
 * contributes 4 bits LSB-first.  The resulting bitstream is then parsed nibble-by-nibble:
 *
 *   Nibble layout (bits 0,1,2,3 in LSB-first read order):
 *     bits 0-1  = `num_of_split_sides` (0 = leaf, 1/2/3 = split into 2/3/4 children)
 *     bits 2-3  = state (small case, 0..2) OR special_side (split case)
 *
 *   Extended state encoding:  if bits 2-3 == 0b11 AND leaf, the next nibble holds
 *   `state - 3`.  If that next nibble == 0b1111 we keep reading further nibbles
 *   (each adds 15 to the eventual state).  See `TriangleSelector.cpp:1791-1801`.
 *
 *   Split node:  emits a special_side from bits 2-3, then recursively encodes
 *   `num_of_split_sides + 1` children depth-first.
 *
 * The Kotlin `paintCharToState` heuristic in [ThreeMfParser] (which uses the FIRST hex
 * character as a 0..15 state index) silently disagrees with this encoding for any
 * triangle that uses extended (state >= 3) encoding or a split tree.  Buzz Lightyear
 * plate 9's two paint_color shapes "8C" and "3C" decode to states 11 and 6 respectively,
 * while the heuristic reports 8 and 3 — a divergence that prevented v1.6.11's
 * `computeEmbedTargetCount` bump from sizing the embed correctly for the plate.
 *
 * This decoder is the prerequisite for the real B95 fix: with correct max-state
 * detection the embedded `filament_colour` array can be sized to address every paint
 * state the slicer will see, and the `multi_material_segmentation_by_painting()` pass
 * will no longer silently drop high-index states.
 */
object PaintColorDecoder {

    /**
     * Decode a single triangle's hex-encoded paint state tree and return the set of all
     * leaf states found.  Returns an empty set for blank or malformed input.  State 0
     * (= no paint) is NOT included in the result — callers wanting to distinguish
     * "had paint vs not" should use [hasAnyNonZeroState] or check `result.isNotEmpty()`.
     */
    fun decodeStates(hexEncoded: String): Set<Int> {
        if (hexEncoded.isEmpty()) return emptySet()

        // Build the bitstream: walk hex chars right-to-left, each contributing 4 bits LSB-first.
        val bits = BooleanArray(hexEncoded.length * 4)
        var bitIdx = 0
        for (i in hexEncoded.length - 1 downTo 0) {
            val c = hexEncoded[i]
            val nib = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'F' -> c - 'A' + 10
                in 'a'..'f' -> c - 'a' + 10
                else -> return emptySet()  // malformed
            }
            for (b in 0..3) bits[bitIdx++] = (nib and (1 shl b)) != 0
        }

        val states = mutableSetOf<Int>()
        var ibit = 0

        // Pending-children stack: nesting depth of split nodes whose children are still being read.
        val pending = ArrayDeque<Int>()

        fun nextNibble(): Int {
            // Returns -1 when bitstream is exhausted (malformed input).
            if (ibit + 4 > bits.size) return -1
            var n = 0
            for (i in 0..3) if (bits[ibit + i]) n = n or (1 shl i)
            ibit += 4
            return n
        }

        // Decrement the deepest pending count; pop and cascade-decrement parents whose
        // counts hit zero, mirroring the C++ `while (parents.back().processed_children
        // == parents.back().total_children)` walk-back loop.
        fun decrementParentChain() {
            while (pending.isNotEmpty()) {
                val last = pending.removeLast() - 1
                if (last > 0) { pending.addLast(last); return }
                // last <= 0 → this split node is fully consumed; loop again to decrement
                // ITS parent.
            }
        }

        while (true) {
            val code = nextNibble()
            if (code < 0) break  // bitstream exhausted

            val splitSides = code and 0b11
            val isSplit = splitSides != 0

            if (isSplit) {
                // The current nibble's upper 2 bits are special_side (ignored for state extraction).
                // We must read `splitSides + 1` children before this node is "done".
                pending.addLast(splitSides + 1)
            } else {
                // Leaf — extract state.
                val state = if ((code and 0b1100) == 0b1100) {
                    var next = nextNibble()
                    if (next < 0) break
                    var num = 0
                    while (next == 0b1111) {
                        num++
                        next = nextNibble()
                        if (next < 0) break
                    }
                    if (next < 0) break
                    next + 15 * num + 3
                } else {
                    code shr 2
                }
                if (state > 0) states.add(state)
                // Tell the parent we've consumed one of its children.
                if (pending.isEmpty()) break  // root leaf — done with this triangle
                decrementParentChain()
                // If we cascaded all the way out, the root split tree is fully consumed.
                if (pending.isEmpty()) break
            }
        }

        return states
    }

    /** Convenience: true if any leaf in [hexEncoded] has a non-zero state. */
    fun hasAnyNonZeroState(hexEncoded: String): Boolean = decodeStates(hexEncoded).isNotEmpty()

    /**
     * Returns the maximum 1-based filament state across all attribute strings, or 0 when
     * none of them carry any non-zero state.  Useful for callers that want to size
     * `filament_colour` arrays to fit every paint state the native slicer will see.
     */
    fun maxState(hexAttributes: Iterable<String>): Int {
        var max = 0
        for (s in hexAttributes) for (state in decodeStates(s)) if (state > max) max = state
        return max
    }
}
