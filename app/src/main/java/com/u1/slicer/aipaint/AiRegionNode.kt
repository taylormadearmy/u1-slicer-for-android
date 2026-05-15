package com.u1.slicer.aipaint

/**
 * One row in the segmentation tree. Carries an [AiRegion] (label/colour/slot/coverage),
 * optional children for nested groups, the [SegmentationSource] that produced it, and the
 * explicit triangle membership used by paint/lasso ops + cascade-reassign.
 */
data class AiRegionNode(
    val region: AiRegion,
    val children: List<AiRegionNode> = emptyList(),
    val nodeSource: SegmentationSource,
    val triangleIds: IntArray,
    val expanded: Boolean = true,
) {
    val isLeaf: Boolean get() = children.isEmpty()

    /** Depth-first flatten with depth annotations. Caller uses this to render a LazyColumn. */
    fun flatten(): List<Pair<AiRegionNode, Int>> {
        val out = mutableListOf<Pair<AiRegionNode, Int>>()
        fun visit(node: AiRegionNode, depth: Int) {
            out.add(node to depth)
            for (c in node.children) visit(c, depth + 1)
        }
        visit(this, 0)
        return out
    }

    /** Total leaf count under this node (including self if leaf). */
    fun leafCount(): Int =
        if (isLeaf) 1 else children.sumOf { it.leafCount() }

    /** Slot with the most triangles across all leaves under this node. Tie → lower slot index. */
    fun dominantSlot(): Int {
        val hist = slotHistogram()
        if (hist.isEmpty()) return region.slot
        val maxCount = hist.values.max()
        return hist.entries.filter { it.value == maxCount }.minOf { it.key }
    }

    /** Slot with the second-most triangles; null when all leaves share a single slot. */
    fun secondarySlot(): Int? {
        val hist = slotHistogram()
        if (hist.size < 2) return null
        return hist.entries.sortedByDescending { it.value }[1].key
    }

    private fun slotHistogram(): Map<Int, Int> {
        val counts = mutableMapOf<Int, Int>()
        fun visit(node: AiRegionNode) {
            if (node.isLeaf) {
                counts.merge(node.region.slot, node.triangleIds.size) { a, b -> a + b }
            } else {
                node.children.forEach(::visit)
            }
        }
        visit(this)
        return counts
    }

    // ByteArray-shaped helpers rely on identity equality; declare consistent equals/hashCode
    // so Compose recomposition + List equality use reference identity, not deep content compare.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
