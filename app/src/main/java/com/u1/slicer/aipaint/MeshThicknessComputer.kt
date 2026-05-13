package com.u1.slicer.aipaint

import kotlin.math.sqrt

/**
 * Computes per-triangle local thickness by casting rays along each triangle's face normal
 * (both inward and outward) and measuring how far the ray travels before hitting another
 * triangle. Thin parts (legs, ears, horns) get small values; thick parts (torso, head)
 * get large values.
 *
 * Uses a BVH (Bounding Volume Hierarchy) for O(log n) ray-triangle intersection.
 */
object MeshThicknessComputer {

    private data class Aabb(
        val x0: Float, val y0: Float, val z0: Float,
        val x1: Float, val y1: Float, val z1: Float
    ) {
        fun union(o: Aabb) = Aabb(
            minOf(x0, o.x0), minOf(y0, o.y0), minOf(z0, o.z0),
            maxOf(x1, o.x1), maxOf(y1, o.y1), maxOf(z1, o.z1)
        )

        // Slab method: returns true if ray (origin + t*dir) hits this box for any t >= 0.
        // invDx/invDy/invDz = 1/dx etc. (passed in to avoid per-node division).
        fun hitsRay(ox: Float, oy: Float, oz: Float, invDx: Float, invDy: Float, invDz: Float): Boolean {
            val tx0 = (x0 - ox) * invDx; val tx1 = (x1 - ox) * invDx
            val ty0 = (y0 - oy) * invDy; val ty1 = (y1 - oy) * invDy
            val tz0 = (z0 - oz) * invDz; val tz1 = (z1 - oz) * invDz
            val tNear = maxOf(minOf(tx0, tx1), minOf(ty0, ty1), minOf(tz0, tz1))
            val tFar  = minOf(maxOf(tx0, tx1), maxOf(ty0, ty1), maxOf(tz0, tz1))
            return tFar >= 0f && tNear <= tFar
        }
    }

    private sealed class BvhNode {
        abstract val aabb: Aabb
        class Leaf(override val aabb: Aabb, val triIdx: Int) : BvhNode()
        class Branch(override val aabb: Aabb, val left: BvhNode, val right: BvhNode) : BvhNode()
    }

    private fun buildBvh(
        triOrder: IntArray, from: Int, to: Int,
        aabbs: Array<Aabb>, cx: FloatArray, cy: FloatArray, cz: FloatArray
    ): BvhNode {
        if (to - from == 1) {
            val i = triOrder[from]
            return BvhNode.Leaf(aabbs[i], i)
        }
        var combined = aabbs[triOrder[from]]
        for (j in from + 1 until to) combined = combined.union(aabbs[triOrder[j]])

        val spanX = combined.x1 - combined.x0
        val spanY = combined.y1 - combined.y0
        val spanZ = combined.z1 - combined.z0
        val axis = if (spanX >= spanY && spanX >= spanZ) 0 else if (spanY >= spanZ) 1 else 2

        val slice = Array(to - from) { triOrder[from + it] }
        when (axis) {
            0 -> slice.sortBy { cx[it] }
            1 -> slice.sortBy { cy[it] }
            else -> slice.sortBy { cz[it] }
        }
        for (j in slice.indices) triOrder[from + j] = slice[j]

        val mid = (from + to) / 2
        val left  = buildBvh(triOrder, from, mid, aabbs, cx, cy, cz)
        val right = buildBvh(triOrder, mid, to,  aabbs, cx, cy, cz)
        return BvhNode.Branch(combined, left, right)
    }

    // Möller-Trumbore ray-triangle intersection. Returns t > EPSILON or null.
    private fun rayTriHit(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float
    ): Float? {
        val EPSILON = 1e-7f
        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
        val hx = dy * e2z - dz * e2y
        val hy = dz * e2x - dx * e2z
        val hz = dx * e2y - dy * e2x
        val det = e1x * hx + e1y * hy + e1z * hz
        if (det > -EPSILON && det < EPSILON) return null
        val inv = 1f / det
        val sx = ox - ax; val sy = oy - ay; val sz = oz - az
        val u = (sx * hx + sy * hy + sz * hz) * inv
        if (u < 0f || u > 1f) return null
        val qx = sy * e1z - sz * e1y
        val qy = sz * e1x - sx * e1z
        val qz = sx * e1y - sy * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inv
        if (v < 0f || u + v > 1f) return null
        val t = (e2x * qx + e2y * qy + e2z * qz) * inv
        return if (t > EPSILON) t else null
    }

    private fun castRayMin(
        node: BvhNode, positions: FloatArray,
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        invDx: Float, invDy: Float, invDz: Float,
        selfIdx: Int
    ): Float? {
        if (!node.aabb.hitsRay(ox, oy, oz, invDx, invDy, invDz)) return null
        return when (node) {
            is BvhNode.Leaf -> {
                if (node.triIdx == selfIdx) null
                else {
                    val b = node.triIdx * 9
                    rayTriHit(
                        ox, oy, oz, dx, dy, dz,
                        positions[b],     positions[b + 1], positions[b + 2],
                        positions[b + 3], positions[b + 4], positions[b + 5],
                        positions[b + 6], positions[b + 7], positions[b + 8]
                    )
                }
            }
            is BvhNode.Branch -> {
                val tL = castRayMin(node.left,  positions, ox, oy, oz, dx, dy, dz, invDx, invDy, invDz, selfIdx)
                val tR = castRayMin(node.right, positions, ox, oy, oz, dx, dy, dz, invDx, invDy, invDz, selfIdx)
                when {
                    tL != null && tR != null -> minOf(tL, tR)
                    else -> tL ?: tR
                }
            }
        }
    }

    /**
     * Returns a FloatArray of length nTri, where each value is the estimated local
     * thickness at that triangle (in model-space units). Triangles with no opposite
     * surface hit return 0.
     */
    fun compute(positions: FloatArray): FloatArray {
        val nTri = positions.size / 9
        if (nTri == 0) return FloatArray(0)

        val aabbs = Array(nTri) { i ->
            val b = i * 9
            Aabb(
                minOf(positions[b], positions[b + 3], positions[b + 6]),
                minOf(positions[b + 1], positions[b + 4], positions[b + 7]),
                minOf(positions[b + 2], positions[b + 5], positions[b + 8]),
                maxOf(positions[b], positions[b + 3], positions[b + 6]),
                maxOf(positions[b + 1], positions[b + 4], positions[b + 7]),
                maxOf(positions[b + 2], positions[b + 5], positions[b + 8])
            )
        }
        val centX = FloatArray(nTri) { i -> val b = i * 9; (positions[b]     + positions[b + 3] + positions[b + 6]) / 3f }
        val centY = FloatArray(nTri) { i -> val b = i * 9; (positions[b + 1] + positions[b + 4] + positions[b + 7]) / 3f }
        val centZ = FloatArray(nTri) { i -> val b = i * 9; (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f }

        val triOrder = IntArray(nTri) { it }
        val root = buildBvh(triOrder, 0, nTri, aabbs, centX, centY, centZ)

        return FloatArray(nTri) { i ->
            val b = i * 9
            val ax = positions[b];     val ay = positions[b + 1]; val az = positions[b + 2]
            val bx = positions[b + 3]; val by = positions[b + 4]; val bz = positions[b + 5]
            val cx = positions[b + 6]; val cy = positions[b + 7]; val cz = positions[b + 8]

            val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
            val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
            var nx = e1y * e2z - e1z * e2y
            var ny = e1z * e2x - e1x * e2z
            var nz = e1x * e2y - e1y * e2x
            val nlen = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
            nx /= nlen; ny /= nlen; nz /= nlen

            val ocx = centX[i]; val ocy = centY[i]; val ocz = centZ[i]
            val eps = 1e-3f

            // Cast inward (along -normal): measures thickness from the outside surface inward.
            val invNx = if (nx == 0f) Float.MAX_VALUE else 1f / (-nx)
            val invNy = if (ny == 0f) Float.MAX_VALUE else 1f / (-ny)
            val invNz = if (nz == 0f) Float.MAX_VALUE else 1f / (-nz)
            val tIn = castRayMin(root, positions,
                ocx - nx * eps, ocy - ny * eps, ocz - nz * eps,
                -nx, -ny, -nz, invNx, invNy, invNz, i)

            // Cast outward (along +normal): catches back-facing surfaces (inconsistent normals).
            val invPx = if (nx == 0f) Float.MAX_VALUE else 1f / nx
            val invPy = if (ny == 0f) Float.MAX_VALUE else 1f / ny
            val invPz = if (nz == 0f) Float.MAX_VALUE else 1f / nz
            val tOut = castRayMin(root, positions,
                ocx + nx * eps, ocy + ny * eps, ocz + nz * eps,
                nx, ny, nz, invPx, invPy, invPz, i)

            when {
                tIn != null && tOut != null -> minOf(tIn, tOut)
                tIn != null -> tIn
                tOut != null -> tOut
                else -> 0f
            }
        }
    }
}
