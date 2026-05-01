package com.u1.slicer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Phase 2.1 — instrumented coverage of the top-level [canonicalListAtLoad]
 * dispatcher across all supported file formats.
 */
@RunWith(AndroidJUnit4::class)
class CanonicalListAtLoadTest {

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
    fun bambu3mf_routesToBambuPath() {
        val file = asset("die-single-colour.3mf")
        val list = canonicalListAtLoad(file)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertEquals(FilamentSource.FILE_COLOUR, list.filaments[0].source)
        assertEquals("#A6A9AA", list.filaments[0].color)
    }

    @Test
    fun bambu3mf_semm_routesToBambuPath() {
        val file = asset("colored_3DBenchy (1).3mf")
        val list = canonicalListAtLoad(file)
        assertNotNull(list)
        assertTrue("colored Benchy must produce >= 4 entries", list!!.size >= 4)
        // First 4 are FILE_COLOUR (declared filaments)
        for (i in 0..3) {
            assertEquals(FilamentSource.FILE_COLOUR, list.filaments[i].source)
        }
    }

    @Test
    fun prusaSlicer3mf_korok_routesToPrusaPath() {
        val file = asset("PrusaSlicer-printables-Korok_mask_4colour.3mf")
        val list = canonicalListAtLoad(file)
        assertNotNull("Korok PrusaSlicer 3MF must dispatch successfully", list)
        // Korok declares 5 extruder colours
        assertEquals(5, list!!.size)
        assertEquals("#000000", list.filaments[0].color)
        assertEquals("#DB5182", list.filaments[1].color)
        assertEquals("#008000", list.filaments[2].color)
        assertEquals("#00FF00", list.filaments[3].color)
        assertEquals("#FBEB7D", list.filaments[4].color)
        list.filaments.forEach { e ->
            assertEquals(FilamentSource.FILE_COLOUR, e.source)
        }
    }

    @Test
    fun stlFile_returnsNull_callerSynthesises() {
        // STL files have no embedded filament data; the dispatcher returns
        // null and callers fall through to stlCanonicalList.
        val file = asset("3DBenchy.stl")
        assertNull(canonicalListAtLoad(file))

        // The fallback chain produces a single STL_DEFAULT entry.
        val synthesised = canonicalListAtLoad(file)
            ?: stlCanonicalList(presetColor = "#FF0000", presetMaterialType = "PLA")
        assertEquals(1, synthesised.size)
        assertEquals(FilamentSource.STL_DEFAULT, synthesised.filaments[0].source)
        assertEquals("#FF0000", synthesised.filaments[0].color)
    }

    @Test
    fun missingFile_returnsNull() {
        val nonExistent = File(cacheDir, "does-not-exist.3mf")
        assertNull(canonicalListAtLoad(nonExistent))
    }
}
