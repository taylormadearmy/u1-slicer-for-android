package com.u1.slicer.slice

import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.SliceConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GenericMachineProfileTest {
    private fun profile(vararg changes: Pair<String, Any>): String = JSONObject().apply {
        put("name", "Test Klipper 0.4")
        put("type", "machine")
        put("gcode_flavor", "klipper")
        put("nozzle_diameter", JSONArray(listOf("0.4")))
        put("printable_area", JSONArray(listOf("0x0", "220x0", "220x230", "0x230")))
        put("printable_height", "240")
        put("machine_start_gcode", "G28\nSTART_PRINT BED_TEMP=[bed_temperature_initial_layer_single]")
        put("machine_end_gcode", "END_PRINT")
        changes.forEach { (key, value) -> put(key, value) }
    }.toString()

    @Test fun importsRectangularSingleNozzleMachine() {
        val parsed = GenericMachineProfile.fromJson(profile())
        assertEquals(220f, parsed.bedSizeX)
        assertEquals(230f, parsed.bedSizeY)
        assertEquals(240f, parsed.maxPrintHeight)
        assertEquals(0.4f, parsed.nozzleDiameter)
        assertEquals(64, parsed.fingerprint.length)
        assertEquals(parsed, GenericMachineProfile.fromJson(parsed.resolvedJson))
    }

    @Test fun fingerprintsIgnoreJsonKeyOrderingButTrackMacros() {
        val first = GenericMachineProfile.fromJson(profile())
        val values = JSONObject(profile())
        val reversed = JSONObject().apply { values.keys().asSequence().toList().reversed().forEach { put(it, values.get(it)) } }
        assertEquals(first.fingerprint, GenericMachineProfile.fromJson(reversed.toString()).fingerprint)
        assertNotEquals(first.fingerprint, GenericMachineProfile.fromJson(profile("machine_end_gcode" to "M84")).fingerprint)
    }

    @Test fun resolvesInheritanceBatchAndPreservesChildOverrides() {
        val parent = JSONObject(profile()).apply { put("name", "base"); put("instantiation", "false") }
        val child = JSONObject().apply { put("name", "child"); put("inherits", "base"); put("printable_height", "300") }
        val imported = GenericMachineProfile.import(JSONArray(listOf(parent, child)).toString())
        assertEquals("child", imported.name)
        assertEquals(300f, imported.maxPrintHeight)
        assertFalse(JSONObject(imported.resolvedJson).has("inherits"))
    }

    @Test fun missingParentFailsInsteadOfUsingU1() {
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("inherits" to "missing")) }
    }

    @Test fun cyclicParentsFail() {
        val a = JSONObject(profile("inherits" to "b")).apply { put("name", "a") }
        val b = JSONObject().apply { put("name", "b"); put("inherits", "a") }
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.import(a.toString(), listOf(b.toString())) }
    }

    @Test fun rejectsOtherFirmwareAndMultiNozzleMachines() {
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("gcode_flavor" to "marlin")) }
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("nozzle_diameter" to JSONArray(listOf("0.4", "0.4")))) }
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("extruder_offset" to JSONArray(listOf("10x0")))) }
    }

    @Test fun rejectsInvalidGeometryAndLimits() {
        listOf("NaN", "Infinity", "-1", "0", "2001").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("printable_height" to invalid)) }
        }
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("printable_area" to JSONArray(listOf("0x0", "10x0", "5x10")))) }
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("machine_max_speed_x" to JSONArray(listOf("NaN")))) }
    }

    @Test fun rejectsMissingTemplatesAndWrongProfileType() {
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("machine_start_gcode" to "")) }
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("type" to "process")) }
    }

    @Test fun normalizesRectangularCornerOrderInsteadOfPassingBowtieToNative() {
        val parsed = GenericMachineProfile.fromJson(profile("printable_area" to JSONArray(listOf("0x0", "220x230", "220x0", "0x230"))))
        assertEquals(listOf("0x0", "220x0", "220x230", "0x230"), parsed.settings["printable_area"])
    }

    @Test fun rejectsInvalidOrUnsupportedExclusionGeometry() {
        listOf(
            listOf("NaNx0", "10x0", "10x10", "0x10"),
            listOf("0x0", "500x0", "500x10", "0x10"),
            listOf("0x0", "10x0", "5x10"),
            listOf("0x0", "10x0", "8x10", "0x10"),
        ).forEach { polygon -> assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(profile("bed_exclude_area" to JSONArray(polygon))) } }
    }

    @Test fun composeNormalizesMachineFilamentArraysAndKeepsMotionModeArrays() {
        val parsed = GenericMachineProfile.fromJson(profile("filament_colour" to JSONArray(listOf("#FFFFFF", "#000000")), "machine_max_speed_x" to JSONArray(listOf("180", "100"))))
        assertEquals(listOf("#FFFFFF"), parsed.compose()["filament_colour"])
        assertEquals(listOf("180", "100"), parsed.compose()["machine_max_speed_x"])
    }

    @Test fun composerRejectsForeignMachineAndFilamentCommands() {
        val parsed = GenericMachineProfile.fromJson(profile())
        val composed = parsed.compose(mapOf(
            "machine_start_gcode" to "PRINT_START SM_PRINT_AUTO_FEED=1",
            "printable_height" to "270",
            "nozzle_diameter" to listOf("0.8"),
            "filament_start_gcode" to listOf("M620"),
            "layer_height" to "0.16",
            "filament_type" to listOf("PETG", "ABS"),
            "enable_prime_tower" to "1",
        ))
        assertEquals(parsed.settings["machine_start_gcode"], composed["machine_start_gcode"])
        assertEquals("240", composed["printable_height"])
        assertEquals(listOf("0.4"), composed["nozzle_diameter"])
        assertEquals(listOf(""), composed["filament_start_gcode"])
        assertEquals("0.16", composed["layer_height"])
        assertEquals(listOf("PETG"), composed["filament_type"])
        assertEquals("0", composed["enable_prime_tower"])
    }

    @Test fun explicitTuningWinsWithoutOverridingHardware() {
        val parsed = GenericMachineProfile.fromJson(profile())
        val composed = parsed.compose(mapOf("layer_height" to "0.16"), mapOf("layer_height" to "0.12", "machine_end_gcode" to "FOREIGN"))
        assertEquals("0.12", composed["layer_height"])
        assertEquals("END_PRINT", composed["machine_end_gcode"])
    }

    @Test fun targetResolverDoesNotCarryU1TopologyIntoGeneric() {
        val generic = resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(extruderCount = 4, wipeTowerEnabled = true, mixedFilamentDefinitions = "U1 mix"), GenericMachineProfile.fromJson(profile()))
        assertEquals("GENERIC_KLIPPER", generic.machineTarget)
        assertEquals(1, generic.extruderCount)
        assertFalse(generic.wipeTowerEnabled)
        assertEquals("", generic.mixedFilamentDefinitions)
        assertEquals(220f, generic.bedSizeX)
        assertThrows(IllegalArgumentException::class.java) { resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig()) }
    }

    @Test fun cc1UsesBundledOfficialGeometryAndTemplates() {
        val cc1 = GenericMachineProfile.centauriCarbon()
        assertEquals(257f, cc1.bedSizeX)
        assertEquals(257f, cc1.bedSizeY)
        assertEquals(257f, cc1.maxPrintHeight)
        assertEquals(listOf("246x0", "256x0", "256x20", "246x20"), cc1.settings["bed_exclude_area"])
        assertTrue(cc1.settings.getValue("machine_start_gcode").toString().contains("M729"))
        assertFalse(cc1.resolvedJson.contains("SM_PRINT"))
        val cfg = resolveTargetedSliceConfig(SlicerTarget.ElegooCentauriCarbon, SliceConfig())
        assertEquals("ELEGOO_CC1", cfg.machineTarget)
        assertEquals(cc1.settings["machine_end_gcode"], cfg.machineEndGcode)
    }

    @Test fun genericCapabilitiesDoNotAdvertiseU1OrAms() {
        val caps = capabilityProfileFor(SlicerTarget.GenericKlipper)
        assertFalse(caps.supportsAmsMapping)
        assertFalse(caps.supportsColorMix)
        assertTrue(caps.supportsImportedProcessProfiles)
    }
}
