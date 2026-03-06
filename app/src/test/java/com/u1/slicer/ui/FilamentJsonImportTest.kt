package com.u1.slicer.ui

import org.junit.Assert.*
import org.junit.Test

class FilamentJsonImportTest {

    @Test
    fun `parses simple array with snake_case keys`() {
        val json = """
            [
              {
                "name": "Bambu PLA Basic",
                "material": "PLA",
                "nozzle_temp": 220,
                "bed_temp": 35,
                "print_speed": 150,
                "retract_length": 0.8,
                "retract_speed": 30
              }
            ]
        """.trimIndent()
        val profiles = parseFilamentJson(json)
        assertEquals(1, profiles.size)
        assertEquals("Bambu PLA Basic", profiles[0].name)
        assertEquals("PLA", profiles[0].material)
        assertEquals(220, profiles[0].nozzleTemp)
        assertEquals(35, profiles[0].bedTemp)
        assertEquals(150f, profiles[0].printSpeed, 0.001f)
        assertEquals(0.8f, profiles[0].retractLength, 0.001f)
        assertEquals(30f, profiles[0].retractSpeed, 0.001f)
    }

    @Test
    fun `parses array with camelCase keys`() {
        val json = """[{"name":"ABS+","material":"ABS","nozzleTemp":250,"bedTemp":90,"printSpeed":80,"retractLength":1.0,"retractSpeed":45}]"""
        val profiles = parseFilamentJson(json)
        assertEquals(1, profiles.size)
        assertEquals("ABS+", profiles[0].name)
        assertEquals(250, profiles[0].nozzleTemp)
        assertEquals(90, profiles[0].bedTemp)
    }

    @Test
    fun `parses wrapped object with filaments key`() {
        val json = """{"filaments":[{"name":"TPU Shore 95A","material":"TPU","nozzle_temp":230,"bed_temp":40,"print_speed":30,"retract_length":0.5,"retract_speed":25}]}"""
        val profiles = parseFilamentJson(json)
        assertEquals(1, profiles.size)
        assertEquals("TPU Shore 95A", profiles[0].name)
        assertEquals("TPU", profiles[0].material)
    }

    @Test
    fun `parses multiple profiles`() {
        val json = """
            [
              {"name":"PLA White","material":"PLA","nozzle_temp":215,"bed_temp":60,"print_speed":60,"retract_length":0.8,"retract_speed":45},
              {"name":"PETG Black","material":"PETG","nozzle_temp":240,"bed_temp":80,"print_speed":50,"retract_length":1.2,"retract_speed":35},
              {"name":"ASA Red","material":"ASA","nozzle_temp":260,"bed_temp":90,"print_speed":55,"retract_length":1.0,"retract_speed":40}
            ]
        """.trimIndent()
        val profiles = parseFilamentJson(json)
        assertEquals(3, profiles.size)
        assertEquals("PLA White", profiles[0].name)
        assertEquals("PETG Black", profiles[1].name)
        assertEquals("ASA Red", profiles[2].name)
    }

    @Test
    fun `skips entries without name`() {
        val json = """[{"material":"PLA","nozzle_temp":210},{"name":"Valid","material":"PLA","nozzle_temp":210,"bed_temp":60,"print_speed":60,"retract_length":0.8,"retract_speed":45}]"""
        val profiles = parseFilamentJson(json)
        assertEquals(1, profiles.size)
        assertEquals("Valid", profiles[0].name)
    }

    @Test
    fun `uses defaults for missing optional fields`() {
        val json = """[{"name":"Minimal PLA","material":"PLA"}]"""
        val profiles = parseFilamentJson(json)
        assertEquals(1, profiles.size)
        assertEquals(210, profiles[0].nozzleTemp)
        assertEquals(60, profiles[0].bedTemp)
        assertEquals(60f, profiles[0].printSpeed, 0.001f)
        assertEquals(0.8f, profiles[0].retractLength, 0.001f)
        assertEquals(45f, profiles[0].retractSpeed, 0.001f)
    }

    @Test
    fun `material defaults to PLA when blank`() {
        val json = """[{"name":"Unknown","material":"","nozzle_temp":200,"bed_temp":55,"print_speed":60,"retract_length":0.8,"retract_speed":45}]"""
        val profiles = parseFilamentJson(json)
        assertEquals("PLA", profiles[0].material)
    }

    @Test(expected = Exception::class)
    fun `throws on invalid JSON`() {
        parseFilamentJson("not json at all")
    }

    @Test(expected = Exception::class)
    fun `throws on JSON object without filaments key`() {
        parseFilamentJson("""{"foo":"bar"}""")
    }

    @Test
    fun `empty array returns empty list`() {
        val profiles = parseFilamentJson("[]")
        assertTrue(profiles.isEmpty())
    }

    @Test
    fun `snake_case takes priority over camelCase`() {
        // When both are present snake_case is read first via optInt fallback chain
        val json = """[{"name":"Test","material":"PLA","nozzle_temp":225,"nozzleTemp":999,"bed_temp":65,"bedTemp":999,"print_speed":70,"retract_length":0.9,"retract_speed":40}]"""
        val profiles = parseFilamentJson(json)
        assertEquals(225, profiles[0].nozzleTemp)
        assertEquals(65, profiles[0].bedTemp)
    }
}
