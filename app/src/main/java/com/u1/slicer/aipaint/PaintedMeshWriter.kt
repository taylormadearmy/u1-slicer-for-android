package com.u1.slicer.aipaint

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PaintedMeshWriter {

    /**
     * Leaf-triangle paint_color code for a 0-based [slot] (engine paint state = slot + 1).
     * States 1-2 direct (state<<2); states >=3 use the extended escape: rightmost nibble 0xC,
     * next nibble = state-3. Single-nibble extended range covers states 3..18 (slots 2..17).
     *
     * Derived from OrcaSlicer TriangleSelector bitstream format (right-to-left nibble read):
     *   State 1 (≤2, direct): nibble = state<<2 = 4  → "4"
     *   State 2 (≤2, direct): nibble = state<<2 = 8  → "8"
     *   State 3 (extended):   nibble1=0xC, nibble2=0 → rightmost hex='C', next='0' → "0C"
     *   State 4 (extended):   nibble1=0xC, nibble2=1 → rightmost hex='C', next='1' → "1C"
     *   State 5 (extended):   nibble1=0xC, nibble2=2 → "2C"  … up to state 18 → "FC"
     *
     * Verified end-to-end by MixSlotPaintRoundTripTest (native re-read of decoded states).
     */
    fun encodePaintColor(slot: Int): String {
        val state = slot.coerceAtLeast(0) + 1
        return when {
            state <= 2 -> (state shl 2).toString(16).uppercase()
            state - 3 <= 0xF -> "${(state - 3).toString(16).uppercase()}C"
            else -> throw IllegalArgumentException("paint slot $slot exceeds single-nibble range")
        }
    }

    /**
     * @param printerColours when provided, used verbatim for the painted 3MF's `filament_colour`
     *   array. This is what the slicer pipeline reads to populate its canonical filament list,
     *   so passing the user's loaded extruder slot colours makes the Prepare / Preview viewers
     *   render the print in the user's physical filament colours, NOT the AI's suggestions.
     *   When null, the AI region's effectiveColour values are used (legacy / test path).
     */
    fun write(
        positions: FloatArray,
        regionIds: IntArray,
        regions: List<AiRegion>,
        outputFile: File,
        printerColours: List<String>? = null,
        mixDisplayColours: List<String> = emptyList()
    ) {
        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(RELS_XML.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            // B116: stream the model XML directly to the zip stream instead of building
            // a single giant StringBuilder + toString() in memory. An 880k-tri axolotl
            // mesh would have OOM'd allocating ~80 MB for the final String; streaming
            // keeps peak memory bounded to the vertex dedup map (~30-100 MB) plus the
            // small per-line buffer.
            streamModelXml(positions, regionIds, zip)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("Metadata/model_settings.config"))
            zip.write(SETTINGS_XML.toByteArray())
            zip.closeEntry()

            // Bambu/OrcaSlicer canonical filament metadata. Without this file
            // bambuCanonicalList() returns null, getCanonicalFilamentList() falls
            // back to the single-entry STL synthesiser, and the slicer's embedded
            // project_settings.config ends up with filament_colour size 1 → the
            // native paint segmentation collapses to a single tool.
            zip.putNextEntry(ZipEntry("Metadata/project_settings.config"))
            zip.write(buildProjectSettings(regions, printerColours, mixDisplayColours).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES_XML.toByteArray())
            zip.closeEntry()
        }
    }

    /**
     * B116: streams the painted-mesh 3MF model XML directly to [output]. Replaced the
     * earlier `buildModelXml` that materialised the whole document as a StringBuilder
     * then called `toString()` — for an 880k-tri axolotl that allocates ~80 MB twice
     * (StringBuilder buffer + String byte[]) which OOMs the app on Pixel-class
     * devices. Streaming keeps peak memory bounded to the vertex dedup map.
     *
     * Caller is responsible for opening the ZipEntry and closing it after this returns.
     * The writer is flushed before returning so all bytes hit the zip stream.
     */
    internal fun streamModelXml(positions: FloatArray, regionIds: IntArray, output: OutputStream) {
        val nTri = positions.size / 9
        val w = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), 64 * 1024)

        // Dedup vertices using a quantised Long key (1µm precision, ±524 mm) so each entry
        // costs ~32 bytes instead of ~150 bytes per Triple<Float,Float,Float> wrapper +
        // boxed-Float overhead. For an 880k-tri axolotl the LinkedHashMap was burning
        // ~70 MB by itself, eating the budget right before PaintedMeshWriter's String
        // allocation OOM'd. Quantised keys keep the dedup under ~15 MB.
        // Worst case: no shared vertices → nTri * 3 unique. Watertight meshes are typically
        // closer to nTri / 2, but reserve the full capacity to avoid resizing.
        val maxVerts = nTri * 3
        val keyToIndex = HashMap<Long, Int>(maxVerts)
        val orderedKeysX = FloatArray(maxVerts)
        val orderedKeysY = FloatArray(maxVerts)
        val orderedKeysZ = FloatArray(maxVerts)
        var uniqueVertexCount = 0
        val triV1 = IntArray(nTri)
        val triV2 = IntArray(nTri)
        val triV3 = IntArray(nTri)

        fun acquireIndex(x: Float, y: Float, z: Float): Int {
            val key = vertexKey(x, y, z)
            val existing = keyToIndex[key]
            if (existing != null) return existing
            val idx = uniqueVertexCount++
            keyToIndex[key] = idx
            // Grow if our pre-allocated arrays would overflow (rare).
            if (idx >= orderedKeysX.size) {
                throw IllegalStateException("vertex count exceeded pre-allocated buffer: $idx >= ${orderedKeysX.size}")
            }
            orderedKeysX[idx] = x
            orderedKeysY[idx] = y
            orderedKeysZ[idx] = z
            return idx
        }

        for (i in 0 until nTri) {
            val b = i * 9
            triV1[i] = acquireIndex(positions[b],     positions[b + 1], positions[b + 2])
            triV2[i] = acquireIndex(positions[b + 3], positions[b + 4], positions[b + 5])
            triV3[i] = acquireIndex(positions[b + 6], positions[b + 7], positions[b + 8])
        }

        w.write("""<?xml version="1.0" encoding="UTF-8"?>""")
        w.write("\n")
        // xmlns:BambuStudio namespace + Application metadata set m_is_bbl_3mf=true in the
        // native BBS parser so paint_color attributes are fully honoured.
        w.write("""<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:BambuStudio="http://schemas.bambulab.com/package/2021">""")
        w.write("""<metadata name="Application">BambuStudio-2.2.4</metadata>""")
        w.write("\n")
        w.write("""<resources><object id="1" type="model"><mesh>""")
        w.write("\n")

        w.write("<vertices>")
        for (idx in 0 until uniqueVertexCount) {
            w.write("\n  ")
            w.write("""<vertex x="${"%.4f".format(orderedKeysX[idx])}" y="${"%.4f".format(orderedKeysY[idx])}" z="${"%.4f".format(orderedKeysZ[idx])}"/>""")
        }
        w.write("\n</vertices>\n<triangles>")
        for (i in 0 until nTri) {
            val paint = encodePaintColor(regionIds[i])
            w.write("\n  ")
            w.write("""<triangle v1="${triV1[i]}" v2="${triV2[i]}" v3="${triV3[i]}" paint_color="$paint"/>""")
        }
        w.write("\n</triangles></mesh></object></resources>")
        w.write("\n")
        w.write("""<build><item objectid="1"/></build></model>""")
        w.flush()
    }

    /**
     * Builds the Bambu-format JSON used by [bambuCanonicalList][com.u1.slicer.bambu.bambuCanonicalList].
     * We only need filament_colour for canonical-list extraction; filament_type, _settings_id, and
     * filament_count are included so the embedder has sensible defaults to merge user overrides into.
     *
     * When [printerColours] is supplied, the i-th entry in `filament_colour` is taken from
     * `printerColours[i]` (the user's loaded slot colour). Slots with an invalid / missing hex
     * fall back to the AI's effectiveColour for the corresponding region.
     */
    internal fun buildProjectSettings(
        regions: List<AiRegion>,
        printerColours: List<String>? = null,
        mixDisplayColours: List<String> = emptyList()
    ): String {
        // Physical slot colours: one entry per region (existing behaviour). A mix slot
        // referenced by a triangle's paint_color resolves to a filament id beyond the
        // physical count, so the canonical filament_colour list MUST have an entry for
        // each mix too — otherwise the engine's filament list is short and segmentation
        // collapses for any triangle painted to a mix slot.
        val physicalColours = regions.indices.map { i ->
            val printerHex = printerColours?.getOrNull(i)?.let { sanitizeOrNull(it) }
            printerHex ?: sanitizeHex(regions[i].effectiveColour)
        }
        val mixColours = mixDisplayColours.map { sanitizeHex(it) }
        val allColours = physicalColours + mixColours

        val coloursJson = allColours.joinToString(", ") { "\"$it\"" }
        val typesJson = allColours.joinToString(", ") { "\"PLA\"" }
        val settingsIdJson = allColours.joinToString(", ") { "\"Generic PLA\"" }
        val n = allColours.size
        return """{
  "filament_colour": [$coloursJson],
  "filament_type": [$typesJson],
  "filament_settings_id": [$settingsIdJson],
  "filament_count": "$n"
}"""
    }

    /**
     * Pack a vertex position into a single Long for HashMap dedup. 21 bits per axis at
     * 1 µm quantisation covers ±524 mm — well outside the U1's 270×270×270 build plate.
     * Matches the convention used in [com.u1.slicer.aipaint.MeshSegmenter.vertexKey].
     */
    private fun vertexKey(x: Float, y: Float, z: Float): Long {
        val xi = (Math.round(x * 1000f).toLong() + 524_288L).coerceIn(0L, 0x1FFFFFL)
        val yi = (Math.round(y * 1000f).toLong() + 524_288L).coerceIn(0L, 0x1FFFFFL)
        val zi = (Math.round(z * 1000f).toLong() + 524_288L).coerceIn(0L, 0x1FFFFFL)
        return (xi shl 42) or (yi shl 21) or zi
    }

    private fun sanitizeOrNull(hex: String): String? =
        if (HEX_REGEX.matches(hex)) hex else null

    private val HEX_REGEX = Regex("^#[0-9A-Fa-f]{6}$")
    private fun sanitizeHex(hex: String): String =
        if (HEX_REGEX.matches(hex)) hex else "#808080"

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
