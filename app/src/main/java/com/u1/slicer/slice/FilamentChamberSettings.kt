package com.u1.slicer.slice

/** Changing material clears only that filament's inherited chamber target; colour changes do not. */
internal fun materialChamberOverrides(
    source: Map<String, Any>, materialOverrides: Map<Int, String>, filamentCount: Int,
): Map<String, Any> {
    if (materialOverrides.isEmpty()) return emptyMap()
    fun values(key: String): List<String> = when (val value = source[key]) {
        is List<*> -> value.map { it.toString() }
        null -> emptyList()
        else -> listOf(value.toString())
    }
    val types = values("filament_type")
    val targets = values("chamber_temperature")
    return mapOf("chamber_temperature" to List(filamentCount) { index ->
        val changed = materialOverrides[index]?.let { !it.equals(types.getOrNull(index), ignoreCase = true) } == true
        if (changed) "0" else targets.getOrNull(index)?.takeUnless { it == "nil" } ?: "0"
    })
}
