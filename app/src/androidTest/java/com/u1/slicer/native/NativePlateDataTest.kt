package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 1 sub-plan #2: JNI smoke tests for the per-plate accessors.
 * Loads a few representative Bambu fixtures and asserts that
 * nativeGetPlateCount / nativeGetPlateData return the expected shapes.
 */
@RunWith(AndroidJUnit4::class)
class NativePlateDataTest {

    private lateinit var lib: NativeLibrary

    @Before
    fun setup() {
        assertTrue(NativeLibrary.isLoaded)
        lib = NativeLibrary()
    }

    @After
    fun teardown() {
        lib.clearModel()
    }

    private fun copyFixture(name: String): File {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(
            targetContext.cacheDir,
            "plate_fixture_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        )
        assetContext.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun native_get_plate_count_returns_zero_when_no_model_loaded() {
        lib.clearModel()
        assertEquals(0, lib.nativeGetPlateCount())
    }

    @Test
    fun native_get_plate_data_returns_null_when_no_model_loaded() {
        lib.clearModel()
        assertNull(lib.nativeGetPlateData(0))
    }

    @Test
    fun native_get_plate_data_returns_single_plate_for_colored_benchy() {
        val fixture = copyFixture("colored_3DBenchy (1).3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            assertEquals(1, lib.nativeGetPlateCount())
            val json = lib.nativeGetPlateData(0)
            assertNotNull("plate 0 JSON should be non-null for colored_3DBenchy", json)
            val obj = JSONObject(json!!)
            assertEquals(0, obj.getInt("plateIndex"))
            // objectInstanceMap is present and structurally valid (may be empty
            // for component-ref 3MFs where the BBS importer doesn't populate
            // PlateData::objects_and_instances — the diff harness baseline is
            // the authority on exact content).
            val instances = obj.getJSONArray("objectInstanceMap")
            for (i in 0 until instances.length()) {
                val inst = instances.getJSONObject(i)
                assertTrue(inst.has("objectId"))
                assertTrue(inst.has("instanceId"))
            }
            // Every colour entry is #-prefixed.
            val colours = obj.getJSONArray("filamentColours")
            assertTrue(colours.length() > 0)
            for (i in 0 until colours.length()) {
                assertTrue(
                    "filamentColours[$i] must start with #, got '${colours.getString(i)}'",
                    colours.getString(i).startsWith("#")
                )
            }
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun native_get_plate_data_returns_per_plate_entries_for_multi_plate_buzz() {
        val fixture = copyFixture("Buzz_Multipart_3MF_Bambu.3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val count = lib.nativeGetPlateCount()
            assertTrue("Buzz multi-plate fixture should have > 1 plate, got $count", count > 1)
            for (pi in 0 until count) {
                val json = lib.nativeGetPlateData(pi)
                assertNotNull("plate $pi JSON should be non-null", json)
                val obj = JSONObject(json!!)
                assertEquals(
                    "plateIndex should match positional index (0-based)",
                    pi,
                    obj.getInt("plateIndex")
                )
            }
            assertNull(lib.nativeGetPlateData(count))
            assertNull(lib.nativeGetPlateData(-1))
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun native_get_plate_data_emits_custom_gcode_for_layer_tool_fixture() {
        val fixture = copyFixture("flippy+flappy+mini-with-plate-painted.3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val count = lib.nativeGetPlateCount()
            assertTrue(count >= 1)
            var sawCustomGcode = false
            for (pi in 0 until count) {
                val json = lib.nativeGetPlateData(pi) ?: continue
                val obj = JSONObject(json)
                if (obj.getJSONArray("customGcode").length() > 0) {
                    sawCustomGcode = true
                    break
                }
            }
            assertTrue("expected at least one plate with customGcode entries", sawCustomGcode)
        } finally {
            fixture.delete()
        }
    }
}
