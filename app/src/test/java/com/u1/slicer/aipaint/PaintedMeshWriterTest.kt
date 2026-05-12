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
    fun `model settings config has minimal BBS trigger entry`() {
        val out = tmp.newFile("painted.3mf")
        PaintedMeshWriter.write(fourRegionPositions(), fourRegionIds(), regions(), out)
        ZipFile(out).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("Metadata/model_settings.config")).reader().readText()
            assertTrue("Must have object id=1 to trigger BBS parser", xml.contains("""id="1""""))
        }
    }
}
