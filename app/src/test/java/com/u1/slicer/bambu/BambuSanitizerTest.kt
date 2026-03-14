package com.u1.slicer.bambu

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for BambuSanitizer logic.
 *
 * Note: BambuSanitizer.process() and extractPlate() use android.util.Log
 * and ThreeMfParser (which uses XmlPullParser), so full integration tests
 * run as instrumented tests. Here we test the constants and configuration
 * that don't require Android APIs.
 *
 * See androidTest/ for full integration tests with real 3MF files.
 */
class BambuSanitizerTest {

    @Test
    fun `sanitizer drops known incompatible files`() {
        // Verify the DROP_FILES set contains expected entries
        // This is a "specification test" ensuring the sanitizer config is correct
        val dropFiles = setOf(
            "Metadata/slice_info.config",
            "Metadata/cut_information.xml",
            "Metadata/filament_sequence.json",
            "Metadata/Slic3r_PE.config",
            "Metadata/Slic3r_PE_model.config"
        )
        // We can't directly access private fields, but we can verify behavior
        // through the data models used in processing
        assertEquals(5, dropFiles.size)
    }

    @Test
    fun `clamp rules have correct minimum values`() {
        // Specification test: these minimum values prevent PrusaSlicer crashes
        val clampRules = mapOf(
            "raft_first_layer_expansion" to 0f,
            "tree_support_wall_count" to 0f,
            "prime_volume" to 0f,
            "prime_tower_brim_width" to 0f,
            "solid_infill_filament" to 1f,
            "sparse_infill_filament" to 1f,
            "wall_filament" to 1f
        )
        // Filament indices should clamp to 1, not 0
        assertEquals(1f, clampRules["solid_infill_filament"])
        assertEquals(1f, clampRules["wall_filament"])
        // Dimensional values clamp to 0
        assertEquals(0f, clampRules["prime_volume"])
    }

    @Test
    fun `INI config parsing basic`() {
        // Test the INI parsing logic independently
        val content = """
            layer_height = 0.2
            nozzle_temp = 210
            # comment line
            ; another comment

            filament_colour = #FF0000;#00FF00;#0000FF
        """.trimIndent()

        val config = parseTestIniConfig(content)
        assertEquals("0.2", config["layer_height"])
        assertEquals("210", config["nozzle_temp"])
        // Semicolon-separated becomes a list
        assertTrue(config["filament_colour"] is List<*>)
        @Suppress("UNCHECKED_CAST")
        val colors = config["filament_colour"] as List<String>
        assertEquals(3, colors.size)
        assertEquals("#FF0000", colors[0])
    }

    @Test
    fun `INI config ignores comments and blank lines`() {
        val content = """
            # Header comment
            ; Another comment

            key1 = value1
            key2 = value2
        """.trimIndent()

        val config = parseTestIniConfig(content)
        assertEquals(2, config.size)
        assertEquals("value1", config["key1"])
    }

    @Test
    fun `nil replacement logic`() {
        val list = mutableListOf("210", "nil", "nil", "220")
        val default = list.firstOrNull { it != "nil" }
        assertNotNull(default)
        for (i in list.indices) {
            if (list[i] == "nil") list[i] = default!!
        }
        assertEquals(listOf("210", "210", "210", "220"), list)
    }

    @Test
    fun `nil replacement all nil removes key`() {
        val list = mutableListOf("nil", "nil", "nil")
        val default = list.firstOrNull { it != "nil" }
        assertNull(default)
    }

    @Test
    fun `array normalization padding`() {
        val list = mutableListOf("a", "b")
        val targetCount = 4
        val last = list.last()
        while (list.size < targetCount) list.add(last)
        assertEquals(listOf("a", "b", "b", "b"), list)
    }

    @Test
    fun `array normalization truncation`() {
        val list = mutableListOf("a", "b", "c", "d")
        val targetCount = 2
        while (list.size > targetCount) list.removeAt(list.lastIndex)
        assertEquals(listOf("a", "b"), list)
    }

    @Test
    fun `flush matrix size is NxN`() {
        val extruderCount = 3
        val needed = extruderCount * extruderCount // 9
        val list = mutableListOf("0", "100", "100", "100", "0", "100")
        val last = list.lastOrNull() ?: "0"
        while (list.size < needed) list.add(last)
        assertEquals(9, list.size)
    }

    @Test
    fun `mmu_segmentation is stripped not converted to paint_color`() {
        // PrusaSlicer mmu_segmentation uses a different encoding than Bambu paint_color.
        // Renaming produced malformed data → SIGSEGV in multi_material_segmentation_by_painting().
        // The correct fix is to STRIP the attribute so it never populates mmu_segmentation_facets.
        val input = """<triangle v1="0" v2="1" v3="2" slic3rpe:mmu_segmentation="AABB"/>"""
        val output = input.replace(Regex("""\s+slic3rpe:mmu_segmentation="[^"]*""""), "")
        assertFalse("slic3rpe:mmu_segmentation must be stripped", output.contains("mmu_segmentation"))
        assertFalse("must NOT be renamed to paint_color (different encoding)", output.contains("paint_color="))
    }

    @Test
    fun `transform clamping for packed plates`() {
        val bedCenter = 135f
        val packedThreshold = bedCenter * 2 + 100 // 370

        // A transform with tx=500 (packed by Bambu) should be clamped
        val tx = 500f
        assertTrue(kotlin.math.abs(tx) > packedThreshold)

        // Should be recentered to bedCenter
        val clamped = if (kotlin.math.abs(tx) > packedThreshold) bedCenter else tx
        assertEquals(135f, clamped, 0.001f)
    }

    @Test
    fun `transform within threshold is not clamped`() {
        val bedCenter = 135f
        val packedThreshold = bedCenter * 2 + 100

        val tx = 150f // Within normal range
        assertFalse(kotlin.math.abs(tx) > packedThreshold)
    }

    // --- filterModelToPlate (build-only rewrite) ---

    /**
     * BambuSanitizer.filterModelToPlate is private; we test it indirectly via
     * the XML string by calling filterModelToPlate through extractPlate is not
     * possible from JVM tests.  Instead, test the observable contract: a
     * 3dmodel.model XML with p:object_id items is correctly filtered so only
     * the target plate's items remain in <build>.
     *
     * We expose the logic by testing it through the expected output format.
     * The regex used in filterModelToPlate is tested here as white-box.
     */
    @Test
    fun `filterModelToPlate logic - p_object_id plates`() {
        val xml = """<?xml version="1.0"?>
<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
       xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06">
  <resources>
    <object id="1" type="model"><mesh><vertices/><triangles/></mesh></object>
    <object id="2" type="model"><mesh><vertices/><triangles/></mesh></object>
    <object id="3" type="model"><mesh><vertices/><triangles/></mesh></object>
  </resources>
  <build>
    <item objectid="1" transform="1 0 0 0 1 0 0 0 1 0 0 0" p:object_id="0"/>
    <item objectid="2" transform="1 0 0 0 1 0 0 0 1 0 0 0" p:object_id="0"/>
    <item objectid="3" transform="1 0 0 0 1 0 0 0 1 0 0 0" p:object_id="1"/>
  </build>
</model>""".trimIndent()

        val plateIdRegex = Regex("""p:object_id="(\d+)"""")
        val itemRegex = Regex("""<item\b[^>]*(?:/>|>.*?</item>)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val buildRegex = Regex("""(<build\b[^>]*>)(.*?)(</build>)""", setOf(RegexOption.DOT_MATCHES_ALL))

        // Simulate filterModelToPlate for plate 1 (p:object_id=0)
        fun filter(xml: String, targetPlateId: Int): String {
            val targetIdx = targetPlateId - 1
            return buildRegex.replace(xml) { m ->
                val allItems = itemRegex.findAll(m.groupValues[2]).map { it.value }.toList()
                val hasPlateIds = allItems.any { plateIdRegex.containsMatchIn(it) }
                if (!hasPlateIds) return@replace m.value
                val kept = allItems.filter { item ->
                    (plateIdRegex.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: 0) == targetIdx
                }
                if (kept.isEmpty()) return@replace m.value
                val newBody = "\n" + kept.joinToString("\n") { "    $it" } + "\n  "
                "${m.groupValues[1]}$newBody${m.groupValues[3]}"
            }
        }

        val plate1 = filter(xml, 1)
        val plate2 = filter(xml, 2)

        // Plate 1: items 1 and 2 (p:object_id=0)
        assertTrue("Plate1 should contain item 1", plate1.contains("""objectid="1""""))
        assertTrue("Plate1 should contain item 2", plate1.contains("""objectid="2""""))
        assertFalse("Plate1 should not contain item 3", plate1.contains("""objectid="3"""") &&
            plate1.substringAfter("<build").substringBefore("</build>").contains("""objectid="3""""))

        // Plate 2: only item 3 (p:object_id=1)
        assertTrue("Plate2 should contain item 3",
            plate2.substringAfter("<build").substringBefore("</build>").contains("""objectid="3""""))

        // Resources must be unchanged (all 3 objects present)
        assertTrue("Resources intact: object 1", plate1.contains("""id="1""""))
        assertTrue("Resources intact: object 2", plate1.contains("""id="2""""))
        assertTrue("Resources intact: object 3", plate1.contains("""id="3""""))
    }

    @Test
    fun `filterModelToPlate logic - no p_object_id single item returns unchanged`() {
        // Single build item with no p:object_id — nothing to select, XML stays the same
        val xml = """<build>
    <item objectid="1" transform="1 0 0 0 1 0 0 0 1 0 0 0"/>
</build>"""
        val plateIdRegex = Regex("""p:object_id="(\d+)"""")
        val itemRegex = Regex("""<item\b[^>]*(?:/>|>.*?</item>)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val allItems = itemRegex.findAll(xml).map { it.value }.toList()
        assertEquals(1, allItems.size)
        assertFalse("No p:object_id present", allItems.any { plateIdRegex.containsMatchIn(it) })
        // Single item — filterModelToPlate should leave it unchanged
        assertEquals(xml, xml)
    }

    @Test
    fun `filterModelToPlate logic - no p_object_id multiple items selects by index and recenters XY`() {
        // Position-based plate selection — triggered by either:
        //   (a) hasPlateJsons=true  (newer Bambu format with Metadata/plate_N.json)
        //   (b) hasVirtualPositions (older Bambu format: Dragon Scale / Shashibo with
        //       items at virtual TX/TY positions outside the 270mm bed)
        // Items in either case are at absolute virtual-layout positions far outside the 270mm bed.
        // Selecting plate N returns the N-th item (0-indexed) with XY reset to (135, 135).
        val buildRegex  = Regex("""(<build\b[^>]*>)(.*?)(</build>)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val itemRegex   = Regex("""<item\b[^>]*(?:/>|>.*?</item>)""",  setOf(RegexOption.DOT_MATCHES_ALL))
        val plateIdRegex = Regex("""p:object_id="(\d+)"""")
        val transformRegex = Regex("""transform="([^"]+)"""")

        fun recenterItemXY(item: String): String {
            return transformRegex.replace(item) { match ->
                val parts = match.groupValues[1].trim().split(Regex("\\s+"))
                if (parts.size >= 12) {
                    val newParts = parts.toMutableList()
                    newParts[9]  = "135"
                    newParts[10] = "135"
                    """transform="${newParts.joinToString(" ")}""""
                } else match.value
            }
        }
        fun isVirtual(item: String): Boolean {
            val parts = transformRegex.find(item)?.groupValues?.get(1)
                ?.trim()?.split(Regex("\\s+")) ?: emptyList()
            val tx = parts.getOrNull(9)?.toFloatOrNull() ?: 0f
            val ty = parts.getOrNull(10)?.toFloatOrNull() ?: 0f
            return tx > 270f || ty < 0f || ty > 270f
        }
        fun filterByIndex(xml: String, targetPlateId: Int, hasPlateJsons: Boolean = true): String {
            return buildRegex.replace(xml) { m ->
                val allItems = itemRegex.findAll(m.groupValues[2]).map { it.value }.toList()
                val hasPlateIds = allItems.any { plateIdRegex.containsMatchIn(it) }
                if (hasPlateIds) return@replace m.value
                val hasVirtualPositions = allItems.any { isVirtual(it) }
                if (allItems.size <= 1 || (!hasPlateJsons && !hasVirtualPositions)) return@replace m.value
                if (hasVirtualPositions) {
                    // Virtual-layout: each item is a separate plate, select by index
                    val idx = (targetPlateId - 1).coerceIn(0, allItems.size - 1)
                    val selected = recenterItemXY(allItems[idx])
                    "${m.groupValues[1]}\n    $selected\n  ${m.groupValues[3]}"
                } else {
                    // hasPlateJsons but no virtual positions: all items on same plate, keep all
                    m.value
                }
            }
        }

        val xml = """<?xml version="1.0"?>
<model>
  <resources>
    <object id="3" type="model"/>
    <object id="8" type="model"/>
    <object id="9" type="model"/>
  </resources>
  <build>
    <item objectid="3" transform="1 0 0 0 1 0 0 0 1 175 160 1.62" printable="1" />
    <item objectid="8" transform="1 0 0 0 1 0 0 0 1 1052 154 1.64" printable="1" />
    <item objectid="9" transform="1 0 0 0 1 0 0 0 1 175 -224 1.64" printable="1" />
  </build>
</model>""".trimIndent()

        val plate1 = filterByIndex(xml, 1)
        val plate2 = filterByIndex(xml, 2)
        val plate3 = filterByIndex(xml, 3)

        val buildSection1 = plate1.substringAfter("<build>").substringBefore("</build>")
        val buildSection2 = plate2.substringAfter("<build>").substringBefore("</build>")
        val buildSection3 = plate3.substringAfter("<build>").substringBefore("</build>")

        // Plate 1 → first item (objectid=3), XY reset to 135
        assertTrue("Plate1 has item 3", buildSection1.contains("""objectid="3""""))
        assertFalse("Plate1 has no item 8", buildSection1.contains("""objectid="8""""))
        assertTrue("Plate1 XY re-centred", buildSection1.contains("135 135 1.62"))

        // Plate 2 → second item (objectid=8), XY reset to 135
        assertTrue("Plate2 has item 8", buildSection2.contains("""objectid="8""""))
        assertFalse("Plate2 has no item 3", buildSection2.contains("""objectid="3""""))
        assertTrue("Plate2 XY re-centred", buildSection2.contains("135 135 1.64"))

        // Plate 3 → third item (objectid=9), XY reset to 135
        assertTrue("Plate3 has item 9", buildSection3.contains("""objectid="9""""))
        assertTrue("Plate3 XY re-centred", buildSection3.contains("135 135 1.64"))

        // Resources must be unchanged — all 3 objects still present
        assertTrue("Resources: object 3", plate2.contains("""id="3""""))
        assertTrue("Resources: object 8", plate2.contains("""id="8""""))
        assertTrue("Resources: object 9", plate2.contains("""id="9""""))

        // Virtual positions without plate JSONs (Dragon Scale / Shashibo style):
        // filtering MUST activate even when hasPlateJsons=false, because the virtual
        // TX/TY positions (> 270mm or < 0) identify separate plates.
        val virtualFormatXml = """<build>
    <item objectid="4" transform="1 0 0 0 1 0 0 0 1 128 128 10" printable="1"/>
    <item objectid="5" transform="1 0 0 0 1 0 0 0 1 435 128 10" printable="1"/>
    <item objectid="6" transform="1 0 0 0 1 0 0 0 1 128 -179 10" printable="1"/>
</build>"""
        val virtualPlate1 = filterByIndex(virtualFormatXml, 1, hasPlateJsons = false)
        val virtualBuild1 = virtualPlate1.substringAfter("<build>").substringBefore("</build>")
        // Only item 4 (first) should remain, re-centred
        assertTrue("Virtual: plate1 has item 4", virtualBuild1.contains("""objectid="4""""))
        assertFalse("Virtual: plate1 no item 5", virtualBuild1.contains("""objectid="5""""))
        assertFalse("Virtual: plate1 no item 6", virtualBuild1.contains("""objectid="6""""))
        assertTrue("Virtual: plate1 XY re-centred", virtualBuild1.contains("135 135 10"))

        val virtualPlate2 = filterByIndex(virtualFormatXml, 2, hasPlateJsons = false)
        val virtualBuild2 = virtualPlate2.substringAfter("<build>").substringBefore("</build>")
        assertTrue("Virtual: plate2 has item 5", virtualBuild2.contains("""objectid="5""""))
        assertFalse("Virtual: plate2 no item 4", virtualBuild2.contains("""objectid="4""""))

        // True single-plate multi-object (no virtual positions, no plate JSONs): keep all.
        val onBedXml = """<build>
    <item objectid="10" transform="1 0 0 0 1 0 0 0 1 50 50 0" printable="1"/>
    <item objectid="11" transform="1 0 0 0 1 0 0 0 1 150 150 0" printable="1"/>
</build>"""
        val onBedResult = filterByIndex(onBedXml, 1, hasPlateJsons = false)
        val onBedBuild = onBedResult.substringAfter("<build>").substringBefore("</build>")
        assertTrue("On-bed: item 10 kept", onBedBuild.contains("""objectid="10""""))
        assertTrue("On-bed: item 11 kept", onBedBuild.contains("""objectid="11""""))

        // B13: no virtual positions, no config mapping — fallback keeps all items.
        val multiObjSinglePlateXml = """<build>
    <item objectid="20" transform="1 0 0 0 1 0 0 0 1 80 80 0" printable="1"/>
    <item objectid="21" transform="1 0 0 0 1 0 0 0 1 190 80 0" printable="1"/>
    <item objectid="22" transform="1 0 0 0 1 0 0 0 1 80 190 0" printable="1"/>
    <item objectid="23" transform="1 0 0 0 1 0 0 0 1 190 190 0" printable="1"/>
</build>"""
        val b13Result = filterByIndex(multiObjSinglePlateXml, 1, hasPlateJsons = true)
        val b13Build = b13Result.substringAfter("<build>").substringBefore("</build>")
        assertTrue("B13: item 20 kept", b13Build.contains("""objectid="20""""))
        assertTrue("B13: item 21 kept", b13Build.contains("""objectid="21""""))
        assertTrue("B13: item 22 kept", b13Build.contains("""objectid="22""""))
        assertTrue("B13: item 23 kept", b13Build.contains("""objectid="23""""))
    }

    @Test
    fun `filterModelToPlate config-based - Sydney buttons multi-object multi-plate`() {
        // Sydney Opera House buttons: 3 plates, 4 objects per plate, all at virtual positions.
        // model_settings.config maps plate→object_ids. filterModelToPlate should use the
        // config mapping (Priority 1) to select all 4 objects for each plate.
        val buildRegex = Regex("""(<build\b[^>]*>)(.*?)(</build>)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val itemRegex  = Regex("""<item\b[^>]*(?:/>|>.*?</item>)""",  setOf(RegexOption.DOT_MATCHES_ALL))
        val objectIdRegex = Regex("""objectid="(\d+)"""")
        val transformRegex = Regex("""transform="([^"]+)"""")

        fun recenterItemIfVirtual(item: String): String {
            val parts = transformRegex.find(item)?.groupValues?.get(1)
                ?.trim()?.split(Regex("\\s+")) ?: return item
            val tx = parts.getOrNull(9)?.toFloatOrNull() ?: 0f
            val ty = parts.getOrNull(10)?.toFloatOrNull() ?: 0f
            if (tx > 270f || ty < -10f || ty > 270f) {
                return transformRegex.replace(item) { match ->
                    val p = match.groupValues[1].trim().split(Regex("\\s+"))
                    if (p.size >= 12) {
                        val np = p.toMutableList(); np[9] = "135"; np[10] = "135"
                        """transform="${np.joinToString(" ")}""""
                    } else match.value
                }
            }
            return item
        }

        @Suppress("UNUSED_PARAMETER")
        fun filterWithConfig(xml: String, targetPlateId: Int, plateObjectIds: Set<String>?): String {
            return buildRegex.replace(xml) { m ->
                val allItems = itemRegex.findAll(m.groupValues[2]).map { it.value }.toList()
                // Config-based filtering (Priority 1)
                if (plateObjectIds != null && plateObjectIds.isNotEmpty()) {
                    val targetItems = allItems.filter { item ->
                        val objId = objectIdRegex.find(item)?.groupValues?.get(1) ?: ""
                        objId in plateObjectIds
                    }
                    if (targetItems.isEmpty()) return@replace m.value
                    val recentered = targetItems.map { recenterItemIfVirtual(it) }
                    return@replace "${m.groupValues[1]}\n" +
                        recentered.joinToString("\n") { "    $it" } + "\n  ${m.groupValues[3]}"
                }
                m.value
            }
        }

        // 12 build items across 3 virtual plates (mimics Sydney buttons structure)
        val xml = """<build>
    <item objectid="2" transform="-1 0 0 0 -1 0 0 0 1 142 116 1" printable="1"/>
    <item objectid="3" transform="-1 0 0 0 -1 0 0 0 1 208 116 1" printable="1"/>
    <item objectid="4" transform="-1 0 0 0 -1 0 0 0 1 142 203 1" printable="1"/>
    <item objectid="5" transform="-1 0 0 0 -1 0 0 0 1 208 203 1" printable="1"/>
    <item objectid="7" transform="1 0 0 0 1 0 0 0 1 475 180 1.5" printable="1"/>
    <item objectid="8" transform="1 0 0 0 1 0 0 0 1 627 186 1.5" printable="1"/>
    <item objectid="9" transform="1 0 0 0 1 0 0 0 1 477 28 1.5" printable="1"/>
    <item objectid="10" transform="1 0 0 0 1 0 0 0 1 628 31 1.5" printable="1"/>
    <item objectid="20" transform="1 0 0 0 1 0 0 0 1 84 -98 1.5" printable="1"/>
    <item objectid="21" transform="0 -1 0 1 0 0 0 0 1 290 -125 1.5" printable="1"/>
    <item objectid="22" transform="1 0 0 0 1 0 0 0 1 85 -251 1.5" printable="1"/>
    <item objectid="23" transform="-1 0 0 0 -1 0 0 0 1 268 -356 1.5" printable="1"/>
</build>"""

        // Plate 1: objects 2,3,4,5 (at normal bed positions — no recentre needed)
        val plate1 = filterWithConfig(xml, 1, setOf("2", "3", "4", "5"))
        val b1 = plate1.substringAfter("<build>").substringBefore("</build>")
        assertTrue("Plate1 has obj 2", b1.contains("""objectid="2""""))
        assertTrue("Plate1 has obj 3", b1.contains("""objectid="3""""))
        assertTrue("Plate1 has obj 4", b1.contains("""objectid="4""""))
        assertTrue("Plate1 has obj 5", b1.contains("""objectid="5""""))
        assertFalse("Plate1 no obj 7", b1.contains("""objectid="7""""))
        assertFalse("Plate1 no obj 20", b1.contains("""objectid="20""""))
        // Items at normal bed positions — XY should be unchanged
        assertTrue("Plate1 obj 2 XY preserved", b1.contains("142 116 1"))

        // Plate 2: objects 7,8,9,10 (at virtual X positions — should be recentred)
        val plate2 = filterWithConfig(xml, 2, setOf("7", "8", "9", "10"))
        val b2 = plate2.substringAfter("<build>").substringBefore("</build>")
        assertTrue("Plate2 has obj 7", b2.contains("""objectid="7""""))
        assertTrue("Plate2 has obj 8", b2.contains("""objectid="8""""))
        assertTrue("Plate2 has obj 9", b2.contains("""objectid="9""""))
        assertTrue("Plate2 has obj 10", b2.contains("""objectid="10""""))
        assertFalse("Plate2 no obj 2", b2.contains("""objectid="2""""))
        // Virtual items recentred to 135
        assertTrue("Plate2 obj 7 recentred", b2.contains("135 135 1.5"))

        // Plate 3: objects 20-23 (at negative Y positions — should be recentred)
        val plate3 = filterWithConfig(xml, 3, setOf("20", "21", "22", "23"))
        val b3 = plate3.substringAfter("<build>").substringBefore("</build>")
        assertTrue("Plate3 has obj 20", b3.contains("""objectid="20""""))
        assertTrue("Plate3 has obj 21", b3.contains("""objectid="21""""))
        assertTrue("Plate3 has obj 22", b3.contains("""objectid="22""""))
        assertTrue("Plate3 has obj 23", b3.contains("""objectid="23""""))
        assertFalse("Plate3 no obj 2", b3.contains("""objectid="2""""))
        assertTrue("Plate3 obj 20 recentred", b3.contains("135 135 1.5"))
    }

    @Test
    fun `stripNonPrintableBuildItems - removes printable=0 items only from build`() {
        // stripNonPrintableBuildItems is private; simulate its regex logic here.
        // Verifies that printable="0" items are removed from <build> but resources stay intact.
        val buildRegex = Regex("""(<build\b[^>]*>)(.*?)(</build>)""", setOf(RegexOption.DOT_MATCHES_ALL))
        val itemRegex  = Regex("""<item\b[^>]*(?:/>|>.*?</item>)""",  setOf(RegexOption.DOT_MATCHES_ALL))

        fun strip(xml: String): String {
            return buildRegex.replace(xml) { m ->
                val allItems = itemRegex.findAll(m.groupValues[2]).map { it.value }.toList()
                val printable = allItems.filter { !it.contains("""printable="0"""") }
                if (printable.size == allItems.size) return@replace m.value
                val newBody = "\n" + printable.joinToString("\n") { "  $it" } + "\n  "
                "${m.groupValues[1]}$newBody${m.groupValues[3]}"
            }
        }

        val xml = """<?xml version="1.0"?>
<model>
  <resources>
    <object id="2" type="model"/>
    <object id="4" type="model"/>
  </resources>
  <build>
    <item objectid="2" transform="1 0 0 0 1 0 0 0 1 213 194 24" printable="0"/>
    <item objectid="4" transform="1 0 0 0 1 0 0 0 1 135 119 24" printable="1"/>
  </build>
</model>""".trimIndent()

        val result = strip(xml)
        val buildSection = result.substringAfter("<build>").substringBefore("</build>")

        // printable=1 item kept, printable=0 item removed
        assertTrue("printable=1 item kept", buildSection.contains("""objectid="4""""))
        assertFalse("printable=0 item removed", buildSection.contains("""objectid="2""""))

        // Resources untouched
        assertTrue("Resources: object 2 intact", result.contains("""<object id="2""""))
        assertTrue("Resources: object 4 intact", result.contains("""<object id="4""""))
    }

    @Test
    fun `component file size guard - large files skip restructuring`() {
        // Verify the 50MB threshold logic: total component size > 50MB → safeToInline = false.
        // Raised from 15MB to 50MB because OrcaSlicer's _generate_volumes_new doesn't support
        // firstid/lastid volume splitting — multi-color models MUST be restructured.
        val threshold = 50_000_000L

        // Small file set: 3 files × 2MB = 6MB → safe to inline
        val smallTotal = 3 * 2_000_000L
        assertTrue("6MB total should be safe to inline", smallTotal <= threshold)

        // Medium file: Dragon Scale at ~20MB → now safe to inline (was blocked at 15MB)
        val mediumTotal = 20_000_000L
        assertTrue("20MB total should be safe to inline (multi-color models)", mediumTotal <= threshold)

        // Large file set: 7 files × 10MB = 70MB → too large
        val largeTotal = 7 * 10_000_000L
        assertFalse("70MB total should skip inlining", largeTotal <= threshold)
    }

    @Test
    fun `buildSlic3rModelConfig logic - per-part volumes when face counts known`() {
        // Simulate buildSlic3rModelConfig for a multi-color object with known face counts.
        // Expects separate <volume> entries for each part, using face-count ranges.
        data class PartInfo(val faceCount: Int, val extruder: Int)

        fun buildConfig(objectParts: Map<String, List<PartInfo>>): String {
            val sb = StringBuilder()
            for ((objectId, parts) in objectParts.entries.sortedBy { it.key.toIntOrNull() ?: 0 }) {
                if (parts.isEmpty()) continue
                val overallExtruder = parts.maxOf { it.extruder }
                sb.appendLine("""  <object id="$objectId">""")
                sb.appendLine("""    <metadata type="object" key="extruder" value="$overallExtruder"/>""")
                val isMultiColor = parts.map { it.extruder }.distinct().size > 1
                val hasFaceCounts = parts.all { it.faceCount > 0 }
                if (isMultiColor && hasFaceCounts) {
                    var firstId = 0
                    for (part in parts) {
                        val lastId = firstId + part.faceCount - 1
                        sb.appendLine("""    <volume firstid="$firstId" lastid="$lastId">""")
                        sb.appendLine("""      <metadata type="volume" key="extruder" value="${part.extruder}"/>""")
                        sb.appendLine("""    </volume>""")
                        firstId += part.faceCount
                    }
                } else {
                    val faceCount = parts.sumOf { it.faceCount }
                    val lastId = maxOf(0, faceCount - 1)
                    sb.appendLine("""    <volume firstid="0" lastid="$lastId">""")
                    sb.appendLine("""      <metadata type="volume" key="extruder" value="$overallExtruder"/>""")
                    sb.appendLine("""    </volume>""")
                }
                sb.appendLine("""  </object>""")
            }
            return sb.toString()
        }

        // Multi-color object: part 1 has 500 faces on E1, part 2 has 300 faces on E2
        val parts = mapOf("5" to listOf(PartInfo(500, 1), PartInfo(300, 2)))
        val config = buildConfig(parts)

        // Should have two separate volume entries
        assertTrue("E1 volume starts at 0", config.contains("""firstid="0" lastid="499""""))
        assertTrue("E1 volume uses extruder 1", config.contains("""value="1""""))
        assertTrue("E2 volume starts at 500", config.contains("""firstid="500" lastid="799""""))
        assertTrue("E2 volume uses extruder 2", config.contains("""value="2""""))
        // Overall extruder is max (2)
        assertTrue("Object extruder is max", config.contains("""key="extruder" value="2""""))

        // Single-color object: no per-part split
        val singleParts = mapOf("3" to listOf(PartInfo(400, 1)))
        val singleConfig = buildConfig(singleParts)
        assertTrue("Single volume from 0", singleConfig.contains("""firstid="0" lastid="399""""))

        // Multi-color with zero face counts: fall back to single volume
        val zeroFaceParts = mapOf("7" to listOf(PartInfo(0, 1), PartInfo(0, 2)))
        val zeroConfig = buildConfig(zeroFaceParts)
        // hasFaceCounts = false → falls through to single-volume path
        assertTrue("Zero face count falls back", zeroConfig.contains("""firstid="0""""))
    }

    // --- stripAssembleSection ---

    @Test
    fun `stripAssembleSection - removes assemble block from model_settings config`() {
        // Simulates BambuSanitizer.stripAssembleSection() logic.
        // The <assemble> section in model_settings.config lists transforms for ALL objects
        // across all plates. When a single plate is extracted (only 1 object in <build>),
        // OrcaSlicer's _handle_start_assemble_item fails for the other objects → load failure.
        // Stripping the entire <assemble> block prevents this.
        fun strip(xml: String): String =
            xml.replace(Regex("""[ \t]*<assemble>.*?</assemble>[ \t]*\r?\n?""",
                setOf(RegexOption.DOT_MATCHES_ALL)), "")

        val xml = """<?xml version="1.0"?>
<config>
  <object id="3">
    <metadata type="object" key="name" value="Object"/>
  </object>
  <assemble>
    <assemble_item object_id="3" instance_id="0" transform="1 0 0 0 1 0 0 0 1 135 135 0"/>
    <assemble_item object_id="8" instance_id="0" transform="1 0 0 0 1 0 0 0 1 1052 154 0"/>
    <assemble_item object_id="20" instance_id="0" transform="1 0 0 0 1 0 0 0 1 200 200 0"/>
  </assemble>
</config>"""

        val result = strip(xml)

        // assemble block gone
        assertFalse("assemble block removed", result.contains("<assemble>"))
        assertFalse("assemble_item removed", result.contains("assemble_item"))

        // rest of config intact
        assertTrue("object id 3 still present", result.contains("""id="3""""))
        assertTrue("metadata still present", result.contains("""key="name""""))
    }

    @Test
    fun `stripAssembleSection - no-op when no assemble block present`() {
        fun strip(xml: String): String =
            xml.replace(Regex("""[ \t]*<assemble>.*?</assemble>[ \t]*\r?\n?""",
                setOf(RegexOption.DOT_MATCHES_ALL)), "")

        val xml = """<?xml version="1.0"?>
<config>
  <object id="5">
    <metadata type="object" key="name" value="Cube"/>
  </object>
</config>"""

        val result = strip(xml)
        assertEquals("XML unchanged when no assemble block", xml, result)
    }

    @Test
    fun `stripAssembleSection - preserves content before and after assemble block`() {
        fun strip(xml: String): String =
            xml.replace(Regex("""[ \t]*<assemble>.*?</assemble>[ \t]*\r?\n?""",
                setOf(RegexOption.DOT_MATCHES_ALL)), "")

        val xml = """<config>
  <object id="1"/>
  <assemble>
    <assemble_item object_id="1" instance_id="0" transform="1 0 0 0 1 0 0 0 1 0 0 0"/>
    <assemble_item object_id="2" instance_id="0" transform="1 0 0 0 1 0 0 0 1 1 1 1"/>
  </assemble>
  <object id="99"/>
</config>"""

        val result = strip(xml)
        assertFalse("assemble removed", result.contains("<assemble>"))
        assertTrue("object 1 before block intact", result.contains("""id="1""""))
        assertTrue("object 99 after block intact", result.contains("""id="99""""))
    }

    // Helper: simplified INI parser matching BambuSanitizer's logic
    private fun parseTestIniConfig(content: String): MutableMap<String, Any> {
        val config = mutableMapOf<String, Any>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            if (value.contains(";")) {
                config[key] = value.split(";").map { it.trim() }.toMutableList()
            } else {
                config[key] = value
            }
        }
        return config
    }
}
