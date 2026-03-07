package com.u1.slicer.viewer

import android.util.Log
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.sqrt

/**
 * Parses mesh data from 3MF files for OpenGL preview rendering.
 * Uses indexOf-based parsing (no regex) and flat arrays to minimise allocations
 * and avoid GC pressure when processing large models (500K+ triangles).
 */
object ThreeMfMeshParser {

    // Flat arrays: verts = [x0,y0,z0, x1,y1,z1, ...], tris = [i0,i1,i2, ...]
    private data class RawMesh(
        val verts: FloatArray,
        val tris: IntArray,
        val vertexCount: Int,
        val triCount: Int
    )

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

    fun parse(file: File): MeshData? {
        Log.d("ThreeMfMesh", "Parsing ${file.name} (${file.length() / 1024}KB)")
        ZipFile(file).use { zip ->
            val modelEntry = zip.getEntry("3D/3dmodel.model") ?: run {
                Log.w("ThreeMfMesh", "No 3D/3dmodel.model in ZIP")
                return null
            }
            val modelXml = zip.getInputStream(modelEntry).bufferedReader().readText()
            Log.d("ThreeMfMesh", "Main model XML: ${modelXml.length / 1024}KB")

            val buildItems = parseBuildItems(modelXml)
            val mainObjects = parseObjects(modelXml)
            Log.d("ThreeMfMesh", "Build items: ${buildItems.size}, main objects: ${mainObjects.size}")

            val meshList = mutableListOf<Pair<RawMesh, FloatArray?>>()
            // Cache parsed objects per component path to avoid re-parsing the same file
            val componentCache = mutableMapOf<String, Map<String, ObjectInfo>>()

            for (item in buildItems) {
                val obj = mainObjects[item.objectId]
                Log.d("ThreeMfMesh", "Build item objectId=${item.objectId} -> obj=${obj != null}, hasMesh=${obj?.mesh != null}, components=${obj?.components?.size}")
                if (obj != null) {
                    try {
                        collectMeshes(obj, item.transform, mainObjects, zip, meshList, componentCache)
                    } catch (t: Throwable) {
                        Log.e("ThreeMfMesh", "collectMeshes failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
            Log.d("ThreeMfMesh", "Collected ${meshList.size} mesh segments")

            if (meshList.isEmpty()) {
                // Legacy fallback for simple 3MF files
                val raw = extractRawMesh(modelXml) ?: run {
                    val componentPath = Regex("""p:path="([^"]*\.model)"""").find(modelXml)
                        ?.groupValues?.get(1)?.trimStart('/')
                    if (componentPath != null) {
                        val compEntry = zip.getEntry(componentPath) ?: return null
                        val compXml = zip.getInputStream(compEntry).bufferedReader().readText()
                        extractRawMesh(compXml)
                    } else null
                } ?: return null
                return buildMeshData(listOf(Pair(raw, null)))
            }

            return buildMeshData(meshList)
        }
    }

    private fun parseBuildItems(xml: String): List<BuildItem> {
        val items = mutableListOf<BuildItem>()
        val regex = Regex("""<item\s+([^>]+)>""")
        for (match in regex.findAll(xml)) {
            val attrs = match.groupValues[1]
            val printable = Regex("""printable="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            if (printable == "0") continue
            val objId = Regex("""objectid="(\d+)"""").find(attrs)?.groupValues?.get(1) ?: continue
            val transform = Regex("""transform="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            items.add(BuildItem(objId, parseTransform(transform)))
        }
        if (items.size > 1) {
            val txVals = items.mapNotNull { it.transform?.get(12) }
            val tyVals = items.mapNotNull { it.transform?.get(13) }
            val spreadX = if (txVals.size > 1) txVals.max() - txVals.min() else 0f
            val spreadY = if (tyVals.size > 1) tyVals.max() - tyVals.min() else 0f
            if (spreadX > 270f || spreadY > 270f) {
                Log.d("ThreeMfMesh", "Multi-plate source detected (spread ${spreadX.toInt()}x${spreadY.toInt()}mm), showing plate 1 only")
                return items.take(1)
            }
        }
        return items
    }

    // O(n) indexOf-based object parser — avoids catastrophic backtracking of (.*?) regex on large XML
    private fun parseObjects(xml: String): Map<String, ObjectInfo> {
        val objects = mutableMapOf<String, ObjectInfo>()
        val openTag = "<object"
        val closeTag = "</object>"
        var pos = 0
        while (pos < xml.length) {
            val objStart = xml.indexOf(openTag, pos)
            if (objStart < 0) break
            val tagEnd = xml.indexOf('>', objStart)
            if (tagEnd < 0) break
            val tag = xml.substring(objStart, tagEnd + 1)
            val id = Regex("""id="(\d+)"""").find(tag)?.groupValues?.get(1)
            if (id == null) { pos = tagEnd + 1; continue }
            val bodyStart = tagEnd + 1
            val bodyEnd = xml.indexOf(closeTag, bodyStart)
            if (bodyEnd < 0) break
            val body = xml.substring(bodyStart, bodyEnd)
            val mesh = extractRawMesh(body)
            val components = parseComponents(body)
            objects[id] = ObjectInfo(id, mesh, components)
            pos = bodyEnd + closeTag.length
        }
        return objects
    }

    private fun parseComponents(xml: String): List<ComponentRef> {
        val comps = mutableListOf<ComponentRef>()
        val regex = Regex("""<component\b([^>]*)""")
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
        meshList: MutableList<Pair<RawMesh, FloatArray?>>,
        componentCache: MutableMap<String, Map<String, ObjectInfo>>
    ) {
        if (obj.mesh != null) {
            meshList.add(Pair(obj.mesh, parentTransform))
        }
        for (comp in obj.components) {
            val combinedTransform = multiplyTransforms(parentTransform, comp.transform)
            if (comp.path != null) {
                Log.d("ThreeMfMesh", "  Component path=${comp.path} objectId=${comp.objectId} (cached=${componentCache.containsKey(comp.path)})")
                val extObjects = componentCache.getOrPut(comp.path) {
                    val entry = zip.getEntry(comp.path)
                    if (entry == null) {
                        Log.w("ThreeMfMesh", "  Entry not found: ${comp.path}")
                        return@getOrPut emptyMap()
                    }
                    Log.d("ThreeMfMesh", "  Reading+parsing ${comp.path} (${entry.compressedSize / 1024}KB compressed)")
                    parseObjects(zip.getInputStream(entry).bufferedReader().readText())
                }
                val extObj = extObjects[comp.objectId]
                if (extObj != null) {
                    collectMeshes(extObj, combinedTransform, extObjects, zip, meshList, componentCache)
                } else {
                    Log.w("ThreeMfMesh", "  Object ${comp.objectId} not found in component (have: ${extObjects.keys})")
                }
            } else {
                val refObj = mainObjects[comp.objectId]
                if (refObj != null) {
                    collectMeshes(refObj, combinedTransform, mainObjects, zip, meshList, componentCache)
                }
            }
        }
    }

    /**
     * Parse vertices and triangles using indexOf — no regex, no per-vertex allocations.
     * Uses flat FloatArray/IntArray instead of List<FloatArray>/List<IntArray>.
     */
    private fun extractRawMesh(xml: String): RawMesh? {
        val verticesStart = xml.indexOf("<vertices>")
        val verticesEnd = xml.indexOf("</vertices>")
        val trianglesStart = xml.indexOf("<triangles>")
        val trianglesEnd = xml.indexOf("</triangles>")
        if (verticesStart < 0 || verticesEnd < 0 || trianglesStart < 0 || trianglesEnd < 0) return null

        val vertCount = countTag(xml, "<vertex", verticesStart, verticesEnd)
        val triCount = countTag(xml, "<triangle", trianglesStart, trianglesEnd)
        if (vertCount == 0 || triCount == 0) return null

        val verts = FloatArray(vertCount * 3)
        var vi = 0
        var pos = verticesStart
        while (vi < vertCount * 3) {
            val xq1 = xml.indexOf("x=\"", pos); if (xq1 < 0 || xq1 >= verticesEnd) break; val xqS = xq1 + 3
            val xq2 = xml.indexOf('"', xqS); if (xq2 < 0) break
            val yq1 = xml.indexOf("y=\"", xq2); if (yq1 < 0 || yq1 >= verticesEnd) break; val yqS = yq1 + 3
            val yq2 = xml.indexOf('"', yqS); if (yq2 < 0) break
            val zq1 = xml.indexOf("z=\"", yq2); if (zq1 < 0 || zq1 >= verticesEnd) break; val zqS = zq1 + 3
            val zq2 = xml.indexOf('"', zqS); if (zq2 < 0) break
            verts[vi++] = parseFloat(xml, xqS, xq2)
            verts[vi++] = parseFloat(xml, yqS, yq2)
            verts[vi++] = parseFloat(xml, zqS, zq2)
            pos = zq2 + 1
        }
        val actualVertCount = vi / 3

        val tris = IntArray(triCount * 3)
        var ti = 0
        pos = trianglesStart
        while (ti < triCount * 3) {
            val v1q1 = xml.indexOf("v1=\"", pos); if (v1q1 < 0 || v1q1 >= trianglesEnd) break; val v1qS = v1q1 + 4
            val v1q2 = xml.indexOf('"', v1qS); if (v1q2 < 0) break
            val v2q1 = xml.indexOf("v2=\"", v1q2); if (v2q1 < 0 || v2q1 >= trianglesEnd) break; val v2qS = v2q1 + 4
            val v2q2 = xml.indexOf('"', v2qS); if (v2q2 < 0) break
            val v3q1 = xml.indexOf("v3=\"", v2q2); if (v3q1 < 0 || v3q1 >= trianglesEnd) break; val v3qS = v3q1 + 4
            val v3q2 = xml.indexOf('"', v3qS); if (v3q2 < 0) break
            tris[ti++] = parseInt(xml, v1qS, v1q2)
            tris[ti++] = parseInt(xml, v2qS, v2q2)
            tris[ti++] = parseInt(xml, v3qS, v3q2)
            pos = v3q2 + 1
        }
        val actualTriCount = ti / 3
        if (actualVertCount == 0 || actualTriCount == 0) return null

        return RawMesh(
            if (actualVertCount == vertCount) verts else verts.copyOf(actualVertCount * 3),
            if (actualTriCount == triCount) tris else tris.copyOf(actualTriCount * 3),
            actualVertCount,
            actualTriCount
        )
    }

    private fun countTag(s: String, tag: String, from: Int, to: Int): Int {
        var count = 0; var pos = from
        while (true) {
            pos = s.indexOf(tag, pos)
            if (pos < 0 || pos >= to) break
            count++; pos += tag.length
        }
        return count
    }

    /** Parse int in s[start..<end] without creating a substring. */
    private fun parseInt(s: String, start: Int, end: Int): Int {
        var result = 0
        for (i in start until end) {
            val c = s[i]
            if (c in '0'..'9') result = result * 10 + (c - '0')
        }
        return result
    }

    /** Parse float in s[start..<end] without creating a substring. Handles sign, decimal, exponent. */
    private fun parseFloat(s: String, start: Int, end: Int): Float {
        var intPart = 0L; var fracPart = 0L; var fracDivisor = 1L
        var negative = false; var inFrac = false; var inExp = false
        var expSign = 1; var exp = 0
        var i = start
        if (i < end && s[i] == '-') { negative = true; i++ }
        while (i < end) {
            when (val c = s[i]) {
                in '0'..'9' -> {
                    val d = c - '0'
                    when {
                        inExp -> exp = exp * 10 + d
                        inFrac -> { fracPart = fracPart * 10 + d; fracDivisor *= 10 }
                        else -> intPart = intPart * 10 + d
                    }
                }
                '.' -> inFrac = true
                'e', 'E' -> inExp = true
                '+' -> { /* skip */ }
                '-' -> if (inExp) expSign = -1
            }
            i++
        }
        var result = intPart.toDouble() + fracPart.toDouble() / fracDivisor.toDouble()
        if (negative) result = -result
        if (inExp && exp > 0) {
            var scale = 1.0; repeat(exp) { scale *= 10 }
            result = if (expSign > 0) result * scale else result / scale
        }
        return result.toFloat()
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
        return floatArrayOf(
            v[0], v[3], v[6], 0f,
            v[1], v[4], v[7], 0f,
            v[2], v[5], v[8], 0f,
            v[9], v[10], v[11], 1f
        )
    }

    private fun multiplyTransforms(a: FloatArray?, b: FloatArray?): FloatArray? {
        if (a == null) return b
        if (b == null) return a
        val r = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                r[col * 4 + row] = sum
            }
        }
        return r
    }

    /**
     * Build a MeshData buffer from a list of (RawMesh, transform) pairs.
     * Applies transforms and computes face normals in a single pass with no intermediate allocations.
     */
    private fun buildMeshData(meshes: List<Pair<RawMesh, FloatArray?>>): MeshData? {
        val totalTris = meshes.sumOf { it.first.triCount }
        if (totalTris == 0) return null

        val buf = MeshData.allocateBuffer(totalTris)
        var minX = Float.MAX_VALUE;  var minY = Float.MAX_VALUE;  var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for ((mesh, transform) in meshes) {
            val verts = mesh.verts
            val tris = mesh.tris
            val nTris = mesh.triCount
            val t = transform

            for (i in 0 until nTris) {
                val a = tris[i * 3] * 3
                val b = tris[i * 3 + 1] * 3
                val c = tris[i * 3 + 2] * 3
                if (a + 2 >= verts.size || b + 2 >= verts.size || c + 2 >= verts.size) continue

                // Apply transform inline — no FloatArray allocation per vertex
                val ax: Float; val ay: Float; val az: Float
                val bx: Float; val by: Float; val bz: Float
                val cx: Float; val cy: Float; val cz: Float

                if (t == null) {
                    ax = verts[a];   ay = verts[a+1]; az = verts[a+2]
                    bx = verts[b];   by = verts[b+1]; bz = verts[b+2]
                    cx = verts[c];   cy = verts[c+1]; cz = verts[c+2]
                } else {
                    val va0 = verts[a]; val va1 = verts[a+1]; val va2 = verts[a+2]
                    val vb0 = verts[b]; val vb1 = verts[b+1]; val vb2 = verts[b+2]
                    val vc0 = verts[c]; val vc1 = verts[c+1]; val vc2 = verts[c+2]
                    ax = t[0]*va0+t[4]*va1+t[8]*va2+t[12]; ay = t[1]*va0+t[5]*va1+t[9]*va2+t[13]; az = t[2]*va0+t[6]*va1+t[10]*va2+t[14]
                    bx = t[0]*vb0+t[4]*vb1+t[8]*vb2+t[12]; by = t[1]*vb0+t[5]*vb1+t[9]*vb2+t[13]; bz = t[2]*vb0+t[6]*vb1+t[10]*vb2+t[14]
                    cx = t[0]*vc0+t[4]*vc1+t[8]*vc2+t[12]; cy = t[1]*vc0+t[5]*vc1+t[9]*vc2+t[13]; cz = t[2]*vc0+t[6]*vc1+t[10]*vc2+t[14]
                }

                val ux = bx-ax; val uy = by-ay; val uz = bz-az
                val vx = cx-ax; val vy = cy-ay; val vz = cz-az
                var nx = uy*vz-uz*vy; var ny = uz*vx-ux*vz; var nz = ux*vy-uy*vx
                val len = sqrt(nx*nx+ny*ny+nz*nz)
                if (len > 0) { nx /= len; ny /= len; nz /= len }

                buf.put(ax); buf.put(ay); buf.put(az); buf.put(nx); buf.put(ny); buf.put(nz)
                buf.put(bx); buf.put(by); buf.put(bz); buf.put(nx); buf.put(ny); buf.put(nz)
                buf.put(cx); buf.put(cy); buf.put(cz); buf.put(nx); buf.put(ny); buf.put(nz)

                if (ax < minX) minX = ax; if (ay < minY) minY = ay; if (az < minZ) minZ = az
                if (ax > maxX) maxX = ax; if (ay > maxY) maxY = ay; if (az > maxZ) maxZ = az
                if (bx < minX) minX = bx; if (by < minY) minY = by; if (bz < minZ) minZ = bz
                if (bx > maxX) maxX = bx; if (by > maxY) maxY = by; if (bz > maxZ) maxZ = bz
                if (cx < minX) minX = cx; if (cy < minY) minY = cy; if (cz < minZ) minZ = cz
                if (cx > maxX) maxX = cx; if (cy > maxY) maxY = cy; if (cz > maxZ) maxZ = cz
            }
        }
        buf.flip()
        val vertexCount = buf.limit() / MeshData.FLOATS_PER_VERTEX
        if (vertexCount == 0) return null
        return MeshData(buf, vertexCount, minX, minY, minZ, maxX, maxY, maxZ)
    }
}
