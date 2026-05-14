package com.u1.slicer.aipaint

/**
 * A single brush stroke / lasso commit. The id is a monotonically-increasing tag so the screen
 * can refer back to the row. slot is the physical filament slot the user assigned. triangleIds
 * is the explicit set of triangles painted in this stroke.
 */
data class CustomSelection(
    val id: Int,
    val slot: Int,
    val triangleIds: IntArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

object CustomSelections {
    /**
     * Build the root-level "Custom selections" tree node from the user's accumulated brush
     * strokes. Returns null when there are no strokes so the tree doesn't show an empty group.
     */
    fun buildGroup(selections: List<CustomSelection>): AiRegionNode? {
        if (selections.isEmpty()) return null
        val children = selections.map { sel ->
            AiRegionNode(
                region = AiRegion(
                    id = sel.id,
                    label = "Custom selection · ${sel.triangleIds.size} tri",
                    suggestedColour = "#888888",
                    slot = sel.slot,
                ),
                children = emptyList(),
                nodeSource = SegmentationSource.BRUSH,
                triangleIds = sel.triangleIds,
            )
        }
        val totalTris = selections.sumOf { it.triangleIds.size }
        return AiRegionNode(
            region = AiRegion(
                id = -1,
                label = "Custom selections",
                suggestedColour = "#888888",
                slot = children.firstOrNull()?.region?.slot ?: 0,
            ),
            children = children,
            nodeSource = SegmentationSource.BRUSH,
            triangleIds = IntArray(totalTris).also { out ->
                var p = 0
                for (s in selections) {
                    System.arraycopy(s.triangleIds, 0, out, p, s.triangleIds.size)
                    p += s.triangleIds.size
                }
            },
        )
    }
}
