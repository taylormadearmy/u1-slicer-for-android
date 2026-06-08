package com.u1.slicer.ui

// ---------------------------------------------------------------------------
// Companion — pure slot-id helpers (single source of truth for the invariant)
// ---------------------------------------------------------------------------

/**
 * Slot-id invariant (Phase B):
 *   Physical chip  → slot = index in 0..(numPhysical-1)
 *   Mix chip       → slot = numPhysical + mixIndex
 *
 * Use [FilamentMixChipRow.physicalSlotId] and [FilamentMixChipRow.mixSlotId] to produce ids
 * consistently across callers.
 *
 * NOTE: The companion `@Composable fun FilamentMixChipRow(...)` and its private helpers
 * (PhysicalChip, MixChip, AddMixChip) were deleted in M4 cleanup (#3) — they had no
 * production callers. The slot-id helpers below remain as the single source of truth
 * for the id invariant and are tested by FilamentMixChipRowTest.
 */
object FilamentMixChipRow {
    /** Slot id for a physical chip at [index]. */
    fun physicalSlotId(index: Int): Int = index

    /** Slot id for a mix chip at [index] in the mix list, given [numPhysical] physical slots. */
    fun mixSlotId(index: Int, numPhysical: Int): Int = numPhysical + index
}
