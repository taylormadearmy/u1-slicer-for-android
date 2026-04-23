package com.u1.slicer.bambu.snapshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeDumpSmokeTest {
    @Test
    fun nativeDumpBambuModel_returns_JSON_with_header_fields_for_a_Bambu_fixture() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val tmp = File(targetContext.cacheDir, "colored_3DBenchy.3mf")
        assetContext.assets.open("colored_3DBenchy (1).3mf").use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }

        val native = NativeLibrary()
        assertTrue("loadModel must succeed", native.loadModel(tmp.absolutePath))

        val json = native.nativeDumpBambuModel(tmp.absolutePath)
        assertNotNull("nativeDumpBambuModel returned null", json)

        val root = JSONObject(json!!)
        assertEquals("colored_3DBenchy.3mf", root.getString("source"))
        assertTrue("isBbl should be true for a Bambu Studio file", root.getBoolean("isBbl"))
        val fileVersion = root.getString("fileVersion")
        // Either empty (Semver missing/invalid in file) or a 3-or-4-part dotted version (Semver::to_string format).
        assertTrue(
            "fileVersion must be empty or 3/4-part dotted version, got: '$fileVersion'",
            fileVersion.isEmpty() || fileVersion.matches(Regex("""^\d+\.\d+\.\d+(\.\d+)?$"""))
        )
        assertTrue("plates array should be present", root.has("plates"))
        assertTrue("objects array should be present", root.has("objects"))
        assertTrue("volumes array should be present", root.has("volumes"))
    }

    @Test
    fun nativeDumpBambuModel_populates_per_plate_filament_colours_and_custom_gcode_for_flippy_multiplate() {
        // flippy+flappy+mini.3mf ships a multi-plate file with a non-trivial
        // Metadata/custom_gcode_per_layer.xml (several plates carry layer
        // tool-change entries) and a 5-colour project palette — both the
        // customGcode and filamentColours fallback paths get exercised here.
        // colored_3DBenchy was the original fixture choice in the plan but
        // its custom_gcode_per_layer.xml is a stub with zero <layer> entries,
        // so it can't validate the customGcode emission.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val tmp = File(targetContext.cacheDir, "flippy_flappy_mini_p5.3mf")
        assetContext.assets.open("flippy+flappy+mini.3mf").use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val native = NativeLibrary()
        assertTrue(native.loadModel(tmp.absolutePath))
        val snapshot = NativeBambuSnapshot.parse(native.nativeDumpBambuModel(tmp.absolutePath)!!)

        assertTrue(
            "expected multiple plates for flippy multi-plate file, got ${snapshot.plates.size}",
            snapshot.plates.size >= 2
        )

        // Each plate surfaces the filaments actually used on that plate (BBS's
        // PlateData.slice_filaments_info is populated from slice_info.config's
        // <plater> block and can be 1 colour on single-colour plates, 2+ on
        // multi-colour plates). Hex format is required.
        snapshot.plates.forEach { plate ->
            assertTrue(
                "plate ${plate.plateIndex}: expected at least 1 filament colour, got ${plate.filamentColours}",
                plate.filamentColours.isNotEmpty()
            )
            plate.filamentColours.forEach {
                assertTrue("colour must be hex (#RRGGBB or #RRGGBBAA), got: $it", it.startsWith("#"))
            }
        }

        // Proof the per-plate emission reflects reality: at least one plate
        // in this multi-colour file must carry >= 2 distinct filament colours
        // (flippy+flappy+mini has dual-colour plates).
        assertTrue(
            "expected at least one plate with >= 2 filament colours, got sizes ${snapshot.plates.map { it.filamentColours.size }}",
            snapshot.plates.any { it.filamentColours.size >= 2 }
        )

        // Several plates in flippy ship layer tool-change entries. Assert
        // that at least one plate's customGcode list is non-empty — pins
        // the customGcode emission path without over-pinning specific values
        // (Task 9 corpus runner pins exact content).
        assertTrue(
            "expected at least one plate to carry customGcode entries, got sizes ${snapshot.plates.map { it.customGcode.size }}",
            snapshot.plates.any { it.customGcode.isNotEmpty() }
        )
    }

    @Test
    fun nativeDumpBambuModel_populates_objects_with_extruder_for_colored_3DBenchy() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val tmp = File(targetContext.cacheDir, "colored_3DBenchy_o.3mf")
        assetContext.assets.open("colored_3DBenchy (1).3mf").use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val native = NativeLibrary()
        assertTrue(native.loadModel(tmp.absolutePath))
        val snapshot = NativeBambuSnapshot.parse(native.nativeDumpBambuModel(tmp.absolutePath)!!)

        assertTrue("expected at least one object, got ${snapshot.objects.size}",
                   snapshot.objects.isNotEmpty())
        snapshot.objects.forEach {
            assertTrue("extruder must be 0..32 (0=inherit), got ${it.extruder}",
                       it.extruder in 0..32)
        }
    }

    @Test
    fun nativeDumpBambuModel_populates_volume_paintStateSet_for_H2C_benchy() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val tmp = File(targetContext.cacheDir, "h2c_benchy.3mf")
        assetContext.assets.open("3DBenchy-H2C-Multi-Color.3mf").use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val native = NativeLibrary()
        assertTrue(native.loadModel(tmp.absolutePath))
        val snap = NativeBambuSnapshot.parse(native.nativeDumpBambuModel(tmp.absolutePath)!!)

        assertTrue("expected at least one volume, got ${snap.volumes.size}",
                   snap.volumes.isNotEmpty())
        val mmPaintedVolumes = snap.volumes.filter { it.isMmPainted }
        assertTrue("H2C benchy should have mm-painted volumes",
                   mmPaintedVolumes.isNotEmpty())
        val totalPaintStates = mmPaintedVolumes.flatMap { it.paintStateSet.keys }.toSet()
        assertTrue("H2C benchy is known to use 5+ paint states, got: $totalPaintStates",
                   totalPaintStates.size >= 5)
        mmPaintedVolumes.forEach { vol ->
            vol.paintStateSet.values.forEach { count ->
                assertTrue("triangle count must be positive, got $count", count > 0)
            }
        }
    }
}
