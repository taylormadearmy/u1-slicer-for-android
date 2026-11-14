package com.u1.slicer.ui

/**
 * F66 — Prepare-screen object selection state. `objectIndex == null` means
 * "no selection, controls act bed-wide" (preserves the pre-F66 behaviour
 * for single-STL workflows). `volumeIndex` is only meaningful while a Parts
 * panel is open and inherits the parent's selection — selecting a volume
 * without an object selected is a no-op.
 */
data class ObjectSelection(
    val objectIndex: Int? = null,
    val volumeIndex: Int? = null,
) {
    /** Replace the selected object; clears any volume selection. */
    fun withObject(idx: Int): ObjectSelection = copy(objectIndex = idx, volumeIndex = null)

    /** Replace the selected volume. No-op if no object is currently selected. */
    fun withVolume(idx: Int?): ObjectSelection =
        if (objectIndex == null) this else copy(volumeIndex = idx)

    /** Clear both object and volume selection. */
    fun cleared(): ObjectSelection = ObjectSelection()

    /**
     * Remap selection after a native split. Convention:
     *   - The object at [removedIdx] was replaced by [addedCount] new pieces
     *     occupying indices [removedIdx, removedIdx + addedCount).
     *   - Objects above [removedIdx] shifted up by [addedCount] - 1 (one removed,
     *     N added; net shift = N - 1).
     *   - The previously-selected object lands on the first new piece if it was
     *     the one being split — matching desktop Orca's auto-select-first-piece
     *     behaviour.
     */
    fun onSplit(removedIdx: Int, addedCount: Int): ObjectSelection {
        val cur = objectIndex ?: return this
        return when {
            cur == removedIdx -> withObject(removedIdx)
            cur > removedIdx  -> withObject(cur + addedCount - 1)
            else              -> this
        }
    }

    /** Remap (or clear) the selection after one object is removed. */
    fun onDelete(removedIdx: Int): ObjectSelection {
        val cur = objectIndex ?: return this
        return when {
            cur == removedIdx -> cleared()
            cur > removedIdx -> withObject(cur - 1)
            else -> this
        }
    }
}
