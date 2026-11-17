package com.u1.slicer.machine

import com.u1.slicer.buildFilamentLibrarySettings
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.ProcessProfileParser
import com.u1.slicer.data.SettingsBackup
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.ui.parseFilamentJson
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiscordU1PresetImportTest {
    @Test fun `high flow only override does not replace inherited Standard speed`() {
        val resolved = requireNotNull(OrcaProfileResolver.resolve("""{
            "name":"Variant child","inherits":"Base",
            "print_extruder_id":["1","1","2","2"],
            "print_extruder_variant":["Direct Drive Standard","Direct Drive High Flow","Direct Drive Standard","Direct Drive High Flow"],
            "outer_wall_speed":["nil","250","nil","nil"]
        }""") { """{"name":"Base","outer_wall_speed":"200"}""" })
        val profile = ProcessProfileParser.parse(resolved, "fallback").single()
        assertEquals("200", profile.keys["outer_wall_speed"])
        assertEquals(com.u1.slicer.data.ProcessSourceVariant(1, "Direct Drive Standard"), profile.sourceVariant)
        assertFalse(profile.keys.containsKey("print_extruder_variant"))
        val restored = com.u1.slicer.data.ProcessProfilesConfig.profileFromJson(
            com.u1.slicer.data.ProcessProfilesConfig.profileToJson(profile.copy(name = "Renamed")))!!
        assertEquals(profile.sourceVariant, restored.sourceVariant)
    }

    @Test fun `simple filament fields survive snake or camel chamber aliases`() {
        for (key in listOf("chamber_temperature", "chamberTemperature")) {
            val profile = parseFilamentJson("""{"name":"Custom spool","material":"ASA","nozzle_temp":275,"bed_temp":95,"$key":65}""").single()
            assertEquals("ASA", profile.material)
            assertEquals(275, profile.nozzleTemp)
            assertEquals(95, profile.bedTemp)
            assertEquals(65, profile.chamberTemperature)
        }
    }
    @Test fun `brush washer resolved ASA snapshot imports without the unavailable System parent`() = runTest {
        val importer = OrcaProfileBundleImporter(
            loadMissing = { _, _, name -> error("Resolved snapshot must not request $name") },
            validateMachine = { error("Filament import must not validate a machine") },
            parseFilaments = { parseFilamentJson(it) },
            parseProcesses = ProcessProfileParser::parse,
        )
        val profile = resource("brush-washer-asa-resolved.json").openStream().use {
            importer.import(it, "Elegoo ASA U1.json", OrcaProfileType.FILAMENT)
        }.filaments.single()
        assertEquals("ASA", profile.material)
        assertEquals(270, profile.nozzleTemp)
        assertEquals(270, profile.nozzleTempInitialLayer)
        assertEquals(90, profile.bedTemp)
        assertEquals(65, profile.chamberTemperature)
        assertEquals(0.98f, profile.flowRatio!!, 0.0001f)
        assertEquals(12f, profile.maxVolumetricSpeed!!, 0.0001f)
        assertEquals(1.1f, profile.density, 0.0001f)
        assertEquals(10, profile.fanMinSpeed)
        assertEquals(80, profile.fanMaxSpeed)
        assertEquals(0.04f, profile.pressureAdvance!!, 0.0001f)
    }

    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResource("imports/discord-u1/$name"))
    private fun importer(validate: suspend (MachineProfile) -> MachineProfileValidationResult = { MachineProfileValidationResult.success() }) =
        OrcaProfileBundleImporter(
            loadMissing = { vendor, type, name ->
                if (vendor != "Snapmaker") null else javaClass.classLoader
                    ?.getResource("imports/discord-u1/parents/${type.name.lowercase()}/$name.json")?.readText()
            },
            validateMachine = validate,
            parseFilaments = { parseFilamentJson(it) },
            parseProcesses = ProcessProfileParser::parse,
        )

    @Test fun `attached machine resolves pinned parents and preserves enclosure commands`() = runTest {
        var validated = false
        val imported = importer { profile ->
            validated = true
            assertTrue(profile.machineStartGcode.contains("WAIT_CHAMBER_TEMP TIMEOUT=180"))
            MachineProfileValidationResult.success()
        }.import(resource("machine.json").openStream(), "Testing.json", OrcaProfileType.MACHINE)
        val snapshot = GenericMachineProfile.fromJson(imported.machines.single().machineJson)
        val original = JSONObject(resource("machine.json").readText())
        val composed = snapshot.compose()
        assertTrue(validated)
        assertEquals(4, snapshot.extruderCount)
        assertEquals(listOf("0.5x1", "270.5x1", "270.5x271", "0.5x271"), composed["printable_area"])
        assertEquals(original.getString("machine_start_gcode"), composed["machine_start_gcode"])
        assertEquals(original.getString("machine_end_gcode"), composed["machine_end_gcode"])
        assertEquals(listOf("500", "200"), composed["machine_max_speed_x"])
        assertEquals(listOf("1.5", "0.8", "0.8", "1.5"), composed["retraction_length"])
        assertEquals("undefine", composed["nozzle_type"])
        assertEquals("143", composed["nozzle_volume"])
    }

    @Test fun `attached process resolves base keys and converts speed variant arrays`() = runTest {
        val result = importer().import(resource("process.json").openStream(), "Supports.json", OrcaProfileType.PROCESS)
        val profile = result.processes.single()
        assertEquals("0.16", profile.keys["layer_height"])
        assertEquals("150", profile.keys["outer_wall_speed"])
        assertEquals("20", profile.keys["support_interface_speed"])
        assertEquals("25%", profile.keys["sparse_infill_density"])
        assertEquals("1", profile.keys["slowdown_for_curled_perimeters"])
        assertTrue("Inherited defaults must be resolved before provenance is removed", profile.keys.containsKey("bottom_shell_layers"))
        assertFalse(profile.keys.containsKey("inherits"))
    }

    @Test fun `attached ASA bundle identifies exact unavailable parent`() = runTest {
        val error = runCatching {
            importer().import(resource("Elegoo ASA.orca_filament").openStream(), "Elegoo ASA.orca_filament", OrcaProfileType.FILAMENT)
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty(), error.message!!.contains("Missing parent preset \"Elegoo ASA @System\""))
    }

    @Test fun `renamed bundle is detected by contents and category mismatch is rejected`() = runTest {
        val error = runCatching {
            importer().import(resource("Elegoo ASA.orca_filament").openStream(), "download.bin", OrcaProfileType.MACHINE)
        }.exceptionOrNull()
        assertEquals("Select a machine preset.", error?.message)
    }

    @Test fun `conflicting newer machine limits are rejected without changing template`() {
        val json = JSONObject(resource("machine.json").readText())
        json.getJSONArray("machine_max_speed_x").put(2, "400")
        val error = runCatching { GenericMachineProfile.fromJson(json.put("inherits", "").toString()) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("differs between extruder variants"))
    }

    @Test fun `conflicting process speeds fail rather than silently picking a tool`() {
        val error = runCatching {
            ProcessProfileParser.parse("""{"name":"Different speeds","outer_wall_speed":["150","200"]}""", "fallback")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("outer_wall_speed"))
    }

    @Test fun `chamber temperature survives backup and slot mapping without heating unspecified slots`() {
        val profile = parseFilamentJson("""{"type":"filament","name":"ASA","chamber_temperature":["65"]}""").single().copy(id = 17)
        assertEquals(65, profile.chamberTemperature)
        val backup = SettingsBackup.BackupData(null, null, null, listOf(profile))
        val restored = SettingsBackup.import(SettingsBackup.export(backup)).filamentProfiles!!.single()
        assertEquals(65, restored.chamberTemperature)
        val settings = buildFilamentLibrarySettings(2, listOf(2, 0), listOf(
            ExtruderPreset(index = 2, filamentProfileId = 17),
        ), listOf(profile))
        assertEquals(listOf("65", "0"), settings["chamber_temperature"])
    }

    @Test fun `invalid chamber target cannot become an imported profile`() {
        val error = runCatching {
            parseFilamentJson("""{"type":"filament","name":"ASA","chamber_temperature":["-1"]}""")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Chamber temperature"))
    }

    @Test fun `empty JSON cannot manufacture a default filament`() = runTest {
        val error = runCatching {
            importer().import("{}".byteInputStream(), "ASA.json", OrcaProfileType.FILAMENT)
        }.exceptionOrNull()
        assertEquals("Imported filament profile is not usable.", error?.message)
    }

    @Test fun `unnamed process arrays retain distinct fallback names`() = runTest {
        val profiles = importer().import(
            """[{"layer_height":"0.16"},{"layer_height":"0.2"}]""".byteInputStream(),
            "Custom.json", OrcaProfileType.PROCESS,
        ).processes
        assertEquals(listOf("Custom #1", "Custom #2"), profiles.map { it.name })
    }
}
