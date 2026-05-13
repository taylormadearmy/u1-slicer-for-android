package com.u1.slicer.viewer

import kotlin.math.abs

/**
 * Möller–Trumbore ray-triangle intersection. Finds the closest triangle hit by a ray
 * cast through a flat FloatArray of per-triangle positions (9 floats per triangle:
 * x1,y1,z1, x2,y2,z2, x3,y3,z3).
 *
 * @param trianglePositions flat positions, length must be a multiple of 9
 * @param ox/oy/oz ray origin in world space
 * @param dx/dy/dz ray direction (need not be normalised)
 * @return index of the closest triangle hit, or -1 if no triangle was hit.
 */
object TrianglePicker {

    private const val EPS = 1e-7f

    fun pick(
        trianglePositions: FloatArray,
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float
    ): Int {
        val nTri = trianglePositions.size / 9
        var bestT = Float.POSITIVE_INFINITY
        var bestIdx = -1

        for (i in 0 until nTri) {
            val b = i * 9
            val v0x = trianglePositions[b];     val v0y = trianglePositions[b + 1]; val v0z = trianglePositions[b + 2]
            val v1x = trianglePositions[b + 3]; val v1y = trianglePositions[b + 4]; val v1z = trianglePositions[b + 5]
            val v2x = trianglePositions[b + 6]; val v2y = trianglePositions[b + 7]; val v2z = trianglePositions[b + 8]

            val e1x = v1x - v0x; val e1y = v1y - v0y; val e1z = v1z - v0z
            val e2x = v2x - v0x; val e2y = v2y - v0y; val e2z = v2z - v0z

            val hx = dy * e2z - dz * e2y
            val hy = dz * e2x - dx * e2z
            val hz = dx * e2y - dy * e2x

            val a = e1x * hx + e1y * hy + e1z * hz
            if (abs(a) < EPS) continue

            val f = 1f / a
            val sx = ox - v0x; val sy = oy - v0y; val sz = oz - v0z
            val u = f * (sx * hx + sy * hy + sz * hz)
            if (u < 0f || u > 1f) continue

            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x

            val v = f * (dx * qx + dy * qy + dz * qz)
            if (v < 0f || u + v > 1f) continue

            val t = f * (e2x * qx + e2y * qy + e2z * qz)
            if (t > EPS && t < bestT) {
                bestT = t
                bestIdx = i
            }
        }
        return bestIdx
    }
}
