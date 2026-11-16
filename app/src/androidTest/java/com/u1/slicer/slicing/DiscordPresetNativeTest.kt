package com.u1.slicer.slicing

import androidx.activity.result.contract.ActivityResultContracts
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.AppDatabase
import com.u1.slicer.data.ProcessProfileParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.machine.*
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SlicerTarget
import com.u1.slicer.ui.parseFilamentJson
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline import and slice checks. Never connects to a printer. */
@RunWith(AndroidJUnit4::class)
class DiscordPresetNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String) = instrumentation.context.assets.open("imports/discord-u1/$name")
    private fun importer() = OrcaProfileBundleImporter(
        loadMissing = { vendor, type, name ->
            if (vendor != "Snapmaker") null else runCatching {
                fixture("parents/${type.name.lowercase()}/$name.json").bufferedReader().use { it.readText() }
            }.getOrNull()
        },
        validateMachine = NativeMachineProfileValidator(context)::validate,
        parseFilaments = { parseFilamentJson(it) },
        parseProcesses = ProcessProfileParser::parse,
    )

    @Test fun unrestrictedPickerAllowsAnyProviderMimeType() {
        val intent = ActivityResultContracts.OpenDocument().createIntent(context, arrayOf("*/*"))
        assertEquals("*/*", intent.type)
        assertArrayEquals(arrayOf("*/*"), intent.getStringArrayExtra(android.content.Intent.EXTRA_MIME_TYPES))
    }

    @Test fun u1EmbeddedProfileUsesImportedChamberTarget() {
        val source = File(context.cacheDir, "discord-chamber-source.3mf")
        val native = NativeLibrary()
        var embedded: File? = null
        try {
            native.clearModel()
            instrumentation.context.assets.open("calib-cube-10-dual-colour-merged.3mf").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val info = com.u1.slicer.bambu.ThreeMfParser.parse(source)
            val embedder = ProfileEmbedder(context)
            val process = runBlocking { fixture("process.json").use { importer().import(it, "Supports.json", OrcaProfileType.PROCESS) } }.processes.single()
            val settings = embedder.buildConfig(info,
                filamentSettings = mapOf("chamber_temperature" to listOf("65", "0")),
                processProfileKeys = process.keys,
                overrides = mapOf("machine_start_gcode" to "G28\n; IMPORTED_CHAMBER={overall_chamber_temperature}",
                    "machine_end_gcode" to "M104 S0\nM140 S0"), targetExtruderCount = 2)
            embedded = embedder.embed(source, settings, context.cacheDir, info)
            assertTrue(native.loadModel(embedded.absolutePath))
            assertTrue(native.setModelInstances(floatArrayOf(100f, 100f)))
            val result = native.slice(SliceConfig(extruderCount = 2, wipeTowerEnabled = false))
            assertTrue(result?.errorMessage, result?.success == true)
            val gcode = File(result!!.gcodePath).readText()
            assertTrue(gcode.contains("; IMPORTED_CHAMBER=65"))
            assertTrue(gcode.contains("; support_top_z_distance = 0"))
            assertTrue(gcode.contains("; support_ironing = 1"))
        } finally {
            native.clearModel()
            source.delete()
            embedded?.delete()
        }
    }

    @Test fun attachedMachineAndProcessProduceCustomEnclosureGcode() = runBlocking {
        val imported = fixture("machine.json").use { importer().import(it, "Testing.json", OrcaProfileType.MACHINE) }
        val process = fixture("process.json").use { importer().import(it, "Supports.json", OrcaProfileType.PROCESS) }.processes.single()
        val profile = GenericMachineProfile.fromJson(imported.machines.single().machineJson)
        val native = NativeLibrary()
        val model = File(context.cacheDir, "discord-import-test.stl")
        val selected = File(context.cacheDir, "discord-import-test.json")
        try {
            native.clearModel()
            context.assets.open(NativeMachineProfileValidator.VALIDATION_MODEL_ASSET).use { source -> model.outputStream().use(source::copyTo) }
            assertTrue(native.loadModel(model.absolutePath))
            assertTrue(native.setModelInstances(floatArrayOf(100f, 100f)))
            val chamber = parseFilamentJson("""{"type":"filament","name":"Test ASA","chamber_temperature":["65"]}""").single()
            val library = com.u1.slicer.buildFilamentLibrarySettings(4, null,
                listOf(com.u1.slicer.data.ExtruderPreset(index = 0, filamentProfileId = 1)), listOf(chamber.copy(id = 1)))
            selected.writeText(ProfileEmbedder(context).serializeConfig(profile.compose(overrides = process.keys + library)))
            assertTrue(native.loadProfile(selected.absolutePath))
            val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper,
                SliceConfig(layerHeight = 0.16f, firstLayerHeight = 0.16f, extruderCount = 4, wipeTowerEnabled = false), profile))
            assertTrue(result?.errorMessage, result?.success == true)
            val gcode = File(result!!.gcodePath).readText()
            assertTrue(gcode.contains("M141 S65"))
            assertTrue(gcode.contains("SET_PURIFIER_MODE MODE=3 DESIRE_TEMP=65"))
            assertTrue(gcode.contains("WAIT_CHAMBER_TEMP TIMEOUT=180"))
            assertTrue(gcode.contains("SET_MAX_Z_ACCEL A=500"))
            assertTrue(gcode.contains("SET_MAX_Z_VELOCITY V=20"))
            assertTrue(gcode.contains("PRINT_END"))
            assertTrue(gcode.contains("; layer_height = 0.16"))
            assertTrue(gcode.contains("; printable_area = 0.5x1,270.5x1,270.5x271,0.5x271"))
            assertTrue(gcode.contains("; outer_wall_speed = 150"))
            assertTrue(gcode.contains("; support_interface_speed = 20"))
            assertTrue(gcode.contains("; support_top_z_distance = 0"))
            assertTrue(gcode.contains("; support_ironing = 1"))
        } finally {
            native.clearModel()
            model.delete()
            selected.delete()
        }
    }

    @Test fun chamberMigrationPreservesExistingFilamentsAndStoresNewTargets() {
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE filament_profiles (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
                    db.execSQL("INSERT INTO filament_profiles VALUES (1, 'Existing PLA')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_9_10.migrate(db)
            db.query("SELECT name, chamberTemperature FROM filament_profiles WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("Existing PLA", it.getString(0))
                assertTrue(it.isNull(1))
            }
            db.execSQL("UPDATE filament_profiles SET chamberTemperature = 65 WHERE id = 1")
            db.query("SELECT chamberTemperature FROM filament_profiles WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals(65, it.getInt(0))
            }
        }
    }

    @Test fun insetPrintableAreaRejectsModelAtExcludedOrigin() = runBlocking {
        val imported = fixture("machine.json").use { importer().import(it, "Testing.json", OrcaProfileType.MACHINE) }
        val profile = GenericMachineProfile.fromJson(imported.machines.single().machineJson)
        val native = NativeLibrary()
        val model = File(context.cacheDir, "discord-inset-origin.stl")
        val selected = File(context.cacheDir, "discord-inset-origin.json")
        try {
            native.clearModel()
            context.assets.open(NativeMachineProfileValidator.VALIDATION_MODEL_ASSET).use { input ->
                model.outputStream().use(input::copyTo)
            }
            assertTrue(native.loadModel(model.absolutePath))
            selected.writeText(ProfileEmbedder(context).serializeConfig(profile.compose()))
            assertTrue(native.loadProfile(selected.absolutePath))
            assertTrue(native.setModelInstances(floatArrayOf(0f, 0f)))
            val result = native.slice(resolveTargetedSliceConfig(SlicerTarget.GenericKlipper, SliceConfig(), profile))
            assertFalse(result?.success == true)
            assertTrue(result?.errorMessage.orEmpty(), result?.errorMessage.orEmpty().contains("printable volume"))
        } finally {
            native.clearModel()
            model.delete()
            selected.delete()
        }
    }
}
