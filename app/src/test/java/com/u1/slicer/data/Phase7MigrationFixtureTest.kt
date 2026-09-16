package com.u1.slicer.data

import com.u1.slicer.machine.AssetProvider
import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfileRepository
import com.u1.slicer.machine.resolveMachineSliceConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-version fixtures captured from the released v4.0.4 persistence shapes. */
class Phase7MigrationFixtureTest {

    @Test
    fun `v4 printers fixture preserves identities active presets webcam and Bambu transport`() {
        val json = requireNotNull(javaClass.classLoader?.getResource("migration/v4_printers_config.json")).readText()
        val first = PrintersConfig.fromJson(json)
        val second = PrintersConfig.fromJson(PrintersConfig.toJson(first))
        val third = PrintersConfig.fromJson(PrintersConfig.toJson(second))

        assertEquals(listOf("u1-primary-v4", "u1-secondary-v4", "bambu-a1-v4"), first.printers.map { it.id })
        assertEquals("u1-secondary-v4", first.activeId)
        assertEquals("Spare U1", first.active.nickname)
        assertEquals("u1-cam-primary", first.printers[0].selectedWebcamUid)
        assertEquals("u1-cam-secondary", first.active.selectedWebcamUid)
        assertEquals(listOf(0, 2), first.active.extruderPresets.map { it.index })
        assertEquals(listOf("PLA", "ABS"), first.active.extruderPresets.map { it.materialType })
        assertEquals(listOf(21L, 22L), first.active.extruderPresets.map { it.filamentProfileId })

        val bambu = first.printers.single { it.kind == PrinterKind.BAMBU_LAN }
        assertEquals(HostType.BAMBU_LAN, bambu.hostType)
        assertEquals(BambuModel.A1, bambu.bambu?.model)
        assertEquals("127.0.0.1", bambu.bambu?.ip)
        assertEquals("fixture-only-access-code", bambu.bambu?.accessCode)
        assertEquals("fixture-a1-serial", bambu.bambu?.serial)
        assertTrue(bambu.moonrakerUrl.isEmpty())

        // Repeating the v4 -> current parse/serialize migration is idempotent.
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `legacy U1 with null machine profile resolves built-in geometry and dialect`() {
        val json = requireNotNull(javaClass.classLoader?.getResource("migration/v4_printers_config.json")).readText()
        val config = PrintersConfig.fromJson(json)
        val cache = createTempDir(prefix = "phase7-u1-profile-").also { it.deleteOnExit() }
        val u1MachineJson = requireNotNull(javaClass.classLoader?.getResource("machine/snapmaker_u1_resolved.json")).readText()
        val repository = MachineProfileRepository(
            assets = AssetProvider { path ->
                assertEquals(MachineProfileRepository.U1_MACHINE_ASSET, path)
                u1MachineJson
            },
            cacheDir = cache,
        )

        val primary = config.printers.first()
        assertNull(primary.machineProfileId)
        val profile = repository.activeFor(primary)
        val resolved = resolveMachineSliceConfig(profile, SliceConfig())

        assertEquals(MachineFamily.SNAPMAKER, profile.family)
        assertEquals(270f, profile.bedSizeX)
        assertEquals(270f, profile.bedSizeY)
        assertEquals(270f, profile.maxPrintHeight)
        assertEquals(4, profile.extruderCount)
        assertEquals(270f, resolved.bedSizeX)
        assertEquals(270f, resolved.bedSizeY)
        assertEquals(270f, resolved.maxPrintHeight)
        assertEquals(com.u1.slicer.machine.GcodeFlavor.KLIPPER, profile.gcodeFlavor)
        assertTrue(profile.machineStartGcode.contains("PRINT_START"))
    }

    @Test
    fun `v4 settings backup retains printer filaments overrides and process profiles`() {
        val json = requireNotNull(javaClass.classLoader?.getResource("migration/v4_settings_backup.json")).readText()
        val expectedPrintersJson = requireNotNull(
            javaClass.classLoader?.getResource("migration/v4_printers_config.json")
        ).readText()
        val expectedPrinters = PrintersConfig.fromJson(expectedPrintersJson)
        val imported = SettingsBackup.import(json)
        val config = requireNotNull(imported.printersConfig)
        val overrides = requireNotNull(imported.slicingOverrides)
        val filaments = requireNotNull(imported.filamentProfiles)
        val processes = requireNotNull(imported.processProfilesConfig)

        assertEquals(expectedPrinters, config)
        assertEquals(listOf(21L, 22L), config.active.extruderPresets.map { it.filamentProfileId })
        assertEquals("u1-cam-secondary", config.active.selectedWebcamUid)
        val bambu = config.printers.single { it.kind == PrinterKind.BAMBU_LAN }
        assertEquals(BambuModel.A1, bambu.bambu?.model)
        assertEquals("fixture-only-access-code", bambu.bambu?.accessCode)
        assertEquals(false, overrides.flowCalibration)
        assertEquals(OverrideMode.OVERRIDE, overrides.layerHeight.mode)
        assertEquals(0.16f, overrides.layerHeight.value)
        assertEquals(1, filaments.size)
        assertEquals("V4 PETG Blue", filaments[0].name)
        assertEquals("PETG", filaments[0].material)
        assertEquals(235, filaments[0].nozzleTemp)
        assertEquals(1, processes.profiles.size)
        assertEquals("v4-process-fine", processes.activeId)
        assertEquals("0.16", processes.profiles[0].keys["layer_height"])

        val roundTripped = SettingsBackup.import(SettingsBackup.export(imported))
        assertEquals(config, roundTripped.printersConfig)
        assertEquals(filaments, roundTripped.filamentProfiles)
        assertEquals(overrides, roundTripped.slicingOverrides)
        assertEquals(processes, roundTripped.processProfilesConfig)
    }

    @Test
    fun `older v1 backup remains importable alongside v4 fixture`() {
        val data = SettingsBackup.import(
            """
            {"version":1,"printerUrl":"http://127.0.0.1:7125","extruderPresets":[
              {"slot":1,"color":"#E53935","materialType":"PLA"}
            ]}
            """.trimIndent()
        )
        assertEquals("http://127.0.0.1:7125", data.printersConfig?.active?.moonrakerUrl)
        assertEquals("#E53935", data.printersConfig?.active?.extruderPresets?.first()?.color)
    }
}
