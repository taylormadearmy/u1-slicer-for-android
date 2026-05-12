package com.u1.slicer.aipaint

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PaintedMeshWriter {

    // Leaf-triangle paint_color encoding for states 1–4 (extruders 1–4).
    // Derived from OrcaSlicer TriangleSelector bitstream format (right-to-left nibble read):
    //   State 1 (≤2, direct): nibble = state<<2 = 4  → "4"
    //   State 2 (≤2, direct): nibble = state<<2 = 8  → "8"
    //   State 3 (extended):   nibble1=0xC, nibble2=0 → rightmost hex='C', next='0' → "0C"
    //   State 4 (extended):   nibble1=0xC, nibble2=1 → rightmost hex='C', next='1' → "1C"
    private val PAINT_COLOR = arrayOf("4", "8", "0C", "1C")  // index = region 0..3

    fun write(
        positions: FloatArray,
        regionIds: IntArray,
        @Suppress("UNUSED_PARAMETER")
        regions: List<AiRegion>,
        outputFile: File
    ) {
        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(RELS_XML.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(buildModelXml(positions, regionIds).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("Metadata/model_settings.config"))
            zip.write(SETTINGS_XML.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES_XML.toByteArray())
            zip.closeEntry()
        }
    }

    private fun buildModelXml(positions: FloatArray, regionIds: IntArray): String {
        val nTri = positions.size / 9
        val sb = StringBuilder(nTri * 120)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">""")
        sb.append("""<resources><object id="1" type="model"><mesh>""")

        val vertexMap = LinkedHashMap<Triple<Float, Float, Float>, Int>(nTri * 2)
        val triVerts = Array(nTri) { i ->
            val b = i * 9
            val v0 = Triple(positions[b],   positions[b + 1], positions[b + 2])
            val v1 = Triple(positions[b + 3], positions[b + 4], positions[b + 5])
            val v2 = Triple(positions[b + 6], positions[b + 7], positions[b + 8])
            Triple(
                vertexMap.getOrPut(v0) { vertexMap.size },
                vertexMap.getOrPut(v1) { vertexMap.size },
                vertexMap.getOrPut(v2) { vertexMap.size }
            )
        }

        sb.append("<vertices>")
        vertexMap.keys.forEach { (x, y, z) ->
            sb.append("""<vertex x="${"%.4f".format(x)}" y="${"%.4f".format(y)}" z="${"%.4f".format(z)}"/>""")
        }
        sb.append("</vertices><triangles>")
        triVerts.forEachIndexed { i, (a, b, c) ->
            val paint = PAINT_COLOR[regionIds[i].coerceIn(0, 3)]
            sb.append("""<triangle v1="$a" v2="$b" v3="$c" paint_color="$paint"/>""")
        }
        sb.append("</triangles></mesh></object></resources>")
        sb.append("""<build><item objectid="1"/></build></model>""")
        return sb.toString()
    }

    // Minimal settings to trigger the native BBS parser path (which reads paint_color).
    private val SETTINGS_XML =
        """<?xml version="1.0" encoding="UTF-8"?><config><object id="1"><metadata type="object" key="extruder" value="1"/></object></config>"""

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
