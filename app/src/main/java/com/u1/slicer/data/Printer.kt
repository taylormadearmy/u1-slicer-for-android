package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One configured U1 printer. Persisted as part of [PrintersConfig] in DataStore.
 * The id is a stable UUID generated at create time so renames don't break references.
 */
data class Printer(
    val id: String,
    val nickname: String,
    val moonrakerUrl: String,
    val extruderPresets: List<ExtruderPreset> = defaultExtruderPresets(),
) {
    companion object {
        fun toJsonObject(p: Printer): JSONObject = JSONObject().apply {
            put("id", p.id)
            put("nickname", p.nickname)
            put("moonrakerUrl", p.moonrakerUrl)
            put("extruderPresets", JSONArray(serializeExtruderPresets(p.extruderPresets)))
        }

        fun fromJsonObject(obj: JSONObject): Printer = Printer(
            id = obj.getString("id"),
            nickname = obj.getString("nickname"),
            moonrakerUrl = obj.getString("moonrakerUrl"),
            extruderPresets = parseExtruderPresetsExact(
                obj.optJSONArray("extruderPresets") ?: JSONArray()
            ),
        )

        /**
         * Deserialise extruder presets exactly as stored — no slot-filling.
         * The fill-to-4 behaviour in [parseExtruderPresets] is a UI concern; [Printer]
         * stores whatever the user configured.
         */
        private fun parseExtruderPresetsExact(arr: JSONArray): List<ExtruderPreset> {
            if (arr.length() == 0) return defaultExtruderPresets()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ExtruderPreset(
                    index = o.getInt("index"),
                    color = o.optString("color", "#FFFFFF"),
                    materialType = o.optString("materialType", "PLA"),
                    filamentProfileId = if (o.has("filamentProfileId")) o.getLong("filamentProfileId") else null,
                )
            }
        }
    }
}

/**
 * The list of all configured printers plus which one is currently active.
 * Invariants (enforced by the constructor):
 *  - printers must be non-empty
 *  - activeId must reference an id in printers
 */
data class PrintersConfig(
    val printers: List<Printer>,
    val activeId: String,
) {
    init {
        require(printers.isNotEmpty()) { "PrintersConfig requires at least one printer" }
        require(printers.any { it.id == activeId }) {
            "PrintersConfig activeId='$activeId' is not present in printers list"
        }
    }

    val active: Printer get() = printers.first { it.id == activeId }

    companion object {
        fun toJson(cfg: PrintersConfig): String = JSONObject().apply {
            val arr = JSONArray()
            cfg.printers.forEach { arr.put(Printer.toJsonObject(it)) }
            put("printers", arr)
            put("activeId", cfg.activeId)
        }.toString()

        fun fromJson(json: String): PrintersConfig {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("printers")
            val list = (0 until arr.length()).map { Printer.fromJsonObject(arr.getJSONObject(it)) }
            return PrintersConfig(printers = list, activeId = obj.getString("activeId"))
        }
    }
}
