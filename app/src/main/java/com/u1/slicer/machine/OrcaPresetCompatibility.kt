package com.u1.slicer.machine

/** Adapts newer desktop extruder-variant arrays to the engine's physical Standard extruders. */
object OrcaPresetCompatibility {
    fun standardMachine(values: Map<String, Any>): Map<String, Any> {
        val ids = values["printer_extruder_id"] as? List<*> ?: return values
        val variants = values["printer_extruder_variant"] as? List<*> ?: return values
        val nozzles = values["nozzle_diameter"] as? List<*> ?: return values
        if (ids.size <= nozzles.size) return values
        require(ids.size == variants.size) { "Extruder variant IDs and names must match." }
        val selected = (1..nozzles.size).map { tool ->
            ids.indices.singleOrNull { ids[it].toString() == tool.toString() && variants[it] == "Direct Drive Standard" }
                ?: throw IllegalArgumentException("Tool $tool requires exactly one Direct Drive Standard variant.")
        }
        return values.mapValues { (key, value) ->
            when {
                value !is List<*> -> value
                (key.startsWith("machine_max_") || key.startsWith("machine_min_")) && value.size > 2 -> {
                    val pairs = value.chunked(2)
                    require(pairs.all { it.size == 2 && it == pairs.first() }) {
                        "Machine limit '$key' differs between extruder variants; export a profile with common limits."
                    }
                    pairs.first().map { it.toString() }
                }
                value.size == ids.size && key !in setOf("nozzle_diameter", "extruder_offset", "printable_area", "bed_exclude_area") ->
                    selected.map { value[it].toString() }
                else -> value
            }
        }
    }

    fun processScalar(key: String, values: List<String>): String? {
        val concrete = values.filter { it.isNotBlank() && it != "nil" }.distinct()
        require(concrete.size <= 1) {
            "Process setting '$key' differs between extruder variants; this engine requires one value."
        }
        return concrete.singleOrNull()
    }
}
