package com.u1.slicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BambuEmbedPrecedenceWiringTest {

    @Test
    fun `bambu compose receives only explicit prepare overrides`() {
        val source = File("src/main/java/com/u1/slicer/SlicerViewModel.kt").readText()
        val start = source.indexOf("val explicitBambuOverrides =")
        val end = source.indexOf("BambuImportedConfigComposer.compose(", start)
        assertTrue(start >= 0 && end > start)
        val wiring = source.substring(start, end)

        assertTrue(wiring.contains("buildExplicitBambuProfileOverrides"))
        assertFalse(wiring.contains("putAll(activeProcessKeys)"))
        assertFalse(wiring.contains("putAll(filamentLibrarySettings)"))
    }
}
