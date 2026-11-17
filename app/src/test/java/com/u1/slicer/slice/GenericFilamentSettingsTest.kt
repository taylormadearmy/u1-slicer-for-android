package com.u1.slicer.slice

import org.junit.Assert.assertEquals
import org.junit.Test

class GenericFilamentSettingsTest {
    @Test fun `material changes clear only changed filament chamber targets`() {
        val source = mapOf<String, Any>("filament_type" to listOf("ASA", "ASA", "PLA"),
            "chamber_temperature" to listOf("65", "65", "0"))
        assertEquals(listOf("0", "65", "0"), materialChamberOverrides(source, mapOf(0 to "PLA"), 3)["chamber_temperature"])
        assertEquals(listOf("65", "65", "0"), materialChamberOverrides(source, mapOf(0 to "ASA"), 3)["chamber_temperature"])
        assertEquals(emptyMap<String, Any>(), materialChamberOverrides(source, emptyMap(), 3))
    }

    @Test fun `resolved slot PLA clears an inherited ASA chamber without an explicit edit`() {
        val source = mapOf<String, Any>("filament_type" to listOf("ASA"), "chamber_temperature" to listOf("65"))
        val resolvedSlotMaterials = listOf("PLA").mapIndexed { index, material -> index to material }.toMap()
        assertEquals(listOf("0"), materialChamberOverrides(source, resolvedSlotMaterials, 1)["chamber_temperature"])
    }

    @Test fun `Bambu colour only edits do not override material or chamber`() {
        val result = com.u1.slicer.buildExplicitBambuProfileOverrides(
            mapOf("filament_colour" to listOf("#ffffff"), "filament_type" to listOf("PLA"),
                "chamber_temperature" to listOf("0")),
            com.u1.slicer.data.SlicingOverrides(), hasFilamentOverrides = true, hasMaterialOverrides = false)
        assertEquals(mapOf("filament_colour" to listOf("#ffffff")), result)
    }
    @Test fun `selecting another material clears the source hot chamber target`() {
        val source = mapOf<String, Any>("filament_type" to listOf("ASA"), "chamber_temperature" to listOf("65"))
        val tuning = buildGenericFilamentSettings(resolved, emptyMap(), sourceSettings = source, useSourceFilament = false)
        val config = GenericMachineProfile.centauriCarbon().compose(source, tuning)
        assertEquals(listOf("0"), config["chamber_temperature"])
    }

    @Test fun `unchanged source material keeps its chamber target`() {
        val tuning = buildGenericFilamentSettings(resolved, emptyMap(), sourceSettings = mapOf("chamber_temperature" to listOf("65")))
        assertEquals(listOf("65"), tuning["chamber_temperature"])
    }

    @Test fun `linked PLA without a chamber target overrides source ASA heating`() {
        val profile = com.u1.slicer.data.FilamentProfile(id = 1, name = "PLA", material = "PLA", nozzleTemp = 220,
            bedTemp = 55, retractLength = 0.8f, retractSpeed = 45f)
        val library = com.u1.slicer.buildFilamentLibrarySettings(1, null,
            listOf(com.u1.slicer.data.ExtruderPreset(0, filamentProfileId = 1)), listOf(profile))
        val tuning = buildGenericFilamentSettings(resolved, library,
            sourceSettings = mapOf("chamber_temperature" to listOf("65")), useSourceFilament = false)
        assertEquals(listOf("0"), tuning["chamber_temperature"])
    }
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
