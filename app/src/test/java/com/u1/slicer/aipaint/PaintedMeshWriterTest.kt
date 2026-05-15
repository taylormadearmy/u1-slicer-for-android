package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class PaintedMeshWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fourRegionPositions(): FloatArray {
        val tri = { x: Float -> floatArrayOf(x,0f,0f, x+1f,0f,0f, x+0.5f,1f,0f) }
        return (0..7).flatMap { i -> tri(i * 2f).toList() }.toFloatArray()
    }
    private fun fourRegionIds() = intArrayOf(0,0, 1,1, 2,2, 3,3)
    private fun regions() = listOf(
        AiRegion(0,"Head","#FFCC00"), AiRegion(1,"Body","#C62828"),
        AiRegion(2,"Wings","#1565C0"), AiRegion(3,"Base","#37474F")
    )

    @Test
    fun `output file is a valid ZIP`() {
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        assertTrue(out.length() > 0)
        ZipFile(out).use { zip -> assertNotNull(zip) }
    }

    @Test
    fun `ZIP contains 3dmodel_model entry`() {
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        ZipFile(out).use { zip ->
            assertNotNull(zip.getEntry("3D/3dmodel.model"))
        }
    }

    @Test
    fun `ZIP contains model_settings_config entry`() {
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        ZipFile(out).use { zip ->
            assertNotNull(zip.getEntry("Metadata/model_settings.config"))
        }
    }

    @Test
    fun `model XML contains single painted object with paint_color on every triangle`() {
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        ZipFile(out).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("3D/3dmodel.model")).reader().readText()
            // Single object (paint_color approach replaces 4-object approach)
            assertEquals(1, Regex("""<object """).findAll(xml).count())
            // Every triangle has a paint_color attribute
            val triCount = Regex("""<triangle """).findAll(xml).count()
            val paintCount = Regex("""paint_color=""").findAll(xml).count()
            assertEquals(triCount, paintCount)
        }
    }

    @Test
    fun `paint_color encodes correct states for each region`() {
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        ZipFile(out).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("3D/3dmodel.model")).reader().readText()
            // fourRegionIds() = [0,0, 1,1, 2,2, 3,3] → 2 tris per region
            // Leaf-triangle encoding: state1="4", state2="8", state3="0C", state4="1C"
            assertTrue("Missing state1 paint", xml.contains("""paint_color="4""""))
            assertTrue("Missing state2 paint", xml.contains("""paint_color="8""""))
            assertTrue("Missing state3 paint", xml.contains("""paint_color="0C""""))
            assertTrue("Missing state4 paint", xml.contains("""paint_color="1C""""))
        }
    }

    @Test
    fun `each paint state is findable by line-by-line scan matching ThreeMfParser behaviour`() {
        // ThreeMfParser.streamCollectPaintSpecs reads the model line-by-line and extracts
        // one paint_color value per line.  If all triangles are on a single line only the
        // first state is detected, giving detectedExtruderCount=1 and a single-colour preview.
        // This test guards against regression to single-line XML output.
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        ZipFile(out).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("3D/3dmodel.model")).reader().readText()
            val regex = Regex("""paint_color="([^"]+)"""")
            val statesPerLine = xml.lines()
                .mapNotNull { regex.find(it)?.groupValues?.getOrNull(1) }
                .toSet()
            assertEquals(setOf("4", "8", "0C", "1C"), statesPerLine)
        }
    }

    @Test
    fun `model settings config has minimal BBS trigger entry`() {
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        ZipFile(out).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("Metadata/model_settings.config")).reader().readText()
            assertTrue("Must have object id=1 to trigger BBS parser", xml.contains("""id="1""""))
        }
    }

    @Test
    fun `ZIP contains project_settings_config with 4 filament_colour entries`() {
        // Without this file bambuCanonicalList() returns null, getCanonicalFilamentList() falls
        // back to a 1-entry STL list, and the slicer's embedded filament_colour ends up size 1 →
        // multi_material_segmentation_by_painting collapses to a single tool. Guards F54 fix9.
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        ZipFile(out).use { zip ->
            val entry = zip.getEntry("Metadata/project_settings.config")
            assertNotNull("Missing Metadata/project_settings.config", entry)
            val json = zip.getInputStream(entry).reader().readText()
            val obj = org.json.JSONObject(json)
            val colours = obj.getJSONArray("filament_colour")
            assertEquals(4, colours.length())
            // The 4 AI suggested region colours should be in the array (effectiveColour wins
            // over suggestedColour when the user has overridden one, but in this test we use defaults).
            assertEquals("#FFCC00", colours.getString(0))
            assertEquals("#C62828", colours.getString(1))
            assertEquals("#1565C0", colours.getString(2))
            assertEquals("#37474F", colours.getString(3))
        }
    }

    @Test
    fun `project_settings_config includes filament_type and filament_count`() {
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        ZipFile(out).use { zip ->
            val json = zip.getInputStream(zip.getEntry("Metadata/project_settings.config"))
                .reader().readText()
            val obj = org.json.JSONObject(json)
            assertEquals(4, obj.getJSONArray("filament_type").length())
            assertEquals("4", obj.getString("filament_count"))
        }
    }

    @Test
    fun `printerColours override the AI's suggested filament_colour when provided`() {
        // User loads E1=Black, E2=Red, E3=Blue, E4=Green. AI suggests Yellow/Crimson/Cyan/Slate.
        // We want the print to use the printer's loaded colours, not the AI's suggestions.
        val printer = listOf("#000000", "#FF0000", "#0000FF", "#00FF00")
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(
            fourRegionPositions(), fourRegionIds(), regions(), out,
            printerColours = printer
        )
        ZipFile(out).use { zip ->
            val json = zip.getInputStream(zip.getEntry("Metadata/project_settings.config"))
                .reader().readText()
            val colours = org.json.JSONObject(json).getJSONArray("filament_colour")
            assertEquals(printer[0], colours.getString(0))
            assertEquals(printer[1], colours.getString(1))
            assertEquals(printer[2], colours.getString(2))
            assertEquals(printer[3], colours.getString(3))
            // None of the AI's region suggestions should leak through.
            val all = (0 until colours.length()).map { colours.getString(it) }
            assertFalse("Yellow leaked through", all.contains("#FFCC00"))
        }
    }

    @Test
    fun `printerColours falls back to AI hex when entry is missing or malformed`() {
        // Only 3 valid printer slots; the 4th region falls back to AI's effectiveColour.
        val printer = listOf("#111111", "not a hex", "#333333")
        val json = org.json.JSONObject(
            PaintedMeshWriter.buildProjectSettings(regions(), printer)
        )
        val colours = json.getJSONArray("filament_colour")
        assertEquals("#111111", colours.getString(0))
        assertEquals("#C62828", colours.getString(1))   // AI fallback (malformed printer entry)
        assertEquals("#333333", colours.getString(2))
        assertEquals("#37474F", colours.getString(3))   // AI fallback (printer entry missing)
    }

    @Test
    fun `buildProjectSettings sanitises malformed hex to grey`() {
        val regions = listOf(
            AiRegion(0, "A", "not a hex"),
            AiRegion(1, "B", "#ABC"),       // too short
            AiRegion(2, "C", "#12345"),     // too short by one
            AiRegion(3, "D", "#1A2B3C")     // valid
        )
        val json = org.json.JSONObject(PaintedMeshWriter.buildProjectSettings(regions))
        val colours = json.getJSONArray("filament_colour")
        assertEquals("#808080", colours.getString(0))
        assertEquals("#808080", colours.getString(1))
        assertEquals("#808080", colours.getString(2))
        assertEquals("#1A2B3C", colours.getString(3))
    }
}
