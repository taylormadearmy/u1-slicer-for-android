package com.u1.slicer.viewer

import android.util.Log
import kotlin.math.sqrt

/**
 * Triangle payload exported directly from the native Orca-loaded model.
 *
 * The native side returns world-space triangle positions only. We rebuild normals
 * and the interleaved MeshData buffer on Android so the existing GL renderer can
 * stay unchanged.
 */
data class NativePreviewMesh(
    @JvmField val trianglePositions: FloatArray,
    @JvmField val extruderIndices: ByteArray
) {
    fun toMeshData(): MeshData? {
        val triangleCount = extruderIndices.size
        if (triangleCount == 0 || trianglePositions.size != triangleCount * 9) return null

        // B88: compact raw extruder indices to 0..N-1 using sorted-unique ordering.
        // Native `compactPreviewIndices` skips compaction when MMU paint data is present
        // (to preserve state_idx for H2C-style folding), but this leaves plates with
        // high filament indices (e.g. Buzz Lightyear plate 9 uses filaments 10/11 → raw
        // indices 9/10) mismatched against the Kotlin `colorMapping`, which is sized to
        // the plate's compacted `detectedColors`. The result: `MeshData.recolor` clamps
        // the OOB index to palette.lastIndex, collapsing the preview to a single colour.
        //
        // Compacting in Kotlin is safe for all existing models — meshes that already use
        // compact 0..N-1 indices are a no-op under this mapping. `mergeThreeMfInfoForPlate`
        // produces `detectedColors` in sorted-filament-index order, so the sorted-unique
        // compact mapping here aligns with the palette ordering.
        val compactedIndices = compactExtruderIndices(extruderIndices)
        val buf = MeshData.allocateBuffer(triangleCount)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        for (tri in 0 until triangleCount) {
            val base = tri * 9
            val x1 = trianglePositions[base]
            val y1 = trianglePositions[base + 1]
            val z1 = trianglePositions[base + 2]
            val x2 = trianglePositions[base + 3]
            val y2 = trianglePositions[base + 4]
            val z2 = trianglePositions[base + 5]
            val x3 = trianglePositions[base + 6]
            val y3 = trianglePositions[base + 7]
            val z3 = trianglePositions[base + 8]

            val ux = x2 - x1
            val uy = y2 - y1
            val uz = z2 - z1
            val vx = x3 - x1
            val vy = y3 - y1
            val vz = z3 - z1
            val nx0 = uy * vz - uz * vy
            val ny0 = uz * vx - ux * vz
            val nz0 = ux * vy - uy * vx
            val len = sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0).takeIf { it > 1e-8f } ?: 1f
            val nx = nx0 / len
            val ny = ny0 / len
            val nz = nz0 / len

            minX = minOf(minX, x1, x2, x3)
            minY = minOf(minY, y1, y2, y3)
            minZ = minOf(minZ, z1, z2, z3)
            maxX = maxOf(maxX, x1, x2, x3)
            maxY = maxOf(maxY, y1, y2, y3)
            maxZ = maxOf(maxZ, z1, z2, z3)

            putVertex(buf, x1, y1, z1, nx, ny, nz)
            putVertex(buf, x2, y2, z2, nx, ny, nz)
            putVertex(buf, x3, y3, z3, nx, ny, nz)
        }

        buf.position(0)
        Log.i(
            "NativePreviewMesh",
            "toMeshData triangles=$triangleCount " +
                "bounds=[$minX,$minY,$minZ]-[$maxX,$maxY,$maxZ] " +
                "indices=${extruderIndices.map { it.toInt() and 0xFF }.groupingBy { it }.eachCount()}"
        )
        return MeshData(
            vertices = buf,
            vertexCount = triangleCount * 3,
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
            extruderIndices = compactedIndices
        )
    }

    private fun putVertex(
        buf: java.nio.FloatBuffer,
        x: Float,
        y: Float,
        z: Float,
        nx: Float,
        ny: Float,
        nz: Float
    ) {
        buf.put(x)
        buf.put(y)
        buf.put(z)
        buf.put(nx)
        buf.put(ny)
        buf.put(nz)
        buf.put(0.8f)
        buf.put(0.8f)
        buf.put(0.8f)
        buf.put(1f)
    }

    companion object {
        /**
         * B88: Map raw filament indices (possibly sparse/high, e.g. 9 and 10 for Buzz
         * Lightyear plate 9) to compact 0..N-1 in sorted-unique order. Matches the
         * native `compactPreviewIndices` algorithm but runs unconditionally on the
         * Kotlin side so per-plate meshes with paint data (`has_mmu_data=true`) — for
         * which native skips compaction — still align with the Kotlin-compacted
         * `detectedColors` + `colorMapping` palette.
         */
        internal fun compactExtruderIndices(raw: ByteArray): ByteArray {
            if (raw.isEmpty()) return raw.copyOf()
            val seen = BooleanArray(256)
            for (b in raw) seen[b.toInt() and 0xFF] = true
            val lut = ByteArray(256)
            var next = 0
            for (i in 0 until 256) {
                if (seen[i]) {
                    lut[i] = next.toByte()
                    next++
                }
            }
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = lut[raw[i].toInt() and 0xFF]
            return out
        }

        /** Target triangle count passed to native QEM decimation. At 100K, GL buffer ≈ 12MB. */
        const val MAX_DECIMATED_TRIANGLES = 100_000

        /**
         * Triangle cap for the Kotlin ThreeMfMeshParser path (painted/SEMM models).
         * Higher than MAX_DECIMATED_TRIANGLES because Kotlin uses stride decimation (drops
         * triangles randomly, breaking connectivity) rather than QEM. Set high enough that
         * typical painted models pass through untouched. At 500K, GL buffer ≈ 60MB — safe
         * on modern devices. Only fires for genuinely enormous painted models (>500K tris).
         * TODO(F48-kotlin-qem): replace stride decimation with proper QEM or route painted
         * models through the native path so this cap can be lowered to match MAX_DECIMATED_TRIANGLES.
         */
        const val MAX_KOTLIN_PREVIEW_TRIANGLES = 500_000

        // Safety-net threshold for LargePreviewFallback — effectively unreachable after decimation.
        // Kept at a high value (not deleted) to preserve B18 regression test coverage.
        const val MAX_SAFE_TRIANGLES = 50_000_000

        fun wouldExceedSafePreviewBudget(triangleCount: Int): Boolean {
            if (triangleCount <= 0) return false
            // After F48 decimation, native export caps at MAX_DECIMATED_TRIANGLES (100K).
            // This threshold is a last-resort OOM guard only.
            return triangleCount > MAX_SAFE_TRIANGLES
        }
    }
}
