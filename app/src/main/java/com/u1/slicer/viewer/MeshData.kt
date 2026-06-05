package com.u1.slicer.viewer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Holds interleaved vertex data for OpenGL rendering.
 * Format per vertex: x, y, z, nx, ny, nz, r, g, b, a (10 floats = 40 bytes)
 */
data class MeshData(
    val vertices: FloatBuffer,  // Interleaved position + normal + color
    val vertexCount: Int,
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
    val extruderIndices: ByteArray? = null,  // Per-triangle extruder index (unsigned byte)
    // F95: index of the first triangle in the trailing negative/modifier-volume block.
    // Triangles at or after this index are non-model-part volumes that recolor() paints
    // translucent and the renderer draws in a separate blended pass. null = no modifiers.
    val modifierBlockStartTriangle: Int? = null
) {
    val centerX get() = (minX + maxX) / 2
    val centerY get() = (minY + maxY) / 2
    val centerZ get() = (minZ + maxZ) / 2
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
    val maxDimension get() = maxOf(sizeX, sizeY, sizeZ)

    /** True when per-triangle extruder indices are available for coloring. */
    val hasPerVertexColor get() = extruderIndices != null

    /**
     * Extract a flat per-triangle xyz array suitable for [ModelViewerView.setTrianglePickingPositions].
     * Layout: 9 floats per triangle (v0xyz, v1xyz, v2xyz), in the same coordinate space as the
     * mesh vertices themselves. Use [toWorldSpacePickingPositions] if you need vertices in the
     * space the renderer actually draws in (after instance translation and global scale).
     */
    fun toPickingPositions(): FloatArray {
        val out = FloatArray(vertexCount * 3)
        val buf = vertices
        for (v in 0 until vertexCount) {
            val srcBase = v * FLOATS_PER_VERTEX
            val dstBase = v * 3
            out[dstBase]     = buf.get(srcBase)
            out[dstBase + 1] = buf.get(srcBase + 1)
            out[dstBase + 2] = buf.get(srcBase + 2)
        }
        return out
    }

    /**
     * Build picking positions in the same world space the renderer's draw path produces.
     *
     * For each instance the renderer applies (see [com.u1.slicer.viewer.ModelRenderer.drawModelAt]
     * / [com.u1.slicer.viewer.ModelRenderer.drawObjectRange]):
     *   T(x + halfW*sx, y + halfH*sy, halfD*sz)  *  S(sx, sy, sz)  *  T(-min - half)
     *
     * Without this transform the ray-cast in `pickTriangle` misses every triangle on a
     * single-instance load — the mesh vertices live near the origin while the rendered
     * geometry sits ~135mm out on the bed. F66's tap-to-select symptom on single STLs
     * was exactly this mismatch.
     *
     * @param objectMeshRanges null for single-mesh draws; populated for multi-object scenes.
     *        When null, the single mesh is treated as one range covering all vertices.
     * @param instancePositions flat `[x0, y0, x1, y1, …]` bed-space lower-left corners, one
     *        per range. Must be non-null and well-sized for multi-object output to be useful.
     * @param modelScale per-axis `[sx, sy, sz]` global scale applied around the per-instance
     *        bounding-box centre (the renderer's default `modelScale` field).
     */
    fun toWorldSpacePickingPositions(
        objectMeshRanges: List<com.u1.slicer.viewer.ModelRenderer.ObjectMeshRange>?,
        instancePositions: FloatArray?,
        modelScale: FloatArray,
    ): FloatArray {
        // OOM guard (Chubby_Darth_Vader_MULTI_COLOR.3mf crash, user report
        // 2026-05-31): MMU-painted meshes can land at 2-3M triangles because
        // mmu_segmentation_facets bypasses the QEM decimation path. Allocating
        // vertexCount * 3 floats then doubles app heap (the interleaved VBO
        // already holds the positions) and pushes single-process OOM on
        // mid-range Android. Return EMPTY for oversize meshes — the viewer's
        // setTrianglePickingPositions(empty) falls back to whole-object taps
        // (cb(0)) which is the right behaviour for single-object large files
        // anyway. Multi-object selection on >1M-triangle scenes is not a
        // supported use case yet.
        if (vertexCount > MAX_PICKING_VERTEX_COUNT) return FloatArray(0)
        val out = FloatArray(vertexCount * 3)
        val buf = vertices
        val sx = modelScale[0]; val sy = modelScale[1]; val sz = modelScale[2]

        if (!objectMeshRanges.isNullOrEmpty() && instancePositions != null
            && instancePositions.size / 2 == objectMeshRanges.size) {
            // Multi-object scene: each range gets its own translation from instancePositions.
            for ((rangeIdx, range) in objectMeshRanges.withIndex()) {
                val x = instancePositions[rangeIdx * 2]
                val y = instancePositions[rangeIdx * 2 + 1]
                val halfW = (range.maxX - range.minX) / 2f
                val halfH = (range.maxY - range.minY) / 2f
                val halfD = (range.maxZ - range.minZ) / 2f
                val tx = x + halfW * sx
                val ty = y + halfH * sy
                val tz = halfD * sz
                val ox = -range.minX - halfW
                val oy = -range.minY - halfH
                val oz = -range.minZ - halfD
                val vStart = range.vertexStart
                val vEnd = vStart + range.vertexCount
                for (v in vStart until vEnd) {
                    val srcBase = v * FLOATS_PER_VERTEX
                    val dstBase = v * 3
                    out[dstBase]     = (buf.get(srcBase) + ox) * sx + tx
                    out[dstBase + 1] = (buf.get(srcBase + 1) + oy) * sy + ty
                    out[dstBase + 2] = (buf.get(srcBase + 2) + oz) * sz + tz
                }
            }
            return out
        }

        // Single-mesh path. Use the first instance position if any (single STL on Prepare has
        // exactly one entry at `[centerX, centerY]`); otherwise fall back to identity.
        val x = instancePositions?.getOrNull(0) ?: 0f
        val y = instancePositions?.getOrNull(1) ?: 0f
        val halfW = (maxX - minX) / 2f
        val halfH = (maxY - minY) / 2f
        val halfD = (maxZ - minZ) / 2f
        val tx = x + halfW * sx
        val ty = y + halfH * sy
        val tz = halfD * sz
        val ox = -minX - halfW
        val oy = -minY - halfH
        val oz = -minZ - halfD
        for (v in 0 until vertexCount) {
            val srcBase = v * FLOATS_PER_VERTEX
            val dstBase = v * 3
            out[dstBase]     = (buf.get(srcBase) + ox) * sx + tx
            out[dstBase + 1] = (buf.get(srcBase + 1) + oy) * sy + ty
            out[dstBase + 2] = (buf.get(srcBase + 2) + oz) * sz + tz
        }
        return out
    }

    /**
     * Writes per-vertex RGBA into the interleaved buffer based on extruder indices
     * and the provided color palette. Each triangle's 3 vertices get the same color
     * from the palette entry corresponding to its extruder index.
     *
     * @param colorPalette list of RGBA float arrays (each size 4), indexed by extruder
     */
    fun recolor(colorPalette: List<FloatArray>) {
        val indices = extruderIndices ?: return
        if (colorPalette.isEmpty()) return

        val lastIndex = colorPalette.size - 1
        val buf = vertices
        // F95: triangles at/after this boundary are negative/modifier volumes — paint them
        // the fixed translucent colour rather than a palette entry. null → no modifier block.
        val modStart = modifierBlockStartTriangle ?: indices.size

        for (tri in indices.indices) {
            val color = if (tri >= modStart) {
                MODIFIER_PREVIEW_COLOR
            } else {
                colorPalette[(indices[tri].toInt() and 0xFF).coerceAtMost(lastIndex)]
            }
            val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]

            for (v in 0 until 3) {
                val base = (tri * 3 + v) * FLOATS_PER_VERTEX + 6
                buf.put(base, r)
                buf.put(base + 1, g)
                buf.put(base + 2, b)
                buf.put(base + 3, a)
            }
        }
    }

    /**
     * Recolours mesh triangles based on their Z centroid position relative to layer-tool segments.
     * Used for Hueforge/layer-tool models where colour changes at specific Z heights.
     *
     * @param segments Ordered list of Z-band boundaries (ascending topZ). The last segment whose
     *                 topZ ≤ triangle Z centroid determines the extruder. If no segment matches,
     *                 extruder 1 (base colour) is used.
     * @param colorPalette RGBA float arrays indexed by compact palette index (extruderBambu-1).
     */
    fun recolorByZBands(
        segments: List<com.u1.slicer.bambu.LayerToolSegment>,
        colorPalette: List<FloatArray>
    ) {
        if (segments.isEmpty() || colorPalette.isEmpty()) return
        val buf = vertices
        val triCount = vertexCount / 3
        for (tri in 0 until triCount) {
            val base0 = tri * 3 * FLOATS_PER_VERTEX
            val z0 = buf.get(base0 + 2)
            val z1 = buf.get(base0 + FLOATS_PER_VERTEX + 2)
            val z2 = buf.get(base0 + FLOATS_PER_VERTEX * 2 + 2)
            val zCentroid = (z0 + z1 + z2) / 3f

            // Last segment whose topZ ≤ zCentroid; if none, default to extruder 1 (base colour)
            val extruderBambu = segments.lastOrNull { it.topZ <= zCentroid }?.extruderBambu ?: 1
            // extruderBambu is 1-based index into the layer-tool colour sequence.
            // Convert directly to compact palette index (extruder 1 → palette[0], extruder 2 → palette[1]).
            val safeIndex = (extruderBambu - 1).coerceIn(0, colorPalette.size - 1)
            val color = colorPalette[safeIndex]
            val r = color[0]; val g = color[1]; val b = color[2]; val a = color[3]

            for (v in 0 until 3) {
                val vBase = (tri * 3 + v) * FLOATS_PER_VERTEX + 6
                buf.put(vBase, r)
                buf.put(vBase + 1, g)
                buf.put(vBase + 2, b)
                buf.put(vBase + 3, a)
            }
        }
    }

    companion object {
        const val FLOATS_PER_VERTEX = 10 // x,y,z, nx,ny,nz, r,g,b,a
        const val BYTES_PER_VERTEX = FLOATS_PER_VERTEX * 4

        /**
         * F95: fixed RGBA applied to negative/modifier-volume triangles on the Prepare
         * preview. Distinct translucent magenta (alpha < 1) so the renderer's depth-test-off
         * blended pass shows the cut/modifier region THROUGH the solid body and stands out
         * against typical filament colours (B140: a subtle grey-blue + depth-test-on made it
         * invisible). Matches desktop OrcaSlicer/PrusaSlicer's translucent modifier rendering.
         */
        val MODIFIER_PREVIEW_COLOR = floatArrayOf(0.90f, 0.15f, 0.85f, 0.50f)

        /**
         * Picking-array allocation threshold. Above this, [toWorldSpacePickingPositions]
         * returns an empty array and the viewer falls back to whole-object taps. Set
         * conservatively (1M vertices ≈ 12 MB extra heap) — the OOM crash on
         * Chubby_Darth_Vader_MULTI_COLOR.3mf (8.5M vertices, ~100 MB extra allocation)
         * happened on top of an already-loaded interleaved VBO plus native cache, so
         * we leave room for the rest of the app.
         */
        const val MAX_PICKING_VERTEX_COUNT = 1_000_000

        fun allocateBuffer(triangleCount: Int): FloatBuffer {
            val floatCount = triangleCount * 3 * FLOATS_PER_VERTEX
            return ByteBuffer.allocateDirect(floatCount * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
    }
}
