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

        // Single copy: center on the bed
        if (copyCount == 1) {
            return floatArrayOf(
                (bedSizeX - objectSizeX) / 2f,
                (bedSizeY - objectSizeY) / 2f
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
}
