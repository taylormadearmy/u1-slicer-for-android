package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class ModelFileNamingTest {

    @Test
    fun `baseName returns model name when present`() {
        assertEquals("MyModel", ModelFileNaming.baseName("MyModel.3mf", "output.gcode"))
        assertEquals(
            "Dragon_Scale_infinity",
            ModelFileNaming.baseName("Dragon Scale infinity.3mf", "output.gcode")
        )
    }

    @Test
    fun `baseName sanitises special chars in model name`() {
        assertEquals("foo_bar_baz", ModelFileNaming.baseName("foo/bar:baz.3mf", "output.gcode"))
    }

    @Test
    fun `baseName falls back to on-disk name when modelName is null`() {
        assertEquals("output", ModelFileNaming.baseName(null, "output.gcode"))
        assertEquals(
            "output_1234567890",
            ModelFileNaming.baseName(null, "output_1234567890.gcode")
        )
    }

    @Test
    fun `baseName falls back to on-disk name when modelName is blank`() {
        assertEquals("output", ModelFileNaming.baseName("", "output.gcode"))
        assertEquals("output", ModelFileNaming.baseName("   ", "output.gcode"))
    }

    @Test
    fun `baseName handles all-special-chars name gracefully`() {
        assertEquals("output", ModelFileNaming.baseName("///.3mf", "output.gcode"))
    }

    @Test
    fun `baseName strips multiple extensions correctly`() {
        // File like "model.gcode.tmp" — only the last extension is stripped
        assertEquals("model.gcode", ModelFileNaming.baseName("model.gcode.tmp", "output.gcode"))
    }
}
