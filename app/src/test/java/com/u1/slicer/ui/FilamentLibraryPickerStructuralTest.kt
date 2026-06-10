package com.u1.slicer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guards for FilamentLibraryPicker (no Compose UI harness in project).
 * Pins: search via FilamentLibrary.search, material filter chips, favourites star,
 * Failed-state retry, snapshot footer, and the import affordance gated on
 * hasImportableData.
 */
class FilamentLibraryPickerStructuralTest {

    private val src = File("src/main/java/com/u1/slicer/ui/FilamentLibraryPicker.kt").readText()

    @Test
    fun `picker searches through FilamentLibrary search with favourites and recents`() {
        assertTrue(src.contains(".search("))
        assertTrue(src.contains("favourites"))
        assertTrue(src.contains("recents"))
    }

    @Test
    fun `picker offers material filter chips`() {
        assertTrue(src.contains("FilterChip"))
        listOf("\"PLA\"", "\"PETG\"", "\"ABS\"", "\"TPU\"", "\"ASA\"").forEach { m ->
            assertTrue("missing material chip $m", src.contains(m))
        }
    }

    @Test
    fun `picker renders failed state with retry`() {
        assertTrue(src.contains("LibraryState.Failed"))
        assertTrue(src.contains("onRetry"))
    }

    @Test
    fun `import affordance is gated on hasImportableData`() {
        assertTrue(src.contains("hasImportableData"))
    }

    @Test
    fun `import preview dialog lists rows from buildImportPreview`() {
        assertTrue(src.contains("buildImportPreview"))
        assertTrue(src.contains("FilamentImportPreviewDialog"))
    }

    @Test
    fun `snapshot info shown in footer`() {
        assertTrue(src.contains("snapshot"))
    }

    @Test
    fun `star toggle wired`() {
        assertTrue(src.contains("onToggleFavourite"))
        assertTrue(src.contains("Icons.Default.Star") || src.contains("Icons.Filled.Star"))
    }
}
