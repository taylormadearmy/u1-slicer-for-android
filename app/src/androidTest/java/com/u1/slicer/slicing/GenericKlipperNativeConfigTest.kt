package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SlicerTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Offline slicing only. These tests never connect to or start a physical printer. */
@RunWith(AndroidJUnit4::class)
class GenericKlipperNativeConfigTest {
    private lateinit var native: NativeLibrary
    private lateinit var model: File
    private lateinit var profileFile: File

    @Before fun setUp() {
        assertTrue(NativeLibrary.isLoaded)
        native = NativeLibrary()
        native.clearModel()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        model = File(context.cacheDir, "generic-klipper-tetrahedron.stl")
        profileFile = File(context.cacheDir, "generic-klipper-selected-profile.json")
        InstrumentationRegistry.getInstrumentation().context.assets.open("tetrahedron.stl").use { source -> model.outputStream().use(source::copyTo) }
        assertTrue(native.loadModel(model.absolutePath))
        assertTrue(native.setModelInstances(floatArrayOf(100f, 100f)))
    }

    @After fun tearDown() {
        native.clearModel()
        model.delete()
        profileFile.delete()
    }

    private fun generic(): GenericMachineProfile = GenericMachineProfile.fromJson(JSONObject().apply {
        put("name", "Fixture Klipper")
        put("gcode_flavor", "klipper")
        put("nozzle_diameter", JSONArray(listOf("0.4")))
        put("printable_area", JSONArray(listOf("0x0", "220x0", "220x230", "0x230")))
        put("printable_height", "240")
        put("machine_start_gcode", "G28\nM190 S[bed_temperature_initial_layer_single]\nM109 S[nozzle_temperature_initial_layer]\n; GENERIC_START_SENTINEL")
        put("machine_end_gcode", "M104 S0\nM140 S0\n; GENERIC_END_SENTINEL")
        put("retraction_speed", JSONArray(listOf("27")))
        put("deretraction_speed", JSONArray(listOf("28")))
        put("machine_max_speed_x", JSONArray(listOf("180", "180")))
    }.toString())

    private fun select(profile: GenericMachineProfile) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        profileFile.writeText(ProfileEmbedder(context).serializeConfig(profile.compose()))
        assertTrue("Selected profile must load", native.loadProfile(profileFile.absolutePath))
    }

    @Test fun genericRawStlUsesSelectedMachineWithoutU1MacrosOrTuning() {
        val profile = generic()
        select(profile)
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), profile))
        assertTrue(result?.errorMessage, result?.success == true)
        val gcode = File(result!!.gcodePath).readText()
        assertTrue(gcode.contains("; printable_area = 0x0,220x0,220x230,0x230"))
        assertTrue(gcode.contains("; printable_height = 240"))
        assertTrue(gcode.contains("; gcode_flavor = klipper"))
        assertTrue(gcode.contains("; nozzle_diameter = 0.4"))
        assertTrue(gcode.contains("; deretraction_speed = 28"))
        assertTrue(gcode.contains("; machine_max_speed_x = 180,180"))
        assertTrue(gcode.contains("GENERIC_START_SENTINEL"))
        assertTrue(gcode.contains("GENERIC_END_SENTINEL"))
        assertFalse(gcode.contains("SM_PRINT"))
        assertFalse(gcode.contains("PRINT_START"))
        assertFalse(gcode.contains("; printable_area = 0x0,270x0"))
        assertTrue(gcode.contains("; single_extruder_multi_material = 0"))
        assertTrue(gcode.contains("; enable_prime_tower = 0"))
    }

    @Test fun selectedPetgHeatsToResolvedTemperaturesInExecutableGcode() {
        val profile = generic()
        val tuning = com.u1.slicer.slice.buildGenericFilamentSettings(
            mapOf("filament_type" to listOf("PETG"), "nozzle_temperature" to listOf("245"),
                "nozzle_temperature_initial_layer" to listOf("245"), "bed_temperature" to listOf("80")),
            emptyMap(),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        profileFile.writeText(ProfileEmbedder(context).serializeConfig(profile.compose(overrides = tuning)))
        assertTrue(native.loadProfile(profileFile.absolutePath))
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), profile))
        assertTrue(result?.errorMessage, result?.success == true)
        val commands = File(result!!.gcodePath).readLines().filterNot { it.startsWith(";") }
        assertTrue(commands.any { it.startsWith("M109 S245") })
        assertTrue(commands.any { it.startsWith("M190 S80") })
        assertFalse(commands.any { it.startsWith("M109 S210") })
    }

    @Test fun centauriCarbonRawStlRetainsOfficialTemplatesAndExclusion() {
        val profile = GenericMachineProfile.centauriCarbon()
        select(profile)
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.ElegooCentauriCarbon, SliceConfig()))
        assertTrue(result?.errorMessage, result?.success == true)
        val gcode = File(result!!.gcodePath).readText()
        assertTrue(gcode.contains("; printable_area = 0x0,257x0,257x257,0x257"))
        assertTrue(gcode.contains("; printable_height = 257"))
        assertTrue(gcode.contains("; bed_exclude_area = 246x0,256x0,256x20,246x20"))
        assertTrue(gcode.contains("M729 ;Clean Nozzle"))
        assertTrue(gcode.contains("M84 ;Disable all steppers"))
        assertTrue(gcode.contains("SET_PRINT_STATS_INFO TOTAL_LAYER="))
        assertFalse(gcode.contains("SM_PRINT"))
        assertFalse(gcode.contains("PRINT_START"))
        assertFalse(gcode.lineSequence().any { it.matches(Regex("T[1-9][0-9]*")) })
    }

    @Test fun staleSelectedProfileCannotSatisfyDifferentMachineTemplates() {
        select(generic())
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.ElegooCentauriCarbon, SliceConfig()))
        assertFalse(result?.success == true)
        assertTrue(result?.errorMessage?.contains("missing or inconsistent") == true)
    }

    @Test fun selectedKlipperProfileDoesNotLeakIntoBambuSlice() {
        select(generic())
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.BambuA1Mini, SliceConfig()))
        assertTrue(result?.errorMessage, result?.success == true)
        val gcode = File(result!!.gcodePath).readText()
        assertTrue(gcode.contains("; printable_area = 0x0,180x0,180x180,0x180"))
        assertFalse(gcode.contains("GENERIC_START_SENTINEL"))
        assertTrue(gcode.contains("; gcode_flavor = marlin"))
    }

    @Test fun staleProfileWithSameTemplatesButDifferentGeometryOrNozzleFails() {
        val original = generic()
        select(original)
        val variants = listOf(
            "printable_area" to JSONArray(listOf("0x0", "210x0", "210x230", "0x230")),
            "printable_height" to "200",
            "nozzle_diameter" to JSONArray(listOf("0.6")),
        )
        variants.forEach { (key, value) ->
            val newProfile = GenericMachineProfile.fromJson(JSONObject(original.resolvedJson).put(key, value).toString())
            val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), newProfile))
            assertFalse("Stale profile must fail for $key", result?.success == true)
            assertTrue(result?.errorMessage?.contains("does not match the slice target") == true)
        }
    }

    @Test fun genericRejectsModelAbovePrintableHeight() {
        val profile = generic()
        select(profile)
        assertTrue(native.setModelScale(1f, 1f, 30f))
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), profile))
        assertFalse(result?.success == true)
        assertTrue(result?.errorMessage?.contains("printable volume") == true)
    }

    @Test fun centauriCarbonRejectsModelOverExclusion() {
        select(GenericMachineProfile.centauriCarbon())
        assertTrue(native.setModelInstances(floatArrayOf(246f, 5f)))
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.ElegooCentauriCarbon, SliceConfig()))
        assertFalse(result?.success == true)
        assertTrue(result?.errorMessage?.contains("bed exclusion") == true)
    }

    @Test fun genericRejectsModelLargerThanSelectedBed() {
        val profile = GenericMachineProfile.fromJson(JSONObject(generic().resolvedJson).apply {
            put("printable_area", JSONArray(listOf("0x0", "5x0", "5x5", "0x5")))
        }.toString())
        select(profile)
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), profile))
        assertFalse(result?.success == true)
        assertTrue(result?.errorMessage?.contains("larger than") == true)
    }

    @Test fun genericRejectsGeneratedSkirtBeyondSelectedBed() {
        val profile = generic()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        profileFile.writeText(ProfileEmbedder(context).serializeConfig(profile.compose(overrides = mapOf(
            "skirt_loops" to "1", "skirt_distance" to "20", "skirt_height" to "1",
        ))))
        assertTrue(native.loadProfile(profileFile.absolutePath))
        assertTrue(native.setModelInstances(floatArrayOf(1f, 100f)))
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), profile))
        assertFalse(result?.success == true)
        assertTrue(result?.errorMessage?.contains("printable volume") == true)
    }

    @Test fun genericRejectsToolOutsideSelectedTopology() {
        val profile = generic()
        select(profile)
        assertTrue(native.nativeSetVolumeExtruder(0, 0, 2))
        val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), profile))
        assertFalse(result?.success == true)
        assertTrue(result?.errorMessage?.contains("Model tool") == true)
    }

    @Test fun selectedMarlinProfileRejectsKlipperTargetAndAcceptsMarlinAlias() {
        val json = JSONObject(generic().resolvedJson).put("gcode_flavor", "marlin")
        select(GenericMachineProfile.fromJson(json.toString()))
        val klipperConfig = resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), generic())
        val rejected = native.slice(klipperConfig)
        assertFalse(rejected?.success == true)
        assertTrue(rejected?.errorMessage?.contains("missing or inconsistent") == true)
        val accepted = native.slice(klipperConfig.copy(machineTarget = "GENERIC_MARLIN"))
        assertTrue(accepted?.errorMessage, accepted?.success == true)
        val gcode = File(accepted!!.gcodePath).readText()
        assertTrue(gcode.contains("; gcode_flavor = marlin"))
        assertFalse(gcode.contains("EXCLUDE_OBJECT_"))
        assertFalse(gcode.contains("SM_PRINT"))
    }

    @Test fun importedPerLayerMacroDoesNotLeakIntoGenericOutput() {
        val source = File(model.parentFile, "generic-foreign-hook.3mf")
        try {
            ZipOutputStream(source.outputStream()).use { zip ->
                fun entry(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
                entry("_rels/.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""")
                entry("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""")
                entry("3D/3dmodel.model", """<?xml version="1.0"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/></vertices><triangles><triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/><triangle v1="1" v2="2" v3="3"/><triangle v1="2" v2="0" v3="3"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>""")
                entry("Metadata/custom_gcode_per_layer.xml", """<?xml version="1.0"?><custom_gcodes_per_layer><plate><plate_info id="1"/><layer top_z="1.0" type="4" extruder="1" color="" extra="SM_PRINT_FOREIGN_SOURCE_HOOK"/><mode value="SingleExtruder"/></plate></custom_gcodes_per_layer>""")
            }
            assertTrue(native.loadModel(source.absolutePath))
            assertTrue(native.setModelInstances(floatArrayOf(100f, 100f)))
            val profile = generic()
            select(profile)
            val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), profile))
            assertTrue(result?.errorMessage, result?.success == true)
            assertFalse(File(result!!.gcodePath).readText().contains("SM_PRINT_FOREIGN_SOURCE_HOOK"))
        } finally { source.delete() }
    }
}
