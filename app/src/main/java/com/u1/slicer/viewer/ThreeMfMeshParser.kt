package com.u1.slicer.viewer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.sqrt

/**
 * Parses mesh data from 3MF files for OpenGL preview rendering.
 * Extracts vertices and triangles from the first mesh found in the 3MF,
 * computing per-face normals for Gouraud lighting.
 */
object ThreeMfMeshParser {

    fun parse(file: File): MeshData? {
        ZipFile(file).use { zip ->
            // Try main model first, then component files
            val modelEntry = zip.getEntry("3D/3dmodel.model") ?: return null
            val modelXml = zip.getInputStream(modelEntry).bufferedReader().readText()

            // Extract first inline mesh from main model
            var mesh = extractMesh(modelXml)
            if (mesh != null) return mesh

            // If main model has component refs, try the first component file
            val componentPath = Regex("""p:path="([^"]*\.model)"""").find(modelXml)
                ?.groupValues?.get(1)?.trimStart('/')
            if (componentPath != null) {
                val compEntry = zip.getEntry(componentPath) ?: return null
                val compXml = zip.getInputStream(compEntry).bufferedReader().readText()
                mesh = extractMesh(compXml)
                if (mesh != null) return mesh
            }
        }
        return null
    }

    private fun extractMesh(xml: String): MeshData? {
        // Find <vertices> and <triangles> sections
        val verticesStart = xml.indexOf("<vertices>")
        val verticesEnd = xml.indexOf("</vertices>")
        val trianglesStart = xml.indexOf("<triangles>")
        val trianglesEnd = xml.indexOf("</triangles>")
        if (verticesStart < 0 || trianglesStart < 0) return null

        val verticesXml = xml.substring(verticesStart, verticesEnd + "</vertices>".length)
        val trianglesXml = xml.substring(trianglesStart, trianglesEnd + "</triangles>".length)

        // Parse vertices: <vertex x="..." y="..." z="..."/>
        val vertexRegex = Regex("""x="([^"]+)"\s+y="([^"]+)"\s+z="([^"]+)"""")
        val verts = mutableListOf<FloatArray>()
        for (match in vertexRegex.findAll(verticesXml)) {
            verts.add(floatArrayOf(
                match.groupValues[1].toFloat(),
                match.groupValues[2].toFloat(),
                match.groupValues[3].toFloat()
            ))
        }
        if (verts.isEmpty()) return null

        // Parse triangles: <triangle v1="..." v2="..." v3="..."/>
        val triRegex = Regex("""v1="(\d+)"\s+v2="(\d+)"\s+v3="(\d+)"""")
        val tris = mutableListOf<IntArray>()
        for (match in triRegex.findAll(trianglesXml)) {
            tris.add(intArrayOf(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            ))
        }
        if (tris.isEmpty()) return null

        // Build interleaved vertex buffer with per-face normals
        val buf = MeshData.allocateBuffer(tris.size)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE; var maxZ = Float.MIN_VALUE

        for (tri in tris) {
            val v0 = verts.getOrNull(tri[0]) ?: continue
            val v1 = verts.getOrNull(tri[1]) ?: continue
            val v2 = verts.getOrNull(tri[2]) ?: continue

            // Compute face normal
            val ux = v1[0] - v0[0]; val uy = v1[1] - v0[1]; val uz = v1[2] - v0[2]
            val vx = v2[0] - v0[0]; val vy = v2[1] - v0[1]; val vz = v2[2] - v0[2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0) { nx /= len; ny /= len; nz /= len }

            for (v in arrayOf(v0, v1, v2)) {
                buf.put(v[0]); buf.put(v[1]); buf.put(v[2])
                buf.put(nx); buf.put(ny); buf.put(nz)
                minX = minOf(minX, v[0]); minY = minOf(minY, v[1]); minZ = minOf(minZ, v[2])
                maxX = maxOf(maxX, v[0]); maxY = maxOf(maxY, v[1]); maxZ = maxOf(maxZ, v[2])
            }
        }
        buf.flip()

        return MeshData(buf, tris.size * 3, minX, minY, minZ, maxX, maxY, maxZ)
    }
}
