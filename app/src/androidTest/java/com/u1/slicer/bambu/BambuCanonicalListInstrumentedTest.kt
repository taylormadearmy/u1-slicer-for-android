package com.u1.slicer.bambu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.FilamentSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 2.1 — instrumented coverage of [bambuFileColourList] reading a real
 * Bambu 3MF from disk.
 *
 * The pure-JSON contract is exercised in JVM-side
 * [com.u1.slicer.bambu.BambuCanonicalListTest]. This file verifies the ZIP
 * read path on actual fixtures.
 */
@RunWith(AndroidJUnit4::class)
class BambuCanonicalListInstrumentedTest {

    private lateinit var cacheDir: File

    @Before
    fun setup() {
        cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    }

    @After
    fun cleanup() {
        cacheDir.listFiles()?.filter { it.name.endsWith(".3mf") }?.forEach { it.delete() }
    }

    private fun asset(name: String): File {
        val file = File(cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    @Test
    fun dieSingleColour_returnsOneFileColourEntry() {
        // Fixture: G:/My Drive/tes-data/Die+Single+Colour+-+Die.3mf,
        // staged into androidTest/assets as die-single-colour.3mf.
        // project_settings.config:
        //   filament_colour      = ["#A6A9AA"]
        //   filament_type        = ["PLA"]
        //   filament_settings_id = ["Bambu PLA Basic @BBL X1C"]
        val file = asset("die-single-colour.3mf")

        val list = bambuFileColourList(file)
        assertNotNull("bambuFileColourList must succeed for valid Bambu 3MF", list)
        assertEquals(1, list!!.size)
        assertFalse(list.isMultiColour)
        assertTrue(list.paintStateMap.isEmpty())

        val only = list.filaments[0]
        assertEquals(0, only.fileIndex)
        assertEquals("#A6A9AA", only.color)
        assertEquals("PLA", only.materialType)
        assertEquals(FilamentSource.FILE_COLOUR, only.source)
    }

    @Test
    fun missingFile_returnsNull() {
        val nonExistent = File(cacheDir, "does-not-exist.3mf")
        assertNull(bambuFileColourList(nonExistent))
    }
}
