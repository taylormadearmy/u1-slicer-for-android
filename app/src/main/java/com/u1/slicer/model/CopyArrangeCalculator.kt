package com.u1.slicer.model

import kotlin.math.cos
import kotlin.math.sin

/**
 * Computes grid positions for multiple copies of an object on the print bed.
 *
 * Objects are arranged in a row-major grid starting from (margin, margin).
 * Returns flat FloatArray [x0, y0, x1, y1, ...] in mm (bed-space), suitable
 * for passing directly to NativeLibrary.setModelInstances().
 *
 * @param objectSizeX object bounding box X in mm
 * @param objectSizeY object bounding box Y in mm
 * @param copyCount desired number of copies (1..maxCopies())
 * @param bedSizeX print bed X dimension (default 270mm for Snapmaker U1)
 * @param bedSizeY print bed Y dimension (default 270mm for Snapmaker U1)
 * @param margin gap between copies in mm (default 5mm)
 */
object CopyArrangeCalculator {

    fun calculate(
        objectSizeX: Float,
        objectSizeY: Float,
        copyCount: Int,
        bedSizeX: Float = 270f,
        bedSizeY: Float = 270f,
        margin: Float = 5f
    ): FloatArray {
        require(objectSizeX > 0f && objectSizeY > 0f) { "Object dimensions must be positive" }
        require(copyCount >= 1) { "Copy count must be at least 1" }

        // Single copy: center on the bed (clamped to 0 for oversized models)
        if (copyCount == 1) {
            return floatArrayOf(
                maxOf(0f, (bedSizeX - objectSizeX) / 2f),
                maxOf(0f, (bedSizeY - objectSizeY) / 2f)
            )
        }

        // Multiple copies: arrange in a centered grid
        val colCount = maxOf(1, ((bedSizeX + margin) / (objectSizeX + margin)).toInt())
        val rowCount = maxOf(1, ((bedSizeY + margin) / (objectSizeY + margin)).toInt())
        val actualCount = minOf(copyCount, colCount * rowCount)
        val usedRows = minOf(rowCount, (actualCount + colCount - 1) / colCount)
        val usedCols = if (actualCount <= colCount) actualCount else colCount

        // Center the grid on the bed
        val gridWidth = usedCols * objectSizeX + (usedCols - 1) * margin
        val gridHeight = usedRows * objectSizeY + (usedRows - 1) * margin
        val offsetX = maxOf(0f, (bedSizeX - gridWidth) / 2f)
        val offsetY = maxOf(0f, (bedSizeY - gridHeight) / 2f)

        val positions = FloatArray(actualCount * 2)
        for (i in 0 until actualCount) {
            val col = i % colCount
            val row = i / colCount
            positions[i * 2]     = offsetX + col * (objectSizeX + margin)
            positions[i * 2 + 1] = offsetY + row * (objectSizeY + margin)
        }
        return positions
    }

    fun maxCopies(
        objectSizeX: Float,
        objectSizeY: Float,
        bedSizeX: Float = 270f,
        bedSizeY: Float = 270f,
        margin: Float = 5f
    ): Int {
        if (objectSizeX <= 0f || objectSizeY <= 0f) return 1
        val cols = maxOf(1, ((bedSizeX + margin) / (objectSizeX + margin)).toInt())
        val rows = maxOf(1, ((bedSizeY + margin) / (objectSizeY + margin)).toInt())
        return cols * rows
    }

    /**
     * Returns a warning message if [count] copies of the given object exceed the bed's
     * grid capacity, or null if they fit. Used to warn (not block) the user.
     */
    fun copyBedWarning(
        objectSizeX: Float,
        objectSizeY: Float,
        count: Int,
        bedSizeX: Float = 270f,
        bedSizeY: Float = 270f,
        margin: Float = 5f
    ): String? {
        if (count <= 1) return null
        val max = maxCopies(objectSizeX, objectSizeY, bedSizeX, bedSizeY, margin)
        return if (count > max) "Copies may overlap or exceed bed (max $max for this size)" else null
    }

    /**
     * Compute a wipe tower position that avoids overlapping the model(s).
     * Tries eight candidate positions around the bed perimeter, picks the one with
     * the most clearance from all object bounding boxes.
     *
     * @param objectPositions flat [x0,y0,x1,y1,...] model positions (min-corner, mm)
     * @param objectSizeX model bounding box X
     * @param objectSizeY model bounding box Y
     * @param towerWidth wipe tower width (X dimension)
     * @param towerDepth wipe tower depth (Y dimension); defaults to towerWidth for backward compat
     * @param bedSizeX bed X dimension
     * @param bedSizeY bed Y dimension
     * @return Pair(towerX, towerY) in mm
     */
    fun computeWipeTowerPosition(
        objectPositions: FloatArray,
        objectSizeX: Float,
        objectSizeY: Float,
        towerWidth: Float = 60f,
        towerDepth: Float = towerWidth,
        bedSizeX: Float = 270f,
        bedSizeY: Float = 270f
    ): Pair<Float, Float> {
        val bedCenter = bedSizeX / 2f
        // Margin from bed edge: prime_tower_brim_width (3mm) + skirt_distance (6mm)
        // + 1 skirt loop (~0.5mm) ≈ 9.5mm. Use 10mm to be safe.
        val edgeMargin = 10f
        // F75 (GitHub #90): default the prime tower to the back of the bed when
        // the source 3MF doesn't pin a position. Candidates are listed back-first
        // so that on tie clearance the back-of-plate position wins. When the model
        // occupies the back, front candidates still beat them on raw clearance and
        // the model dictates placement as before.
        val candidates = listOf(
            bedCenter - towerWidth / 2f to bedSizeY - towerDepth - edgeMargin,               // top-center  (back, default)
            edgeMargin to bedSizeY - towerDepth - edgeMargin,                                // top-left
            bedSizeX - towerWidth - edgeMargin to bedSizeY - towerDepth - edgeMargin,        // top-right
            edgeMargin to bedCenter - towerDepth / 2f,                                       // left-center
            bedSizeX - towerWidth - edgeMargin to bedCenter - towerDepth / 2f,               // right-center
            bedCenter - towerWidth / 2f to edgeMargin,                                       // bottom-center
            edgeMargin to edgeMargin,                                                        // bottom-left
            bedSizeX - towerWidth - edgeMargin to edgeMargin                                 // bottom-right
        )

        // Build list of object bounding boxes [minX, minY, maxX, maxY]
        val objectCount = objectPositions.size / 2
        val objectBoxes = (0 until objectCount).map { i ->
            val ox = objectPositions[i * 2]
            val oy = objectPositions[i * 2 + 1]
            floatArrayOf(ox, oy, ox + objectSizeX, oy + objectSizeY)
        }

        // For each candidate, compute the minimum distance to any object box
        var bestCandidate = candidates[0]
        var bestMinDist = Float.NEGATIVE_INFINITY

        for ((cx, cy) in candidates) {
            val tMinX = cx; val tMinY = cy
            val tMaxX = cx + towerWidth; val tMaxY = cy + towerDepth
            var minDist = Float.MAX_VALUE

            for (box in objectBoxes) {
                val oMinX = box[0]; val oMinY = box[1]
                val oMaxX = box[2]; val oMaxY = box[3]
                // Signed distance: negative = overlapping
                val dx = maxOf(oMinX - tMaxX, tMinX - oMaxX, 0f)
                val dy = maxOf(oMinY - tMaxY, tMinY - oMaxY, 0f)
                val dist = if (dx == 0f && dy == 0f) {
                    // Overlapping — compute negative penetration
                    val overlapX = minOf(tMaxX - oMinX, oMaxX - tMinX)
                    val overlapY = minOf(tMaxY - oMinY, oMaxY - tMinY)
                    -minOf(overlapX, overlapY)
                } else {
                    dx + dy
                }
                minDist = minOf(minDist, dist)
            }

            if (minDist > bestMinDist) {
                bestMinDist = minDist
                bestCandidate = cx to cy
            }
        }

        return bestCandidate
    }

    /**
     * Per-object overload: avoids using a single shared size for all objects.
     * Each object's footprint is taken directly from [objectBoxes] (same flat
     * [sX0,sY0,sZ0,...] format as [getObjectBoundingBoxes]).
     */
    fun computeWipeTowerPositionForObjects(
        objectPositions: FloatArray,
        objectBoxes: FloatArray,
        towerWidth: Float = 60f,
        towerDepth: Float = towerWidth,
        bedSizeX: Float = 270f,
        bedSizeY: Float = 270f,
    ): Pair<Float, Float> {
        val objectCount = minOf(objectPositions.size / 2, objectBoxes.size / 3)
        if (objectCount == 0) return computeWipeTowerPosition(floatArrayOf(), 0f, 0f, towerWidth, towerDepth, bedSizeX, bedSizeY)
        val flatPositions = FloatArray(objectCount * 2) { i -> objectPositions[i] }
        // Synthesize a unified-size proxy for the candidate scoring loop by
        // building the real per-object box list inline. We reuse the existing
        // candidate-scoring logic but pass per-object sizes directly.
        val bedCenter = bedSizeX / 2f
        val edgeMargin = 10f
        val candidates = listOf(
            bedCenter - towerWidth / 2f to bedSizeY - towerDepth - edgeMargin,
            edgeMargin to bedSizeY - towerDepth - edgeMargin,
            bedSizeX - towerWidth - edgeMargin to bedSizeY - towerDepth - edgeMargin,
            edgeMargin to bedCenter - towerDepth / 2f,
            bedSizeX - towerWidth - edgeMargin to bedCenter - towerDepth / 2f,
            bedCenter - towerWidth / 2f to edgeMargin,
            edgeMargin to edgeMargin,
            bedSizeX - towerWidth - edgeMargin to edgeMargin
        )
        val objectBoxesList = (0 until objectCount).map { i ->
            val ox = objectPositions[i * 2]
            val oy = objectPositions[i * 2 + 1]
            val sx = objectBoxes[i * 3]
            val sy = objectBoxes[i * 3 + 1]
            floatArrayOf(ox, oy, ox + sx, oy + sy)
        }
        var best = candidates[0]
        var bestDist = Float.NEGATIVE_INFINITY
        for ((cx, cy) in candidates) {
            val tMaxX = cx + towerWidth; val tMaxY = cy + towerDepth
            var minDist = Float.MAX_VALUE
            for (box in objectBoxesList) {
                val dx = maxOf(box[0] - tMaxX, cx - box[2], 0f)
                val dy = maxOf(box[1] - tMaxY, cy - box[3], 0f)
                val dist = if (dx == 0f && dy == 0f) {
                    val overlapX = minOf(tMaxX - box[0], box[2] - cx)
                    val overlapY = minOf(tMaxY - box[1], box[3] - cy)
                    -minOf(overlapX, overlapY)
                } else dx + dy
                minDist = minOf(minDist, dist)
            }
            if (minDist > bestDist) { bestDist = minDist; best = cx to cy }
        }
        return best
    }

    /**
     * Single source of truth for the effective placement footprint (XY, in mm) used
     * by every B109 caller — drag clamp, auto-center, and bed-warning. Prefers the
     * caller's live native preview-mesh AABB when available because it reflects the
     * **actual** rotated mesh geometry; falls back to the box-rotation approximation
     * via [computeRotatedFootprint] otherwise.
     *
     * The two routes diverge for non-box meshes at off-axis rotations. For a sphere
     * rotated 45° around Z, the rotated mesh AABB is unchanged (still a circle) but
     * the rotated **box** AABB grows by √2 because the box corners sweep out a wider
     * span. Dragon Scale and most organic prints fall in between, with the box
     * approximation over-estimating the actual footprint by up to ~40% at 45°. The
     * renderer draws the model using the mesh AABB ([com.u1.slicer.viewer.ModelRenderer.drawModelAt]
     * reads `mesh.maxX - mesh.minX`), so clamps / centers computed from the box
     * approximation visibly disagree with the rendered model — that's the v2.2.6
     * reopen of [GitHub #135](https://github.com/taylormadearmy/u1-slicer-for-android/issues/135).
     *
     * Pass [rotatedMeshSizeXY] = null when no mesh has been fetched yet (cold load
     * before the first `getPreparePreviewMesh()` returns) — the box-rotation
     * approximation is conservative but matches the only signal we have.
     *
     * @param rotatedMeshSizeXY (rotatedSizeX, rotatedSizeY) read from the live native
     *   preview mesh **before** user scale is applied. Null when unavailable.
     * @param loadTimeSizeX load-time model width (mm)
     * @param loadTimeSizeY load-time model depth (mm)
     * @param loadTimeSizeZ load-time model height (mm)
     * @param scaleX user scale on X
     * @param scaleY user scale on Y
     * @param rotationXDeg rotation around X axis in degrees
     * @param rotationYDeg rotation around Y axis in degrees
     * @param rotationZDeg rotation around Z axis in degrees
     * @return Pair(effectiveWidth, effectiveDepth) in mm, ready to feed into
     *   `coerceIn` drag bounds or `calculate(...)` auto-centering.
     */
    fun effectivePlacementFootprint(
        rotatedMeshSizeXY: Pair<Float, Float>?,
        loadTimeSizeX: Float,
        loadTimeSizeY: Float,
        loadTimeSizeZ: Float,
        scaleX: Float,
        scaleY: Float,
        rotationXDeg: Float,
        rotationYDeg: Float,
        rotationZDeg: Float,
    ): Pair<Float, Float> {
        if (rotatedMeshSizeXY != null) {
            return Pair(
                rotatedMeshSizeXY.first * scaleX,
                rotatedMeshSizeXY.second * scaleY,
            )
        }
        val (rotW, rotH) = computeRotatedFootprint(
            loadTimeSizeX, loadTimeSizeY, loadTimeSizeZ,
            rotationXDeg, rotationYDeg, rotationZDeg,
        )
        return Pair(rotW * scaleX, rotH * scaleY)
    }

    /**
     * Computes the axis-aligned XY footprint (width × height) of a box after applying
     * ZYX Euler rotation (same convention as setModelRotation in native code).
     *
     * Used by B109 fix: drag placement bounds must use the rotated footprint, not the
     * load-time bounding box, so the model can be placed across the full bed after rotation.
     *
     * @param sizeX load-time model width (mm)
     * @param sizeY load-time model depth (mm)
     * @param sizeZ load-time model height (mm)
     * @param rxDeg rotation around X axis in degrees
     * @param ryDeg rotation around Y axis in degrees
     * @param rzDeg rotation around Z axis in degrees
     * @return Pair(effectiveWidth, effectiveDepth) after rotation
     */
    fun computeRotatedFootprint(
        sizeX: Float, sizeY: Float, sizeZ: Float,
        rxDeg: Float, ryDeg: Float, rzDeg: Float
    ): Pair<Float, Float> {
        if (rxDeg == 0f && ryDeg == 0f && rzDeg == 0f) return Pair(sizeX, sizeY)

        val rx = rxDeg * Math.PI / 180.0
        val ry = ryDeg * Math.PI / 180.0
        val rz = rzDeg * Math.PI / 180.0

        val cxR = cos(rx); val sxR = sin(rx)
        val cyR = cos(ry); val syR = sin(ry)
        val czR = cos(rz); val szR = sin(rz)

        // ZYX rotation matrix: R = Rz * Ry * Rx (matches native setModelRotation convention)
        val r00 = cyR * czR
        val r01 = czR * sxR * syR - cxR * szR
        val r02 = cxR * czR * syR + sxR * szR
        val r10 = cyR * szR
        val r11 = cxR * czR + sxR * syR * szR
        val r12 = cxR * syR * szR - czR * sxR

        // AABB of the 8 corners of the centered box
        val hw = sizeX / 2.0; val hh = sizeY / 2.0; val hd = sizeZ / 2.0
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE

        for (bx in doubleArrayOf(-hw, hw)) {
            for (by in doubleArrayOf(-hh, hh)) {
                for (bz in doubleArrayOf(-hd, hd)) {
                    val nx = r00 * bx + r01 * by + r02 * bz
                    val ny = r10 * bx + r11 * by + r12 * bz
                    if (nx < minX) minX = nx
                    if (nx > maxX) maxX = nx
                    if (ny < minY) minY = ny
                    if (ny > maxY) maxY = ny
                }
            }
        }
        return Pair((maxX - minX).toFloat(), (maxY - minY).toFloat())
    }

    /**
     * Assigns initial row-packing positions for N distinct objects on the print bed.
     *
     * Objects are placed left-to-right with [margin] gaps, wrapping to the next row
     * when the current row would overflow. The returned array is flat [x0,y0,x1,y1,...]
     * in mm (bed-space lower-left convention), suitable for `setObjectPositions`.
     *
     * @param boxes flat [sizeX0,sizeY0,sizeZ0, sizeX1,...] bounding box array from
     *   `NativeLibrary.getObjectBoundingBoxes()`.
     * @param bedSize bed edge length (default 270mm for Snapmaker U1).
     * @param margin gap between objects in mm (default 5mm).
     */
    /**
     * Places a newly added Nth object on the bed without disturbing objects 0..(N-2).
     *
     * Existing objects stay at [currentPositions]. The new object is placed to the right
     * of the rightmost existing object (aligned to its Y), or below all existing objects
     * if it doesn't fit to the right.
     *
     * @param currentPositions flat [x0,y0,...] for the already-placed N-1 objects
     * @param boxes flat [sX0,sY0,sZ0,...] sizes for ALL N objects (including the new one)
     */
    fun placeAdditionalObject(
        currentPositions: FloatArray,
        boxes: FloatArray,
        bedSize: Float = 270f,
        margin: Float = 5f,
    ): FloatArray {
        val objectCount = boxes.size / 3
        if (objectCount <= 1) return currentPositions.copyOf().takeIf { it.size >= 2 }
            ?: floatArrayOf(maxOf(0f, (bedSize - boxes[0]) / 2f), maxOf(0f, (bedSize - boxes[1]) / 2f))
        val existingCount = currentPositions.size / 2
        val newIdx = objectCount - 1
        val newSizeX = boxes[newIdx * 3]
        val newSizeY = boxes[newIdx * 3 + 1]

        val result = FloatArray(objectCount * 2)
        currentPositions.copyInto(result, 0, 0, minOf(currentPositions.size, existingCount * 2))

        // Find rightmost X edge; use that object's Y for alignment
        var maxRight = 0f
        var alignY = margin
        for (i in 0 until existingCount) {
            val right = result[i * 2] + boxes[i * 3]
            if (right > maxRight) { maxRight = right; alignY = result[i * 2 + 1] }
        }

        // Try to the right of all existing objects
        val tryX = maxRight + margin
        if (tryX + newSizeX <= bedSize) {
            result[newIdx * 2] = tryX
            result[newIdx * 2 + 1] = alignY
            return result
        }

        // Doesn't fit to the right — place below all existing objects
        var maxBottom = 0f
        var leftX = margin
        for (i in 0 until existingCount) {
            val bottom = result[i * 2 + 1] + boxes[i * 3 + 1]
            if (bottom > maxBottom) { maxBottom = bottom; leftX = result[i * 2] }
        }
        result[newIdx * 2] = leftX
        result[newIdx * 2 + 1] = maxBottom + margin
        return result
    }

    fun buildMultiObjectPositions(
        boxes: FloatArray,
        bedSize: Float = 270f,
        margin: Float = 5f,
    ): FloatArray {
        val count = boxes.size / 3
        if (count == 0) return floatArrayOf()
        val positions = FloatArray(count * 2)
        var curX = margin
        var curY = margin
        var rowMaxY = 0f
        for (i in 0 until count) {
            val sizeX = boxes[i * 3]
            val sizeY = boxes[i * 3 + 1]
            if (i > 0 && curX + sizeX > bedSize - margin) {
                curX = margin
                curY += rowMaxY + margin
                rowMaxY = 0f
            }
            positions[i * 2] = curX
            positions[i * 2 + 1] = curY
            curX += sizeX + margin
            if (sizeY > rowMaxY) rowMaxY = sizeY
        }
        return positions
    }

    /**
     * Result of [autoArrange]: packed positions + count of objects that could not be placed.
     *
     * Note: not value-comparable (FloatArray equals/hashCode is identity-based) — don't use in equality-sensitive collections.
     */
    data class ArrangeResult(
        val positions: FloatArray,
        val overflowCount: Int,
    )

    /**
     * F92 — translation-only auto-arrange. Shelf-packs N object footprints into the bed
     * (front-left, wrapping upward), skipping any placement that overlaps [reservedRect]
     * (the pinned wipe tower, already margin-inflated by the caller) or would fall off the
     * bed. Objects that cannot be placed on-bed are left at their [incoming] position and
     * counted in [ArrangeResult.overflowCount] — never placed off-bed (the structural fix
     * B135 needs).
     *
     * @param boxes flat [sX0,sY0,sZ0, ...] from getObjectBoundingBoxes() (already
     *   post-rotation, so translation-only keeps each object's rotation).
     * @param reservedRect [minX,minY,maxX,maxY] keep-out, or null when no tower is active.
     * @param incoming flat [x0,y0,...] current positions, used for overflow fallback.
     * @param bedSize bed edge length (default 270mm for Snapmaker U1).
     * @param margin gap between objects and from the bed edge in mm (default 5mm).
     */
    fun autoArrange(
        boxes: FloatArray,
        reservedRect: FloatArray?,
        incoming: FloatArray,
        bedSize: Float = 270f,
        margin: Float = 5f,
    ): ArrangeResult {
        val n = boxes.size / 3
        if (n == 0) return ArrangeResult(floatArrayOf(), 0)

        val positions = FloatArray(n * 2)
        // Seed from incoming so overflow objects keep a sane (existing) position.
        for (i in 0 until n) {
            positions[i * 2] = incoming.getOrElse(i * 2) { margin }
            positions[i * 2 + 1] = incoming.getOrElse(i * 2 + 1) { margin }
        }

        val maxEdge = bedSize - margin
        // Next-fit-decreasing shelf packer: objects are placed left-to-right on the current shelf
        // and wrap to a new shelf when the row overflows. Earlier shelves are never backfilled —
        // non-optimal but keeps keep-out skip logic simple and placement fully predictable.
        val maxIters = (bedSize / maxOf(margin, 1f)).toInt() * 3 + 16 // generous worst-case bound: a few wraps + reserved-skips per object; the guard can never spin
        // Largest-area-first for tighter packing; stable on ties via index.
        val order = (0 until n).sortedWith(
            compareByDescending<Int> { boxes[it * 3] * boxes[it * 3 + 1] }.thenBy { it }
        )

        var curX = margin
        var curY = margin
        var rowMaxY = 0f
        var overflow = 0

        for (idx in order) {
            val sx = boxes[idx * 3]
            val sy = boxes[idx * 3 + 1]
            // Physically too large to ever fit on the bed.
            if (sx > bedSize - 2 * margin || sy > bedSize - 2 * margin) { overflow++; continue }

            var placed = false
            var guard = 0
            while (guard++ < maxIters) {
                // Wrap to next shelf if this object overflows the row width.
                if (curX + sx > maxEdge) {
                    curX = margin
                    curY += rowMaxY + margin
                    rowMaxY = 0f
                }
                // Out of vertical space on the bed.
                if (curY + sy > maxEdge) break
                val cMaxX = curX + sx
                val cMaxY = curY + sy
                if (reservedRect != null &&
                    curX < reservedRect[2] && cMaxX > reservedRect[0] &&
                    curY < reservedRect[3] && cMaxY > reservedRect[1]
                ) {
                    // Overlaps the keep-out — skip past it on this shelf and retry.
                    curX = reservedRect[2] + margin
                    continue
                }
                positions[idx * 2] = curX
                positions[idx * 2 + 1] = curY
                curX += sx + margin
                if (sy > rowMaxY) rowMaxY = sy
                placed = true
                break
            }
            if (!placed) overflow++
        }
        return ArrangeResult(positions, overflow)
    }
}
