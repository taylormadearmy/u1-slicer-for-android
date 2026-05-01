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
        assertTrue(
            "Must preserve object metadata type",
            result.contains("""<metadata type="object" key="extruder" value="1"/>""")
        )
        assertTrue(
            "Must preserve volume metadata type",
            result.contains("""<metadata type="volume" key="extruder" value="1"/>""")
        )
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
            result.contains("""<metadata type="object" key="extruder" value="2"/>"""))

        // All 3 volumes must be preserved
        assertTrue("Volume 0-181579 must exist",
            result.contains("""firstid="0" lastid="181579""""))
        assertTrue("Volume 181580-207895 must exist",
            result.contains("""firstid="181580" lastid="207895""""))
        assertTrue("Volume 207896-260855 must exist",
            result.contains("""firstid="207896" lastid="260855""""))

        // Volume 1 must have extruder=1 (different from object-level)
        val vol1Match = Regex("""firstid="0" lastid="181579">\s*\n\s*<metadata type="volume" key="extruder" value="(\d+)"""")
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
            result.contains("""<metadata type="object" key="extruder" value="4"/>"""))

        // Volume extruders must also be remapped
        val vol1Match = Regex("""firstid="0" lastid="100">\s*\n\s*<metadata type="volume" key="extruder" value="(\d+)"""")
            .find(result)
        assertEquals("Volume 1 must be remapped 1→3", "3", vol1Match!!.groupValues[1])

        val vol2Match = Regex("""firstid="101" lastid="200">\s*\n\s*<metadata type="volume" key="extruder" value="(\d+)"""")
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

    /**
     * S-Buttons mesh-diversity guard: with a collapsing extruderRemap (which
     * `buildCompactExtruderRemap` returns for users whose slot presets collapse
     * multiple file colours onto fewer slots), `convertToModelSettings`
     * collapses per-object extruder values, eliminating the per-object diversity
     * the mesh-recolour path needs.
     *
     * Phase 2 fix: `embedProfile` now passes `extruderRemap = null` so the
     * embedded model_settings.config keeps the source's distinct extruder
     * values. The slicer emits canonical T-indices; the user's slot mapping is
     * applied at print time by [PrintTimeRemap].
     *
     * This test pins both behaviours so a future regression that re-enables
     * the collapse remap is caught here.
     */
    @Test
    fun `extruderRemap collapses extruders when applied to per-object model`() {
        // S-Buttons-shaped config: 4 separate objects, each with a distinct
        // object-level extruder (1..4). No volume sub-elements (mirrors
        // the calib-cube case but with 4 objects).
        val input = """
            <config>
              <object id="2">
                <metadata type="object" key="extruder" value="1"/>
              </object>
              <object id="3">
                <metadata type="object" key="extruder" value="2"/>
              </object>
              <object id="4">
                <metadata type="object" key="extruder" value="3"/>
              </object>
              <object id="5">
                <metadata type="object" key="extruder" value="4"/>
              </object>
            </config>
        """.trimIndent()

        // Collapsing remap (4 source extruders → 2 distinct targets).
        // This is what `buildCompactExtruderRemap` returns when the user's
        // slot presets collapse multiple file colours onto 2 slots.
        val collapsing = mapOf(1 to 1, 2 to 2, 3 to 1, 4 to 2)
        val collapsed = ProfileEmbedder.convertToModelSettings(input.toByteArray(), collapsing)
        val collapsedExtruders = Regex("""key="extruder"\s+value="(\d+)"""")
            .findAll(collapsed)
            .map { it.groupValues[1].toInt() }
            .toSet()
        assertEquals(
            "Collapsing remap reduces 4 source extruders to 2 distinct values — " +
                "this loses mesh diversity for the 3D Prepare body recolour",
            setOf(1, 2),
            collapsedExtruders
        )

        // Phase 2 contract: embedProfile passes null, so all 4 source extruders
        // survive and the mesh keeps its per-object diversity.
        val preserved = ProfileEmbedder.convertToModelSettings(input.toByteArray(), null)
        val preservedExtruders = Regex("""key="extruder"\s+value="(\d+)"""")
            .findAll(preserved)
            .map { it.groupValues[1].toInt() }
            .toSet()
        assertEquals(
            "extruderRemap=null preserves all 4 distinct source extruders so the " +
                "Prepare preview mesh keeps its 4-distinct-colour layout",
            setOf(1, 2, 3, 4),
            preservedExtruders
        )
    }
}
