package com.u1.slicer.machine

import org.json.JSONObject

/** Resolves Orca machine presets into a single flat, child-overrides-parent JSON object. */
object OrcaProfileResolver {
    private const val MAX_INHERITS_DEPTH = 10
    private val BOOKKEEPING_KEYS = setOf("inherits", "instantiation", "from")

    /**
     * Merges an [inherits] chain, loading each parent with [loadByName]. Returns null when a
     * parent cannot be loaded or parsed, the chain cycles, or it exceeds ten parent links.
     */
    fun resolve(rootJson: String, loadByName: (String) -> String?): String? {
        val root = parse(rootJson) ?: return null
        val seen = mutableSetOf<String>()
        root.optString("name").takeIf { it.isNotBlank() }?.let(seen::add)
        return resolveObject(root, seen, depth = 0, loadByName)?.toString()
    }

    private fun resolveObject(
        current: JSONObject,
        seen: MutableSet<String>,
        depth: Int,
        loadByName: (String) -> String?
    ): JSONObject? {
        val parentName = current.optString("inherits").trim()
        val parent = if (parentName.isEmpty()) {
            null
        } else {
            if (depth >= MAX_INHERITS_DEPTH || !seen.add(parentName)) return null
            val parentJson = loadByName(parentName) ?: return null
            val parentObject = parse(parentJson) ?: return null
            resolveObject(parentObject, seen, depth + 1, loadByName) ?: return null
        }

        val merged = parent ?: JSONObject()
        val keys = current.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in BOOKKEEPING_KEYS) {
                merged.put(key, current.get(key))
            }
        }
        BOOKKEEPING_KEYS.forEach(merged::remove)
        return merged
    }

    private fun parse(json: String): JSONObject? =
        runCatching { JSONObject(json) }.getOrNull()
}
