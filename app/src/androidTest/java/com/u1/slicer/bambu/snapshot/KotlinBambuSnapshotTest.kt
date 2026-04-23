package com.u1.slicer.bambu.snapshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        val snapshot = KotlinBambuSnapshot.snapshot(fixture)

        assertEquals("colored_3DBenchy (1).3mf", snapshot.source)
        assertTrue("isBbl should be true for Bambu 3MF fixture", snapshot.isBbl)
        // fileVersion is not exposed by the current Kotlin parsers — Task 2 leaves it empty.
        assertEquals("", snapshot.fileVersion)

        // Exactly one plate in this single-plate Bambu file.
        assertEquals(1, snapshot.plates.size)
        val plate = snapshot.plates.single()
        assertEquals(1, plate.plateIndex)

        // The Kotlin parser resolves colours from project_settings.config's
        // filament_colour array. The file's palette has 4 slots even though the
        // model itself is dual-colour, because project_settings.config lists the
        // full device palette. Exact captured values at the time of Task 2:
        //   [#0086D6, #FB0207, #F4EE2A, #E2DEDB]
        assertEquals(
            listOf("#0086D6", "#FB0207", "#F4EE2A", "#E2DEDB"),
            plate.filamentColours
        )

        // Kotlin doesn't parse filament_settings_id today — left empty so the
        // diff harness surfaces the gap vs the native loader.
        assertEquals(emptyList<String>(), plate.filamentSettingsIds)

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

        // volumes: left empty by Task 2. Volume-level paint state data is not
        // exposed by ThreeMfParser today — the diff harness will surface this
        // as a known gap.
        assertEquals(emptyList<VolumeSnapshot>(), snapshot.volumes)
    }
}
