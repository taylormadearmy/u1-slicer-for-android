package com.u1.slicer.data

/**
 * F66 — Per-object pose on the Prepare bed. Rotation is Euler XYZ in degrees;
 * scale is per-axis. Identity (no user transformation) is rotation (0,0,0)
 * and scale (1,1,1) — the load-time pose for a raw STL. For 3MFs that embed
 * a non-identity rotation, the load-time baseline is captured by
 * [SlicerViewModel] at load time so Reset restores the file-declared pose,
 * not (0,0,0).
 */
data class PerObjectPose(
    val rotXDeg: Float = 0f,
    val rotYDeg: Float = 0f,
    val rotZDeg: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val scaleZ: Float = 1f,
) {
    fun isIdentity(): Boolean =
        rotXDeg == 0f && rotYDeg == 0f && rotZDeg == 0f &&
            scaleX == 1f && scaleY == 1f && scaleZ == 1f
}

/**
 * After a `nativeSplitObject(removedIdx)` returning `addedCount` new pieces,
 * shift the keys of a per-object map so existing state continues to point at
 * the same logical objects. The new pieces at [removedIdx, removedIdx +
 * addedCount) get no entry (they pick up defaults via `getOrElse(...)` on the
 * read side).
 *
 * Net delta is `addedCount - 1` (removed one original, added N new pieces).
 * Objects below [removedIdx] are unchanged; the object at [removedIdx] is
 * dropped; objects above shift up by the net delta.
 */
fun <V> remapPerObjectMapOnSplit(
    map: Map<Int, V>,
    removedIdx: Int,
    addedCount: Int,
): Map<Int, V> {
    val shift = addedCount - 1
    val out = HashMap<Int, V>(map.size)
    for ((k, v) in map) {
        when {
            k < removedIdx  -> out[k] = v
            k == removedIdx -> { /* dropped — new objects default */ }
            k > removedIdx  -> out[k + shift] = v
        }
    }
    return out
}
