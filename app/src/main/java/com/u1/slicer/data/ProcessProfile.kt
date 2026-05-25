package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * F87: An imported OrcaSlicer process profile. Stored verbatim as a flat key→value map so the
 * values can be spilled directly into the [com.u1.slicer.bambu.ProfileEmbedder] config map.
 *
 * Values are kept as `String` (scalar) or `List<String>` (array) to match the on-disk OrcaSlicer
 * format and the [ProfileEmbedder]'s existing config map shape (`MutableMap<String, Any>`).
 *
 * Process profiles are user-imported (Settings → Process Profiles → Import JSON). They are
 * not editable in-app — users re-import to update.
 */
data class ProcessProfile(
    val id: String,
    val name: String,
    /** Original file/profile name, retained for display when the user has renamed the profile. */
    val sourceName: String?,
    val keys: Map<String, Any>
)

/**
 * Persisted list of imported process profiles + the currently active id.
 * Stored as a single JSON blob in DataStore (modelled on [PrintersConfig]).
 */
data class ProcessProfilesConfig(
    val profiles: List<ProcessProfile> = emptyList(),
    val activeId: String? = null
) {
    val active: ProcessProfile? get() = profiles.firstOrNull { it.id == activeId }

    companion object {
        fun toJson(cfg: ProcessProfilesConfig): String {
            val root = JSONObject()
            val arr = JSONArray()
            cfg.profiles.forEach { p -> arr.put(profileToJson(p)) }
            root.put("profiles", arr)
            cfg.activeId?.let { root.put("activeId", it) }
            return root.toString()
        }

        fun fromJson(json: String): ProcessProfilesConfig {
            return try {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("profiles") ?: JSONArray()
                val profiles = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { profileFromJson(it) }
                }
                ProcessProfilesConfig(
                    profiles = profiles,
                    activeId = if (obj.has("activeId")) obj.optString("activeId").takeIf { it.isNotBlank() } else null
                )
            } catch (_: Exception) {
                ProcessProfilesConfig()
            }
        }

        fun profileToJson(p: ProcessProfile): JSONObject {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            p.sourceName?.let { obj.put("sourceName", it) }
            val keysObj = JSONObject()
            for ((k, v) in p.keys) {
                when (v) {
                    is List<*> -> {
                        val arr = JSONArray()
                        v.forEach { arr.put(it?.toString() ?: "") }
                        keysObj.put(k, arr)
                    }
                    else -> keysObj.put(k, v.toString())
                }
            }
            obj.put("keys", keysObj)
            return obj
        }

        fun profileFromJson(obj: JSONObject): ProcessProfile? {
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
            val sourceName = if (obj.has("sourceName")) obj.optString("sourceName").takeIf { it.isNotBlank() } else null
            val keysObj = obj.optJSONObject("keys") ?: JSONObject()
            val keys = mutableMapOf<String, Any>()
            for (k in keysObj.keys()) {
                when (val v = keysObj.opt(k)) {
                    is JSONArray -> {
                        val list = mutableListOf<String>()
                        for (i in 0 until v.length()) list.add(v.optString(i, ""))
                        keys[k] = list
                    }
                    null -> { /* skip */ }
                    else -> keys[k] = v.toString()
                }
            }
            return ProcessProfile(id, name, sourceName, keys)
        }
    }
}
