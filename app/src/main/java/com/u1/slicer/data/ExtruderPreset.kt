package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents the filament loaded in one extruder slot on the printer.
 * Persisted locally in DataStore (4 slots for Snapmaker U1).
 * Color is set per-printer on the printer page; filament profiles handle material settings.
 */
data class ExtruderPreset(
    val index: Int,                     // 0-based (E1=0, E2=1, E3=2, E4=3)
    val color: String = DEFAULT_COLORS[0], // "#RRGGBB" — physical filament color
    val materialType: String = "PLA",   // "PLA", "PETG", "ABS", "TPU", …
    val filamentProfileId: Long? = null // optional link to FilamentProfile for settings
) {
    val label: String get() = "E${index + 1}"

    companion object {
        /** Default colours: Red, Green, Blue, White — distinct for multi-colour testing. */
        val DEFAULT_COLORS = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF")
    }
}

fun defaultExtruderPresets(): List<ExtruderPreset> =
    (0..3).map { ExtruderPreset(it, color = ExtruderPreset.DEFAULT_COLORS[it]) }

fun serializeExtruderPresets(presets: List<ExtruderPreset>): String {
    val arr = JSONArray()
    presets.forEach { p ->
        arr.put(JSONObject().apply {
            put("index", p.index)
            put("color", p.color)
            put("materialType", p.materialType)
            p.filamentProfileId?.let { put("filamentProfileId", it) }
        })
    }
    return arr.toString()
}

fun parseExtruderPresets(json: String): List<ExtruderPreset> {
    if (json.isBlank()) return defaultExtruderPresets()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ExtruderPreset(
                index = obj.getInt("index"),
                color = obj.optString("color", "#FFFFFF"),
                materialType = obj.optString("materialType", "PLA"),
                filamentProfileId = if (obj.has("filamentProfileId")) obj.getLong("filamentProfileId") else null
            )
        }.also { list ->
            // Ensure all 4 slots are present, filling defaults for missing ones
            val indices = list.map { it.index }.toSet()
            val full = list.toMutableList()
            (0..3).forEach { i -> if (i !in indices) full.add(ExtruderPreset(i)) }
            return full.sortedBy { it.index }.take(4)
        }
    } catch (_: Exception) {
        defaultExtruderPresets()
    }
}
