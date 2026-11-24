package com.u1.slicer.slice

/** Projects address source filaments; a single-nozzle profile must contain the selected entry only. */
internal fun selectSingleNozzleFilament(settings: Map<String, Any>, sourceFilamentIndex: Int): Map<String, Any> {
    require(sourceFilamentIndex >= 0)
    return settings.mapValues { (_, value) ->
        if (value is List<*> && value.isNotEmpty()) listOf(value.getOrElse(sourceFilamentIndex) { value.last() }.toString())
        else value
    }
}

/** Carries the resolved material and temperatures into the selected native machine profile. */
internal fun buildGenericFilamentSettings(
    resolvedProfile: Map<String, Any>,
    librarySettings: Map<String, Any>,
    libraryBedTemp: Int? = null,
    useLibraryBedTemp: Boolean = true,
    sourceSettings: Map<String, Any> = emptyMap(),
    useSourceFilament: Boolean = true,
    sourceFilamentIndex: Int = 0,
): Map<String, Any> {
    val selectedSource = selectSingleNozzleFilament(sourceSettings, sourceFilamentIndex)
    val selectedResolved = selectSingleNozzleFilament(resolvedProfile, sourceFilamentIndex)
    val bed = if (useLibraryBedTemp && libraryBedTemp != null) listOf(libraryBedTemp.toString())
        else checkNotNull(selectedResolved["bed_temperature"])
    val initialBed = if (useLibraryBedTemp) librarySettings["hot_plate_temp_initial_layer"] ?: bed else bed
    val chamber = librarySettings["chamber_temperature"]
        ?: if (useSourceFilament) selectedSource["chamber_temperature"] ?: selectedResolved["chamber_temperature"] ?: listOf("0")
        else selectedResolved["chamber_temperature"] ?: listOf("0")
    val selected = selectedResolved.filterKeys {
        it in setOf("filament_type", "filament_colour", "nozzle_temperature", "nozzle_temperature_initial_layer")
    } + librarySettings + mapOf(
        "hot_plate_temp" to bed,
        "textured_plate_temp" to bed,
        "hot_plate_temp_initial_layer" to initialBed,
        "textured_plate_temp_initial_layer" to initialBed,
        "cool_plate_temp" to bed,
        "cool_plate_temp_initial_layer" to initialBed,
        "chamber_temperature" to chamber,
    )
    return selected + selectedSource.filterKeys {
        (useSourceFilament && it in setOf("filament_type", "filament_colour", "nozzle_temperature", "nozzle_temperature_initial_layer")) ||
            (useLibraryBedTemp && libraryBedTemp == null && it in setOf("hot_plate_temp", "hot_plate_temp_initial_layer", "textured_plate_temp", "textured_plate_temp_initial_layer", "cool_plate_temp", "cool_plate_temp_initial_layer"))
    }
}

/** Retain source filament identities in UI statistics after native slicing compacts to T0. */
internal fun alignSingleNozzleFilamentUsage(physicalMm: List<Float>, sourceIndex: Int, canonicalSize: Int): List<Float> {
    if (physicalMm.isEmpty() || sourceIndex !in 0 until canonicalSize || physicalMm.drop(1).any { it != 0f }) return physicalMm
    return List(canonicalSize) { if (it == sourceIndex) physicalMm[0] else 0f }
}
