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
    @JvmField val extruderIndices: ByteArray,
) {
    /** F54: per-volume triangle index ranges, in mesh build order. Populated post-construction
     *  by NativeLibrary.getPreparePreviewMesh from JSON returned by nativeGetAllVolumeExtruders
     *  (each volume's triangleCount). null when not supplied. When present, ranges are
     *  disjoint, contiguous, and sum to the triangleCount. Drives the AI Paint cascade's
     *  per-volume branch (B). Excluded from data-class equality (see equals override on the
     *  containing AiPaintResultState; this class itself relies on default reference equality
     *  via data-class semantics, but batchRanges is a `var` set once after JNI returns). */
    @JvmField
    var batchRanges: List<IntRange>? = null

    /** F95: index of the first triangle in the trailing negative/modifier-volume block,
     *  or -1 when the model has no modifier/negative volumes. Set post-construction by
     *  NativeLibrary.getPreparePreviewMesh from the native modifier-block-start accessor,
     *  mirroring the [batchRanges] population pattern. Forwarded to MeshData by toMeshData. */
    @JvmField
    var modifierBlockStartTriangle: Int = -1

    fun toMeshData(): MeshData? {
        val meshT0 = System.currentTimeMillis()
        val triangleCount = extruderIndices.size
        if (triangleCount == 0 || trianglePositions.size != triangleCount * 9) return null

        // Phase 2 (Approach A1): preserve raw file-filament-indexed extruder
        // indices verbatim. The Phase 2 canonical-list palette is sized to
        // the file's full filament count and is indexed by file-filament index
        // (palette[0] = file filament 1, palette[7] = file filament 8, etc.).
        //
        // Native `compactPreviewIndices` runs only when MMU paint data is absent,
        // and even then only collapses sparse filament numbering. For non-MMU
        // files where filaments 1..N are dense (e.g. Calicube uses filaments 1-4),
        // the compaction is a sorted-unique no-op. For MMU files (e.g. Buzz
        // Lightyear plate 9 uses paint states 8 and 10), native preserves the
        // raw paint-state indices so the Phase 2 palette resolves the correct
        // file-filament colour.
        //
        // The previous B88 Kotlin-side compaction was correct for the
        // pre-Phase-2 contract where the palette was plate-narrowed. With the
        // Phase 2 canonical palette, compacting here remaps file-filament index
        // 9 ("filament 10") to mesh index 0 / 1, which then resolves to
        // `palette[0]` (file filament 1) instead of `palette[9]`. Removing the
        // Kotlin compaction restores the file-filament-index identity that the
        // canonical palette expects.
        //
        // The `compactExtruderIndices` companion helper is kept for callers that
        // still want sorted-unique compaction (e.g. legacy slot-narrowed paths
        // not yet migrated to the canonical list).
        val previewIndices = extruderIndices.copyOf()
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
        val loopMs = System.currentTimeMillis() - meshT0
        val logT0 = System.currentTimeMillis()
        Log.i(
            "NativePreviewMesh",
            "toMeshData triangles=$triangleCount " +
                "bounds=[$minX,$minY,$minZ]-[$maxX,$maxY,$maxZ] " +
                "indices=${extruderIndices.map { it.toInt() and 0xFF }.groupingBy { it }.eachCount()}"
        )
        val logMs = System.currentTimeMillis() - logT0
        Log.i("LoadTiming", "toMeshData breakdown loop=${loopMs}ms log.i=${logMs}ms triangles=$triangleCount")
        val batch = NativeRenderBatch(
            triangleCount = triangleCount,
            geometry = buf,
            materialIndices = java.nio.ByteBuffer.allocateDirect(previewIndices.size).apply {
                put(previewIndices)
                rewind()
            }
        )
        return MeshData(
            batches = listOf(batch),
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
            modifierBlockStartTriangle = modifierBlockStartTriangle.takeIf { it >= 0 }
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
         * Absolute triangle cap for painted/SEMM preview meshes (used by
         * SlicerViewModel.isLargeTriangleCount to gate the LargePreviewFallback UI).
         * Higher than MAX_DECIMATED_TRIANGLES so typical painted models pass through
         * untouched; only genuinely enormous painted models (>500K tris) fall back.
         * At 500K, GL buffer ≈ 60MB — safe on modern devices.
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
