package com.u1.slicer.aipaint

import com.u1.slicer.viewer.MeshData
import kotlin.math.sqrt

/**
 * Builds a [MeshData] from raw triangle positions plus per-triangle component IDs.
 * The component ID is stored in `extruderIndices` so [MeshData.recolor] paints each
 * component from the palette entry at `palette[componentId]`.
 *
 * Component IDs must fit in an unsigned byte (0..255). The AI Paint pipeline caps
 * the intermediate component count at 32 (see [MeshSegmenter.MAX_INTERMEDIATE_COMPONENTS]).
 */
object AiPaintMeshBuilder {

    fun build(trianglePositions: FloatArray, componentIds: IntArray): MeshData {
        val triCount = componentIds.size
        require(trianglePositions.size == triCount * 9) {
            "trianglePositions length ${trianglePositions.size} does not match $triCount triangles × 9 floats"
        }

        val buf = MeshData.allocateBuffer(triCount)
        val byteIndices = ByteArray(triCount) { componentIds[it].toByte() }

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
        return MeshData(
            vertices = buf,
            vertexCount = triCount * 3,
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ,
            extruderIndices = byteIndices
        )
    }

    /**
     * Builds an RGBA palette where palette[componentId] is the colour of the region that
     * component is currently assigned to. If [highlightComponentId] is set, that one
     * component is rendered bright yellow and all others are dimmed to gray, useful for
     * "show me which part this chip refers to" previews.
     */
    fun buildPalette(
        numComponents: Int,
        componentToRegion: IntArray,
        regionColours: List<FloatArray>,
        highlightComponentId: Int? = null
    ): List<FloatArray> {
        val fallback = floatArrayOf(0.55f, 0.55f, 0.55f, 1f)
        val highlight = floatArrayOf(1f, 0.92f, 0.20f, 1f)
        val dim       = floatArrayOf(0.25f, 0.25f, 0.27f, 1f)
        return List(numComponents) { c ->
            if (highlightComponentId != null) {
                if (c == highlightComponentId) highlight else dim
            } else {
                val region = componentToRegion.getOrNull(c) ?: -1
                regionColours.getOrNull(region) ?: fallback
            }
        }
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
