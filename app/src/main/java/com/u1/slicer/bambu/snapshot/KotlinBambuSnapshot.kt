package com.u1.slicer.bambu.snapshot

import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.ThreeMfParser
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Composes the existing Kotlin Bambu parsers (currently just [ThreeMfParser])
 * with a native walk of g_model for the volumes list into a single
 * [BambuFileSnapshot]. Phase 1 sub-plan #1 moved the volumes field from
 * Kotlin-parsed-empty to native-sourced via five thin JNI accessors on
 * [NativeLibrary]; plates / objects / custom gcode remain Kotlin-parsed
 * until later sub-plans take them.
 *
 * Phase 0 only pure-Kotlin. Phase 1 sub-plan #1 adds the native volumes path.
 * Later sub-plans will delete [ThreeMfParser] entirely.
 */
object KotlinBambuSnapshot {

    /**
     * Holds native-sourced blocks read under one previewMutex+loadModel scope.
     * Sub-plan #1 added volumes; sub-plan #5 added projectConfig; sub-plan #2
     * added plates.
     */
    private data class NativeData(
        val volumes: List<VolumeSnapshot>,
        val projectConfig: ProjectConfig?,
        val plates: List<NativePlate>?,  // null when loadModel failed
        val objects: List<ObjectSnapshot>?,  // null when loadModel failed
    )

    private data class ProjectConfig(
        val isBbl: Boolean,
        val fileVersion: String,
        val filamentColours: List<String>,
        val filamentSettingsIds: List<String>,
        val filamentIds: List<String>,
    )

    private data class NativePlate(
        val plateIndex: Int,
        val filamentColours: List<String>,
        val filamentSettingsIds: List<String>,
        val objectInstanceMap: List<ObjectInstance>,
        val customGcode: List<CustomGcodeEntry>,
        val plateConfig: Map<String, String>,
    )

    /**
     * Bambu object IDs are always numeric integers in well-formed files. Non-numeric IDs
     * indicate a malformed file; we surface them as `-1` rather than silently dropping
     * so the diff harness flags the malformation instead of hiding it.
     */
    private fun parseObjectId(s: String): Int = s.toIntOrNull() ?: -1

    /**
     * Builds a snapshot combining Kotlin-parsed plates/objects/custom-gcode
     * with a native walk of g_model for the volumes list and project config.
     *
     * Suspend because [NativeLibrary.previewMutex] is a coroutine Mutex and
     * the native reads need exclusive access for loadModel + per-volume and
     * project-config accessor calls. Callers in instrumented tests wrap with
     * `runBlocking { }`.
     */
    suspend fun snapshot(file: File, native: NativeLibrary): BambuFileSnapshot {
        if (!file.exists() || !file.name.endsWith(".3mf", ignoreCase = true)) {
            return empty(file.name)
        }
        val info = ThreeMfParser.parse(file)
        val customGcodeByPlate = readCustomGcodeByPlate(file)
        val nativeData = readNativeData(file, native)

        // Sub-plan #2: plates sourced from native PlateData via nativeGetPlateData.
        // When loadModel fails (corrupt 3MF), fall back to the Kotlin
        // ThreeMfParser-derived plate list + project palette so the snapshot is
        // still populated.
        val plates: List<PlateSnapshot> = nativeData.plates?.map { np ->
            PlateSnapshot(
                plateIndex = np.plateIndex,
                filamentColours = np.filamentColours,
                filamentSettingsIds = np.filamentSettingsIds,
                objectInstanceMap = np.objectInstanceMap,
                customGcode = np.customGcode,
                plateConfig = np.plateConfig,
            )
        } ?: info.plates.map { plate ->
            val fallbackColours = nativeData.projectConfig?.filamentColours.orEmpty()
            val fallbackSettingsIds = nativeData.projectConfig?.filamentSettingsIds.orEmpty()
            PlateSnapshot(
                plateIndex = plate.plateId,
                filamentColours = fallbackColours,
                filamentSettingsIds = fallbackSettingsIds,
                objectInstanceMap = plate.objectIds
                    .map { ObjectInstance(objectId = parseObjectId(it), instanceId = 0) },
                customGcode = customGcodeByPlate[plate.plateId].orEmpty(),
                plateConfig = emptyMap(),
            )
        }
        // Sub-plan #4: objects sourced from native g_model.objects via
        // nativeGetObjectExtruderMap. Kotlin's previous path filtered <object>
        // elements to those with > 0 inline vertices and missed component-ref
        // merges; native walks every object after Slic3r's import-time merge.
        // Runtime ObjectID fills ObjectSnapshot.objectId and is NOT the XML id;
        // production code keeps reading ThreeMfInfo.objectExtruderMap for that.
        val objects: List<ObjectSnapshot> = nativeData.objects ?: info.objects.map { obj ->
            val extruder = info.objectExtruderMap[obj.objectId] ?: 0
            ObjectSnapshot(
                objectId = parseObjectId(obj.objectId),
                name = obj.name,
                extruder = extruder,
                sourcePath = "",
            )
        }

        return BambuFileSnapshot(
            source = file.name,
            // Sub-plan #5: isBbl sourced from g_is_bbl via JNI; fall back to the
            // Kotlin ZIP-marker detection when native loadModel failed (corrupt file).
            isBbl = nativeData.projectConfig?.isBbl ?: info.isBambu,
            // Sub-plan #5: fileVersion from g_file_version.to_string() via JNI.
            fileVersion = nativeData.projectConfig?.fileVersion ?: "",
            plates = plates,
            objects = objects,
            volumes = nativeData.volumes,
        )
    }

    /**
     * Walks g_model via the Phase 1 JNI accessors to produce the volumes list
     * and project-level config. Holds [NativeLibrary.previewMutex] across the
     * whole loadModel + accessor sequence so no concurrent
     * setModelRotation / getPreparePreviewMesh caller races the read.
     *
     * Returns [NativeData] with empty volumes and null projectConfig if loadModel
     * fails — a corrupt 3MF surfaces as "no native data" rather than blowing up
     * the whole snapshot.
     */
    private suspend fun readNativeData(
        file: File,
        native: NativeLibrary,
    ): NativeData = NativeLibrary.previewMutex.withLock {
        if (!native.loadModel(file.absolutePath)) {
            return@withLock NativeData(
                volumes = emptyList(),
                projectConfig = null,
                plates = null,
                objects = null,
            )
        }
        val objectCount = native.nativeGetObjectCount()
        val volumes = buildList {
            for (oi in 0 until objectCount) {
                // VolumeSnapshot.objectId is Int. Slic3r ObjectID is size_t;
                // truncation is safe in practice (IDs start at ~1 and increment).
                val objectModelId = native.nativeGetObjectModelId(oi).toInt()
                val volumeCount = native.nativeGetVolumeCount(oi)
                for (vi in 0 until volumeCount) {
                    val scalars = native.nativeGetVolumeScalars(oi, vi) ?: continue
                    val extruder = if (scalars[0] == -1) null else scalars[0]
                    val mmPacked = native.nativeGetPaintStateCounts(oi, vi, 0) ?: intArrayOf()
                    val supPacked = native.nativeGetPaintStateCounts(oi, vi, 1) ?: intArrayOf()
                    add(
                        VolumeSnapshot(
                            objectId = objectModelId,
                            volumeIndex = vi,
                            extruder = extruder,
                            paintStateSet = unpackStateCounts(mmPacked),
                            paintSupportsStateSet = unpackStateCounts(supPacked),
                            isMmPainted = scalars[1] != 0,
                            isSeamPainted = scalars[2] != 0,
                        )
                    )
                }
            }
        }
        val projectConfig = parseProjectConfig(native.nativeGetProjectConfig())
        val plateCount = native.nativeGetPlateCount()
        val plates = buildList<NativePlate> {
            for (pi in 0 until plateCount) {
                val json = native.nativeGetPlateData(pi) ?: continue
                parseNativePlate(json)?.let { add(it) }
            }
        }
        val objects = parseObjectArray(native.nativeGetObjectExtruderMap())
        NativeData(
            volumes = volumes,
            projectConfig = projectConfig,
            plates = plates,
            objects = objects,
        )
    }

    private fun parseObjectArray(json: String?): List<ObjectSnapshot>? {
        if (json.isNullOrEmpty()) return null
        return try {
            val arr = org.json.JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.optJSONObject(i) ?: return@List ObjectSnapshot(
                    objectId = -1, name = "", extruder = 0, sourcePath = ""
                )
                ObjectSnapshot(
                    // Runtime ObjectID (size_t) arrives as a JSON number; Int
                    // truncation matches VolumeSnapshot.objectId's Int contract
                    // established by sub-plan #1.
                    objectId = o.optLong("objectId", -1L).toInt(),
                    name = o.optString("name", ""),
                    extruder = o.optInt("extruder", 0),
                    sourcePath = o.optString("sourcePath", ""),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseProjectConfig(json: String?): ProjectConfig? {
        if (json.isNullOrEmpty()) return null
        return try {
            val obj = JSONObject(json)
            ProjectConfig(
                isBbl = obj.optBoolean("isBbl", false),
                fileVersion = obj.optString("fileVersion", ""),
                filamentColours = readStringArray(obj, "filamentColours"),
                filamentSettingsIds = readStringArray(obj, "filamentSettingsIds"),
                filamentIds = readStringArray(obj, "filamentIds"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readStringArray(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { arr.optString(it, "") }
    }

    private fun parseNativePlate(json: String): NativePlate? {
        return try {
            val obj = JSONObject(json)
            NativePlate(
                plateIndex = obj.optInt("plateIndex", -1),
                filamentColours = readStringArray(obj, "filamentColours"),
                filamentSettingsIds = readStringArray(obj, "filamentSettingsIds"),
                objectInstanceMap = readObjectInstanceMap(obj),
                customGcode = readCustomGcodeArray(obj),
                plateConfig = readPlateConfig(obj),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readObjectInstanceMap(obj: JSONObject): List<ObjectInstance> {
        val arr = obj.optJSONArray("objectInstanceMap") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.optJSONObject(i)
            ObjectInstance(
                objectId = o?.optInt("objectId", -1) ?: -1,
                instanceId = o?.optInt("instanceId", 0) ?: 0,
            )
        }
    }

    private fun readCustomGcodeArray(obj: JSONObject): List<CustomGcodeEntry> {
        val arr = obj.optJSONArray("customGcode") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.optJSONObject(i)
            CustomGcodeEntry(
                printZ = o?.optDouble("printZ", 0.0) ?: 0.0,
                type = o?.optString("type", "") ?: "",
                extruder = o?.optInt("extruder", 0) ?: 0,
                color = o?.optString("color", "") ?: "",
            )
        }
    }

    private fun readPlateConfig(obj: JSONObject): Map<String, String> {
        val o = obj.optJSONObject("plateConfig") ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            result[k] = o.optString(k, "")
        }
        return result
    }

    private fun unpackStateCounts(packed: IntArray): Map<Int, Int> {
        if (packed.isEmpty()) return emptyMap()
        require(packed.size % 2 == 0) { "packed paint-state counts must be even-length" }
        val out = LinkedHashMap<Int, Int>(packed.size / 2)
        var i = 0
        while (i < packed.size) { out[packed[i]] = packed[i + 1]; i += 2 }
        return out
    }

    private fun empty(name: String) = BambuFileSnapshot(
        source = name,
        isBbl = false,
        fileVersion = "",
        plates = emptyList(),
        objects = emptyList(),
        volumes = emptyList(),
    )

    /**
     * Re-read `Metadata/custom_gcode_per_layer.xml` and split into per-plate
     * [CustomGcodeEntry]s. Mirrors the regexes in
     * [com.u1.slicer.bambu.parseLayerToolCustomGcodeXml] /
     * `parseLayerToolCustomGcodeXmlPerPlate` but preserves `top_z` (which the
     * existing parser discards). This is pure re-derivation — no change to
     * the shipped parser.
     *
     * If the ZIP has no such entry (common for non-Bambu 3MFs or Bambu
     * single-colour files) we return an empty map so callers see empty lists
     * per plate.
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
        for (section in xml.split("<plate>").drop(1)) {
            val content = section.substringBefore("</plate>")
            val plateId = plateInfoIdRegex.find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val entries = layerRegex.findAll(content).mapNotNull { match ->
                val attrs = match.groupValues[1]
                val type = typeRegex.find(attrs)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                val topZ = topZRegex.find(attrs)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    ?: return@mapNotNull null
                val extruder = extruderRegex.find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val color = colorRegex.find(attrs)?.groupValues?.getOrNull(1) ?: ""
                CustomGcodeEntry(printZ = topZ, type = type, extruder = extruder, color = color)
            }.toList()
            out[plateId] = entries
        }
        return out
    }
}
