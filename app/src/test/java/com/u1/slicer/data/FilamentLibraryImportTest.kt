package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentLibraryImportTest {

    private val full = FilamentLibraryEntry(
        slug = "acme-pla-red", brand = "Acme", name = "PLA Red", material = "PLA",
        hex = "#FF0000", td = 2.5, ri = 1.46, density = 1.24,
        minNozzle = 205, maxNozzle = 225, minBed = 40, maxBed = 60,
    )
    private val colourOnly = FilamentLibraryEntry(
        slug = "acme-pla-blue", brand = "Acme", name = "PLA Blue", material = "PLA", hex = "#0000FF",
    )

    @Test
    fun `hasImportableData true only when fields beyond colour and material exist`() {
        assertTrue(hasImportableData(full))
        assertFalse(hasImportableData(colourOnly))
        assertTrue(hasImportableData(colourOnly.copy(density = 1.2)))
        assertTrue(hasImportableData(colourOnly.copy(td = 1.0)))
    }

    @Test
    fun `preview rows list only present fields with units`() {
        val rows = buildImportPreview(full)
        val labels = rows.map { it.label }
        assertEquals(
            listOf("Nozzle temperature", "Bed temperature", "Density",
                "Transmission distance", "Refractive index"),
            labels
        )
        assertEquals("205–225 °C", rows[0].value)
        assertEquals("40–60 °C", rows[1].value)
        assertEquals("1.24 g/cm³", rows[2].value)
        assertEquals(FUTURE_TRANSLUCENCY_NOTE, rows[3].note)
        assertEquals(FUTURE_TRANSLUCENCY_NOTE, rows[4].note)
        assertNull(rows[0].note)
    }

    @Test
    fun `preview handles single-ended temperature ranges`() {
        val rows = buildImportPreview(full.copy(maxNozzle = null, minBed = null))
        assertEquals("205 °C", rows.first { it.label == "Nozzle temperature" }.value)
        assertEquals("60 °C", rows.first { it.label == "Bed temperature" }.value)
    }

    @Test
    fun `preview empty for colour-only entry`() {
        assertTrue(buildImportPreview(colourOnly).isEmpty())
    }

    @Test
    fun `profile mapping uses midpoints and entry colour`() {
        val p = libraryEntryToProfile(full, existing = null)
        assertEquals("Acme PLA Red", p.name)
        assertEquals("PLA", p.material)
        assertEquals(215, p.nozzleTemp)   // midpoint 205..225
        assertEquals(50, p.bedTemp)       // midpoint 40..60
        assertEquals(1.24f, p.density, 1e-4f)
        assertEquals("#FF0000", p.color)
        assertEquals(0.8f, p.retractLength, 1e-4f)
        assertEquals(45f, p.retractSpeed, 1e-4f)
        assertEquals(0L, p.id)
    }

    @Test
    fun `profile mapping falls back to material defaults when temps absent`() {
        val p = libraryEntryToProfile(colourOnly, existing = null)
        assertEquals(220, p.nozzleTemp)   // PLA default
        assertEquals(60, p.bedTemp)
        val petg = libraryEntryToProfile(colourOnly.copy(material = "PETG"), existing = null)
        assertEquals(235, petg.nozzleTemp)
        assertEquals(70, petg.bedTemp)
    }

    @Test
    fun `re-import updates the existing profile in place keeping its id`() {
        val existing = libraryEntryToProfile(full, existing = null).copy(id = 42L)
        val updated = libraryEntryToProfile(full.copy(minNozzle = 210, maxNozzle = 230), existing = existing)
        assertEquals(42L, updated.id)
        assertEquals(220, updated.nozzleTemp)
        assertEquals("Acme PLA Red", updated.name)
    }
}
