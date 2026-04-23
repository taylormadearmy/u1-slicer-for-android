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
        assertTrue("plates array should be present", root.has("plates"))
    }
}
