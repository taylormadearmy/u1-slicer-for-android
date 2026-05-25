package com.u1.slicer.data

import com.u1.slicer.SlicerViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// Printer / PrintersConfig are in the same package — no import needed.

class SettingsBackupTest {

    @Test
    fun `export produces valid JSON with version field`() {
        val json = SettingsBackup.export(
            SliceConfig(), SlicingOverrides(), "", emptyList(), emptyList()
        )
        val obj = JSONObject(json)
        assertEquals(3, obj.getInt("version"))
        assertTrue(obj.has("sliceConfig"))
        assertTrue(obj.has("slicingOverrides"))
        // v2 always mirrors active printer URL into legacy field for rollback compat
        assertTrue(obj.has("printerUrl"))
        assertTrue(obj.has("extruderPresets"))
        assertTrue(obj.has("filamentProfiles"))
    }

    @Test
    fun `round-trip preserves SliceConfig`() {
        val original = SliceConfig(
            layerHeight = 0.3f,
            nozzleTemp = 230,
            bedTemp = 70,
            fillDensity = 0.25f,
            printSpeed = 80f,
            wipeTowerEnabled = true,
            wipeTowerX = 150f
        )
        val json = SettingsBackup.export(original, SlicingOverrides(), "", emptyList(), emptyList())
        val data = SettingsBackup.import(json)
        assertNotNull(data.sliceConfig)
        val cfg = data.sliceConfig!!
        assertEquals(0.3f, cfg.layerHeight, 0.001f)
        assertEquals(230, cfg.nozzleTemp)
        assertEquals(70, cfg.bedTemp)
        assertEquals(0.25f, cfg.fillDensity, 0.001f)
        assertEquals(80f, cfg.printSpeed, 0.1f)
        assertTrue(cfg.wipeTowerEnabled)
        assertEquals(150f, cfg.wipeTowerX, 0.1f)
    }

    @Test
    fun `round-trip preserves SlicingOverrides`() {
        val overrides = SlicingOverrides(
            layerHeight = OverrideValue(OverrideMode.OVERRIDE, 0.28f),
            infillDensity = OverrideValue(OverrideMode.ORCA_DEFAULT),
            flowCalibration = false
        )
        val json = SettingsBackup.export(SliceConfig(), overrides, "", emptyList(), emptyList())
        val data = SettingsBackup.import(json)
        assertNotNull(data.slicingOverrides)
        assertEquals(OverrideMode.OVERRIDE, data.slicingOverrides!!.layerHeight.mode)
        assertEquals(0.28f, data.slicingOverrides!!.layerHeight.value!!, 0.001f)
        assertEquals(OverrideMode.ORCA_DEFAULT, data.slicingOverrides!!.infillDensity.mode)
        assertFalse(data.slicingOverrides!!.flowCalibration)
    }

    @Test
    fun `round-trip preserves printer URL`() {
        val json = SettingsBackup.export(
            SliceConfig(), SlicingOverrides(), "192.168.0.151", emptyList(), emptyList()
        )
        val data = SettingsBackup.import(json)
        assertEquals("192.168.0.151", data.printersConfig?.active?.moonrakerUrl)
    }

    @Test
    fun `round-trip preserves extruder presets`() {
        val presets = listOf(
            ExtruderPreset(0, "#FF0000", "PLA"),
            ExtruderPreset(1, "#00FF00", "PETG"),
            ExtruderPreset(2, "#0000FF", "ABS"),
            ExtruderPreset(3, "#FFFF00", "TPU")
        )
        val json = SettingsBackup.export(SliceConfig(), SlicingOverrides(), "", presets, emptyList())
        val data = SettingsBackup.import(json)
        val importedPresets = data.printersConfig?.active?.extruderPresets
        assertNotNull(importedPresets)
        assertEquals(4, importedPresets!!.size)
        assertEquals(0, importedPresets[0].index)
        assertEquals("#FF0000", importedPresets[0].color)
        assertEquals("PLA", importedPresets[0].materialType)
        assertEquals(3, importedPresets[3].index)
        assertEquals("#FFFF00", importedPresets[3].color)
    }

    @Test
    fun `round-trip preserves filament profile names on extruder presets`() {
        val profiles = mapOf(1L to "My PLA", 3L to "My PETG")
        val presets = listOf(
            ExtruderPreset(0, "#FF0000", "PLA", filamentProfileId = 1L),
            ExtruderPreset(1, "#00FF00", "PETG", filamentProfileId = 3L),
            ExtruderPreset(2, "#0000FF", "ABS"),
            ExtruderPreset(3, "#FFFF00", "TPU")
        )
        val json = SettingsBackup.export(
            SliceConfig(), SlicingOverrides(), "", presets, emptyList(),
            filamentNameResolver = { id -> profiles[id] }
        )

        // Verify filamentProfileName is in the JSON
        val root = JSONObject(json)
        val arr = root.getJSONArray("extruderPresets")
        val parsed = SettingsBackup.parseExtruderPresetsWithNames(arr)
        assertEquals("My PLA", parsed[0].filamentProfileName)
        assertEquals("My PETG", parsed[1].filamentProfileName)
        assertNull(parsed[2].filamentProfileName)
        assertNull(parsed[3].filamentProfileName)
    }

    @Test
    fun `round-trip preserves filament profiles`() {
        val profiles = listOf(
            FilamentProfile(name = "Test PLA", material = "PLA", nozzleTemp = 210,
                bedTemp = 60, retractLength = 0.8f, retractSpeed = 45f,
                color = "#FF5733", density = 1.24f),
            FilamentProfile(name = "Test PETG", material = "PETG", nozzleTemp = 240,
                bedTemp = 80, retractLength = 1.0f, retractSpeed = 40f)
        )
        val json = SettingsBackup.export(SliceConfig(), SlicingOverrides(), "", emptyList(), profiles)
        val data = SettingsBackup.import(json)
        assertNotNull(data.filamentProfiles)
        assertEquals(2, data.filamentProfiles!!.size)
        assertEquals("Test PLA", data.filamentProfiles!![0].name)
        assertEquals("PLA", data.filamentProfiles!![0].material)
        assertEquals(210, data.filamentProfiles!![0].nozzleTemp)
        assertEquals("#FF5733", data.filamentProfiles!![0].color)
        assertEquals(1.24f, data.filamentProfiles!![0].density, 0.001f)
        assertEquals("Test PETG", data.filamentProfiles!![1].name)
        assertEquals(240, data.filamentProfiles!![1].nozzleTemp)
    }

    @Test
    fun `import rejects version 0`() {
        val json = """{"version": 0}"""
        try {
            SettingsBackup.import(json)
            fail("Should throw for version 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    @Test
    fun `import handles partial backup with only sliceConfig`() {
        val json = """{"version": 1, "sliceConfig": {"layerHeight": 0.15, "nozzleTemp": 200}}"""
        val data = SettingsBackup.import(json)
        assertNotNull(data.sliceConfig)
        assertEquals(0.15f, data.sliceConfig!!.layerHeight, 0.001f)
        assertEquals(200, data.sliceConfig!!.nozzleTemp)
        assertNull(data.printersConfig)
        assertNull(data.filamentProfiles)
    }

    @Test
    fun `import handles missing optional fields with defaults`() {
        val json = """{"version": 1, "sliceConfig": {}}"""
        val data = SettingsBackup.import(json)
        assertNotNull(data.sliceConfig)
        assertEquals(0.2f, data.sliceConfig!!.layerHeight, 0.001f)
        assertEquals(210, data.sliceConfig!!.nozzleTemp)
        assertEquals("gyroid", data.sliceConfig!!.fillPattern)
    }

    @Test
    fun `import resolves filament profile names to new IDs`() {
        // Simulate: export with old IDs, then import and resolve names to new IDs
        val oldProfiles = mapOf(10L to "My PLA", 20L to "My PETG")
        val presets = listOf(
            ExtruderPreset(0, "#FF0000", "PLA", filamentProfileId = 10L),
            ExtruderPreset(1, "#00FF00", "PETG", filamentProfileId = 20L),
            ExtruderPreset(2, "#0000FF", "ABS"),
            ExtruderPreset(3, "#FFFF00", "TPU")
        )
        val json = SettingsBackup.export(
            SliceConfig(), SlicingOverrides(), "", presets, emptyList(),
            filamentNameResolver = { id -> oldProfiles[id] }
        )

        // Simulate what importBackup() does: parse names, then resolve to new IDs
        val root = JSONObject(json)
        val presetsArr = root.getJSONArray("extruderPresets")
        val parsed = SettingsBackup.parseExtruderPresetsWithNames(presetsArr)

        // Simulate newly-inserted profiles getting different IDs
        val newNameToId = mapOf("My PLA" to 99L, "My PETG" to 100L)
        val resolved = parsed.map { p ->
            val resolvedId = p.filamentProfileName?.let { newNameToId[it] }
            p.preset.copy(filamentProfileId = resolvedId)
        }

        assertEquals(99L, resolved[0].filamentProfileId)
        assertEquals(100L, resolved[1].filamentProfileId)
        assertNull(resolved[2].filamentProfileId)
        assertNull(resolved[3].filamentProfileId)
    }

    @Test
    fun `import without filamentProfileName leaves presets unlinked`() {
        // Backups from older versions won't have filamentProfileName
        val json = """{"version": 1, "extruderPresets": [
            {"slot": 1, "color": "#FF0000", "materialType": "PLA"},
            {"slot": 2, "color": "#00FF00", "materialType": "PETG"}
        ]}"""
        val root = JSONObject(json)
        val parsed = SettingsBackup.parseExtruderPresetsWithNames(root.getJSONArray("extruderPresets"))
        assertNull(parsed[0].filamentProfileName)
        assertNull(parsed[1].filamentProfileName)
        // Presets still import fine, just without profile links
        assertEquals(0, parsed[0].preset.index)
        assertEquals("#FF0000", parsed[0].preset.color)
    }

    @Test
    fun `extruder preset slot numbers are 1-based in JSON`() {
        val presets = listOf(ExtruderPreset(0, "#FF0000", "PLA"))
        val json = SettingsBackup.export(SliceConfig(), SlicingOverrides(), "", presets, emptyList())
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("extruderPresets")
        assertEquals(1, arr.getJSONObject(0).getInt("slot"))
    }

    @Test
    fun `import backup with legacy makerworld cookie keys does not throw`() {
        // Old backups contain these keys; importer must ignore them gracefully
        val json = """{"version": 1, "makerWorldCookies":"session=abc","makerWorldCookiesEnabled":true}"""
        val data = SettingsBackup.import(json)
        assertNotNull(data)
    }

    @Test
    fun `normalizeImportedSliceConfig clears stale skirt loops from backup`() {
        val imported = SliceConfig(skirtLoops = 1, brimWidth = 0f)
        val normalized = SlicerViewModel.normalizeImportedSliceConfig(imported)
        assertEquals(0, normalized.skirtLoops)
        assertEquals(0f, normalized.brimWidth, 0.001f)
    }

    // ---- F78: VERSION=2 multi-printer backups ----

    @Test
    fun `v1 backup imports as a single printer with legacy values`() {
        val v1Json = """
            {
              "version": 1,
              "printerUrl": "http://10.0.0.5",
              "extruderPresets": [
                {"slot":1,"color":"#FF0000","materialType":"PLA"},
                {"slot":2,"color":"#00FF00","materialType":"PETG"},
                {"slot":3,"color":"#0000FF","materialType":"PLA"},
                {"slot":4,"color":"#FFFFFF","materialType":"PLA"}
              ]
            }
        """.trimIndent()
        val data = SettingsBackup.import(v1Json)
        val cfg = data.printersConfig
        assertNotNull(cfg)
        assertEquals(1, cfg!!.printers.size)
        assertEquals("Printer 1", cfg.printers[0].nickname)
        assertEquals("http://10.0.0.5", cfg.printers[0].moonrakerUrl)
        assertEquals("#FF0000", cfg.printers[0].extruderPresets[0].color)
    }

    @Test
    fun `v2 backup imports all printers and preserves active`() {
        val v2Json = """
            {
              "version": 2,
              "printers": [
                {"id":"a","nickname":"P1","moonrakerUrl":"http://1","extruderPresets":[]},
                {"id":"b","nickname":"P2","moonrakerUrl":"http://2","extruderPresets":[]}
              ],
              "activePrinterId": "b",
              "printerUrl": "http://2",
              "extruderPresets": []
            }
        """.trimIndent()
        val data = SettingsBackup.import(v2Json)
        val cfg = data.printersConfig
        assertNotNull(cfg)
        assertEquals(2, cfg!!.printers.size)
        assertEquals("b", cfg.activeId)
    }

    @Test
    fun `v2 export includes legacy printerUrl from active printer for v1 rollback`() {
        val cfg = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "P1", moonrakerUrl = "http://1"),
                Printer(id = "b", nickname = "P2", moonrakerUrl = "http://2"),
            ),
            activeId = "b",
        )
        val json = SettingsBackup.export(SettingsBackup.BackupData(
            sliceConfig = null,
            slicingOverrides = null,
            printersConfig = cfg,
            filamentProfiles = null,
            makerWorldCookies = null,
        ))
        val obj = org.json.JSONObject(json)
        assertEquals(3, obj.getInt("version"))
        // Legacy duplicate fields populated from the active printer:
        assertEquals("http://2", obj.getString("printerUrl"))
        // Extra: full printers array also present
        assertEquals(2, obj.getJSONArray("printers").length())
        assertEquals("b", obj.getString("activePrinterId"))
    }

    @Test
    fun `v2 backup with both printers array and legacy fields uses the printers array`() {
        val json = """
            {
              "version": 2,
              "printers": [
                {"id":"new","nickname":"Modern","moonrakerUrl":"http://new","extruderPresets":[]}
              ],
              "activePrinterId": "new",
              "printerUrl": "http://stale-legacy",
              "extruderPresets": []
            }
        """.trimIndent()
        val data = SettingsBackup.import(json)
        val cfg = data.printersConfig
        assertNotNull(cfg)
        assertEquals(1, cfg!!.printers.size)
        assertEquals("Modern", cfg.printers[0].nickname)
        assertEquals("http://new", cfg.printers[0].moonrakerUrl)
    }

    @Test
    fun `unsupported backup version throws clear error`() {
        val json = """{"version": 999}"""
        try {
            SettingsBackup.import(json)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    // F87 — process profile round-trip
    @Test
    fun `v3 round-trip preserves process profiles + active id`() {
        val profile = ProcessProfile(
            id = "p1",
            name = "Fine 0.16",
            sourceName = "0.16mm Fine @Snapmaker U1",
            keys = mapOf(
                "layer_height" to "0.16",
                "wall_loops" to "3",
                "sparse_infill_pattern" to "gyroid"
            )
        )
        val ppCfg = ProcessProfilesConfig(profiles = listOf(profile), activeId = "p1")
        val json = SettingsBackup.export(
            sliceConfig = SliceConfig(),
            slicingOverrides = SlicingOverrides(),
            printerUrl = "",
            extruderPresets = emptyList(),
            filamentProfiles = emptyList(),
            processProfilesConfig = ppCfg
        )
        val obj = JSONObject(json)
        assertTrue("v3 export contains processProfiles array", obj.has("processProfiles"))
        assertEquals("p1", obj.optString("activeProcessProfileId"))

        val data = SettingsBackup.import(json)
        assertNotNull(data.processProfilesConfig)
        assertEquals(1, data.processProfilesConfig!!.profiles.size)
        val restored = data.processProfilesConfig!!.profiles[0]
        assertEquals("p1", restored.id)
        assertEquals("Fine 0.16", restored.name)
        assertEquals("0.16mm Fine @Snapmaker U1", restored.sourceName)
        assertEquals("0.16", restored.keys["layer_height"])
        assertEquals("3", restored.keys["wall_loops"])
        assertEquals("p1", data.processProfilesConfig!!.activeId)
    }

    @Test
    fun `v2 backup without process profiles imports cleanly`() {
        // v2 backup synthesised with only printers field — no processProfiles.
        val json = """
            {
              "version": 2,
              "sliceConfig": {},
              "slicingOverrides": {},
              "printers": [{"id":"p","nickname":"P","moonrakerUrl":"http://x","extruderPresets":[]}],
              "activePrinterId": "p"
            }
        """.trimIndent()
        val data = SettingsBackup.import(json)
        assertNull("v2 backup has no processProfilesConfig", data.processProfilesConfig)
    }

    // F91 — extended filament profile round-trip
    @Test
    fun `v3 round-trip preserves extended filament fields`() {
        val profile = FilamentProfile(
            id = 1L,
            name = "PLA Pro",
            material = "PLA",
            nozzleTemp = 220,
            bedTemp = 60,
            retractLength = 0.8f,
            retractSpeed = 45f,
            nozzleTempInitialLayer = 225,
            bedTempInitialLayer = 65,
            flowRatio = 0.98f,
            maxVolumetricSpeed = 18f,
            filamentCost = 25f,
            fanMinSpeed = 100,
            fanMaxSpeed = 100,
            overhangFanSpeed = 80,
            additionalCoolingFanSpeed = 70,
            slowDownLayerTime = 4f,
            slowDownMinSpeed = 20f,
            closeFanFirstLayers = 1,
            fullFanSpeedLayer = 3,
            enablePressureAdvance = true,
            pressureAdvance = 0.045f,
            filamentMinimalPurgeOnWipeTower = 15f,
        )
        val json = SettingsBackup.export(
            sliceConfig = SliceConfig(),
            slicingOverrides = SlicingOverrides(),
            printerUrl = "",
            extruderPresets = emptyList(),
            filamentProfiles = listOf(profile)
        )
        val data = SettingsBackup.import(json)
        assertNotNull(data.filamentProfiles)
        val p = data.filamentProfiles!![0]
        assertEquals(225, p.nozzleTempInitialLayer)
        assertEquals(65, p.bedTempInitialLayer)
        assertEquals(0.98f, p.flowRatio!!, 0.001f)
        assertEquals(18f, p.maxVolumetricSpeed!!, 0.001f)
        assertEquals(25f, p.filamentCost!!, 0.001f)
        assertEquals(100, p.fanMinSpeed)
        assertEquals(100, p.fanMaxSpeed)
        assertEquals(80, p.overhangFanSpeed)
        assertEquals(70, p.additionalCoolingFanSpeed)
        assertEquals(4f, p.slowDownLayerTime!!, 0.001f)
        assertEquals(20f, p.slowDownMinSpeed!!, 0.001f)
        assertEquals(1, p.closeFanFirstLayers)
        assertEquals(3, p.fullFanSpeedLayer)
        assertEquals(true, p.enablePressureAdvance)
        assertEquals(0.045f, p.pressureAdvance!!, 0.001f)
        assertEquals(15f, p.filamentMinimalPurgeOnWipeTower!!, 0.001f)
    }

    @Test
    fun `v1 backup without extended filament fields imports them as null`() {
        // Earlier-version backup with the basic 7-field filament shape only.
        val legacyJson = """
            {
              "version": 2,
              "filamentProfiles": [{
                "name": "Old PLA",
                "material": "PLA",
                "nozzleTemp": 210,
                "bedTemp": 60,
                "retractLength": 0.8,
                "retractSpeed": 45,
                "color": "#808080",
                "density": 1.24
              }]
            }
        """.trimIndent()
        val data = SettingsBackup.import(legacyJson)
        assertNotNull(data.filamentProfiles)
        val p = data.filamentProfiles!![0]
        assertEquals("Old PLA", p.name)
        assertEquals(210, p.nozzleTemp)
        assertNull(p.flowRatio)
        assertNull(p.maxVolumetricSpeed)
        assertNull(p.enablePressureAdvance)
        assertNull(p.filamentMinimalPurgeOnWipeTower)
    }

    @Test
    fun `export omits processProfiles when none provided`() {
        val json = SettingsBackup.export(
            SliceConfig(), SlicingOverrides(), "", emptyList(), emptyList()
        )
        val obj = JSONObject(json)
        assertFalse("no processProfiles key when no profiles supplied", obj.has("processProfiles"))
    }
}
