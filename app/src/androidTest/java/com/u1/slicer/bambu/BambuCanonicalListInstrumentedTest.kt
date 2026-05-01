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

    // ─── bambuCanonicalList — full path including paint segmentation ──────

    @Test
    fun coloredBenchy_returnsFileColourPlusPaintDerivedEntries() {
        // colored_3DBenchy declares 4 filaments in project_settings.config
        // but paint_color triangle attributes use OrcaSlicer's extended-state
        // encoding (B95 shape) — leaf states decode to higher numbers than
        // the 4 declared. The canonical list must size to address every
        // state the slicer will see.
        val file = asset("colored_3DBenchy (1).3mf")

        val list = bambuCanonicalList(file)
        assertNotNull("bambuCanonicalList must succeed for colored Benchy", list)
        assertTrue("size must be >= 4 (the declared filament_colour count)",
            list!!.size >= 4)
        assertTrue(list.isMultiColour)

        // First 4 entries are FILE_COLOUR — the file's declared filaments.
        for (i in 0..3) {
            assertEquals("entry $i fileIndex", i, list.filaments[i].fileIndex)
            assertEquals("entry $i source", FilamentSource.FILE_COLOUR,
                list.filaments[i].source)
        }

        // Anything beyond the declared 4 is a PAINT_DERIVED placeholder
        // sized so paint segmentation can address it.
        for (i in 4 until list.size) {
            assertEquals("entry $i source", FilamentSource.PAINT_DERIVED,
                list.filaments[i].source)
            assertEquals("entry $i fileIndex", i, list.filaments[i].fileIndex)
        }

        // Paint segmentation must produce a non-empty paintStateMap.
        assertTrue("paintStateMap must be non-empty for SEMM file",
            list.paintStateMap.isNotEmpty())

        // Identity: state N → fileIndex N-1, all in bounds.
        list.paintStateMap.forEach { (state, fileIdx) ->
            assertEquals("state $state must map to fileIndex ${state - 1}",
                state - 1, fileIdx)
            assertTrue("fileIdx $fileIdx must be in bounds [0, ${list.size})",
                fileIdx in 0 until list.size)
        }
    }

    @Test
    fun dieSingleColour_canonicalListMatchesFileColourOnly() {
        // No paint segmentation in the Die file → bambuCanonicalList should
        // return the same shape as bambuFileColourList plus an empty
        // paintStateMap.
        val file = asset("die-single-colour.3mf")

        val list = bambuCanonicalList(file)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertTrue(list.paintStateMap.isEmpty())
        assertEquals(FilamentSource.FILE_COLOUR, list.filaments[0].source)
        assertEquals("#A6A9AA", list.filaments[0].color)
    }
}
