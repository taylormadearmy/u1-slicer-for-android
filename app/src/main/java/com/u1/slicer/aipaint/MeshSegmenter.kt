package com.u1.slicer.aipaint

import kotlin.math.sqrt

object MeshSegmenter {

    private const val COPLANAR_THRESHOLD = 0.866f

    fun segment(positions: FloatArray, targetRegions: Int = 4): IntArray {
        val nTri = positions.size / 9
        if (nTri == 0) return IntArray(0)

        val normals = Array(nTri) { i -> faceNormal(positions, i) }

        val edgeToTris = HashMap<Long, MutableList<Int>>(nTri * 3)
        for (i in 0 until nTri) {
            val base = i * 9
            val vKeys = IntArray(3) { v -> vertexKey(positions, base + v * 3) }
            for (e in 0..2) {
                val a = vKeys[e]; val b = vKeys[(e + 1) % 3]
                val edgeKey = if (a < b) (a.toLong() shl 32) or b.toLong()
                              else (b.toLong() shl 32) or a.toLong()
                edgeToTris.getOrPut(edgeKey) { mutableListOf() }.add(i)
            }
        }

        val adj = Array(nTri) { mutableListOf<Int>() }
        for ((_, tris) in edgeToTris) {
            if (tris.size == 2) {
                val (a, b) = tris
                adj[a].add(b); adj[b].add(a)
            }
        }

        val regionIds = IntArray(nTri) { -1 }
        var nextRegion = 0
        for (seed in 0 until nTri) {
            if (regionIds[seed] != -1) continue
            val queue = ArrayDeque<Int>()
            queue.add(seed)
            regionIds[seed] = nextRegion
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (nb in adj[cur]) {
                    if (regionIds[nb] == -1 &&
                        dot(normals[cur], normals[nb]) >= COPLANAR_THRESHOLD) {
                        regionIds[nb] = nextRegion
                        queue.add(nb)
                    }
                }
            }
            nextRegion++
        }

        return mergeToTarget(regionIds, adj, nextRegion, targetRegions)
    }

    fun coverageFractions(regionIds: IntArray, targetRegions: Int): FloatArray {
        val counts = IntArray(targetRegions)
        regionIds.forEach { counts[it]++ }
        val total = regionIds.size.toFloat()
        return FloatArray(targetRegions) { counts[it] / total }
    }

    private fun faceNormal(pos: FloatArray, triIdx: Int): FloatArray {
        val b = triIdx * 9
        val ax = pos[b+3]-pos[b]; val ay = pos[b+4]-pos[b+1]; val az = pos[b+5]-pos[b+2]
        val bx = pos[b+6]-pos[b]; val by = pos[b+7]-pos[b+1]; val bz = pos[b+8]-pos[b+2]
        val nx = ay*bz - az*by; val ny = az*bx - ax*bz; val nz = ax*by - ay*bx
        val len = sqrt((nx*nx + ny*ny + nz*nz).toDouble()).toFloat().coerceAtLeast(1e-9f)
        return floatArrayOf(nx/len, ny/len, nz/len)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0]*b[0] + a[1]*b[1] + a[2]*b[2]

    private fun vertexKey(pos: FloatArray, offset: Int): Int {
        val ix = (pos[offset]   * 1000).toInt()
        val iy = (pos[offset+1] * 1000).toInt()
        val iz = (pos[offset+2] * 1000).toInt()
        return ix * 1_000_003 + iy * 1009 + iz
    }

    private fun mergeToTarget(
        ids: IntArray,
        adj: Array<MutableList<Int>>, numRegions: Int, target: Int
    ): IntArray {
        if (numRegions <= target) {
            // Fewer natural regions than requested — return as-is (no splitting)
            return remap(ids)
        }

        val sizes = IntArray(numRegions)
        ids.forEach { sizes[it]++ }
        val regionAdj = HashMap<Long, Unit>()
        for (i in ids.indices) {
            for (nb in adj[i]) {
                val a = ids[i]; val b = ids[nb]
                if (a != b) {
                    val key = if (a < b) (a.toLong() shl 32) or b.toLong()
                              else (b.toLong() shl 32) or a.toLong()
                    regionAdj[key] = Unit
                }
            }
        }

        val mapping = IntArray(numRegions) { it }
        var current = numRegions
        while (current > target) {
            val active = (0 until numRegions).filter { mapping[it] == it && sizes[it] > 0 }
            val smallest = active.minByOrNull { sizes[it] } ?: break
            val neighbours = active.filter { nb ->
                if (nb == smallest) false
                else {
                    val a = smallest; val b = nb
                    val key = if (a < b) (a.toLong() shl 32) or b.toLong()
                              else (b.toLong() shl 32) or a.toLong()
                    regionAdj.containsKey(key)
                }
            }
            val mergeTarget = neighbours.maxByOrNull { sizes[it] }
                ?: active.first { it != smallest }
            mapping[smallest] = mergeTarget
            sizes[mergeTarget] += sizes[smallest]
            sizes[smallest] = 0
            current--
        }

        fun resolve(r: Int): Int = if (mapping[r] == r) r else { mapping[r] = resolve(mapping[r]); mapping[r] }
        val result = IntArray(ids.size) { resolve(ids[it]) }
        return remap(result)
    }

    private fun remap(ids: IntArray): IntArray {
        val seen = HashMap<Int, Int>()
        var next = 0
        return IntArray(ids.size) { seen.getOrPut(ids[it]) { next++ } }
    }
}
