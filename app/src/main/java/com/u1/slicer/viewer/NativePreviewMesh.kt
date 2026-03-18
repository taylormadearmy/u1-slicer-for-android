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
            extruderIndices = extruderIndices.copyOf()
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
}
