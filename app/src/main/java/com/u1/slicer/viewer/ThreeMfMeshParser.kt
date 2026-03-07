package com.u1.slicer.viewer

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.sqrt

/**
 * Parses mesh data from 3MF files for OpenGL preview rendering.
 * Extracts vertices and triangles from all meshes found in the 3MF,
 * applying component and build item transforms, and computing per-face normals.
 */
object ThreeMfMeshParser {

    private data class RawMesh(
        val verts: List<FloatArray>,
        val tris: List<IntArray>
    )

    fun parse(file: File): MeshData? {
        ZipFile(file).use { zip ->
            val modelEntry = zip.getEntry("3D/3dmodel.model") ?: return null
            val modelXml = zip.getInputStream(modelEntry).bufferedReader().readText()

            // Collect all transformed meshes
            val allVerts = mutableListOf<FloatArray>()
            val allTris = mutableListOf<IntArray>()

            // Parse build items for their transforms
            val buildItems = parseBuildItems(modelXml)

            // Parse objects in the main model
            val mainObjects = parseObjects(modelXml)

            // For each build item, resolve its object and apply transforms
            for (item in buildItems) {
                val obj = mainObjects[item.objectId]
                if (obj != null) {
                    collectMeshes(obj, item.transform, mainObjects, zip, allVerts, allTris)
                }
            }

            // If no build items found, try direct mesh extraction (simple 3MF)
            if (allVerts.isEmpty()) {
                val mesh = extractMesh(modelXml)
                if (mesh != null) return mesh

                // Try first component file
                val componentPath = Regex("""p:path="([^"]*\.model)"""").find(modelXml)
                    ?.groupValues?.get(1)?.trimStart('/')
                if (componentPath != null) {
                    val compEntry = zip.getEntry(componentPath) ?: return null
                    val compXml = zip.getInputStream(compEntry).bufferedReader().readText()
                    val compMesh = extractMesh(compXml)
                    if (compMesh != null) return compMesh
                }
                return null
            }

            return buildMeshData(allVerts, allTris)
        }
    }

    private data class BuildItem(val objectId: String, val transform: FloatArray?)
    private data class ObjectInfo(
        val id: String,
        val mesh: RawMesh?,
        val components: List<ComponentRef>
    )
    private data class ComponentRef(
        val objectId: String,
        val path: String?,
        val transform: FloatArray?
    )

    private fun parseBuildItems(xml: String): List<BuildItem> {
        val items = mutableListOf<BuildItem>()
        val regex = Regex("""<item\s+([^>]+)>""")
        for (match in regex.findAll(xml)) {
            val attrs = match.groupValues[1]
            val objId = Regex("""objectid="(\d+)"""").find(attrs)?.groupValues?.get(1) ?: continue
            val transform = Regex("""transform="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            items.add(BuildItem(objId, parseTransform(transform)))
        }
        return items
    }

    private fun parseObjects(xml: String): Map<String, ObjectInfo> {
        val objects = mutableMapOf<String, ObjectInfo>()
        val objRegex = Regex("""<object\s+id="(\d+)"[^>]*>(.*?)</object>""", RegexOption.DOT_MATCHES_ALL)
        for (match in objRegex.findAll(xml)) {
            val id = match.groupValues[1]
            val body = match.groupValues[2]

            val mesh = extractRawMesh(body)
            val components = parseComponents(body)
            objects[id] = ObjectInfo(id, mesh, components)
        }
        return objects
    }

    private fun parseComponents(xml: String): List<ComponentRef> {
        val comps = mutableListOf<ComponentRef>()
        val regex = Regex("""<component\s+([^/]+)/?>""")
        for (match in regex.findAll(xml)) {
            val attrs = match.groupValues[1]
            val objId = Regex("""objectid="(\d+)"""").find(attrs)?.groupValues?.get(1) ?: continue
            val path = Regex("""p:path="([^"]+)"""").find(attrs)?.groupValues?.get(1)?.trimStart('/')
            val transform = Regex("""transform="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            comps.add(ComponentRef(objId, path, parseTransform(transform)))
        }
        return comps
    }

    private fun collectMeshes(
        obj: ObjectInfo,
        parentTransform: FloatArray?,
        mainObjects: Map<String, ObjectInfo>,
        zip: ZipFile,
        outVerts: MutableList<FloatArray>,
        outTris: MutableList<IntArray>
    ) {
        // If object has its own mesh, add it with parent transform
        if (obj.mesh != null) {
            addTransformedMesh(obj.mesh, parentTransform, outVerts, outTris)
        }

        // Process components
        for (comp in obj.components) {
            val combinedTransform = multiplyTransforms(parentTransform, comp.transform)

            if (comp.path != null) {
                // External component file
                val entry = zip.getEntry(comp.path) ?: continue
                val compXml = zip.getInputStream(entry).bufferedReader().readText()
                val extObjects = parseObjects(compXml)
                val extObj = extObjects[comp.objectId]
                if (extObj != null) {
                    collectMeshes(extObj, combinedTransform, extObjects, zip, outVerts, outTris)
                }
            } else {
                // Internal component reference
                val refObj = mainObjects[comp.objectId]
                if (refObj != null) {
                    collectMeshes(refObj, combinedTransform, mainObjects, zip, outVerts, outTris)
                }
            }
        }
    }

    private fun addTransformedMesh(
        mesh: RawMesh,
        transform: FloatArray?,
        outVerts: MutableList<FloatArray>,
        outTris: MutableList<IntArray>
    ) {
        val baseIdx = outVerts.size
        for (v in mesh.verts) {
            outVerts.add(applyTransform(v, transform))
        }
        for (tri in mesh.tris) {
            outTris.add(intArrayOf(tri[0] + baseIdx, tri[1] + baseIdx, tri[2] + baseIdx))
        }
    }

    private fun extractRawMesh(xml: String): RawMesh? {
        val verticesStart = xml.indexOf("<vertices>")
        val verticesEnd = xml.indexOf("</vertices>")
        val trianglesStart = xml.indexOf("<triangles>")
        val trianglesEnd = xml.indexOf("</triangles>")
        if (verticesStart < 0 || trianglesStart < 0) return null

        val verticesXml = xml.substring(verticesStart, verticesEnd + "</vertices>".length)
        val trianglesXml = xml.substring(trianglesStart, trianglesEnd + "</triangles>".length)

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

        return RawMesh(verts, tris)
    }

    /**
     * Parse a 3MF transform string "m00 m01 m02 m10 m11 m12 m20 m21 m22 tx ty tz"
     * into a 4x4 column-major matrix.
     */
    private fun parseTransform(str: String?): FloatArray? {
        if (str == null) return null
        val parts = str.trim().split(Regex("\\s+"))
        if (parts.size != 12) return null
        val v = parts.map { it.toFloatOrNull() ?: return null }
        // 3MF transform is row-major 3x4: [m00 m01 m02 m10 m11 m12 m20 m21 m22 tx ty tz]
        return floatArrayOf(
            v[0], v[3], v[6], 0f,
            v[1], v[4], v[7], 0f,
            v[2], v[5], v[8], 0f,
            v[9], v[10], v[11], 1f
        )
    }

    private fun applyTransform(vertex: FloatArray, transform: FloatArray?): FloatArray {
        if (transform == null) return vertex.copyOf()
        val x = vertex[0]; val y = vertex[1]; val z = vertex[2]
        return floatArrayOf(
            transform[0] * x + transform[4] * y + transform[8] * z + transform[12],
            transform[1] * x + transform[5] * y + transform[9] * z + transform[13],
            transform[2] * x + transform[6] * y + transform[10] * z + transform[14]
        )
    }

    private fun multiplyTransforms(a: FloatArray?, b: FloatArray?): FloatArray? {
        if (a == null) return b
        if (b == null) return a
        val r = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                r[col * 4 + row] = sum
            }
        }
        return r
    }

    private fun buildMeshData(verts: List<FloatArray>, tris: List<IntArray>): MeshData? {
        if (tris.isEmpty()) return null

        val buf = MeshData.allocateBuffer(tris.size)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for (tri in tris) {
            val v0 = verts.getOrNull(tri[0]) ?: continue
            val v1 = verts.getOrNull(tri[1]) ?: continue
            val v2 = verts.getOrNull(tri[2]) ?: continue

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

    /** Legacy fallback for simple 3MF files with a single inline mesh */
    private fun extractMesh(xml: String): MeshData? {
        val mesh = extractRawMesh(xml) ?: return null
        return buildMeshData(mesh.verts, mesh.tris)
    }
}
