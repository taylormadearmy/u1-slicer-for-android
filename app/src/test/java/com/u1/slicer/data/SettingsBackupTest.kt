package com.u1.slicer.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {

    @Test
    fun `export produces valid JSON with version field`() {
        val json = SettingsBackup.export(
            SliceConfig(), SlicingOverrides(), "", emptyList(), emptyList()
        )
        val obj = JSONObject(json)
        assertEquals(1, obj.getInt("version"))
        assertTrue(obj.has("sliceConfig"))
        assertTrue(obj.has("slicingOverrides"))
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
        assertEquals("192.168.0.151", data.printerUrl)
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
        assertNotNull(data.extruderPresets)
        assertEquals(4, data.extruderPresets!!.size)
        assertEquals(0, data.extruderPresets!![0].index)
        assertEquals("#FF0000", data.extruderPresets!![0].color)
        assertEquals("PLA", data.extruderPresets!![0].materialType)
        assertEquals(3, data.extruderPresets!![3].index)
        assertEquals("#FFFF00", data.extruderPresets!![3].color)
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
                bedTemp = 60, printSpeed = 60f, retractLength = 0.8f, retractSpeed = 45f,
                color = "#FF5733", density = 1.24f),
            FilamentProfile(name = "Test PETG", material = "PETG", nozzleTemp = 240,
                bedTemp = 80, printSpeed = 50f, retractLength = 1.0f, retractSpeed = 40f)
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
        assertNull(data.extruderPresets)
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
    fun `round-trip preserves MakerWorld cookies`() {
        val json = SettingsBackup.export(
            SliceConfig(), SlicingOverrides(), "", emptyList(), emptyList(),
            makerWorldCookies = "session_id=abc123; token=xyz",
            makerWorldCookiesEnabled = true
        )
        val data = SettingsBackup.import(json)
        assertEquals("session_id=abc123; token=xyz", data.makerWorldCookies)
        assertEquals(true, data.makerWorldCookiesEnabled)
    }

    @Test
    fun `import without MakerWorld cookies returns null`() {
        val json = SettingsBackup.export(SliceConfig(), SlicingOverrides(), "", emptyList(), emptyList())
        val data = SettingsBackup.import(json)
        assertNull(data.makerWorldCookies)
        // cookiesEnabled should still be present (defaults to false)
        assertEquals(false, data.makerWorldCookiesEnabled)
    }
}
