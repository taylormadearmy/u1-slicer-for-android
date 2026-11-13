package com.u1.slicer.slice

/** Carries the resolved material and temperatures into the selected native machine profile. */
internal fun buildGenericFilamentSettings(
    resolvedProfile: Map<String, Any>,
    librarySettings: Map<String, Any>,
    libraryBedTemp: Int? = null,
    useLibraryBedTemp: Boolean = true,
    sourceSettings: Map<String, Any> = emptyMap(),
    useSourceFilament: Boolean = true,
): Map<String, Any> {
    val bed = if (useLibraryBedTemp && libraryBedTemp != null) listOf(libraryBedTemp.toString())
        else checkNotNull(resolvedProfile["bed_temperature"])
    val initialBed = if (useLibraryBedTemp) librarySettings["hot_plate_temp_initial_layer"] ?: bed else bed
    val selected = resolvedProfile.filterKeys {
        it in setOf("filament_type", "filament_colour", "nozzle_temperature", "nozzle_temperature_initial_layer")
    } + librarySettings + mapOf(
        "hot_plate_temp" to bed,
        "textured_plate_temp" to bed,
        "hot_plate_temp_initial_layer" to initialBed,
        "textured_plate_temp_initial_layer" to initialBed,
        "cool_plate_temp" to bed,
        "cool_plate_temp_initial_layer" to initialBed,
    )
    return selected + sourceSettings.filterKeys {
        (useSourceFilament && it in setOf("filament_type", "filament_colour", "nozzle_temperature", "nozzle_temperature_initial_layer")) ||
            (useLibraryBedTemp && libraryBedTemp == null && it in setOf("hot_plate_temp", "hot_plate_temp_initial_layer", "textured_plate_temp", "textured_plate_temp_initial_layer", "cool_plate_temp", "cool_plate_temp_initial_layer"))
    }
}
