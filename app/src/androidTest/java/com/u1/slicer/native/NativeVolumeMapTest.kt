package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeVolumeMapTest {

    private lateinit var lib: NativeLibrary
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun copyAsset(name: String): File {
        val out = File(targetContext.cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        assetContext.assets.open(name).use { it.copyTo(out.outputStream()) }
        return out
    }

    @Before
    fun setup() {
        assertTrue("Native library must be loaded", NativeLibrary.isLoaded)
        lib = NativeLibrary()
    }

    @After
    fun teardown() { lib.clearModel() }

    @Test
    fun no_model_returns_null() {
        assertNull(lib.nativeGetAllVolumeExtruders())
    }

    @Test
    fun single_color_stl_has_one_object_one_volume() {
        val file = copyAsset("tetrahedron.stl")
        assertTrue(lib.loadModel(file.absolutePath))
        val json = lib.nativeGetAllVolumeExtruders()
        assertNotNull(json)
        val arr = JSONArray(json!!)
        assertEquals("one object", 1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals(0, obj.getInt("objectIndex"))
        val vols = obj.getJSONArray("volumes")
        assertTrue("at least one volume", vols.length() >= 1)
    }

    @Test
    fun dragon_scale_plate3_has_three_volume_extruders() {
        val file = copyAsset("Dragon Scale infinity.3mf")
        assertTrue(lib.loadModelForPlate(file.absolutePath, 2))
        val json = lib.nativeGetAllVolumeExtruders()
        assertNotNull(json)
        val arr = JSONArray(json!!)
        val allExtruders = mutableSetOf<Int>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val vols = obj.getJSONArray("volumes")
            for (j in 0 until vols.length()) {
                val ext = vols.getJSONObject(j).getInt("extruder")
                if (ext > 0) allExtruders.add(ext)
            }
        }
        assertTrue("Dragon plate 3 must have >= 3 distinct extruders, got $allExtruders",
            allExtruders.size >= 3)
    }

    @Test
    fun colored_benchy_reports_paint_data() {
        val file = copyAsset("colored_3DBenchy (1).3mf")
        assertTrue(lib.loadModel(file.absolutePath))
        val json = lib.nativeGetAllVolumeExtruders()
        assertNotNull(json)
        val arr = JSONArray(json!!)
        var hasPainted = false
        for (i in 0 until arr.length()) {
            val vols = arr.getJSONObject(i).getJSONArray("volumes")
            for (j in 0 until vols.length()) {
                if (vols.getJSONObject(j).getBoolean("isMmPainted")) hasPainted = true
            }
        }
        assertTrue("colored_3DBenchy must have at least one mm-painted volume", hasPainted)
    }
}
