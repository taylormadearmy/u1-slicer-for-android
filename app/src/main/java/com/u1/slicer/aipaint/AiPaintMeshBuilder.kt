package com.u1.slicer.aipaint

import com.u1.slicer.viewer.MeshData
import kotlin.math.sqrt

/**
 * Builds a [MeshData] from raw triangle positions plus a per-triangle key array (region IDs
 * 0..3 in the AI Paint pipeline). Keys are stored in `extruderIndices` so [MeshData.recolor]
 * paints each triangle from the palette entry at `palette[key]`. The renderer's
 * `pendingExtruderUpdate` lets us mutate these indices in place when the user paints, so we
 * never have to rebuild the mesh on a brush stroke.
 */
object AiPaintMeshBuilder {

    fun build(trianglePositions: FloatArray, triangleKeys: ByteArray): MeshData {
        val triCount = triangleKeys.size
        require(trianglePositions.size == triCount * 9) {
            "trianglePositions length ${trianglePositions.size} does not match $triCount triangles × 9 floats"
        }

        val buf = MeshData.allocateBuffer(triCount)
        val byteIndices = triangleKeys.copyOf()

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        for (tri in 0 until triCount) {
            val base = tri * 9
            val x1 = trianglePositions[base];     val y1 = trianglePositions[base + 1]; val z1 = trianglePositions[base + 2]
            val x2 = trianglePositions[base + 3]; val y2 = trianglePositions[base + 4]; val z2 = trianglePositions[base + 5]
            val x3 = trianglePositions[base + 6]; val y3 = trianglePositions[base + 7]; val z3 = trianglePositions[base + 8]

            val ux = x2 - x1; val uy = y2 - y1; val uz = z2 - z1
            val vx = x3 - x1; val vy = y3 - y1; val vz = z3 - z1
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-8f) { nx /= len; ny /= len; nz /= len } else { nx = 0f; ny = 0f; nz = 1f }

            putVertex(buf, x1, y1, z1, nx, ny, nz)
            putVertex(buf, x2, y2, z2, nx, ny, nz)
            putVertex(buf, x3, y3, z3, nx, ny, nz)

            if (x1 < minX) minX = x1; if (x2 < minX) minX = x2; if (x3 < minX) minX = x3
            if (y1 < minY) minY = y1; if (y2 < minY) minY = y2; if (y3 < minY) minY = y3
            if (z1 < minZ) minZ = z1; if (z2 < minZ) minZ = z2; if (z3 < minZ) minZ = z3
            if (x1 > maxX) maxX = x1; if (x2 > maxX) maxX = x2; if (x3 > maxX) maxX = x3
            if (y1 > maxY) maxY = y1; if (y2 > maxY) maxY = y2; if (y3 > maxY) maxY = y3
            if (z1 > maxZ) maxZ = z1; if (z2 > maxZ) maxZ = z2; if (z3 > maxZ) maxZ = z3
        }

        buf.position(0)
        val matBuf = java.nio.ByteBuffer.allocateDirect(triCount).order(java.nio.ByteOrder.nativeOrder())
        matBuf.put(triangleKeys)
        matBuf.rewind()
        
        return MeshData(
            batches = listOf(com.u1.slicer.viewer.NativeRenderBatch(buf, matBuf, triCount)),
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ
        )
    }

    /**
     * Region palette: one RGBA entry per region. The mesh's extruderIndices contains per-
     * triangle region keys (0..3), so [MeshData.recolor] will look up palette[regionId] for
     * each triangle.
     */
    fun regionPalette(regionColours: List<FloatArray>): List<FloatArray> {
        val fallback = floatArrayOf(0.55f, 0.55f, 0.55f, 1f)
        return regionColours.ifEmpty { listOf(fallback) }
    }

    private fun putVertex(
        buf: java.nio.FloatBuffer,
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float
    ) {
        buf.put(x); buf.put(y); buf.put(z)
        buf.put(nx); buf.put(ny); buf.put(nz)
        buf.put(0.8f); buf.put(0.8f); buf.put(0.8f); buf.put(1f)
    }
}
