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
        assertEquals(1, plate.plateIndex)

        // Phase 1 sub-plan #5: plate.filamentColours now sourced from the
        // project-level filament_colour array via nativeGetProjectConfig. The
        // previous Kotlin path (detectedColors) ran a regex that truncated to 7
        // chars; the native reader preserves the raw stored hex, which for this
        // fixture is 8-char `#RRGGBBAA` for three of the four slots.
        // Sub-plan #2 will override per-plate when slice_filaments_info is set.
        assertEquals(
            listOf("#0086D6FF", "#FB0207", "#F4EE2AFF", "#E2DEDBFF"),
            plate.filamentColours
        )

        // Phase 1 sub-plan #5: filamentSettingsIds sourced from project config
        // (filament_settings_id with filament_ids fallback). Non-empty for
        // colored_3DBenchy since the file has a 4-slot project palette.
        assertTrue(
            "expected filamentSettingsIds non-empty post sub-plan #5, got ${plate.filamentSettingsIds}",
            plate.filamentSettingsIds.isNotEmpty()
        )

        // Plate → object instance map comes from model_settings.config's
        // `<model_instance><metadata key="object_id" .../></model_instance>`.
        // Instance IDs are not tracked by the current parser so they all land at 0.
        assertEquals(
            listOf(
                ObjectInstance(objectId = 2, instanceId = 0),
                ObjectInstance(objectId = 4, instanceId = 0)
            ),
            plate.objectInstanceMap
        )

        // Kotlin's layer-tool XML parser is empty for this file (no
        // custom_gcode_per_layer.xml entries on the single plate).
        assertEquals(emptyList<CustomGcodeEntry>(), plate.customGcode)

        // plateConfig: the Kotlin parsers do not split Bambu's per-plate config
        // (plate_N.config / plate_N.json) into a typed map. Left empty.
        assertEquals(emptyMap<String, String>(), plate.plateConfig)

        // objects: `ThreeMfInfo.objects` is populated from <object> elements in
        // 3D/3dmodel.model that have > 0 inline vertices. Benchy's main model
        // uses component refs to `3D/Objects/*.model` sub-files for the geometry,
        // so the root model has **zero** direct-geometry objects and this list
        // is empty. The per-plate `objectInstanceMap` above still identifies the
        // objects — this is a known Kotlin peculiarity that the diff harness will
        // surface vs the native loader (which merges component models into the
        // object list).
        assertEquals(emptyList<ObjectSnapshot>(), snapshot.objects)

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
