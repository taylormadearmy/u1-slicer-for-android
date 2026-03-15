package com.u1.slicer.viewer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses binary and ASCII STL files into MeshData for OpenGL rendering.
 */
object StlParser {

    fun parse(file: File): MeshData {
        val bytes = file.readBytes()
        return if (isBinaryStl(bytes)) {
            parseBinary(bytes)
        } else {
            parseAscii(bytes)
        }
    }

    private fun isBinaryStl(bytes: ByteArray): Boolean {
        if (bytes.size < 84) return false
        // Check if starts with "solid " — could be ASCII
        val header = String(bytes, 0, minOf(80, bytes.size), Charsets.US_ASCII)
        if (header.trimStart().startsWith("solid")) {
            // But binary STLs can also start with "solid" in header
            // Check if the declared triangle count matches file size
            val buf = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN)
            val triangles = buf.int.toLong()
            val expectedSize = 84 + triangles * 50
            if (expectedSize == bytes.size.toLong()) return true
            // If file has "facet" keyword, it's likely ASCII
            val sample = String(bytes, 0, minOf(1024, bytes.size), Charsets.US_ASCII)
            return !sample.contains("facet")
        }
        return true
    }

    private fun parseBinary(bytes: ByteArray): MeshData {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80)
        val triangleCount = buf.int

        val vertexBuf = MeshData.allocateBuffer(triangleCount)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for (i in 0 until triangleCount) {
            val nx = buf.float; val ny = buf.float; val nz = buf.float

            for (v in 0 until 3) {
                val x = buf.float; val y = buf.float; val z = buf.float
                vertexBuf.put(x); vertexBuf.put(y); vertexBuf.put(z)
                vertexBuf.put(nx); vertexBuf.put(ny); vertexBuf.put(nz)
                vertexBuf.put(0.7f); vertexBuf.put(0.7f); vertexBuf.put(0.7f); vertexBuf.put(1.0f)

                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }

            buf.short // attribute byte count
        }

        vertexBuf.flip()
        return MeshData(
            vertices = vertexBuf,
            vertexCount = triangleCount * 3,
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ,
            extruderIndices = null
        )
    }

    private fun parseAscii(bytes: ByteArray): MeshData {
        val text = String(bytes, Charsets.UTF_8)
        val vertices = mutableListOf<Float>()
        val normals = mutableListOf<Float>()

        var nx = 0f; var ny = 0f; var nz = 0f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        val normalRegex = Regex("""facet\s+normal\s+([-\d.eE+]+)\s+([-\d.eE+]+)\s+([-\d.eE+]+)""")
        val vertexRegex = Regex("""vertex\s+([-\d.eE+]+)\s+([-\d.eE+]+)\s+([-\d.eE+]+)""")

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            val normalMatch = normalRegex.find(trimmed)
            if (normalMatch != null) {
                nx = normalMatch.groupValues[1].toFloat()
                ny = normalMatch.groupValues[2].toFloat()
                nz = normalMatch.groupValues[3].toFloat()
                continue
            }
            val vertexMatch = vertexRegex.find(trimmed)
            if (vertexMatch != null) {
                val x = vertexMatch.groupValues[1].toFloat()
                val y = vertexMatch.groupValues[2].toFloat()
                val z = vertexMatch.groupValues[3].toFloat()
                vertices.addAll(listOf(x, y, z))
                normals.addAll(listOf(nx, ny, nz))

                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
        }

        val triangleCount = vertices.size / 9
        val vertexBuf = MeshData.allocateBuffer(triangleCount)
        for (i in 0 until vertices.size / 3) {
            vertexBuf.put(vertices[i * 3])
            vertexBuf.put(vertices[i * 3 + 1])
            vertexBuf.put(vertices[i * 3 + 2])
            vertexBuf.put(normals[i * 3])
            vertexBuf.put(normals[i * 3 + 1])
            vertexBuf.put(normals[i * 3 + 2])
            vertexBuf.put(0.7f); vertexBuf.put(0.7f); vertexBuf.put(0.7f); vertexBuf.put(1.0f)
        }
        vertexBuf.flip()

        return MeshData(
            vertices = vertexBuf,
            vertexCount = vertices.size / 3,
            minX = minX, minY = minY, minZ = minZ,
            maxX = maxX, maxY = maxY, maxZ = maxZ,
            extruderIndices = null
        )
    }
}
