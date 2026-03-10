package com.u1.slicer.bambu

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ProfileEmbedder's convertToModelSettings() — the function that converts
 * BambuSanitizer's Slic3r_PE_model.config (PrusaSlicer format) to OrcaSlicer's
 * model_settings.config format.
 *
 * These test the companion function directly (no Context needed).
 */
class ProfileEmbedderTest {

    @Test
    fun `single object single extruder`() {
        val input = """
            <config>
              <object id="1">
                <metadata type="object" key="extruder" value="1"/>
                <volume firstid="0" lastid="999">
                  <metadata type="volume" key="extruder" value="1"/>
                </volume>
              </object>
            </config>
        """.trimIndent()

        val result = ProfileEmbedder.convertToModelSettings(input.toByteArray(), null)

        assertTrue("Must contain object id=1", result.contains("""<object id="1">"""))
        assertTrue("Must contain volume [0-999]", result.contains("""firstid="0" lastid="999""""))
        assertTrue("Must contain extruder value=1", result.contains("""value="1""""))
    }

    @Test
    fun `dragon scale multi-volume preserves per-volume extruders`() {
        // Exact format from BambuSanitizer.buildSlic3rModelConfig for Dragon Scale
        val input = """
            <?xml version="1.0" encoding="UTF-8"?>
            <config>
              <object id="1">
                <metadata type="object" key="extruder" value="2"/>
                <volume firstid="0" lastid="181579">
                  <metadata type="volume" key="extruder" value="1"/>
                </volume>
                <volume firstid="181580" lastid="207895">
                  <metadata type="volume" key="extruder" value="2"/>
                </volume>
                <volume firstid="207896" lastid="260855">
                  <metadata type="volume" key="extruder" value="2"/>
                </volume>
              </object>
            </config>
        """.trimIndent()

        val result = ProfileEmbedder.convertToModelSettings(input.toByteArray(), null)

        // Object-level extruder must be 2
        assertTrue("Object must have extruder=2",
            result.contains("""<metadata key="extruder" value="2"/>"""))

        // All 3 volumes must be preserved
        assertTrue("Volume 0-181579 must exist",
            result.contains("""firstid="0" lastid="181579""""))
        assertTrue("Volume 181580-207895 must exist",
            result.contains("""firstid="181580" lastid="207895""""))
        assertTrue("Volume 207896-260855 must exist",
            result.contains("""firstid="207896" lastid="260855""""))

        // Volume 1 must have extruder=1 (different from object-level)
        val vol1Match = Regex("""firstid="0" lastid="181579">\s*\n\s*<metadata key="extruder" value="(\d+)"""")
            .find(result)
        assertNotNull("Volume 0-181579 must have extruder metadata", vol1Match)
        assertEquals("First volume must be extruder 1", "1", vol1Match!!.groupValues[1])
    }

    @Test
    fun `extruder remap applied to volumes`() {
        val input = """
            <config>
              <object id="1">
                <metadata type="object" key="extruder" value="2"/>
                <volume firstid="0" lastid="100">
                  <metadata type="volume" key="extruder" value="1"/>
                </volume>
                <volume firstid="101" lastid="200">
                  <metadata type="volume" key="extruder" value="2"/>
                </volume>
              </object>
            </config>
        """.trimIndent()

        // Remap: slot 1→3, slot 2→4
        val remap = mapOf(1 to 3, 2 to 4)
        val result = ProfileEmbedder.convertToModelSettings(input.toByteArray(), remap)

        // Object-level must be remapped 2→4
        assertTrue("Object extruder must be remapped to 4",
            result.contains("""<metadata key="extruder" value="4"/>"""))

        // Volume extruders must also be remapped
        val vol1Match = Regex("""firstid="0" lastid="100">\s*\n\s*<metadata key="extruder" value="(\d+)"""")
            .find(result)
        assertEquals("Volume 1 must be remapped 1→3", "3", vol1Match!!.groupValues[1])

        val vol2Match = Regex("""firstid="101" lastid="200">\s*\n\s*<metadata key="extruder" value="(\d+)"""")
            .find(result)
        assertEquals("Volume 2 must be remapped 2→4", "4", vol2Match!!.groupValues[1])
    }

    @Test
    fun `calib cube separate objects no volumes`() {
        // Calib cube: two separate objects, each with one extruder, no per-volume entries
        val input = """
            <config>
              <object id="100">
                <metadata type="object" key="extruder" value="1"/>
              </object>
              <object id="101">
                <metadata type="object" key="extruder" value="2"/>
              </object>
            </config>
        """.trimIndent()

        val result = ProfileEmbedder.convertToModelSettings(input.toByteArray(), null)

        assertTrue("Object 100 must exist", result.contains("""<object id="100">"""))
        assertTrue("Object 101 must exist", result.contains("""<object id="101">"""))
        // No volume elements expected
        assertFalse("No volume elements for separate-object models",
            result.contains("firstid="))
    }

    @Test
    fun `value-before-key attribute order handled`() {
        // Some XML generators may put value= before key=
        val input = """
            <config>
              <object id="1">
                <metadata type="object" value="2" key="extruder"/>
              </object>
            </config>
        """.trimIndent()

        val result = ProfileEmbedder.convertToModelSettings(input.toByteArray(), null)
        assertTrue("Must extract extruder=2 regardless of attribute order",
            result.contains("""value="2""""))
    }
}
