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
        // Margin from bed edge: prime_tower_brim_width (3mm) + skirt_distance (6mm)
        // + 1 skirt loop (~0.5mm) ≈ 9.5mm. Use 10mm to be safe.
        val edgeMargin = 10f
        val candidates = listOf(
            edgeMargin to edgeMargin,                                                       // bottom-left
            bedSizeX - towerWidth - edgeMargin to edgeMargin,                               // bottom-right
            edgeMargin to bedSizeY - towerWidth - edgeMargin,                               // top-left
            bedSizeX - towerWidth - edgeMargin to bedSizeY - towerWidth - edgeMargin,       // top-right
            bedCenter - towerWidth / 2f to edgeMargin,                                      // bottom-center
            bedCenter - towerWidth / 2f to bedSizeY - towerWidth - edgeMargin,              // top-center
            edgeMargin to bedCenter - towerWidth / 2f,                                      // left-center
            bedSizeX - towerWidth - edgeMargin to bedCenter - towerWidth / 2f               // right-center
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
