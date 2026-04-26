package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.json.JSONArray
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
 * Phase 1 sub-plan #4: JNI smoke tests for the full-object-list accessor.
 * Asserts that nativeGetObjectExtruderMap returns a JSON array whose length
 * matches nativeGetObjectCount for a representative multi-part fixture,
 * returns null when no model is loaded, and surfaces merged component-ref
 * objects on colored_3DBenchy.
 */
@RunWith(AndroidJUnit4::class)
class NativeObjectExtruderMapTest {

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
            "obj_fixture_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        )
        assetContext.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun native_get_object_extruder_map_returns_null_when_no_model_loaded() {
        lib.clearModel()
        assertNull(lib.nativeGetObjectExtruderMap())
    }

    @Test
    fun native_get_object_extruder_map_returns_merged_objects_for_colored_benchy() {
        val fixture = copyFixture("colored_3DBenchy (1).3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val json = lib.nativeGetObjectExtruderMap()
            assertNotNull("component-ref fixture should return non-null array", json)
            val arr = JSONArray(json!!)
            assertTrue(
                "component-ref fixture should have >= 1 merged object, got ${arr.length()}",
                arr.length() >= 1
            )
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                assertTrue(
                    "object[$i] runtime objectId must be > 0, got ${o.getLong("objectId")}",
                    o.getLong("objectId") > 0L
                )
                assertTrue(o.has("name"))
                assertTrue(o.has("extruder"))
                assertTrue(o.has("sourcePath"))
            }
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun native_get_object_extruder_map_length_matches_native_get_object_count() {
        val fixture = copyFixture("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf")
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val json = lib.nativeGetObjectExtruderMap()
            assertNotNull(json)
            val arr = JSONArray(json!!)
            assertEquals(
                "nativeGetObjectExtruderMap array length must match nativeGetObjectCount",
                lib.nativeGetObjectCount(),
                arr.length()
            )
        } finally {
            fixture.delete()
        }
    }
}
