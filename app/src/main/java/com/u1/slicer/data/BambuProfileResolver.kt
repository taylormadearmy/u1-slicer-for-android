package com.u1.slicer.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * F91 follow-up: resolve a BambuStudio / OrcaSlicer filament profile JSON's `inherits` chain
 * against a curated set of bundled parent profiles, producing a flattened JSON suitable for
 * [parseFilamentJson].
 *
 * Bambu's filament profile hierarchy is typically:
 *   `Bambu PLA Basic @BBL P1S 0.4 nozzle`  (printer-specific overrides)
 *     ↳ inherits `Bambu PLA Basic @base`   (vendor common — cost, density, max vol speed)
 *       ↳ inherits `fdm_filament_pla`      (material defaults — nozzle/bed temps, fan curves)
 *         ↳ inherits `fdm_filament_common` (root)
 *
 * A user-imported child file from BambuStudio's system tree is SPARSE — most slicing values
 * live in the parents. We bundle the common parents under `assets/bambu_profile_chain/` and
 * walk the chain in-process, deep-merging child keys on top of parent keys.
 *
 * Child arrays REPLACE parent arrays wholesale. Bambu uses "nil" array entries to mean
 * "use parent's value for this index" — we resolve those by overlaying the parent's value
 * at the corresponding index when the child entry is "nil".
 */
object BambuProfileResolver {

    private const val TAG = "BambuProfileResolver"
    private const val CHAIN_DIR = "bambu_profile_chain"
    private const val MAX_DEPTH = 8  // catastrophic guard against cycles / over-deep chains

    /**
     * Returns a flattened JSONObject if [obj] has an `inherits` key and the chain can be
     * resolved against bundled assets. Returns null if [obj] has no inheritance (caller can
     * use the original directly) or the chain root cannot be located.
     */
    fun flatten(context: Context, obj: JSONObject): JSONObject? {
        val inherits = obj.optString("inherits").takeIf { it.isNotBlank() } ?: return null
        return resolveChain(context, obj, inherits, depth = 0)
    }

    private fun resolveChain(context: Context, child: JSONObject, parentName: String, depth: Int): JSONObject? {
        if (depth > MAX_DEPTH) {
            Log.w(TAG, "inheritance depth exceeded $MAX_DEPTH at parent=$parentName")
            return null
        }
        val parentJson = loadParent(context, parentName) ?: run {
            Log.i(TAG, "parent profile '$parentName' not in bundled chain; returning child as-is")
            return child  // best effort — child stands alone
        }
        // Recurse: resolve parent first so we have a flat ancestor.
        val grandparentName = parentJson.optString("inherits").takeIf { it.isNotBlank() }
        val flatParent = if (grandparentName != null) {
            resolveChain(context, parentJson, grandparentName, depth + 1) ?: parentJson
        } else {
            parentJson
        }
        return merge(flatParent, child)
    }

    private fun loadParent(context: Context, name: String): JSONObject? {
        val filename = "$name.json"
        return try {
            val raw = context.assets.open("$CHAIN_DIR/$filename").bufferedReader().readText()
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deep-merge [child] onto [parent]. Child keys win; child arrays REPLACE parent arrays
     * (except `nil` entries fall through to parent's value at the same index when parent has
     * one). Returns a fresh JSONObject.
     */
    internal fun merge(parent: JSONObject, child: JSONObject): JSONObject {
        val result = JSONObject()
        // Start with all parent keys.
        for (key in parent.keys()) result.put(key, parent.opt(key))
        // Overlay child keys.
        for (key in child.keys()) {
            val childVal = child.opt(key)
            if (childVal is JSONArray && parent.opt(key) is JSONArray) {
                result.put(key, mergeArrays(parent.optJSONArray(key)!!, childVal))
            } else if (childVal != null) {
                result.put(key, childVal)
            }
        }
        // Strip provenance keys that should not propagate.
        for (key in listOf("inherits", "from", "instantiation", "setting_id", "include")) {
            result.remove(key)
        }
        return result
    }

    /**
     * Per-index merge: child's value wins unless it is the string "nil" (Bambu sentinel for
     * "fall back to parent"). When the child array is shorter than the parent, parent's
     * tail elements are preserved; when longer, child's tail is kept.
     */
    private fun mergeArrays(parent: JSONArray, child: JSONArray): JSONArray {
        val out = JSONArray()
        val maxLen = maxOf(parent.length(), child.length())
        for (i in 0 until maxLen) {
            val childEntry = if (i < child.length()) child.optString(i, "") else ""
            val parentEntry = if (i < parent.length()) parent.opt(i) else null
            val resolved = if (i < child.length() && childEntry != "nil") child.opt(i)
                           else parentEntry
            if (resolved != null) out.put(resolved) else out.put("")
        }
        return out
    }
}
