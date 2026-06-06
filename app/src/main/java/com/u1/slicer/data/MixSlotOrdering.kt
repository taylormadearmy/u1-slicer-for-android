package com.u1.slicer.data

/**
 * Single source of truth for the ordered list of *active* mix slots and their slot ids.
 * The painted slot byte, the SectionedSlotPicker chip ids, and
 * MixedFilamentManager.serialize()'s virtual-filament order MUST all derive from here,
 * or a region painted with mix k references a different engine filament than the recipe
 * defines (see the plan's #1 invariant).
 */
object MixSlotOrdering {
    /**
     * Project rows first (in order), then library rows that (a) are not already present by
     * id in the project list and (b) reference only physical filaments <= numPhysical.
     * Mirrors MixedFilamentManager.serialize()'s iteration exactly.
     */
    fun activeOrder(
        projectMixes: List<MixedFilamentRow>,
        libraryMixes: List<MixedFilamentRow>,
        numPhysical: Int,
    ): List<MixedFilamentRow> {
        val out = ArrayList<MixedFilamentRow>(projectMixes.size + libraryMixes.size)
        out.addAll(projectMixes)
        val projectIds = projectMixes.mapTo(HashSet()) { it.id }
        for (r in libraryMixes) {
            if (r.id in projectIds) continue
            if (r.componentA > numPhysical || r.componentB > numPhysical) continue
            out.add(r)
        }
        return out
    }

    /** 0-based paint-byte / picker-chip id for the index-th active mix: numPhysical + index.
     *  The first mix slot gets byte value `numPhysical` (physical slots occupy 0..numPhysical-1). */
    fun slotIdFor(index: Int, numPhysical: Int): Int = numPhysical + index

    /** Inverse: the [ordered] index for a slot id, or -1 if it's a physical slot / out of range. */
    fun indexForSlot(slotId: Int, numPhysical: Int, orderedSize: Int): Int {
        val idx = slotId - numPhysical
        return if (idx in 0 until orderedSize) idx else -1
    }
}
