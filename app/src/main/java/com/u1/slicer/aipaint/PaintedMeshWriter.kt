package com.u1.slicer.aipaint

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PaintedMeshWriter {

    fun write(
        positions: FloatArray,
        regionIds: IntArray,
        @Suppress("UNUSED_PARAMETER")
        regions: List<AiRegion>,
        outputFile: File
    ) {
        val nTri = positions.size / 9
        val groups = Array(4) { mutableListOf<Int>() }
        for (i in 0 until nTri) groups[regionIds[i].coerceIn(0, 3)].add(i)
        val nonEmptyIndices = groups.indices.filter { groups[it].isNotEmpty() }

        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(RELS_XML.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(buildModelXml(positions, groups, nonEmptyIndices).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("Metadata/model_settings.config"))
            zip.write(buildSettingsXml(nonEmptyIndices).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES_XML.toByteArray())
            zip.closeEntry()
        }
    }

    private fun buildModelXml(
        positions: FloatArray,
        groups: Array<MutableList<Int>>,
        nonEmptyIndices: List<Int>
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">""")
        sb.append("<resources>")

        nonEmptyIndices.forEachIndexed { slot, regionIdx ->
            val objectId = slot + 1
            val triIndices = groups[regionIdx]
            sb.append("""<object id="$objectId" type="model"><mesh>""")

            val vertexMap = LinkedHashMap<Triple<Float,Float,Float>, Int>()
            val triangleVertexIds = mutableListOf<Triple<Int,Int,Int>>()

            for (triIdx in triIndices) {
                val b = triIdx * 9
                val v0 = Triple(positions[b],   positions[b+1], positions[b+2])
                val v1 = Triple(positions[b+3], positions[b+4], positions[b+5])
                val v2 = Triple(positions[b+6], positions[b+7], positions[b+8])
                val i0 = vertexMap.getOrPut(v0) { vertexMap.size }
                val i1 = vertexMap.getOrPut(v1) { vertexMap.size }
                val i2 = vertexMap.getOrPut(v2) { vertexMap.size }
                triangleVertexIds.add(Triple(i0, i1, i2))
            }

            sb.append("<vertices>")
            vertexMap.keys.forEach { (x,y,z) ->
                sb.append("""<vertex x="${"%.4f".format(x)}" y="${"%.4f".format(y)}" z="${"%.4f".format(z)}"/>""")
            }
            sb.append("</vertices><triangles>")
            triangleVertexIds.forEach { (a,b,c) ->
                sb.append("""<triangle v1="$a" v2="$b" v3="$c"/>""")
            }
            sb.append("</triangles></mesh></object>")
        }

        sb.append("</resources><build>")
        nonEmptyIndices.indices.forEach { i -> sb.append("""<item objectid="${i+1}"/>""") }
        sb.append("</build></model>")
        return sb.toString()
    }

    private fun buildSettingsXml(regionIndices: List<Int>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?><config>""")
        regionIndices.forEachIndexed { slot, regionIdx ->
            val objectId = slot + 1
            val extruder = regionIdx + 1
            sb.append("""<object id="$objectId"><metadata type="object" key="extruder" value="$extruder"/></object>""")
        }
        sb.append("</config>")
        return sb.toString()
    }

    private val RELS_XML = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>"""

    private val CONTENT_TYPES_XML = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>"""
}
