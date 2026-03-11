package com.u1.slicer.model

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

        // Multiple copies: arrange in a grid starting at (margin, margin)
        val colCount = maxOf(1, ((bedSizeX + margin) / (objectSizeX + margin)).toInt())
        val rowCount = maxOf(1, ((bedSizeY + margin) / (objectSizeY + margin)).toInt())
        val actualCount = minOf(copyCount, colCount * rowCount)

        val positions = FloatArray(actualCount * 2)
        for (i in 0 until actualCount) {
            val col = i % colCount
            val row = i / colCount
            positions[i * 2]     = margin + col * (objectSizeX + margin)
            positions[i * 2 + 1] = margin + row * (objectSizeY + margin)
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
     * Compute a wipe tower position that avoids overlapping the model(s).
     * Tries the four corners of the bed (with margin), picks the one with
     * the most clearance from all object bounding boxes.
     *
     * @param objectPositions flat [x0,y0,x1,y1,...] model positions (min-corner, mm)
     * @param objectSizeX model bounding box X
     * @param objectSizeY model bounding box Y
     * @param towerWidth wipe tower width (square footprint assumed)
     * @param bedSizeX bed X dimension
     * @param bedSizeY bed Y dimension
     * @return Pair(towerX, towerY) in mm
     */
    fun computeWipeTowerPosition(
        objectPositions: FloatArray,
        objectSizeX: Float,
        objectSizeY: Float,
        towerWidth: Float = 60f,
        bedSizeX: Float = 270f,
        bedSizeY: Float = 270f
    ): Pair<Float, Float> {
        val bedCenter = bedSizeX / 2f
        // Candidate positions: 4 corners + 4 edge midpoints (no bed-edge margin —
        // we'd rather be flush with the edge than overlapping the model)
        val candidates = listOf(
            0f to 0f,                                                   // bottom-left
            bedSizeX - towerWidth to 0f,                                // bottom-right
            0f to bedSizeY - towerWidth,                                // top-left
            bedSizeX - towerWidth to bedSizeY - towerWidth,             // top-right
            bedCenter - towerWidth / 2f to 0f,                          // bottom-center
            bedCenter - towerWidth / 2f to bedSizeY - towerWidth,       // top-center
            0f to bedCenter - towerWidth / 2f,                          // left-center
            bedSizeX - towerWidth to bedCenter - towerWidth / 2f        // right-center
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
            val tMaxX = cx + towerWidth; val tMaxY = cy + towerWidth
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
}
