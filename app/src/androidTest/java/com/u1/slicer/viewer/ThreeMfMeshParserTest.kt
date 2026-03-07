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
