package com.u1.slicer.viewer

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.sqrt

/**
 * Parses mesh data from 3MF files for OpenGL preview rendering.
 * Uses line-by-line streaming with indexOf-based attribute parsing —
 * never loads the full XML into memory, but parses as fast as the old
 * readText() approach by avoiding XmlPullParser's per-attribute String allocations.
 *
 * Memory for Baby Dragon Egg (146MB XML, 1.7M triangles):
 *   Old: ~268MB (full XML string)
 *   New: ~40MB (vertex + triangle arrays only)
 */
object ThreeMfMeshParser {

    private data class RawMesh(
        val verts: FloatArray,
        val tris: IntArray,
        val vertexCount: Int,
        val triCount: Int,
        /** Per-triangle paint extruder index, or null if no paint data. 0xFF = unpainted. */
        val paintIndices: ByteArray? = null
    )

    private data class BuildItem(val objectId: String, val transform: FloatArray?, val printable: Boolean = true)
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
    private data class MeshWithContext(
        val mesh: RawMesh,
        val transform: FloatArray?,
        val objectId: Int,
        val printable: Boolean = true
    )

    private data class ModelParseResult(
        val buildItems: List<BuildItem>,
        val objects: Map<String, ObjectInfo>
    )

    fun parse(file: File, extruderMap: Map<Int, Byte>? = null): MeshData? {
        Log.d("ThreeMfMesh", "Parsing ${file.name} (${file.length() / 1024}KB)")
        ZipFile(file).use { zip ->
            val modelEntry = zip.getEntry("3D/3dmodel.model") ?: run {
                Log.w("ThreeMfMesh", "No 3D/3dmodel.model in ZIP")
                return null
            }

            val result = zip.getInputStream(modelEntry).use { streamParseModel(it) }
            val buildItems = filterBuildItems(result.buildItems)
            val mainObjects = result.objects
            Log.d("ThreeMfMesh", "Build items: ${buildItems.size}, main objects: ${mainObjects.size}")

            val meshList = mutableListOf<MeshWithContext>()
            val componentCache = mutableMapOf<String, Map<String, ObjectInfo>>()

            for (item in buildItems) {
                val obj = mainObjects[item.objectId]
                Log.d("ThreeMfMesh", "Build item objectId=${item.objectId} -> obj=${obj != null}, hasMesh=${obj?.mesh != null}, components=${obj?.components?.size}")
                if (obj != null) {
                    try {
                        collectMeshes(obj, item.transform, item.objectId.toIntOrNull() ?: 0, mainObjects, zip, meshList, componentCache, item.printable)
                    } catch (t: Throwable) {
                        Log.e("ThreeMfMesh", "collectMeshes failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
            Log.d("ThreeMfMesh", "Collected ${meshList.size} mesh segments")

            if (meshList.isEmpty()) {
                val firstCompPath = mainObjects.values
                    .flatMap { it.components }
                    .firstOrNull { it.path != null }?.path
                if (firstCompPath != null) {
                    val compEntry = zip.getEntry(firstCompPath) ?: return null
                    Log.d("ThreeMfMesh", "Legacy fallback: streaming ${firstCompPath} (${compEntry.size / 1_000_000}MB)")
                    val compResult = zip.getInputStream(compEntry).use { streamParseModel(it) }
                    val compMesh = compResult.objects.values.firstOrNull()?.mesh
                    if (compMesh != null) {
                        return buildMeshData(listOf(MeshWithContext(compMesh, null, 0)), extruderMap)
                    }
                }
                return null
            }

            return buildMeshData(meshList, extruderMap)
        }
    }

    /**
     * Stream-parse a 3MF model XML line-by-line using indexOf-based attribute extraction.
     * 3MF XML has one tag per line for vertex/triangle data, so this is both
     * memory-efficient (one line in memory at a time) and fast (no XML parser overhead).
     */
    private fun streamParseModel(input: InputStream): ModelParseResult {
        val reader = input.bufferedReader()
        val objects = mutableMapOf<String, ObjectInfo>()
        val buildItems = mutableListOf<BuildItem>()

        var currentObjId: String? = null
        var currentComponents = mutableListOf<ComponentRef>()
        var vertList: GrowableFloatArray? = null
        var triList: GrowableIntArray? = null
        var paintList: GrowableByteArray? = null
        var hasPaintData = false
        var inVertices = false
        var inTriangles = false
        var inBuild = false

        reader.forEachLine { line ->
            // Fast path: mesh data lines are the vast majority (>99.9%)
            // Check for the most common tags first to minimize indexOf calls
            if (inVertices) {
                if (line.contains("<vertex")) {
                    parseVertexLine(line, vertList!!)
                    return@forEachLine
                }
                if (line.contains("</vertices>")) {
                    inVertices = false
                    return@forEachLine
                }
            }
            if (inTriangles) {
                if (line.contains("<triangle")) {
                    parseTriangleLine(line, triList!!)
                    // Fast-path paint_color / mmu_segmentation extraction:
                    // only check if the line contains the attribute (99.9%+ lines won't)
                    if (line.contains("paint_color")) {
                        val idx = parsePaintIndex(line, "paint_color")
                        if (idx >= 0) {
                            if (paintList == null) {
                                // Back-fill previously parsed triangles with 0xFF (unpainted)
                                paintList = GrowableByteArray(triList!!.size / 3)
                                repeat(triList!!.size / 3 - 1) { paintList!!.add(0xFF.toByte()) }
                            }
                            hasPaintData = true
                            paintList!!.add(idx.toByte())
                        } else {
                            paintList?.add(0xFF.toByte())
                        }
                    } else if (line.contains("mmu_segmentation")) {
                        val idx = parsePaintIndex(line, "mmu_segmentation")
                        if (idx >= 0) {
                            if (paintList == null) {
                                paintList = GrowableByteArray(triList!!.size / 3)
                                repeat(triList!!.size / 3 - 1) { paintList!!.add(0xFF.toByte()) }
                            }
                            hasPaintData = true
                            paintList!!.add(idx.toByte())
                        } else {
                            paintList?.add(0xFF.toByte())
                        }
                    } else {
                        paintList?.add(0xFF.toByte())
                    }
                    return@forEachLine
                }
                if (line.contains("</triangles>")) {
                    inTriangles = false
                    return@forEachLine
                }
            }

            // Structural tags — infrequent
            if (line.contains("<object")) {
                currentObjId = extractAttr(line, "id")
                currentComponents = mutableListOf()
                vertList = null
                triList = null
            } else if (line.contains("</object>")) {
                val id = currentObjId
                if (id != null) {
                    val mesh = if (vertList != null && triList != null &&
                        vertList!!.size >= 3 && triList!!.size >= 3) {
                        val vArr = vertList!!.toArray()
                        val tArr = triList!!.toArray()
                        val pArr = if (hasPaintData && paintList != null) paintList!!.toArray() else null
                        RawMesh(vArr, tArr, vArr.size / 3, tArr.size / 3, pArr)
                    } else null
                    objects[id] = ObjectInfo(id, mesh, currentComponents.toList())
                    vertList = null
                    triList = null
                    paintList = null
                    hasPaintData = false
                }
                currentObjId = null
            } else if (line.contains("<vertices>")) {
                inVertices = true
                if (vertList == null) vertList = GrowableFloatArray()
            } else if (line.contains("<triangles>")) {
                inTriangles = true
                if (triList == null) triList = GrowableIntArray()
            } else if (line.contains("<component") && currentObjId != null) {
                val objId = extractAttr(line, "objectid")
                if (objId != null) {
                    val path = extractAttr(line, "p:path")?.trimStart('/')
                        ?: extractAttr(line, "path")?.trimStart('/')
                    val transform = extractAttr(line, "transform")
                    currentComponents.add(ComponentRef(objId, path, parseTransform(transform)))
                }
            } else if (line.contains("<build")) {
                inBuild = true
            } else if (line.contains("</build>")) {
                inBuild = false
            } else if (inBuild && line.contains("<item")) {
                val objId = extractAttr(line, "objectid")
                if (objId != null) {
                    val isPrintable = extractAttr(line, "printable") != "0"
                    val transform = extractAttr(line, "transform")
                    buildItems.add(BuildItem(objId, parseTransform(transform), isPrintable))
                }
            }
        }

        return ModelParseResult(buildItems, objects)
    }

    /** Extract attribute value from an XML tag line: `attrName="value"` → `value` */
    private fun extractAttr(line: String, name: String): String? {
        val prefix = "$name=\""
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val valStart = start + prefix.length
        val valEnd = line.indexOf('"', valStart)
        if (valEnd < 0) return null
        return line.substring(valStart, valEnd)
    }

    /** Parse x/y/z float attributes directly from a `<vertex .../>` line. */
    private fun parseVertexLine(line: String, verts: GrowableFloatArray) {
        val x = extractFloatAttr(line, "x=\"") ?: return
        val y = extractFloatAttr(line, "y=\"") ?: return
        val z = extractFloatAttr(line, "z=\"") ?: return
        verts.add(x); verts.add(y); verts.add(z)
    }

    /** Parse v1/v2/v3 int attributes directly from a `<triangle .../>` line. */
    private fun parseTriangleLine(line: String, tris: GrowableIntArray) {
        val v1 = extractIntAttr(line, "v1=\"") ?: return
        val v2 = extractIntAttr(line, "v2=\"") ?: return
        val v3 = extractIntAttr(line, "v3=\"") ?: return
        tris.add(v1); tris.add(v2); tris.add(v3)
    }

    /**
     * Extract the dominant extruder index from a paint_color or mmu_segmentation attribute value.
     *
     * OrcaSlicer's TriangleSelector encodes states as: NONE=0, Extruder1=1, Extruder2=2, …
     * Simple whole-triangle assignments are "NC" where N is the state digit and C is the leaf
     * marker. For complex subdivisions, the first character approximates the dominant state.
     *
     * Returns the 0-based extruder index (state - 1), or -1 if the triangle is unpainted
     * (state == 0 / NONE) or the attribute is absent.
     */
    internal fun parsePaintIndex(line: String, attrName: String): Int {
        val prefix = "$attrName=\""
        val start = line.indexOf(prefix)
        if (start < 0) return -1
        val valStart = start + prefix.length
        val valEnd = line.indexOf('"', valStart)
        if (valEnd <= valStart) return -1
        val firstChar = line[valStart]
        val state = when {
            firstChar in '0'..'9' -> firstChar - '0'
            firstChar in 'A'..'Z' -> firstChar - 'A' + 10
            else -> return -1
        }
        // State 0 = NONE (unpainted) → fall back to volume-level extruder assignment
        if (state == 0) return -1
        // H2C models use two AMS trays (states 1–4 = AMS1, states 5–8 = AMS2) where slot N
        // on each tray holds the same physical filament. Fold AMS2 states back to AMS1 range
        // so state 5 → index 0 (same as state 1), state 6 → 1, etc.
        // For standard ≤4-extruder models this is a no-op (states never exceed 4).
        return (state - 1) % 4
    }

    /** Parse a float attribute value inline without creating a substring. */
    private fun extractFloatAttr(line: String, prefix: String): Float? {
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val valStart = start + prefix.length
        val valEnd = line.indexOf('"', valStart)
        if (valEnd < 0) return null
        return parseFloatInline(line, valStart, valEnd)
    }

    /** Parse an int attribute value inline without creating a substring. */
    private fun extractIntAttr(line: String, prefix: String): Int? {
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val valStart = start + prefix.length
        val valEnd = line.indexOf('"', valStart)
        if (valEnd < 0) return null
        return parseIntInline(line, valStart, valEnd)
    }

    /** Parse float in s[start..<end] without creating a substring. Handles sign, decimal, exponent. */
    private fun parseFloatInline(s: String, start: Int, end: Int): Float {
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

    /** Parse int in s[start..<end] without creating a substring. */
    private fun parseIntInline(s: String, start: Int, end: Int): Int {
        var result = 0
        for (i in start until end) {
            val c = s[i]
            if (c in '0'..'9') result = result * 10 + (c - '0')
        }
        return result
    }

    private fun filterBuildItems(items: List<BuildItem>): List<BuildItem> {
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

    private fun collectMeshes(
        obj: ObjectInfo,
        parentTransform: FloatArray?,
        objectId: Int,
        mainObjects: Map<String, ObjectInfo>,
        zip: ZipFile,
        meshList: MutableList<MeshWithContext>,
        componentCache: MutableMap<String, Map<String, ObjectInfo>>,
        printable: Boolean = true
    ) {
        if (obj.mesh != null) {
            meshList.add(MeshWithContext(obj.mesh, parentTransform, objectId, printable))
        }
        for (comp in obj.components) {
            val combinedTransform = multiplyTransforms(parentTransform, comp.transform)
            val compObjectId = comp.objectId.toIntOrNull() ?: objectId
            if (comp.path != null) {
                Log.d("ThreeMfMesh", "  Component path=${comp.path} objectId=${comp.objectId} (cached=${componentCache.containsKey(comp.path)})")
                val extObjects = componentCache.getOrPut(comp.path) {
                    val entry = zip.getEntry(comp.path)
                    if (entry == null) {
                        Log.w("ThreeMfMesh", "  Entry not found: ${comp.path}")
                        return@getOrPut emptyMap()
                    }
                    Log.d("ThreeMfMesh", "  Stream-parsing ${comp.path} (${entry.size / 1_000_000}MB uncompressed)")
                    val compResult = zip.getInputStream(entry).use { streamParseModel(it) }
                    compResult.objects
                }
                val extObj = extObjects[comp.objectId]
                if (extObj != null) {
                    collectMeshes(extObj, combinedTransform, compObjectId, extObjects, zip, meshList, componentCache, printable)
                } else {
                    Log.w("ThreeMfMesh", "  Object ${comp.objectId} not found in component (have: ${extObjects.keys})")
                }
            } else {
                val refObj = mainObjects[comp.objectId]
                if (refObj != null) {
                    collectMeshes(refObj, combinedTransform, compObjectId, mainObjects, zip, meshList, componentCache, printable)
                }
            }
        }
    }

    private fun parseTransform(str: String?): FloatArray? {
        if (str == null) return null
        val parts = str.trim().split(Regex("\\s+"))
        if (parts.size != 12) return null
        val v = parts.map { it.toFloatOrNull() ?: return null }
        // 3MF spec: 12 values are m00 m01 m02 m10 m11 m12 m20 m21 m22 tx ty tz
        // representing a row-vector transform: [x' y' z' 1] = [x y z 1] × M
        // where M = | m00 m01 m02 0 |
        //           | m10 m11 m12 0 |
        //           | m20 m21 m22 0 |
        //           | tx  ty  tz  1 |
        // Store as row-major blocks so buildMeshData's t[0]*x+t[4]*y+t[8]*z+t[12]
        // correctly computes x' = m00*x + m10*y + m20*z + tx
        return floatArrayOf(
            v[0], v[1], v[2], 0f,
            v[3], v[4], v[5], 0f,
            v[6], v[7], v[8], 0f,
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
     * Merges paint data for H2C (Hybrid 2-Color) model pairs.
     *
     * H2C models have two identical meshes: one printable (AMS2 states) and one non-printable
     * (AMS1 states) with complementary NONE regions. We merge their paint indices so the single
     * rendered mesh shows complete coverage: non-NONE paint from either object wins for each triangle.
     *
     * Matching criterion: one printable + one non-printable with the same triCount, both having
     * paint data. Non-printable meshes without paint data are dropped (support structures etc.).
     */
    private fun mergeH2cPairs(meshes: List<MeshWithContext>): List<MeshWithContext> {
        val printables = meshes.filter { it.printable }
        val nonPrintables = meshes.filter { !it.printable && it.mesh.paintIndices != null }
        if (nonPrintables.isEmpty()) return printables  // nothing to merge

        // Index non-printable meshes by triCount for O(1) lookup
        val nonPrintableByCount = nonPrintables.groupBy { it.mesh.triCount }
        val result = mutableListOf<MeshWithContext>()
        val mergedCounts = mutableSetOf<Int>()

        for (pm in printables) {
            val npm = nonPrintableByCount[pm.mesh.triCount]
                ?.takeIf { pm.mesh.paintIndices != null }
                ?.firstOrNull()
            if (npm != null && !mergedCounts.contains(pm.mesh.triCount)) {
                mergedCounts.add(pm.mesh.triCount)
                val pi1 = pm.mesh.paintIndices!!
                val pi2 = npm.mesh.paintIndices!!
                val merged = ByteArray(pi1.size) { i ->
                    val v1 = pi1[i].toInt() and 0xFF
                    val v2 = if (i < pi2.size) pi2[i].toInt() and 0xFF else 0xFF
                    when {
                        v1 != 0xFF -> pi1[i]  // printable has paint → use it
                        v2 != 0xFF -> pi2[i]  // non-printable has paint → use it
                        else -> 0.toByte()    // both NONE (bed face etc.) → fallback to extruder 0
                    }
                }
                Log.d("ThreeMfMesh", "H2C merge: ${pm.mesh.triCount} tris, merged printable+non-printable paint data")
                result.add(pm.copy(mesh = pm.mesh.copy(paintIndices = merged)))
            } else {
                result.add(pm)
            }
        }
        return result
    }

    private fun buildMeshData(
        meshes: List<MeshWithContext>,
        extruderIndexMap: Map<Int, Byte>? = null
    ): MeshData? {
        val meshes = mergeH2cPairs(meshes)
        val totalTris = meshes.sumOf { it.mesh.triCount }
        if (totalTris == 0) return null

        val buf = MeshData.allocateBuffer(totalTris)
        val extruderIdxArray = GrowableByteArray(totalTris)
        var minX = Float.MAX_VALUE;  var minY = Float.MAX_VALUE;  var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for (meshCtx in meshes) {
            val mesh = meshCtx.mesh
            val t = meshCtx.transform
            val volumeExtruderIdx: Byte = extruderIndexMap?.get(meshCtx.objectId) ?: 0
            val paintIndices = mesh.paintIndices
            val verts = mesh.verts
            val tris = mesh.tris
            val nTris = mesh.triCount

            for (i in 0 until nTris) {
                // Paint data wins over volume-level extruder index
                val extruderIdx: Byte = if (paintIndices != null && i < paintIndices.size) {
                    val paintVal = paintIndices[i].toInt() and 0xFF
                    if (paintVal != 0xFF) paintVal.toByte() else volumeExtruderIdx
                } else volumeExtruderIdx
                val a = tris[i * 3] * 3
                val b = tris[i * 3 + 1] * 3
                val c = tris[i * 3 + 2] * 3
                if (a + 2 >= verts.size || b + 2 >= verts.size || c + 2 >= verts.size) continue

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

                // Position + normal + default RGBA color (10 floats per vertex)
                buf.put(ax); buf.put(ay); buf.put(az); buf.put(nx); buf.put(ny); buf.put(nz)
                buf.put(0.7f); buf.put(0.7f); buf.put(0.7f); buf.put(1.0f)
                buf.put(bx); buf.put(by); buf.put(bz); buf.put(nx); buf.put(ny); buf.put(nz)
                buf.put(0.7f); buf.put(0.7f); buf.put(0.7f); buf.put(1.0f)
                buf.put(cx); buf.put(cy); buf.put(cz); buf.put(nx); buf.put(ny); buf.put(nz)
                buf.put(0.7f); buf.put(0.7f); buf.put(0.7f); buf.put(1.0f)

                extruderIdxArray.add(extruderIdx)

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
        return MeshData(buf, vertexCount, minX, minY, minZ, maxX, maxY, maxZ,
            extruderIndices = extruderIdxArray.toArray())
    }

    // ── Growable primitive array helpers (avoid boxing overhead of ArrayList<Float/Int>) ──

    private class GrowableFloatArray(initialCapacity: Int = 8192) {
        private var data = FloatArray(initialCapacity)
        var size = 0; private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    private class GrowableIntArray(initialCapacity: Int = 8192) {
        private var data = IntArray(initialCapacity)
        var size = 0; private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }

    private class GrowableByteArray(initialCapacity: Int = 8192) {
        private var data = ByteArray(initialCapacity)
        var size = 0; private set

        fun add(value: Byte) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): ByteArray = data.copyOf(size)
    }
}
