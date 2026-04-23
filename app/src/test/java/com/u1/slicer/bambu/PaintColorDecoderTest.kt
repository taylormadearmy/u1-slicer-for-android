package com.u1.slicer.bambu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B95 (#102) — JVM unit tests for [PaintColorDecoder].
 *
 * Each `state N → "<hex>"` mapping is derived by tracing OrcaSlicer's
 * `TriangleSelector::serialize()` (`app/src/main/cpp/orcaslicer/src/libslic3r/TriangleSelector.cpp`)
 * for a single leaf triangle:
 *
 *   small case (state 0..2):
 *     bitstream = [split_sides_bit_0, split_sides_bit_1, state_bit_0, state_bit_1]
 *               = [0, 0, n & 1, (n & 2) != 0]
 *     hex string = single char with value (n << 2)
 *
 *   extended case (state 3..16):
 *     bitstream = [0, 0, 1, 1, (n-3)_bit_0, ..., (n-3)_bit_3]
 *     hex string is built by walking each nibble RIGHT-TO-LEFT into the string,
 *     so first-pushed nibble (= 0b1100 = 'C') ends up RIGHTMOST.
 *     For state n: rightmost char = 'C', next-leftmost = hex digit of (n - 3).
 *
 * The decoder mirrors `TriangleSelector::deserialize`: read hex string nibbles
 * RIGHT-TO-LEFT (matching `FacetsAnnotation::set_triangle_from_string`'s
 * `crbegin()→crend()` iteration), each char contributes 4 bits LSB-first.
 */
class PaintColorDecoderTest {

    @Test
    fun emptyInput_returnsEmptySet() {
        assertEquals(emptySet<Int>(), PaintColorDecoder.decodeStates(""))
    }

    @Test
    fun malformedChar_returnsEmptySet() {
        assertEquals(emptySet<Int>(), PaintColorDecoder.decodeStates("Z"))
        assertEquals(emptySet<Int>(), PaintColorDecoder.decodeStates("8Z"))
    }

    @Test
    fun state0_excludedFromResult() {
        // "0" = nibble 0 = leaf with state 0 (no paint). Excluded by contract.
        assertEquals(emptySet<Int>(), PaintColorDecoder.decodeStates("0"))
    }

    @Test
    fun state1_singleLeaf() {
        // n=1 → bitstream [0,0,1,0] → nibble = 4 → hex "4"
        assertEquals(setOf(1), PaintColorDecoder.decodeStates("4"))
    }

    @Test
    fun state2_singleLeaf() {
        // n=2 → bitstream [0,0,0,1] → nibble = 8 → hex "8"
        assertEquals(setOf(2), PaintColorDecoder.decodeStates("8"))
    }

    @Test
    fun state3_extendedSingleLeaf() {
        // n=3 → bitstream [0,0,1,1, 0,0,0,0] → first nibble = 'C' (extended), second = '0'
        // hex string is read right-to-left → "0C"
        assertEquals(setOf(3), PaintColorDecoder.decodeStates("0C"))
    }

    @Test
    fun state4_extendedSingleLeaf() {
        // n=4 → push (n-3)=1's bits LSB-first [1,0,0,0] → second nibble = 1 → "1C"
        assertEquals(setOf(4), PaintColorDecoder.decodeStates("1C"))
    }

    @Test
    fun state6_extendedSingleLeaf_b95Plate9() {
        // n=6 → push (n-3)=3's bits LSB-first [1,1,0,0] → second nibble = 3 → "3C"
        // This is one of the two paint_color shapes on Buzz plate 9.
        assertEquals(setOf(6), PaintColorDecoder.decodeStates("3C"))
    }

    @Test
    fun state8_extendedSingleLeaf() {
        // n=8 → push (n-3)=5's bits LSB-first [1,0,1,0] → second nibble = 5 → "5C"
        assertEquals(setOf(8), PaintColorDecoder.decodeStates("5C"))
    }

    @Test
    fun state11_extendedSingleLeaf_b95Plate9() {
        // n=11 → push (n-3)=8's bits LSB-first [0,0,0,1] → second nibble = 8 → "8C"
        // This is the other paint_color shape on Buzz plate 9. The Kotlin first-char
        // heuristic in ThreeMfParser.paintCharToState would return 8 for this string;
        // the actual triangle state is 11.
        assertEquals(setOf(11), PaintColorDecoder.decodeStates("8C"))
    }

    @Test
    fun state16_extendedSingleLeaf_maxEncodableByOrca() {
        // n=16 → push (n-3)=13's bits LSB-first [1,0,1,1] → second nibble = 0b1101 = 13 → 'D' → "DC"
        assertEquals(setOf(16), PaintColorDecoder.decodeStates("DC"))
    }

    @Test
    fun extendedMarkerWithoutContinuation_returnsEmptySet() {
        // "C" alone is only the extended marker; bitstream has no second nibble.
        // Decoder must not crash and must not emit a phantom state.
        assertEquals(emptySet<Int>(), PaintColorDecoder.decodeStates("C"))
    }

    @Test
    fun lowercaseHex_acceptedSameAsUppercase() {
        assertEquals(setOf(11), PaintColorDecoder.decodeStates("8c"))
        assertEquals(setOf(6), PaintColorDecoder.decodeStates("3c"))
    }

    @Test
    fun rootSplit_twoLeavesBothState1_dedupedToSingleState() {
        // Root split (split_sides=1, special_side=0) into 2 children, both leaf state 1.
        // Encoder bitstream:
        //   root: push 1,0 (split_sides=1 LSB-first) + push 0,0 (special_side=0)
        //     → bits [1,0,0,0] → first nibble = 1 → hex '1'
        //   child 2 (serialized first per encoder order): push 0,0 + push 1,0 (state 1)
        //     → bits [0,0,1,0] → second nibble = 4 → hex '4'
        //   child 1: push 0,0 + push 1,0
        //     → bits [0,0,1,0] → third nibble = 4 → hex '4'
        // String built right-to-left → "441"
        assertEquals(setOf(1), PaintColorDecoder.decodeStates("441"))
    }

    @Test
    fun rootSplit_twoLeavesStates1And2_returnsBoth() {
        // Same shape as above but children have states 1 and 2.
        // child 2: state 2 → bits [0,0,0,1] → nibble 8
        // child 1: state 1 → bits [0,0,1,0] → nibble 4
        // Root: nibble 1 (split=1, special_side=0)
        // String right-to-left: '8' '4' '1' → "481" (rightmost = root)
        assertEquals(setOf(1, 2), PaintColorDecoder.decodeStates("481"))
    }

    @Test
    fun rootSplit_threeLeavesAllDistinct_returnsAllThree() {
        // Root split into 3 (split_sides=2). Children: leaf state 1, leaf state 2, leaf state 3.
        // Root: push 0,1 (split_sides=2 LSB-first) + push 0,0 (special_side=0)
        //   → bits [0,1,0,0] → nibble 2 → hex '2'
        // Child 3 (encoded first): leaf state 3 → "0C" worth of bits = bits [0,0,1,1, 0,0,0,0]
        //   → two nibbles: 12 ('C') then 0 ('0')
        // Child 2: leaf state 2 → bits [0,0,0,1] → nibble 8 → '8'
        // Child 1: leaf state 1 → bits [0,0,1,0] → nibble 4 → '4'
        // String right-to-left: '0' 'C' '8' '4' '2' → "48C02"
        // Wait — let me reconsider. Bits accumulated in order:
        //   root bits[0..3]   = [0,1,0,0] → nibble 2 → rightmost char '2'
        //   child3 bits[4..7] = [0,0,1,1] → nibble C → next char 'C'
        //   child3 bits[8..11]= [0,0,0,0] → nibble 0 → next char '0'
        //   child2 bits[12..15]=[0,0,0,1] → nibble 8 → next char '8'
        //   child1 bits[16..19]=[0,0,1,0] → nibble 4 → leftmost char '4'
        // String = "480C2"
        assertEquals(setOf(1, 2, 3), PaintColorDecoder.decodeStates("480C2"))
    }

    @Test
    fun nestedSplit_returnsAllLeafStates() {
        // Root split (split_sides=1, 2 children).
        //   Child 1: leaf state 2.
        //   Child 2: split (split_sides=1, 2 children) → grandchildren both leaf state 1.
        // Encoder serializes children in REVERSE order (per C++:
        //   `for (int child_idx = split_sides; child_idx >= 0; -- child_idx)`).
        // So order pushed: root, child2-tree, child1.
        //
        // Root: bits [1,0,0,0] = nibble 1 → '1'
        // Child2 root (split, special=0): bits [1,0,0,0] = nibble 1 → '1'
        // Child2's child2 (leaf state 1): bits [0,0,1,0] = nibble 4 → '4'
        // Child2's child1 (leaf state 1): bits [0,0,1,0] = nibble 4 → '4'
        // Child1 (leaf state 2): bits [0,0,0,1] = nibble 8 → '8'
        //
        // Bitstream order [root, c2_root, c2_c2, c2_c1, c1] = nibbles [1, 1, 4, 4, 8]
        // String right-to-left: '8' '4' '4' '1' '1' → "84411"
        assertEquals(setOf(1, 2), PaintColorDecoder.decodeStates("84411"))
    }

    @Test
    fun hasAnyNonZeroState_trueWhenStateGreaterThanZero() {
        assertTrue(PaintColorDecoder.hasAnyNonZeroState("4"))
        assertTrue(PaintColorDecoder.hasAnyNonZeroState("8C"))
    }

    @Test
    fun hasAnyNonZeroState_falseForEmptyOrZero() {
        assertFalse(PaintColorDecoder.hasAnyNonZeroState(""))
        assertFalse(PaintColorDecoder.hasAnyNonZeroState("0"))
    }

    @Test
    fun maxState_returnsHighestStateAcrossAttributes() {
        val attrs = listOf("4", "8C", "3C", "8")
        // 4→1, 8C→11, 3C→6, 8→2 → max = 11
        assertEquals(11, PaintColorDecoder.maxState(attrs))
    }

    @Test
    fun maxState_returnsZeroForAllEmpty() {
        assertEquals(0, PaintColorDecoder.maxState(listOf("", "0")))
    }

    @Test
    fun maxState_handlesEmptyIterable() {
        assertEquals(0, PaintColorDecoder.maxState(emptyList()))
    }
}
