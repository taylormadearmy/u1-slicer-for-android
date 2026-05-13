package com.u1.slicer.aipaint

/**
 * Mirrors [AiPaintRenderer]'s orthographic projection so that a 3D point can be back-projected
 * to the same normalised screen-space coordinates the AI sees in the rendered bitmaps.
 *
 * The renderer's transform per [CameraAngle]:
 *   1. Apply the 3×3 rotation matrix.
 *   2. Compute the rotated-mesh XY bbox.
 *   3. scale     = min(w, h) * 0.85 / max(spanX, spanY)
 *      cx, cy    = w/2 - midX*scale,  h/2 + midY*scale     (y is flipped on screen)
 *   4. screen.x  = rx * scale + cx
 *      screen.y  = -ry * scale + cy
 *
 * [build] computes the exact same parameters from `positions`, then [project] returns the
 * normalised screen position (0,0 = top-left, 1,1 = bottom-right) for any (x, y, z) point.
 * Use it to test whether a triangle centroid lies inside an AI-returned bounding box.
 */
class AiPaintProjector(
    private val rot: FloatArray,
    private val scale: Float,
    private val cx: Float,
    private val cy: Float,
    private val width: Int,
    private val height: Int,
) {
    /** Returns (sx, sy) in [0, 1] for normalised screen coords; outside that range = off-screen. */
    fun project(x: Float, y: Float, z: Float): Pair<Float, Float> {
        val rx = rot[0] * x + rot[1] * y + rot[2] * z
        val ry = rot[3] * x + rot[4] * y + rot[5] * z
        val sxPix = rx * scale + cx
        val syPix = -ry * scale + cy
        return Pair(sxPix / width, syPix / height)
    }

    companion object {
        fun build(positions: FloatArray, angle: CameraAngle, width: Int, height: Int): AiPaintProjector {
            val rot = angle.rotationMatrix()
            val nTri = positions.size / 9
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until nTri) {
                val b = i * 9
                for (v in 0 until 3) {
                    val px = positions[b + v * 3]
                    val py = positions[b + v * 3 + 1]
                    val pz = positions[b + v * 3 + 2]
                    val rx = rot[0] * px + rot[1] * py + rot[2] * pz
                    val ry = rot[3] * px + rot[4] * py + rot[5] * pz
                    if (rx < minX) minX = rx; if (rx > maxX) maxX = rx
                    if (ry < minY) minY = ry; if (ry > maxY) maxY = ry
                }
            }
            val spanX = (maxX - minX).coerceAtLeast(1e-6f)
            val spanY = (maxY - minY).coerceAtLeast(1e-6f)
            val scale = minOf(width, height) * 0.85f / maxOf(spanX, spanY)
            val cx = width / 2f - (minX + maxX) / 2f * scale
            val cy = height / 2f + (minY + maxY) / 2f * scale
            return AiPaintProjector(rot, scale, cx, cy, width, height)
        }
    }
}

/**
 * AI-defined region: a label + suggested colour + per-view bounding boxes in normalised screen
 * coordinates. Box is `[xMin, yMin, xMax, yMax]` with both axes in [0, 1] (0,0 = top-left).
 * An "empty" box (`xMax <= xMin || yMax <= yMin`) means the region is not visible in that view.
 */
data class AiRegionBoxes(
    val label: String,
    val suggestedColour: String,
    val boxes: Map<CameraAngle, FloatArray>,
) {
    fun isInBox(angle: CameraAngle, sx: Float, sy: Float): Boolean {
        val b = boxes[angle] ?: return false
        if (b.size != 4) return false
        if (b[2] <= b[0] || b[3] <= b[1]) return false
        return sx >= b[0] && sx <= b[2] && sy >= b[1] && sy <= b[3]
    }

    /** Sum of box areas across views; smaller = "more specific". Used to break vote ties. */
    fun summedArea(): Float {
        var area = 0f
        for ((_, b) in boxes) {
            if (b.size != 4) continue
            val w = (b[2] - b[0]).coerceAtLeast(0f)
            val h = (b[3] - b[1]).coerceAtLeast(0f)
            area += w * h
        }
        return area
    }
}

object AiPaintRegionAssigner {

    /**
     * Per-triangle region assignment. Each triangle independently votes across all 4 views: it
     * scores 1 for each region whose bounding box (in that view) contains the triangle's
     * projected centroid. Highest score wins; ties go to the region with the smaller total box
     * area (so a small "Horns" box wins over the large "Body" box it sits inside).
     *
     * Returns IntArray of length triCount with per-triangle region indices. Triangles that
     * aren't inside any box in any view default to [defaultRegion].
     *
     * Per-triangle granularity is critical: a "Horns" box covering only 5 % of a view might
     * contain hundreds of triangles, but if those triangles belong to topology components that
     * mostly sit elsewhere, a per-component majority vote would drop them. Painting per
     * triangle preserves Gemini's small-region intent.
     */
    fun assign(
        trianglePositions: FloatArray,
        regions: List<AiRegionBoxes>,
        projectors: Map<CameraAngle, AiPaintProjector>,
        defaultRegion: Int = 0,
    ): IntArray {
        val triCount = trianglePositions.size / 9
        if (regions.isEmpty()) return IntArray(triCount) { defaultRegion }

        val areas = FloatArray(regions.size) { regions[it].summedArea() }
        val perTriRegion = IntArray(triCount) { defaultRegion }

        for (t in 0 until triCount) {
            val b = t * 9
            val centroidX = (trianglePositions[b]     + trianglePositions[b + 3] + trianglePositions[b + 6]) / 3f
            val centroidY = (trianglePositions[b + 1] + trianglePositions[b + 4] + trianglePositions[b + 7]) / 3f
            val centroidZ = (trianglePositions[b + 2] + trianglePositions[b + 5] + trianglePositions[b + 8]) / 3f

            val scores = IntArray(regions.size)
            for ((angle, projector) in projectors) {
                val (sx, sy) = projector.project(centroidX, centroidY, centroidZ)
                regions.forEachIndexed { regionIdx, region ->
                    if (region.isInBox(angle, sx, sy)) scores[regionIdx]++
                }
            }

            var bestRegion = -1
            var bestScore = 0
            for (r in scores.indices) {
                val s = scores[r]
                if (s > bestScore || (s == bestScore && s > 0 && bestRegion >= 0 && areas[r] < areas[bestRegion])) {
                    bestScore = s; bestRegion = r
                }
            }
            perTriRegion[t] = if (bestScore > 0) bestRegion else defaultRegion
        }
        return perTriRegion
    }
}
