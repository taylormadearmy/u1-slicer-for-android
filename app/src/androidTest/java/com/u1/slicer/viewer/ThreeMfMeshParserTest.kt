package com.u1.slicer.viewer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThreeMfMeshParserTest {

    private fun asset(name: String): File {
        val instr = InstrumentationRegistry.getInstrumentation()
        val out = File(instr.targetContext.cacheDir, name)
        instr.context.assets.open(name).use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        return out
    }

    @Test
    fun dragonScale_1plate_parsesMesh() {
        val file = asset("Dragon Scale infinity-1-plate-2-colours.3mf")
        Log.d("ThreeMfMeshTest", "File: ${file.name} (${file.length()/1024}KB)")
        val start = System.currentTimeMillis()
        val mesh = try {
            ThreeMfMeshParser.parse(file)
        } catch (t: Throwable) {
            Log.e("ThreeMfMeshTest", "Parse threw: ${t.javaClass.simpleName}: ${t.message}", t)
            throw AssertionError("ThreeMfMeshParser.parse() threw ${t.javaClass.simpleName}: ${t.message}", t)
        }
        val elapsed = System.currentTimeMillis() - start
        Log.d("ThreeMfMeshTest", "Parsed in ${elapsed}ms, mesh=${mesh != null}, verts=${mesh?.vertexCount}")
        assertNotNull("Expected non-null MeshData for Dragon Scale 1-plate-2-colours.3mf", mesh)
        assertTrue("Expected > 0 vertices", mesh!!.vertexCount > 0)
        Log.d("ThreeMfMeshTest", "Bounds: x=${mesh.minX}..${mesh.maxX}, y=${mesh.minY}..${mesh.maxY}, z=${mesh.minZ}..${mesh.maxZ}")
    }

    @Test
    fun old3mf_parsesMeshWithCorrectBounds() {
        val file = asset("old.3mf")
        Log.d("ThreeMfMeshTest", "File: ${file.name} (${file.length()/1024}KB)")
        val mesh = ThreeMfMeshParser.parse(file)
        assertNotNull("Expected non-null MeshData for old.3mf", mesh)
        assertTrue("Expected > 0 vertices", mesh!!.vertexCount > 0)
        Log.d("ThreeMfMeshTest", "old.3mf bounds: x=${mesh.minX}..${mesh.maxX}, y=${mesh.minY}..${mesh.maxY}, z=${mesh.minZ}..${mesh.maxZ}")
        // Model should be roughly centered around (165, 161, 63) based on the build item transform
        // and should fit within 270x270x270 bed
        assertTrue("maxX should be within bed bounds", mesh.maxX <= 270f)
        assertTrue("maxY should be within bed bounds", mesh.maxY <= 270f)
        assertTrue("minZ should be >= 0 (on bed)", mesh.minZ >= -1f)
    }

    @Test
    fun calibCube_parsesMeshWithDistinctExtruderIndices() {
        // Calicube uses SEMM paint_color attributes for per-triangle coloring.
        // After sanitization, paint_color attributes are preserved in the inlined model.
        val file = asset("calib-cube-10-dual-colour-merged.3mf")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val origInfo = com.u1.slicer.bambu.ThreeMfParser.parse(file)
        Log.d("B22Test", "CalibCube origInfo: hasPaintData=${origInfo.hasPaintData}, " +
            "objectExtruderMap=${origInfo.objectExtruderMap}, " +
            "detectedExtruderCount=${origInfo.detectedExtruderCount}")

        val processed = com.u1.slicer.bambu.BambuSanitizer.process(file, context.cacheDir, isBambu = origInfo.isBambu)

        // Build extruder map from objectExtruderMap (per-object assignments)
        val extruderMap = origInfo.objectExtruderMap.mapNotNull { (idStr, ext1) ->
            val id = idStr.toIntOrNull() ?: return@mapNotNull null
            id to (ext1 - 1).coerceAtLeast(0).toByte()
        }.toMap()

        // Parse mesh from the processed (sanitized) file directly
        val mesh = ThreeMfMeshParser.parse(processed, extruderMap = extruderMap)
        assertNotNull("Mesh should parse from sanitized calicube", mesh)
        assertNotNull("Should have extruder indices", mesh!!.extruderIndices)
        assertTrue("Should have triangles", mesh.extruderIndices!!.isNotEmpty())

        val distinctIndices = mesh.extruderIndices!!.toSet()
        Log.d("B22Test", "Calicube extruder indices: $distinctIndices " +
            "(${mesh.extruderIndices!!.size} triangles, extruderMap=$extruderMap)")
        // Calicube should have at least 2 distinct indices from either paint data or object assignments
        assertTrue(
            "Calicube should have >=2 distinct extruder indices, got $distinctIndices",
            distinctIndices.size >= 2
        )
    }

    @Test
    fun dragonScale_multiPlate_parsesMesh() {
        val file = asset("Dragon Scale infinity.3mf")
        val mesh = try {
            ThreeMfMeshParser.parse(file)
        } catch (t: Throwable) {
            throw AssertionError("ThreeMfMeshParser.parse() threw ${t.javaClass.simpleName}: ${t.message}", t)
        }
        assertNotNull("Expected non-null MeshData for Dragon Scale infinity.3mf (multi-plate)", mesh)
        assertTrue("Expected > 0 vertices", mesh!!.vertexCount > 0)
        Log.d("ThreeMfMeshTest", "Multi-plate bounds: x=${mesh.minX}..${mesh.maxX}, y=${mesh.minY}..${mesh.maxY}, z=${mesh.minZ}..${mesh.maxZ}")
    }
}
