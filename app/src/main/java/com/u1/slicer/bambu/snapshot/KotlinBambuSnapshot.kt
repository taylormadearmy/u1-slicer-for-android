package com.u1.slicer.bambu.snapshot

import com.u1.slicer.bambu.ThreeMfParser
import java.io.File
import java.util.zip.ZipFile

/**
 * Composes the existing Kotlin Bambu parsers (currently just [ThreeMfParser] —
 * the other Kotlin helpers in this package either consume a
 * [com.u1.slicer.bambu.ThreeMfInfo] or rewrite the file on disk, so the
 * snapshot-worthy observations come from [ThreeMfParser.parse] plus a light
 * re-read of the 3MF ZIP for fields the parser doesn't surface) into a single
 * [BambuFileSnapshot].
 *
 * Pure observation: **no parser logic changes here.** If a snapshot field has
 * no current Kotlin source — for example `fileVersion` (nowhere in ThreeMfInfo),
 * `filamentSettingsIds` (parser skips the `filament_settings_id` array),
 * `sourcePath` (parser never tracks component `.model` paths per object), and
 * the volumes list (parser does not enumerate per-volume paint state triangle
 * counts) — we return the empty default. The diff harness surfaces those gaps
 * as known disagreements vs the native loader; Phase 1 closes them by deletion.
 *
 * The only fresh parsing in this file is the Bambu `custom_gcode_per_layer.xml`
 * re-read: [ThreeMfParser.parseForPlateSelection] already owns a regex that
 * pulls top_z / type / extruder / color per plate, but its internal data class
 * `LayerToolCustomGcodeXmlInfo` discards `top_z`. Rather than change the parser,
 * we re-read the XML from the ZIP and run the four attribute regexes ourselves
 * to build [CustomGcodeEntry]s with `printZ` populated.
 *
 * Phase 0 only. Phase 1 deletes [ThreeMfParser] once the native loader agrees.
 */
object KotlinBambuSnapshot {

    fun snapshot(file: File): BambuFileSnapshot {
        if (!file.exists() || !file.name.endsWith(".3mf", ignoreCase = true)) {
            return empty(file.name)
        }
        val info = ThreeMfParser.parse(file)
        val customGcodeByPlate = readCustomGcodeByPlate(file)
        val plates = info.plates.map { plate ->
            PlateSnapshot(
                plateIndex = plate.plateId,
                // filamentColours: ThreeMfParser stores detected colours on the top-level
                // ThreeMfInfo, not per plate. For a single-plate file (like the benchy)
                // this is effectively per-plate. For multi-plate files the colours are
                // a file-wide palette — the native loader will emit per-plate colours
                // and the diff harness will flag the disagreement.
                filamentColours = info.detectedColors.toList(),
                // filamentSettingsIds: not parsed by ThreeMfParser. Left empty so the
                // diff harness surfaces the gap.
                filamentSettingsIds = emptyList(),
                // objectInstanceMap: ThreeMfPlate.objectIds is the only per-plate source.
                // Instance IDs are not tracked by the current parser, so we record 0.
                objectInstanceMap = plate.objectIds.mapNotNull { it.toIntOrNull() }
                    .map { ObjectInstance(objectId = it, instanceId = 0) },
                customGcode = customGcodeByPlate[plate.plateId].orEmpty(),
                // plateConfig: Bambu ships per-plate config under
                // Metadata/plate_N.config / plate_N.json, but no Kotlin parser reads
                // those entries into a typed map today. Left empty so the diff harness
                // surfaces the gap.
                plateConfig = emptyMap()
            )
        }
        val objects = info.objects.map { obj ->
            val extruder = info.objectExtruderMap[obj.objectId] ?: 0
            ObjectSnapshot(
                objectId = obj.objectId.toIntOrNull() ?: -1,
                name = obj.name,
                // ObjectSnapshot.extruder is non-null Int with 0 meaning "unset/inherit".
                // ThreeMfParser returns the assigned 1-based extruder or omits the entry;
                // an absent entry maps to 0 here.
                extruder = extruder,
                // sourcePath: ThreeMfParser records a per-object vertex/triangle count
                // but not the component `.model` ZIP entry it came from. Left empty.
                sourcePath = ""
            )
        }
        // Volumes: ThreeMfMeshParser does parse per-triangle paint states when it
        // builds meshes for OpenGL, but that code path needs a live OpenGL renderer
        // and doesn't expose aggregate per-volume paint state counts through a public
        // API. Task 2 leaves the volume list empty — the diff harness will flag this
        // as a known Kotlin gap to be closed by the native loader in later phases.
        val volumes = emptyList<VolumeSnapshot>()

        return BambuFileSnapshot(
            source = file.name,
            // ThreeMfInfo carries no dedicated fileVersion field. Bambu markers drive
            // isBambu; we default fileVersion to empty so the diff harness surfaces
            // the gap vs the native loader (which reads <metadata name="BambuStudio:3mfVersion">).
            isBbl = info.isBambu,
            fileVersion = "",
            plates = plates,
            objects = objects,
            volumes = volumes
        )
    }

    private fun empty(name: String) = BambuFileSnapshot(
        source = name,
        isBbl = false,
        fileVersion = "",
        plates = emptyList(),
        objects = emptyList(),
        volumes = emptyList()
    )

    /**
     * Re-read `Metadata/custom_gcode_per_layer.xml` and split into per-plate
     * [CustomGcodeEntry]s. Mirrors the regexes in
     * [com.u1.slicer.bambu.parseLayerToolCustomGcodeXml] / `parseLayerToolCustomGcodeXmlPerPlate`
     * but preserves `top_z` (which the existing parser discards). This is pure
     * re-derivation — no change to the shipped parser.
     *
     * If the ZIP has no such entry (common for non-Bambu 3MFs or Bambu single-colour
     * files) we return an empty map so callers see empty lists per plate.
     */
    private fun readCustomGcodeByPlate(file: File): Map<Int, List<CustomGcodeEntry>> {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("Metadata/custom_gcode_per_layer.xml")
                    ?: return emptyMap()
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                parseCustomGcodeXmlPerPlate(xml)
            }
        } catch (_: Exception) {
            // Matches the existing parser's swallow-and-return-empty behaviour on
            // malformed XML — we're snapshotting what Kotlin would currently see.
            emptyMap()
        }
    }

    private val plateInfoIdRegex = Regex("""<plate_info\b[^>]*\bid="(\d+)"""")
    private val layerRegex = Regex("""<layer\b([^>]*)>""")
    private val topZRegex = Regex("""\btop_z="([^"]+)"""")
    private val typeRegex = Regex("""\btype="([^"]+)"""")
    private val extruderRegex = Regex("""\bextruder="([^"]+)"""")
    private val colorRegex = Regex("""\bcolor="([^"]+)"""")

    private fun parseCustomGcodeXmlPerPlate(xml: String): Map<Int, List<CustomGcodeEntry>> {
        val out = mutableMapOf<Int, List<CustomGcodeEntry>>()
        // Same section split pattern as parseLayerToolCustomGcodeXmlPerPlate.
        for (section in xml.split("<plate>").drop(1)) {
            val content = section.substringBefore("</plate>")
            val plateId = plateInfoIdRegex.find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val entries = layerRegex.findAll(content).mapNotNull { match ->
                val attrs = match.groupValues[1]
                val type = typeRegex.find(attrs)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                // The existing Kotlin parser only counts type 1 or 2 as tool changes, but
                // the snapshot records every layer entry (so the native loader can be
                // compared on its full emission).
                val topZ = topZRegex.find(attrs)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val extruder = extruderRegex.find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val color = colorRegex.find(attrs)?.groupValues?.getOrNull(1) ?: ""
                CustomGcodeEntry(printZ = topZ, type = type, extruder = extruder, color = color)
            }.toList()
            out[plateId] = entries
        }
        return out
    }
}
