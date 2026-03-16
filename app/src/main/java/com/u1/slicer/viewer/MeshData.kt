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
