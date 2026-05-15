package com.u1.slicer.aipaint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * fix44: covers the pure logic of `switchToAlternate`'s customSelection-replay (fix43).
 *
 * The function rebuilds per-triangle slot bytes from the alternate tree's leaf assignments,
 * then re-applies the user's manual paint / lasso commits on top. Without this, every
 * Painted ↔ Regions toggle wiped the user's edits — Reviewer B flagged it as the most
 * critical untested behaviour because a regression would silently destroy work.
 */
class SwitchToAlternateReplayTest {

    private fun leaf(id: Int, slot: Int, triIds: IntArray) = AiRegionNode(
        region = AiRegion(id = id, label = "L$id", suggestedColour = "#000", slot = slot),
        nodeSource = SegmentationSource.TOPOLOGY,
        triangleIds = triIds,
    )

    private fun rootOf(vararg leaves: AiRegionNode) = AiRegionNode(
        region = AiRegion(id = -1, label = "Model", suggestedColour = "#888"),
        children = leaves.toList(),
        nodeSource = SegmentationSource.TOPOLOGY,
        triangleIds = leaves.flatMap { it.triangleIds.toList() }.toIntArray(),
    )

    @Test
    fun `base colouring follows alternate tree's leaf slots when no custom selections`() {
        // Two leaves on a 10-triangle mesh: leaf 0 owns tris 0..4 in slot 1, leaf 1 owns
        // 5..9 in slot 2.
        val tree = listOf(rootOf(
            leaf(0, slot = 1, triIds = intArrayOf(0, 1, 2, 3, 4)),
            leaf(1, slot = 2, triIds = intArrayOf(5, 6, 7, 8, 9)),
        ))
        val out = buildSwitchedTriangleRegions(tree, emptyList(), triCount = 10)
        assertArrayEquals(
            byteArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 2),
            out,
        )
    }

    @Test
    fun `customSelections override base colouring on overlapping triangles`() {
        val tree = listOf(rootOf(
            leaf(0, slot = 1, triIds = intArrayOf(0, 1, 2, 3, 4)),
            leaf(1, slot = 2, triIds = intArrayOf(5, 6, 7, 8, 9)),
        ))
        // User painted triangles 3 and 4 (originally slot 1) to slot 3 — those bytes must
        // reflect slot 3 in the output, not the leaf's slot 1.
        val selections = listOf(
            CustomSelection(id = 0, slot = 3, triangleIds = intArrayOf(3, 4)),
        )
        val out = buildSwitchedTriangleRegions(tree, selections, triCount = 10)
        assertArrayEquals(
            byteArrayOf(1, 1, 1, 3, 3, 2, 2, 2, 2, 2),
            out,
        )
    }

    @Test
    fun `later customSelection overrides earlier on the same triangle (last-write-wins)`() {
        val tree = listOf(rootOf(
            leaf(0, slot = 0, triIds = intArrayOf(0, 1, 2, 3, 4)),
        ))
        // User painted triangle 2 → slot 1, then later painted it → slot 3. Final state
        // must be slot 3.
        val selections = listOf(
            CustomSelection(id = 0, slot = 1, triangleIds = intArrayOf(2)),
            CustomSelection(id = 1, slot = 3, triangleIds = intArrayOf(2)),
        )
        val out = buildSwitchedTriangleRegions(tree, selections, triCount = 5)
        assertEquals(3.toByte(), out[2])
    }

    @Test
    fun `triangle indices outside triCount are ignored`() {
        val tree = listOf(rootOf(
            leaf(0, slot = 1, triIds = intArrayOf(0, 100, 200)),
        ))
        // The 100 / 200 indices are out of range for a 3-triangle mesh — must not throw.
        val out = buildSwitchedTriangleRegions(tree, emptyList(), triCount = 3)
        assertEquals(1.toByte(), out[0])
        assertEquals(0.toByte(), out[1])
        assertEquals(0.toByte(), out[2])
    }

    @Test
    fun `empty tree leaves output zero-filled but does not throw`() {
        val out = buildSwitchedTriangleRegions(emptyList(), emptyList(), triCount = 5)
        assertArrayEquals(ByteArray(5), out)
    }

    @Test
    fun `nested band+component hierarchy walks down to leaves only`() {
        // Mimics fix40.2 topology shape: band parent (NOT a leaf) with two component children
        // (the leaves). Parent's own triangleIds list should NOT be used for colouring — only
        // the leaves' assignments.
        val child1 = leaf(10, slot = 2, triIds = intArrayOf(0, 1))
        val child2 = leaf(11, slot = 3, triIds = intArrayOf(2, 3))
        val bandParent = AiRegionNode(
            region = AiRegion(id = -100, label = "Band", suggestedColour = "#000", slot = 0),
            children = listOf(child1, child2),
            nodeSource = SegmentationSource.TOPOLOGY,
            triangleIds = intArrayOf(0, 1, 2, 3),
        )
        val tree = listOf(rootOf(bandParent))
        val out = buildSwitchedTriangleRegions(tree, emptyList(), triCount = 4)
        // If the parent's slot (0) was applied, all bytes would be 0 — but the leaves should
        // override with 2 and 3.
        assertArrayEquals(byteArrayOf(2, 2, 3, 3), out)
    }
}
