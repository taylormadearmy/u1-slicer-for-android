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
    val extruderIndices: ByteArray? = null  // Per-triangle extruder index (unsigned byte)
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
     * Layout: 9 floats per triangle (v0xyz, v1xyz, v2xyz), in the same world space as the
     * mesh vertices. Caller must reapply this whenever the mesh changes.
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

        for (tri in indices.indices) {
            val extruder = (indices[tri].toInt() and 0xFF).coerceAtMost(lastIndex)
            val color = colorPalette[extruder]
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

        fun allocateBuffer(triangleCount: Int): FloatBuffer {
            val floatCount = triangleCount * 3 * FLOATS_PER_VERTEX
            return ByteBuffer.allocateDirect(floatCount * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
    }
}
