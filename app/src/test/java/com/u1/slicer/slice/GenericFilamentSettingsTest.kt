package com.u1.slicer.slice

import org.junit.Assert.assertEquals
import org.junit.Test

class GenericFilamentSettingsTest {
    private val resolved = mapOf<String, Any>(
        "filament_type" to listOf("PETG"),
        "nozzle_temperature" to listOf("245"),
        "nozzle_temperature_initial_layer" to listOf("245"),
        "bed_temperature" to listOf("75"),
    )

    @Test fun `primary selected filament reaches the composed machine profile`() {
        val config = GenericMachineProfile.centauriCarbon().compose(
            overrides = buildGenericFilamentSettings(resolved, emptyMap(), libraryBedTemp = 80),
        )
        assertEquals(listOf("PETG"), config["filament_type"])
        assertEquals(listOf("245"), config["nozzle_temperature"])
        assertEquals(listOf("245"), config["nozzle_temperature_initial_layer"])
        assertEquals(listOf("80"), config["hot_plate_temp"])
        assertEquals(listOf("80"), config["textured_plate_temp_initial_layer"])
    }

    @Test fun `source filament temperatures survive default slot fallback`() {
        val source = mapOf<String, Any>("filament_type" to listOf("ABS"), "nozzle_temperature" to listOf("260"), "cool_plate_temp_initial_layer" to listOf("100"))
        val config = GenericMachineProfile.centauriCarbon().compose(
            source = mapOf("curr_bed_type" to "Cool Plate"),
            overrides = buildGenericFilamentSettings(resolved, emptyMap(), sourceSettings = source),
        )
        assertEquals(listOf("ABS"), config["filament_type"])
        assertEquals(listOf("260"), config["nozzle_temperature"])
        assertEquals(listOf("100"), config["cool_plate_temp_initial_layer"])
    }

    @Test fun `selected library filament wins over imported material and plate temperatures`() {
        val config = buildGenericFilamentSettings(resolved, emptyMap(), 80,
            sourceSettings = mapOf("filament_type" to listOf("PLA"), "cool_plate_temp" to listOf("35")), useSourceFilament = false)
        assertEquals(listOf("PETG"), config["filament_type"])
        assertEquals(listOf("80"), config["cool_plate_temp"])
        assertEquals(listOf("80"), config["cool_plate_temp_initial_layer"])
    }

    @Test fun `library first layer bed tuning applies to both plate types`() {
        val config = buildGenericFilamentSettings(resolved, mapOf("hot_plate_temp_initial_layer" to listOf("85")), 80)
        assertEquals(listOf("85"), config["hot_plate_temp_initial_layer"])
        assertEquals(listOf("85"), config["textured_plate_temp_initial_layer"])
    }

    @Test fun `explicit bed temperature wins over library temperatures`() {
        val config = buildGenericFilamentSettings(resolved, mapOf("hot_plate_temp_initial_layer" to listOf("85")), 80, false)
        assertEquals(listOf("75"), config["textured_plate_temp"])
        assertEquals(listOf("75"), config["textured_plate_temp_initial_layer"])
    }
}
