package com.u1.slicer.bambu

import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.1 — JVM unit tests for [buildFromProjectSettings].
 *
 * The file-reading entry point [bambuFileColourList] is exercised in the
 * instrumented test `BambuCanonicalListInstrumentedTest`; this file covers
 * the pure JSON-to-canonical-list logic.
 */
class BambuCanonicalListTest {

    @Test
    fun `single-colour project settings produces one FILE_COLOUR entry`() {
        // Mirrors the Die fixture (G:/My Drive/tes-data/Die+Single+Colour+-+Die.3mf)
        // which we ship as app/src/androidTest/assets/die-single-colour.3mf.
        val json = """{
            "filament_colour": ["#A6A9AA"],
            "filament_type":   ["PLA"],
            "filament_settings_id": ["Bambu PLA Basic @BBL X1C"]
        }"""

        val list = buildFromProjectSettings(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertFalse(list.isMultiColour)

        val only = list.filaments[0]
        assertEquals(0, only.fileIndex)
        assertEquals("#A6A9AA", only.color)
        assertEquals("PLA", only.materialType)
        assertEquals(FilamentSource.FILE_COLOUR, only.source)
        assertTrue(list.paintStateMap.isEmpty())
    }

    @Test
    fun `multi-colour project settings produces N FILE_COLOUR entries in order`() {
        val json = """{
            "filament_colour": ["#FF0000", "#00FF00", "#0000FF", "#FFFFFF"],
            "filament_type":   ["PLA",     "PETG",    "PLA",     "PLA"]
        }"""

        val list = buildFromProjectSettings(json)
        assertNotNull(list)
        assertEquals(4, list!!.size)
        assertTrue(list.isMultiColour)

        assertEquals("#FF0000", list.filaments[0].color)
        assertEquals("PLA", list.filaments[0].materialType)
        assertEquals("#00FF00", list.filaments[1].color)
        assertEquals("PETG", list.filaments[1].materialType)
        assertEquals("#0000FF", list.filaments[2].color)
        assertEquals("#FFFFFF", list.filaments[3].color)

        list.filaments.forEachIndexed { i, e ->
            assertEquals(i, e.fileIndex)
            assertEquals(FilamentSource.FILE_COLOUR, e.source)
        }
    }

    @Test
    fun `colour with alpha channel truncates to 7-char hex`() {
        // OrcaSlicer occasionally emits "#RRGGBBAA". Canonical list keeps "#RRGGBB".
        val json = """{ "filament_colour": ["#FF0000FF"] }"""
        val list = buildFromProjectSettings(json)
        assertNotNull(list)
        assertEquals("#FF0000", list!!.filaments[0].color)
    }

    @Test
    fun `missing filament_type fields produce null materialType`() {
        val json = """{ "filament_colour": ["#FF0000", "#00FF00"] }"""
        val list = buildFromProjectSettings(json)
        assertNotNull(list)
        assertEquals(2, list!!.size)
        assertNull(list.filaments[0].materialType)
        assertNull(list.filaments[1].materialType)
    }

    @Test
    fun `partial filament_type list — entries without a matching type get null`() {
        val json = """{
            "filament_colour": ["#FF0000", "#00FF00", "#0000FF"],
            "filament_type":   ["PLA",     "PETG"]
        }"""
        val list = buildFromProjectSettings(json)
        assertEquals("PLA", list!!.filaments[0].materialType)
        assertEquals("PETG", list.filaments[1].materialType)
        assertNull(list.filaments[2].materialType)
    }

    @Test
    fun `empty filament_colour returns null`() {
        val json = """{ "filament_colour": [] }"""
        assertNull(buildFromProjectSettings(json))
    }

    @Test
    fun `missing filament_colour returns null`() {
        val json = """{ "filament_type": ["PLA"] }"""
        assertNull(buildFromProjectSettings(json))
    }

    @Test
    fun `malformed JSON returns null`() {
        assertNull(buildFromProjectSettings("not json"))
        assertNull(buildFromProjectSettings(""))
    }

    @Test
    fun `colours without hex prefix still parse if regex matches`() {
        // Defensive — some files emit colours embedded inside other strings.
        val json = """{ "filament_colour": ["before#ABCDEFafter"] }"""
        val list = buildFromProjectSettings(json)
        assertEquals("#ABCDEF", list!!.filaments[0].color)
    }

    // ─── extractPaintSpecs ────────────────────────────────────────────────

    @Test
    fun `extractPaintSpecs returns empty for line without paint attributes`() {
        assertEquals(0, extractPaintSpecs("<vertex x=\"1\" y=\"2\"/>").size)
        assertEquals(0, extractPaintSpecs("").size)
    }

    @Test
    fun `extractPaintSpecs finds single paint_color value`() {
        val line = """<triangle v1="0" v2="1" v3="2" paint_color="4"/>"""
        val specs = extractPaintSpecs(line)
        assertEquals(1, specs.size)
        assertEquals("4", specs[0])
    }

    @Test
    fun `extractPaintSpecs finds mmu_segmentation value`() {
        val line = """<triangle mmu_segmentation="8C"/>"""
        val specs = extractPaintSpecs(line)
        assertEquals(1, specs.size)
        assertEquals("8C", specs[0])
    }

    @Test
    fun `extractPaintSpecs finds slic3rpe namespaced attribute`() {
        val line = """<triangle slic3rpe:mmu_segmentation="3C"/>"""
        val specs = extractPaintSpecs(line)
        assertEquals(1, specs.size)
        assertEquals("3C", specs[0])
    }

    @Test
    fun `extractPaintSpecs returns multiple values from single line`() {
        // Compact 3MFs sometimes pack many triangles onto one line.
        val line = """<t paint_color="4"/><t paint_color="8"/><t mmu_segmentation="C"/>"""
        val specs = extractPaintSpecs(line)
        assertEquals(3, specs.size)
        assertEquals(listOf("4", "8", "C"), specs)
    }

    @Test
    fun `extractPaintSpecs filters blank values`() {
        val line = """<t paint_color=""/><t paint_color="4"/>"""
        val specs = extractPaintSpecs(line)
        assertEquals(1, specs.size)
        assertEquals("4", specs[0])
    }

    // ─── mergePaintStates ─────────────────────────────────────────────────

    @Test
    fun `mergePaintStates with empty paintStates returns base unchanged`() {
        val base = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        val merged = mergePaintStates(base, emptySet())
        assertEquals(base, merged)
        assertTrue(merged.paintStateMap.isEmpty())
    }

    @Test
    fun `mergePaintStates with paint states within bounds builds identity paintStateMap`() {
        // 4-colour SEMM (e.g. colored Benchy): filament_colour has 4 entries,
        // paint_color triangles reference states 1..4. State N addresses
        // fileIndex N-1; no PAINT_DERIVED entries needed.
        val base = CanonicalFilamentList(
            filaments = (0..3).map { i ->
                FilamentEntry(i, "#%06X".format(i * 0x404040), "PLA", FilamentSource.FILE_COLOUR)
            }
        )
        val merged = mergePaintStates(base, setOf(1, 2, 3, 4))
        assertEquals(4, merged.size)  // unchanged size
        assertEquals(0, merged.paintStateMap[1])
        assertEquals(1, merged.paintStateMap[2])
        assertEquals(2, merged.paintStateMap[3])
        assertEquals(3, merged.paintStateMap[4])
        // All entries are still FILE_COLOUR
        merged.filaments.forEach { e ->
            assertEquals(FilamentSource.FILE_COLOUR, e.source)
        }
    }

    // ─── parseLayerToolColourPairs + mergeLayerToolData ───────────────────

    @Test
    fun `parseLayerToolColourPairs returns first colour seen per extruder`() {
        val xml = """
            <layer top_z="0.4" type="1" extruder="2" color="#00FF00"/>
            <layer top_z="1.2" type="1" extruder="3" color="#0000FF"/>
            <layer top_z="2.4" type="1" extruder="2" color="#888888"/>
            <layer top_z="3.0" type="0" extruder="1" color="#FF0000"/>
        """.trimIndent()
        val pairs = parseLayerToolColourPairs(xml)
        assertEquals(2, pairs.size)
        assertEquals("#00FF00", pairs[2])  // first occurrence kept
        assertEquals("#0000FF", pairs[3])
        assertNull("type=0 layers ignored", pairs[1])
    }

    @Test
    fun `parseLayerToolColourPairs ignores layers without extruder or colour`() {
        val xml = """
            <layer top_z="0.4" type="1"/>
            <layer top_z="1.2" type="1" extruder="2"/>
            <layer top_z="2.4" type="1" color="#FFFFFF"/>
            <layer top_z="3.0" type="2" extruder="3" color="#FF00FF"/>
        """.trimIndent()
        val pairs = parseLayerToolColourPairs(xml)
        assertEquals(1, pairs.size)
        assertEquals("#FF00FF", pairs[3])
    }

    @Test
    fun `mergeLayerToolData with extruders within bounds is no-op`() {
        // 4-entry FILE_COLOUR base; layer-tool references extruders 1..4
        // (all already covered). No changes.
        val base = CanonicalFilamentList(
            filaments = (0..3).map { i ->
                FilamentEntry(i, "#%06X".format(i * 0x404040), "PLA",
                    FilamentSource.FILE_COLOUR)
            }
        )
        val info = LayerToolCustomGcodeXmlInfo(
            hasToolChanges = true,
            colors = listOf("#FF0000", "#00FF00", "#0000FF"),
            extruders = setOf(1, 2, 3, 4),
        )
        val merged = mergeLayerToolData(base, info, emptyMap())
        assertEquals(base, merged)
    }

    @Test
    fun `mergeLayerToolData expands list to cover high-index extruder`() {
        // 1-entry FILE_COLOUR base (Hueforge-style declared filament_colour);
        // layer-tool references extruders 1..4 with colours. Result has 4
        // entries: 1 FILE_COLOUR + 3 LAYER_TOOL with the layer-tool colours.
        val base = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#000000", "PLA", FilamentSource.FILE_COLOUR)
            )
        )
        val info = LayerToolCustomGcodeXmlInfo(
            hasToolChanges = true,
            colors = listOf("#FF0000", "#00FF00", "#0000FF"),
            extruders = setOf(1, 2, 3, 4),
        )
        val pairs = mapOf(2 to "#FF0000", 3 to "#00FF00", 4 to "#0000FF")
        val merged = mergeLayerToolData(base, info, pairs)

        assertEquals(4, merged.size)
        assertEquals(FilamentSource.FILE_COLOUR, merged.filaments[0].source)
        assertEquals("#000000", merged.filaments[0].color)
        assertEquals(FilamentSource.LAYER_TOOL, merged.filaments[1].source)
        assertEquals("#FF0000", merged.filaments[1].color)
        assertEquals(FilamentSource.LAYER_TOOL, merged.filaments[2].source)
        assertEquals("#00FF00", merged.filaments[2].color)
        assertEquals(FilamentSource.LAYER_TOOL, merged.filaments[3].source)
        assertEquals("#0000FF", merged.filaments[3].color)
    }

    @Test
    fun `mergeLayerToolData uses grey placeholder when colour unknown`() {
        val base = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#000000", "PLA", FilamentSource.FILE_COLOUR)
            )
        )
        val info = LayerToolCustomGcodeXmlInfo(
            hasToolChanges = true,
            colors = emptyList(),
            extruders = setOf(1, 2, 3),
        )
        val merged = mergeLayerToolData(base, info, emptyMap())
        assertEquals(3, merged.size)
        assertEquals("#808080", merged.filaments[1].color)
        assertEquals("#808080", merged.filaments[2].color)
        merged.filaments.drop(1).forEach { e ->
            assertEquals(FilamentSource.LAYER_TOOL, e.source)
            assertNull(e.materialType)
        }
    }

    @Test
    fun `mergeLayerToolData with empty extruders returns base`() {
        val base = CanonicalFilamentList(
            filaments = listOf(FilamentEntry(0, "#000000", "PLA",
                FilamentSource.FILE_COLOUR))
        )
        val info = LayerToolCustomGcodeXmlInfo(
            hasToolChanges = false,
            colors = emptyList(),
            extruders = emptySet(),
        )
        assertEquals(base, mergeLayerToolData(base, info, emptyMap()))
    }

    @Test
    fun `mergePaintStates with high-index paint state expands list with PAINT_DERIVED`() {
        // B95 Buzz plate 9 shape: filament_colour has 4 entries, but paint_color
        // attribute "8C" decodes to state 11 (addressing fileIndex 10). The
        // canonical list grows to size 11 with entries 4..10 marked
        // PAINT_DERIVED, and paintStateMap[11] = 10.
        val base = CanonicalFilamentList(
            filaments = (0..3).map { i ->
                FilamentEntry(i, "#%06X".format(i * 0x404040), "PLA", FilamentSource.FILE_COLOUR)
            }
        )
        val merged = mergePaintStates(base, setOf(6, 11))

        assertEquals(11, merged.size)
        // Original 4 entries preserved
        for (i in 0..3) {
            assertEquals(FilamentSource.FILE_COLOUR, merged.filaments[i].source)
        }
        // Entries 4..10 are PAINT_DERIVED placeholders
        for (i in 4..10) {
            assertEquals(FilamentSource.PAINT_DERIVED, merged.filaments[i].source)
            assertEquals("#808080", merged.filaments[i].color)
            assertNull(merged.filaments[i].materialType)
            assertEquals(i, merged.filaments[i].fileIndex)
        }
        // paintStateMap addresses each state to fileIndex = state - 1
        assertEquals(5, merged.paintStateMap[6])
        assertEquals(10, merged.paintStateMap[11])
    }

    // ── M3-B full-spectrum marker ────────────────────────────────────────────

    @Test
    fun `readFullSpectrumPhysicalCount parses the string-form marker`() {
        val json = """{
            "filament_colour": ["#FF0000","#00FF00","#0000FF","#FFFF00"],
            "filament_count": "4",
            "full_spectrum_physical_count": "4"
        }"""
        assertEquals(4, readFullSpectrumPhysicalCount(json))
    }

    @Test
    fun `readFullSpectrumPhysicalCount parses the numeric-form marker`() {
        val json = """{ "filament_colour": ["#FF0000"], "full_spectrum_physical_count": 4 }"""
        assertEquals(4, readFullSpectrumPhysicalCount(json))
    }

    @Test
    fun `readFullSpectrumPhysicalCount returns null when marker absent`() {
        val json = """{ "filament_colour": ["#FF0000","#00FF00"] }"""
        assertNull(readFullSpectrumPhysicalCount(json))
    }

    @Test
    fun `readFullSpectrumPhysicalCount returns null for zero or malformed`() {
        assertNull(readFullSpectrumPhysicalCount("""{ "full_spectrum_physical_count": "0" }"""))
        assertNull(readFullSpectrumPhysicalCount("""{ "full_spectrum_physical_count": "abc" }"""))
        assertNull(readFullSpectrumPhysicalCount("not json"))
    }

    @Test
    fun `mergePaintStates capped at physical count drops virtual mix states`() {
        // A full-spectrum file: 4 physical filaments, all facets painted to mix slot 4
        // (engine paint state 5). The loader filters states > physicalCount (4) BEFORE
        // mergePaintStates, so the canonical list MUST stay at 4 — the virtual mix state
        // must not add a 5th PAINT_DERIVED filament (which would inflate num_physical and
        // break the blend).
        val base = CanonicalFilamentList(
            filaments = (0..3).map {
                FilamentEntry(it, "#FF0000", "PLA", FilamentSource.FILE_COLOUR)
            }
        )
        val physicalCap = 4
        val rawPaintStates = setOf(5) // mix slot 4 → state 5
        val physicalOnly = rawPaintStates.filterTo(mutableSetOf()) { it <= physicalCap }
        val merged = mergePaintStates(base, physicalOnly)
        assertEquals("virtual mix state must not expand the canonical list", 4, merged.size)
        assertTrue(merged.filaments.all { it.source == FilamentSource.FILE_COLOUR })
    }
}
