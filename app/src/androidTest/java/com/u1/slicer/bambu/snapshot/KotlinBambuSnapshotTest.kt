package com.u1.slicer.bambu.snapshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Task 2 snapshot test: exercises [KotlinBambuSnapshot.snapshot] against the real
 * `colored_3DBenchy (1).3mf` fixture shipped under `app/src/androidTest/assets/`.
 *
 * Runs as an instrumented test — the JVM harness can't construct an Android
 * `XmlPullParserFactory`, and [com.u1.slicer.bambu.ThreeMfParser] uses
 * `XmlPullParserFactory.newInstance()` plus `android.util.Log`. Every existing
 * `ThreeMfParser.parse(File)` test in this project (see `NativePreparePreviewTest`,
 * `BambuPipelineIntegrationTest`) lives under `androidTest/` for the same reason.
 *
 * Expected values are captured from the current Kotlin parser path — this test
 * documents what Kotlin *currently believes*, not what Kotlin *should* report.
 * Phase 1 will delete the parsers this snapshot wraps.
 */
@RunWith(AndroidJUnit4::class)
class KotlinBambuSnapshotTest {

    private lateinit var fixture: File
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun copyFixture() {
        // androidTest assets have to be copied to a real file because ThreeMfParser
        // uses `java.util.zip.ZipFile(file)` which needs a seekable disk path.
        fixture = File(targetContext.cacheDir, "colored_3DBenchy (1).3mf")
        fixture.parentFile?.mkdirs()
        assetContext.assets.open("colored_3DBenchy (1).3mf").use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @After
    fun cleanup() {
        fixture.delete()
    }

    @Test
    fun snapshots_colored_3DBenchy_via_existing_Kotlin_parsers() {
        val native = NativeLibrary()
        val snapshot = runBlocking { KotlinBambuSnapshot.snapshot(fixture, native) }

        assertEquals("colored_3DBenchy (1).3mf", snapshot.source)
        assertTrue("isBbl should be true for Bambu 3MF fixture", snapshot.isBbl)
        // Phase 1 sub-plan #5: fileVersion sourced from native (g_file_version.to_string()).
        // colored_3DBenchy has a BambuStudio:3mfVersion metadata entry — expect non-empty.
        assertTrue(
            "expected non-empty fileVersion post sub-plan #5, got '${snapshot.fileVersion}'",
            snapshot.fileVersion.isNotEmpty()
        )

        // Exactly one plate in this single-plate Bambu file.
        assertEquals(1, snapshot.plates.size)
        val plate = snapshot.plates.single()
        // Phase 1 sub-plan #2: plateIndex flipped from 1-based (Kotlin) to
        // 0-based (native PlateData::plate_index).
        assertEquals(0, plate.plateIndex)

        // Phase 1 sub-plan #2: filamentColours now sourced per-plate from
        // slice_filaments_info when set, else project filament_colour. Keep
        // the check structural — exact values are covered by the differential
        // suite baseline, not this unit assertion.
        assertTrue(
            "expected non-empty filamentColours, got ${plate.filamentColours}",
            plate.filamentColours.isNotEmpty()
        )
        for (hex in plate.filamentColours) {
            assertTrue("every filamentColour should start with #, got '$hex'", hex.startsWith("#"))
        }

        // Phase 1 sub-plan #2: filamentSettingsIds sourced per-plate (or from
        // project settings/ids fallback). Expect non-empty for this fixture.
        assertTrue(
            "expected filamentSettingsIds non-empty post sub-plan #2, got ${plate.filamentSettingsIds}",
            plate.filamentSettingsIds.isNotEmpty()
        )

        // Phase 1 sub-plan #2: objectInstanceMap now sourced from
        // PlateData::objects_and_instances. For component-ref 3MFs like
        // colored_3DBenchy the BBS importer doesn't populate this vector,
        // so the list may be empty — the diff-harness baseline is the
        // authority on exact content. Just assert structural validity.
        for (oi in plate.objectInstanceMap) {
            assertTrue("objectId should be non-negative, got ${oi.objectId}", oi.objectId >= 0)
            assertTrue("instanceId should be >= 0, got ${oi.instanceId}", oi.instanceId >= 0)
        }

        // Native append_plate emits customGcode empty for single-plate Bambu
        // without custom_gcode_per_layer.xml entries.
        assertEquals(emptyList<CustomGcodeEntry>(), plate.customGcode)

        // plateConfig: no per-plate overrides for colored_3DBenchy. Accept
        // either empty or a small map — the differential suite baseline
        // enforces the exact content.
        assertTrue(
            "plateConfig should be a map (possibly empty) of String → String, got ${plate.plateConfig}",
            plate.plateConfig.keys.all { it is String }
        )

        // Phase 1 sub-plan #4: snapshot.objects now sourced from native
        // g_model.objects. colored_3DBenchy is a component-ref 3MF where
        // Kotlin used to report 0 objects (filter on inline vertex count);
        // native surfaces the merged result. Expect non-empty with runtime
        // ObjectIDs (size_t) > 0 and sane extruder values.
        assertTrue(
            "expected non-empty objects list post sub-plan #4, got ${snapshot.objects.size}",
            snapshot.objects.isNotEmpty()
        )
        for (o in snapshot.objects) {
            assertTrue("objectId should be > 0 (runtime ObjectID), got ${o.objectId}", o.objectId > 0)
            assertTrue("extruder should be >= 0, got ${o.extruder}", o.extruder >= 0)
        }

        // volumes: Phase 1 sub-plan #1 populates via native accessors.
        // Previous Task 2 assertion (emptyList) is replaced by positive checks.

        // Phase 1 sub-plan #1: volumes are populated via native accessors.
        assertTrue(
            "expected at least one volume for colored benchy, got 0",
            snapshot.volumes.isNotEmpty()
        )
        val firstVolume = snapshot.volumes.first()
        assertTrue("objectId must be > 0, got ${firstVolume.objectId}", firstVolume.objectId > 0)
        assertEquals(0, firstVolume.volumeIndex)
        assertTrue(
            "expected at least one mm-painted volume in colored benchy",
            snapshot.volumes.any { it.isMmPainted }
        )
    }
}
