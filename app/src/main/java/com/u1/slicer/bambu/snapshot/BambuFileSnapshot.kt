package com.u1.slicer.bambu.snapshot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshot of the facts both the Kotlin parsers and the C++ loader should agree on.
 * Boring on purpose: no derived fields, no interpretation. Phase 0 diff harness
 * compares two of these per fixture; Phase 1 deletes Kotlin parsers whose snapshot
 * agrees with the native one.
 */
data class BambuFileSnapshot(
    val source: String,
    val isBbl: Boolean,
    val fileVersion: String,
    val plates: List<PlateSnapshot>,
    val objects: List<ObjectSnapshot>,
    val volumes: List<VolumeSnapshot>
)

data class PlateSnapshot(
    val plateIndex: Int,
    val filamentColours: List<String>,
    val filamentSettingsIds: List<String>,
    val objectInstanceMap: List<ObjectInstance>,
    val customGcode: List<CustomGcodeEntry>,
    val plateConfig: Map<String, String>
)

data class ObjectInstance(val objectId: Int, val instanceId: Int)

data class CustomGcodeEntry(
    val printZ: Double,
    val type: String,
    val extruder: Int,
    val color: String
)

data class ObjectSnapshot(
    val objectId: Int,
    val name: String,
    val extruder: Int,
    val sourcePath: String
)

data class VolumeSnapshot(
    val objectId: Int,
    val volumeIndex: Int,
    val extruder: Int?,
    val paintStateSet: Map<Int, Int>,
    val paintSupportsStateSet: Map<Int, Int>,
    val isMmPainted: Boolean,
    val isSeamPainted: Boolean
)

object BambuFileSnapshotJson {

    fun encode(snapshot: BambuFileSnapshot): String {
        val root = JSONObject()
        root.put("source", snapshot.source)
        root.put("isBbl", snapshot.isBbl)
        root.put("fileVersion", snapshot.fileVersion)
        root.put("plates", JSONArray().apply { snapshot.plates.forEach { put(encodePlate(it)) } })
        root.put("objects", JSONArray().apply { snapshot.objects.forEach { put(encodeObject(it)) } })
        root.put("volumes", JSONArray().apply { snapshot.volumes.forEach { put(encodeVolume(it)) } })
        return root.toString()
    }

    fun decode(json: String): BambuFileSnapshot {
        val root = JSONObject(json)
        return BambuFileSnapshot(
            source = root.optString("source", ""),
            isBbl = root.optBoolean("isBbl", false),
            fileVersion = root.optString("fileVersion", ""),
            plates = root.optJSONArray("plates").toList(::decodePlate),
            objects = root.optJSONArray("objects").toList(::decodeObject),
            volumes = root.optJSONArray("volumes").toList(::decodeVolume)
        )
    }

    private fun encodePlate(p: PlateSnapshot) = JSONObject().apply {
        put("plateIndex", p.plateIndex)
        put("filamentColours", JSONArray(p.filamentColours))
        put("filamentSettingsIds", JSONArray(p.filamentSettingsIds))
        put("objectInstanceMap", JSONArray().apply {
            p.objectInstanceMap.forEach { put(JSONObject().put("objectId", it.objectId).put("instanceId", it.instanceId)) }
        })
        put("customGcode", JSONArray().apply {
            p.customGcode.forEach { e ->
                put(JSONObject()
                    .put("printZ", e.printZ)
                    .put("type", e.type)
                    .put("extruder", e.extruder)
                    .put("color", e.color))
            }
        })
        put("plateConfig", JSONObject(p.plateConfig as Map<*, *>))
    }

    private fun decodePlate(o: JSONObject) = PlateSnapshot(
        plateIndex = o.optInt("plateIndex"),
        filamentColours = o.optJSONArray("filamentColours").toStringList(),
        filamentSettingsIds = o.optJSONArray("filamentSettingsIds").toStringList(),
        objectInstanceMap = o.optJSONArray("objectInstanceMap").toList { ObjectInstance(it.optInt("objectId"), it.optInt("instanceId")) },
        customGcode = o.optJSONArray("customGcode").toList {
            CustomGcodeEntry(it.optDouble("printZ"), it.optString("type"), it.optInt("extruder"), it.optString("color"))
        },
        plateConfig = o.optJSONObject("plateConfig").toStringMap()
    )

    private fun encodeObject(obj: ObjectSnapshot) = JSONObject()
        .put("objectId", obj.objectId)
        .put("name", obj.name)
        .put("extruder", obj.extruder)
        .put("sourcePath", obj.sourcePath)

    private fun decodeObject(o: JSONObject) = ObjectSnapshot(
        objectId = o.optInt("objectId"),
        name = o.optString("name"),
        extruder = o.optInt("extruder"),
        sourcePath = o.optString("sourcePath")
    )

    private fun encodeVolume(v: VolumeSnapshot) = JSONObject().apply {
        put("objectId", v.objectId)
        put("volumeIndex", v.volumeIndex)
        if (v.extruder != null) put("extruder", v.extruder) else put("extruder", JSONObject.NULL)
        put("paintStateSet", JSONObject().apply { v.paintStateSet.forEach { (k, n) -> put(k.toString(), n) } })
        put("paintSupportsStateSet", JSONObject().apply { v.paintSupportsStateSet.forEach { (k, n) -> put(k.toString(), n) } })
        put("isMmPainted", v.isMmPainted)
        put("isSeamPainted", v.isSeamPainted)
    }

    private fun decodeVolume(o: JSONObject): VolumeSnapshot {
        val ex = if (o.isNull("extruder")) null else o.optInt("extruder")
        return VolumeSnapshot(
            objectId = o.optInt("objectId"),
            volumeIndex = o.optInt("volumeIndex"),
            extruder = ex,
            paintStateSet = o.optJSONObject("paintStateSet").toIntIntMap(),
            paintSupportsStateSet = o.optJSONObject("paintSupportsStateSet").toIntIntMap(),
            isMmPainted = o.optBoolean("isMmPainted"),
            isSeamPainted = o.optBoolean("isSeamPainted")
        )
    }

    private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { map(getJSONObject(it)) }
    }
    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }
    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        keys().forEach { out[it] = getString(it) }
        return out
    }
    private fun JSONObject?.toIntIntMap(): Map<Int, Int> {
        if (this == null) return emptyMap()
        val out = mutableMapOf<Int, Int>()
        keys().forEach { out[it.toInt()] = getInt(it) }
        return out
    }
}
