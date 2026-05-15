package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRegionNodeTest {

    private fun leaf(id: Int, slot: Int = id % 4, label: String = "Leaf $id", triangleIds: IntArray = intArrayOf(id)): AiRegionNode =
        AiRegionNode(
            region = AiRegion(id = id, label = label, suggestedColour = "#888888", slot = slot),
            children = emptyList(),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = triangleIds,
        )

    @Test
    fun `flatten visits root then children in order`() {
        val root = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(leaf(1), leaf(2), leaf(3)),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = intArrayOf(1, 2, 3),
        )
        val flat = root.flatten()
        assertEquals(listOf("Root", "Leaf 1", "Leaf 2", "Leaf 3"), flat.map { it.first.region.label })
    }

    @Test
    fun `flatten respects depth limit`() {
        val deep = AiRegionNode(
            region = AiRegion(id = 0, label = "L0", suggestedColour = "#000000"),
            children = listOf(AiRegionNode(
                region = AiRegion(id = 1, label = "L1", suggestedColour = "#111111"),
                children = listOf(AiRegionNode(
                    region = AiRegion(id = 2, label = "L2", suggestedColour = "#222222"),
                    children = listOf(leaf(3, label = "L3")),
                    nodeSource = SegmentationSource.VOLUME,
                    triangleIds = intArrayOf(3),
                )),
                nodeSource = SegmentationSource.OBJECT,
                triangleIds = intArrayOf(3),
            )),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = intArrayOf(3),
        )
        val depths = deep.flatten().map { (_, depth) -> depth }
        assertEquals(listOf(0, 1, 2, 3), depths)
    }

    @Test
    fun `leafCount counts only nodes without children`() {
        val root = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(leaf(1), leaf(2), AiRegionNode(
                region = AiRegion(id = 3, label = "Inner", suggestedColour = "#333333"),
                children = listOf(leaf(4), leaf(5)),
                nodeSource = SegmentationSource.OBJECT,
                triangleIds = intArrayOf(4, 5),
            )),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = intArrayOf(1, 2, 4, 5),
        )
        assertEquals(4, root.leafCount())
    }

    @Test
    fun `dominantSlot returns the slot with the most triangles in children`() {
        val root = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(
                leaf(1, slot = 0, triangleIds = IntArray(100) { it }),
                leaf(2, slot = 1, triangleIds = IntArray(50) { it + 100 }),
                leaf(3, slot = 0, triangleIds = IntArray(25) { it + 150 }),
            ),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = IntArray(175) { it },
        )
        // slot 0 has 100+25 = 125 triangles, slot 1 has 50. Dominant = 0.
        assertEquals(0, root.dominantSlot())
    }

    @Test
    fun `secondarySlot returns the slot with the second-most triangles, null if pure`() {
        val mixed = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(
                leaf(1, slot = 0, triangleIds = IntArray(100) { it }),
                leaf(2, slot = 1, triangleIds = IntArray(50) { it + 100 }),
            ),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = IntArray(150) { it },
        )
        assertEquals(1, mixed.secondarySlot())

        val pure = AiRegionNode(
            region = AiRegion(id = 0, label = "Root", suggestedColour = "#000000"),
            children = listOf(leaf(1, slot = 2, triangleIds = IntArray(100) { it })),
            nodeSource = SegmentationSource.OBJECT,
            triangleIds = IntArray(100) { it },
        )
        assertTrue(pure.secondarySlot() == null)
    }
}
