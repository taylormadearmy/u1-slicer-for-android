package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * F87: Parses OrcaSlicer process profile JSON into [ProcessProfile] objects.
 *
 * Accepts three shapes:
 *  1. Single profile object: `{ "type": "process", "name": "...", "layer_height": "0.16", ... }`
 *  2. Array of profile objects: `[{ ... }, { ... }]`
 *  3. Wrapper object: `{ "process_profiles": [...] }`
 *
 * Keys that are not legitimate process-profile settings (provenance, machine identity, etc.) are
 * dropped so they don't accidentally clobber the user's printer settings on slice.
 */
object ProcessProfileParser {

    /**
     * Top-level metadata keys we do NOT carry into the embed map. These are profile-provenance
     * fields that OrcaSlicer uses for its own bookkeeping and would interfere with the slice if
     * we propagated them blindly.
     */
    private val DROP_KEYS = setOf(
        "type", "name", "from", "instantiation", "inherits", "inherits_group",
        "version", "setting_id", "filament_id", "print_id", "compatible_printers",
        "compatible_printers_condition", "compatible_prints", "compatible_prints_condition",
        "is_custom_defined", "renamed_from", "different_settings_to_system",
        "printer_settings_id", "print_settings_id", "filament_settings_id",
        "upward_compatible_machine"
    )

    /**
     * Parse [json], producing zero or more [ProcessProfile] objects. Each profile gets a fresh
     * UUID id. The [fallbackName] is used when an object has no `name` key (rare).
     *
     * Returns an empty list when the JSON has no recognisable process objects. Throws only on
     * truly unparseable JSON.
     */
    fun parse(json: String, fallbackName: String): List<ProcessProfile> {
        val trimmed = json.trim()

        // Array of profiles
        val arr: JSONArray? = try { JSONArray(trimmed) } catch (_: JSONException) { null }
        if (arr != null) {
            return (0 until arr.length()).mapNotNull { i ->
                val obj = try { arr.getJSONObject(i) } catch (_: Exception) { null } ?: return@mapNotNull null
                parseOne(obj, "$fallbackName #${i + 1}")
            }
        }

        val obj = try { JSONObject(trimmed) } catch (_: JSONException) {
            throw IllegalArgumentException("Could not parse file as JSON")
        }

        // Wrapper { "process_profiles": [...] }
        if (obj.has("process_profiles")) {
            val inner = obj.optJSONArray("process_profiles") ?: return emptyList()
            return (0 until inner.length()).mapNotNull { i ->
                val item = try { inner.getJSONObject(i) } catch (_: Exception) { null } ?: return@mapNotNull null
                parseOne(item, "$fallbackName #${i + 1}")
            }
        }

        // Single profile object
        return listOfNotNull(parseOne(obj, fallbackName))
    }

    private fun parseOne(obj: JSONObject, fallbackName: String): ProcessProfile? {
        // Require either an explicit name OR at least one slicing key to consider this a profile.
        val rawName = obj.optString("name").trim()
        val name = rawName.ifBlank { fallbackName }

        val keys = mutableMapOf<String, Any>()
        for (key in obj.keys()) {
            if (key in DROP_KEYS) continue
            when (val v = obj.opt(key)) {
                is JSONArray -> {
                    val list = mutableListOf<String>()
                    for (i in 0 until v.length()) list.add(v.optString(i, ""))
                    keys[key] = list
                }
                is JSONObject -> {
                    // Nested objects aren't part of OrcaSlicer's flat process schema — skip.
                }
                null -> { /* skip */ }
                else -> {
                    val s = v.toString().trim()
                    if (s.isNotEmpty() && s != "nil") keys[key] = s
                }
            }
        }

        if (keys.isEmpty()) return null

        return ProcessProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            sourceName = rawName.takeIf { it.isNotBlank() },
            keys = keys
        )
    }
}
