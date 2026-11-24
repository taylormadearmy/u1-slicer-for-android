package com.u1.slicer.slice

import org.junit.Assert.assertEquals
import org.junit.Test

class GenericFilamentSettingsTest {
    @Test fun `source flow density and speed limits follow the remapped filament`() {
        val source = mapOf<String, Any>("filament_flow_ratio" to listOf("0.96", "0.95"),
            "filament_max_volumetric_speed" to listOf("12", "15"),
            "filament_density" to listOf("1.24", "1.27"))
        val machine = GenericMachineProfile.centauriCarbon()
        val config = machine.compose(source, sourceFilamentIndex = 1)
        assertEquals(listOf("0.95"), config["filament_flow_ratio"])
        assertEquals(listOf("15"), config["filament_max_volumetric_speed"])
        assertEquals(listOf("1.27"), config["filament_density"])
        assertEquals(listOf("0.96"), machine.compose(source)["filament_flow_ratio"])
        val overridden = machine.compose(source, mapOf("filament_flow_ratio" to listOf("1.02")), sourceFilamentIndex = 1)
        assertEquals(listOf("1.02"), overridden["filament_flow_ratio"])
    }

    @Test fun `material and colour edits use the remapped source index in the composed machine`() {
        val edited = resolved + mapOf("filament_type" to listOf("PLA", "PLA", "PETG"),
            "filament_colour" to listOf("#000000", "#FFFFFF", "#123ABC"),
            "nozzle_temperature" to listOf("220", "220", "235"),
            "nozzle_temperature_initial_layer" to listOf("220", "220", "235"))
        val tuning = buildGenericFilamentSettings(edited, emptyMap(),
            sourceSettings = mapOf("filament_type" to listOf("PLA", "PLA", "PLA")),
            useSourceFilament = false, sourceFilamentIndex = 2)
        assertEquals(listOf("PETG"), tuning["filament_type"])
        assertEquals(listOf("235"), tuning["nozzle_temperature"])
        val config = GenericMachineProfile.centauriCarbon().compose(overrides = tuning +
            selectSingleNozzleFilament(edited.filterKeys { it != "bed_temperature" }, 2))
        assertEquals(listOf("PETG"), config["filament_type"])
        assertEquals(listOf("#123ABC"), config["filament_colour"])
        assertEquals(listOf("235"), config["nozzle_temperature"])
        assertEquals(listOf("235"), config["nozzle_temperature_initial_layer"])
    }

    @Test fun `remapped source filament retains its own material colour and heating`() {
        val source = mapOf<String, Any>("filament_type" to listOf("PLA", "PETG", "ASA"),
            "filament_colour" to listOf("#111111", "#222222", "#333333"),
            "nozzle_temperature" to listOf("210", "245", "260"),
            "nozzle_temperature_initial_layer" to listOf("215", "250", "265"),
            "hot_plate_temp" to listOf("55", "80", "100"),
            "chamber_temperature" to listOf("0", "0", "65"))
        val config = GenericMachineProfile.centauriCarbon().compose(overrides = buildGenericFilamentSettings(
            resolved, emptyMap(), sourceSettings = source, sourceFilamentIndex = 2))
        assertEquals(listOf("ASA"), config["filament_type"])
        assertEquals(listOf("#333333"), config["filament_colour"])
        assertEquals(listOf("260"), config["nozzle_temperature"])
        assertEquals(listOf("265"), config["nozzle_temperature_initial_layer"])
        assertEquals(listOf("100"), config["hot_plate_temp"])
        assertEquals(listOf("65"), config["chamber_temperature"])
    }

    @Test fun `single shared source temperature applies to a higher filament index`() {
        val config = buildGenericFilamentSettings(resolved, emptyMap(),
            sourceSettings = mapOf("nozzle_temperature" to listOf("240")), sourceFilamentIndex = 2)
        assertEquals(listOf("240"), config["nozzle_temperature"])
    }

    @Test fun `selected library material still overrides a remapped source filament`() {
        val config = buildGenericFilamentSettings(resolved, emptyMap(), libraryBedTemp = 75,
            sourceSettings = mapOf("filament_type" to listOf("PLA", "ASA"),
                "nozzle_temperature" to listOf("210", "260")),
            useSourceFilament = false, sourceFilamentIndex = 1)
        assertEquals(listOf("PETG"), config["filament_type"])
        assertEquals(listOf("245"), config["nozzle_temperature"])
    }
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
