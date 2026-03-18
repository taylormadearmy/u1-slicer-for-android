package com.u1.slicer.viewer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for ThreeMfMeshParser, focusing on transform handling (B15 fix).
 *
 * The 3MF spec defines transforms as row-vector convention:
 *   [x' y' z' 1] = [x y z 1] × M
 * where M is stored as: m00 m01 m02 m10 m11 m12 m20 m21 m22 tx ty tz
 */
class ThreeMfMeshParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parse identity transform preserves vertex positions`() {
        val model = buildModelXml(
            transform = "1 0 0 0 1 0 0 0 1 0 0 0",
            vertices = listOf(Triple(0f, 0f, 0f), Triple(10f, 0f, 0f), Triple(5f, 10f, 0f)),
            triangles = listOf(Triple(0, 1, 2))
        )
        val mesh = parseModel(model)
        assertNotNull("Mesh should not be null for identity transform", mesh)
        assertEquals(3, mesh!!.vertexCount)
        // Check bounding box — identity transform should preserve original coords
        assertEquals(0f, mesh.minX, 0.01f)
        assertEquals(10f, mesh.maxX, 0.01f)
        assertEquals(0f, mesh.minY, 0.01f)
        assertEquals(10f, mesh.maxY, 0.01f)
    }

    @Test
    fun `parse translation transform offsets vertices`() {
        val model = buildModelXml(
            transform = "1 0 0 0 1 0 0 0 1 50 60 70",
            vertices = listOf(Triple(0f, 0f, 0f), Triple(10f, 0f, 0f), Triple(5f, 10f, 0f)),
            triangles = listOf(Triple(0, 1, 2))
        )
        val mesh = parseModel(model)
        assertNotNull(mesh)
        // Bounding box should be offset by (50, 60, 70)
        assertEquals(50f, mesh!!.minX, 0.01f)
        assertEquals(60f, mesh.minY, 0.01f)
        assertEquals(70f, mesh.minZ, 0.01f)
    }

    @Test
    fun `parse 90-degree Z rotation transform rotates vertices correctly`() {
        // 90° around Z: cos90=0, sin90=1
        // Row-vector convention: m00=0, m01=1, m02=0, m10=-1, m11=0, m12=0, m20=0, m21=0, m22=1
        // 3MF string: "0 1 0 -1 0 0 0 0 1 0 0 0"
        // Expected: (x,y) → (-y, x)
        val model = buildModelXml(
            transform = "0 1 0 -1 0 0 0 0 1 0 0 0",
            vertices = listOf(Triple(10f, 0f, 0f), Triple(10f, 5f, 0f), Triple(10f, 0f, 5f)),
            triangles = listOf(Triple(0, 1, 2))
        )
        val mesh = parseModel(model)
        assertNotNull(mesh)
        // Original (10,0,0) → (-0, 10, 0) = (0, 10, 0)
        // Original (10,5,0) → (-5, 10, 0)
        // So: minX should be -5, maxX should be 0, minY=10, maxY=10
        assertEquals(-5f, mesh!!.minX, 0.01f)
        assertEquals(0f, mesh.maxX, 0.01f)
        assertEquals(10f, mesh.minY, 0.01f)
        assertEquals(10f, mesh.maxY, 0.01f)
    }

    @Test
    fun `parse no transform returns mesh with original positions`() {
        val model = buildModelXml(
            transform = null,
            vertices = listOf(Triple(5f, 10f, 15f), Triple(20f, 25f, 30f), Triple(35f, 40f, 45f)),
            triangles = listOf(Triple(0, 1, 2))
        )
        val mesh = parseModel(model)
        assertNotNull(mesh)
        assertEquals(5f, mesh!!.minX, 0.01f)
        assertEquals(35f, mesh.maxX, 0.01f)
    }

    @Test
    fun `parse model with no build items returns null`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" />
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build />
            </model>"""
        val mesh = parseModel(xml)
        assertNull("Empty build section should produce null mesh", mesh)
    }

    @Test
    fun `parse compound object with component transforms`() {
        // Simulates a restructured 3MF: parent object with <components> referencing
        // inlined mesh objects (like old.3mf after BambuSanitizer.restructureForMultiColor)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="10" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" />
                        </triangles>
                    </mesh>
                </object>
                <object id="11" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="5" y="0" z="0" />
                            <vertex x="2" y="5" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" />
                        </triangles>
                    </mesh>
                </object>
                <object id="20" type="model">
                    <components>
                        <component objectid="10" transform="1 0 0 0 1 0 0 0 1 0 0 0" />
                        <component objectid="11" transform="1 0 0 0 1 0 0 0 1 20 30 0" />
                    </components>
                </object>
            </resources>
            <build>
                <item objectid="20" transform="1 0 0 0 1 0 0 0 1 100 100 0" />
            </build>
            </model>"""
        val mesh = parseModel(xml)
        assertNotNull("Compound object should produce a mesh", mesh)
        assertEquals(6, mesh!!.vertexCount) // 2 triangles × 3 vertices
        // Component 10: identity comp transform + (100,100,0) build → min (100,100,0), max (110,110,0)
        // Component 11: (20,30,0) comp + (100,100,0) build → min (120,130,0), max (125,135,0)
        assertEquals(100f, mesh.minX, 0.01f)
        assertEquals(125f, mesh.maxX, 0.01f)
        assertEquals(100f, mesh.minY, 0.01f)
        assertEquals(135f, mesh.maxY, 0.01f)
    }

    @Test
    fun `parse compound object with rotation on build item`() {
        // 90° Z-rotation on the build item, identity on components
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" />
                        </triangles>
                    </mesh>
                </object>
                <object id="2" type="model">
                    <components>
                        <component objectid="1" transform="1 0 0 0 1 0 0 0 1 0 0 0" />
                    </components>
                </object>
            </resources>
            <build>
                <item objectid="2" transform="0 1 0 -1 0 0 0 0 1 0 0 0" />
            </build>
            </model>"""
        val mesh = parseModel(xml)
        assertNotNull(mesh)
        // (10,0) → (0,10), (5,10) → (-10,5), (0,0) → (0,0)
        assertEquals(-10f, mesh!!.minX, 0.01f)
        assertEquals(0f, mesh.maxX, 0.01f)
        assertEquals(0f, mesh.minY, 0.01f)
        assertEquals(10f, mesh.maxY, 0.01f)
    }

    // ── Extruder index tests ──

    @Test
    fun `parse with extruder map assigns correct indices`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" />
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1" />
            </build>
            </model>"""
        val file = create3mfZip(xml)
        val mesh = ThreeMfMeshParser.parse(file, extruderMap = mapOf(1 to 2.toByte()))
        assertNotNull(mesh)
        assertNotNull(mesh!!.extruderIndices)
        assertEquals(1, mesh.extruderIndices!!.size)
        assertEquals(2.toByte(), mesh.extruderIndices!![0])
    }

    @Test
    fun `parse without extruder map defaults to index 0`() {
        val model = buildModelXml(
            transform = null,
            vertices = listOf(Triple(0f, 0f, 0f), Triple(10f, 0f, 0f), Triple(5f, 10f, 0f)),
            triangles = listOf(Triple(0, 1, 2))
        )
        val mesh = parseModel(model)
        assertNotNull(mesh)
        assertNotNull(mesh!!.extruderIndices)
        assertEquals(1, mesh.extruderIndices!!.size)
        assertEquals(0.toByte(), mesh.extruderIndices!![0])
    }

    @Test
    fun `compound object components get correct extruder indices`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="10" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" />
                        </triangles>
                    </mesh>
                </object>
                <object id="11" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="5" y="0" z="0" />
                            <vertex x="2" y="5" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" />
                        </triangles>
                    </mesh>
                </object>
                <object id="20" type="model">
                    <components>
                        <component objectid="10" />
                        <component objectid="11" />
                    </components>
                </object>
            </resources>
            <build>
                <item objectid="20" />
            </build>
            </model>"""
        val file = create3mfZip(xml)
        val map = mapOf(10 to 0.toByte(), 11 to 1.toByte())
        val mesh = ThreeMfMeshParser.parse(file, extruderMap = map)
        assertNotNull(mesh)
        assertEquals(6, mesh!!.vertexCount) // 2 triangles x 3 vertices
        assertNotNull(mesh.extruderIndices)
        assertEquals(2, mesh.extruderIndices!!.size) // 2 triangles
        assertEquals(0.toByte(), mesh.extruderIndices!![0]) // object 10 -> extruder 0
        assertEquals(1.toByte(), mesh.extruderIndices!![1]) // object 11 -> extruder 1
    }

    // ── Paint color / mmu_segmentation tests ──

    @Test
    fun `parsePaintIndex extracts simple paint_color value`() {
        // state 2 = Extruder2 → 0-based index 1
        val line = """<triangle v1="0" v2="1" v3="2" paint_color="2C"/>"""
        assertEquals(1, ThreeMfMeshParser.parsePaintIndex(line, "paint_color"))
    }

    @Test
    fun `parsePaintIndex extracts mmu_segmentation value`() {
        // state 4 = Extruder4 → 0-based index 3
        val line = """<triangle v1="0" v2="1" v3="2" slic3rpe:mmu_segmentation="4"/>"""
        assertEquals(3, ThreeMfMeshParser.parsePaintIndex(line, "mmu_segmentation"))
    }

    @Test
    fun `parsePaintIndex returns first digit for complex subdivision`() {
        // state 3 = Extruder3 → 0-based index 2
        val line = """<triangle v1="0" v2="1" v3="2" paint_color="3C43C13CA"/>"""
        assertEquals(2, ThreeMfMeshParser.parsePaintIndex(line, "paint_color"))
    }

    @Test
    fun `parsePaintIndex returns -1 for NONE state`() {
        // state 0 = NONE → unpainted (-1), falls back to volume extruder
        val line = """<triangle v1="0" v2="1" v3="2" paint_color="0C"/>"""
        assertEquals(-1, ThreeMfMeshParser.parsePaintIndex(line, "paint_color"))
    }

    @Test
    fun `parsePaintIndex maps state 1 to index 0`() {
        // state 1 = Extruder1 → 0-based index 0
        val line = """<triangle v1="0" v2="1" v3="2" paint_color="1C"/>"""
        assertEquals(0, ThreeMfMeshParser.parsePaintIndex(line, "paint_color"))
    }

    @Test
    fun `parsePaintIndex folds H2C AMS2 states to AMS1 range`() {
        // H2C: AMS2 slot N (state N+4) = same physical filament as AMS1 slot N (state N)
        // state 5 → (5-1)%4 = 0  (same as state 1)
        // state 6 → (6-1)%4 = 1  (same as state 2)
        // state 7 → (7-1)%4 = 2  (same as state 3)
        // state 8 → (8-1)%4 = 3  (same as state 4)
        assertEquals(0, ThreeMfMeshParser.parsePaintIndex("""<triangle paint_color="5C"/>""", "paint_color"))
        assertEquals(1, ThreeMfMeshParser.parsePaintIndex("""<triangle paint_color="6C"/>""", "paint_color"))
        assertEquals(2, ThreeMfMeshParser.parsePaintIndex("""<triangle paint_color="7C"/>""", "paint_color"))
        assertEquals(3, ThreeMfMeshParser.parsePaintIndex("""<triangle paint_color="8C"/>""", "paint_color"))
    }

    @Test
    fun `parsePaintIndex preserves distinct H2C colors when detected count exceeds four`() {
        assertEquals(4, ThreeMfMeshParser.parsePaintIndex("""<triangle paint_color="5C"/>""", "paint_color", detectedColorCount = 7))
        assertEquals(5, ThreeMfMeshParser.parsePaintIndex("""<triangle paint_color="6C"/>""", "paint_color", detectedColorCount = 7))
        assertEquals(6, ThreeMfMeshParser.parsePaintIndex("""<triangle paint_color="7C"/>""", "paint_color", detectedColorCount = 7))
    }

    @Test
    fun `parsePaintIndex returns -1 when attribute missing`() {
        val line = """<triangle v1="0" v2="1" v3="2"/>"""
        assertEquals(-1, ThreeMfMeshParser.parsePaintIndex(line, "paint_color"))
    }

    @Test
    fun `parsePaintIndex returns -1 for empty value`() {
        val line = """<triangle v1="0" v2="1" v3="2" paint_color=""/>"""
        assertEquals(-1, ThreeMfMeshParser.parsePaintIndex(line, "paint_color"))
    }

    @Test
    fun `parse 3mf with paint_color assigns per-triangle extruder indices`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                            <vertex x="5" y="5" z="10" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" paint_color="0C"/>
                            <triangle v1="0" v2="1" v3="3" paint_color="1C"/>
                            <triangle v1="1" v2="2" v3="3" paint_color="2C"/>
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1" />
            </build>
            </model>"""
        val file = create3mfZip(xml)
        val mesh = ThreeMfMeshParser.parse(file)
        assertNotNull(mesh)
        assertNotNull(mesh!!.extruderIndices)
        assertEquals(3, mesh.extruderIndices!!.size) // 3 triangles
        // "0C" = state 0 (NONE) → unpainted → falls back to volumeExtruderIdx (0)
        assertEquals(0.toByte(), mesh.extruderIndices!![0]) // paint_color="0C"
        // "1C" = state 1 (Extruder1) → index 0
        assertEquals(0.toByte(), mesh.extruderIndices!![1]) // paint_color="1C"
        // "2C" = state 2 (Extruder2) → index 1
        assertEquals(1.toByte(), mesh.extruderIndices!![2]) // paint_color="2C"
    }

    @Test
    fun `parse compacts sparse non H2C paint states into distinct preview colors`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                            <vertex x="20" y="0" z="0" />
                            <vertex x="30" y="0" z="0" />
                            <vertex x="25" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" paint_color="4C"/>
                            <triangle v1="3" v2="4" v3="5" paint_color="8C"/>
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1" />
            </build>
            </model>"""
        val file = create3mfZip(xml)
        val mesh = ThreeMfMeshParser.parse(file, detectedColorCount = 2)
        assertNotNull(mesh)
        assertArrayEquals(byteArrayOf(0, 1), mesh!!.extruderIndices)
    }

    @Test
    fun `parse subdivides triangle selector paint_color into child triangles`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="0" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" paint_color="1C2C2C2"/>
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1" />
            </build>
            </model>"""
        val file = create3mfZip(xml)
        val mesh = ThreeMfMeshParser.parse(file)
        assertNotNull(mesh)
        assertEquals(9, mesh!!.vertexCount)
        assertArrayEquals(byteArrayOf(0, 1, 1), mesh.extruderIndices!!.sortedArray())
    }

    @Test
    fun `parse keeps H2C paint states 5 to 7 distinct when detected color count is seven`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                            <vertex x="20" y="0" z="0" />
                            <vertex x="30" y="0" z="0" />
                            <vertex x="25" y="10" z="0" />
                            <vertex x="40" y="0" z="0" />
                            <vertex x="50" y="0" z="0" />
                            <vertex x="45" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" paint_color="5C"/>
                            <triangle v1="3" v2="4" v3="5" paint_color="6C"/>
                            <triangle v1="6" v2="7" v3="8" paint_color="7C"/>
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1" />
            </build>
            </model>"""
        val file = create3mfZip(xml)
        val mesh = ThreeMfMeshParser.parse(file, detectedColorCount = 7)
        assertNotNull(mesh)
        assertArrayEquals(byteArrayOf(4, 5, 6), mesh!!.extruderIndices)
    }

    @Test
    fun `parse maps H2C state 8 to the last detected color when only seven colors are advertised`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" paint_color="8C"/>
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1" />
            </build>
            </model>"""
        val file = create3mfZip(xml)
        val mesh = ThreeMfMeshParser.parse(file, detectedColorCount = 7)
        assertNotNull(mesh)
        assertArrayEquals(byteArrayOf(6), mesh!!.extruderIndices)
    }

    @Test
    fun `parse keeps H2C triangle selector colors 5 and 6 distinct`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                            <vertex x="20" y="0" z="0" />
                            <vertex x="30" y="0" z="0" />
                            <vertex x="25" y="10" z="0" />
                            <vertex x="40" y="0" z="0" />
                            <vertex x="50" y="0" z="0" />
                            <vertex x="45" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" paint_color="83C883"/>
                            <triangle v1="3" v2="4" v3="5" paint_color="84C883"/>
                            <triangle v1="6" v2="7" v3="8" paint_color="8C"/>
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1" />
            </build>
            </model>"""
        val file = create3mfZip(
            modelXml = xml,
            extraEntries = mapOf(
                "Metadata/project_settings.config" to """{"filament_settings_id":["Bambu PLA Basic @BBL H2C"]}"""
            )
        )
        val mesh = ThreeMfMeshParser.parse(file, detectedColorCount = 7)
        assertNotNull(mesh)
        assertTrue(mesh!!.extruderIndices!!.contains(4.toByte()))
        assertTrue(mesh.extruderIndices!!.contains(5.toByte()))
        assertTrue(mesh.extruderIndices!!.contains(6.toByte()))
    }

    @Test
    fun `paint_color wins over volume extruder index`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" paint_color="3C"/>
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1" />
            </build>
            </model>"""
        val file = create3mfZip(xml)
        // Volume extruder is 1, but paint_color "3C" (state 3 = Extruder3 → index 2) wins
        val mesh = ThreeMfMeshParser.parse(file, extruderMap = mapOf(1 to 1.toByte()))
        assertNotNull(mesh)
        assertNotNull(mesh!!.extruderIndices)
        assertEquals(2.toByte(), mesh.extruderIndices!![0]) // paint wins (state 3 → index 2)
    }

    @Test
    fun `unpainted triangles use volume extruder index`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            <vertex x="0" y="0" z="0" />
                            <vertex x="10" y="0" z="0" />
                            <vertex x="5" y="10" z="0" />
                            <vertex x="5" y="5" z="10" />
                        </vertices>
                        <triangles>
                            <triangle v1="0" v2="1" v3="2" paint_color="2C"/>
                            <triangle v1="0" v2="1" v3="3" />
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1" />
            </build>
            </model>"""
        val file = create3mfZip(xml)
        val mesh = ThreeMfMeshParser.parse(file, extruderMap = mapOf(1 to 1.toByte()))
        assertNotNull(mesh)
        assertNotNull(mesh!!.extruderIndices)
        assertEquals(2, mesh.extruderIndices!!.size)
        assertEquals(1.toByte(), mesh.extruderIndices!![0]) // "2C" = state 2 → index 1
        assertEquals(1.toByte(), mesh.extruderIndices!![1]) // unpainted → volume extruder (1)
    }

    // ── B23: Multi-object extruder map tests ──

    @Test
    fun `multi-object extruder map assigns distinct indices per object`() {
        // Each tag must be on its own line — stream parser processes one line at a time
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
  <resources>
    <object id="1" type="model">
      <mesh>
        <vertices>
          <vertex x="0" y="0" z="0"/>
          <vertex x="10" y="0" z="0"/>
          <vertex x="5" y="10" z="0"/>
        </vertices>
        <triangles>
          <triangle v1="0" v2="1" v3="2"/>
        </triangles>
      </mesh>
    </object>
    <object id="2" type="model">
      <mesh>
        <vertices>
          <vertex x="20" y="0" z="0"/>
          <vertex x="30" y="0" z="0"/>
          <vertex x="25" y="10" z="0"/>
        </vertices>
        <triangles>
          <triangle v1="0" v2="1" v3="2"/>
        </triangles>
      </mesh>
    </object>
    <object id="3" type="model">
      <mesh>
        <vertices>
          <vertex x="40" y="0" z="0"/>
          <vertex x="50" y="0" z="0"/>
          <vertex x="45" y="10" z="0"/>
        </vertices>
        <triangles>
          <triangle v1="0" v2="1" v3="2"/>
        </triangles>
      </mesh>
    </object>
  </resources>
  <build>
    <item objectid="1"/>
    <item objectid="2"/>
    <item objectid="3"/>
  </build>
</model>"""
        val file = create3mfZip(xml)
        val extruderMap = mapOf(1 to 0.toByte(), 2 to 1.toByte(), 3 to 2.toByte())
        val mesh = ThreeMfMeshParser.parse(file, extruderMap = extruderMap)
        assertNotNull(mesh)
        assertNotNull(mesh!!.extruderIndices)
        assertEquals(3, mesh.extruderIndices!!.size)
        assertEquals(0.toByte(), mesh.extruderIndices!![0])  // object 1 → E1
        assertEquals(1.toByte(), mesh.extruderIndices!![1])  // object 2 → E2
        assertEquals(2.toByte(), mesh.extruderIndices!![2])  // object 3 → E3
    }

    @Test
    fun `extruder map with non-matching IDs falls back to index 0`() {
        // Object has ID 5, but extruderMap only has IDs 1,2,3 (old IDs from before restructuring)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
  <resources>
    <object id="5" type="model">
      <mesh>
        <vertices>
          <vertex x="0" y="0" z="0"/>
          <vertex x="10" y="0" z="0"/>
          <vertex x="5" y="10" z="0"/>
        </vertices>
        <triangles>
          <triangle v1="0" v2="1" v3="2"/>
        </triangles>
      </mesh>
    </object>
  </resources>
  <build>
    <item objectid="5"/>
  </build>
</model>"""
        val file = create3mfZip(xml)
        val staleMap = mapOf(1 to 0.toByte(), 2 to 1.toByte(), 3 to 2.toByte())
        val mesh = ThreeMfMeshParser.parse(file, extruderMap = staleMap)
        assertNotNull(mesh)
        assertNotNull(mesh!!.extruderIndices)
        for (idx in mesh.extruderIndices!!) {
            assertEquals(0.toByte(), idx)
        }
    }

    // ── Helpers ──

    private fun buildModelXml(
        transform: String?,
        vertices: List<Triple<Float, Float, Float>>,
        triangles: List<Triple<Int, Int, Int>>
    ): String {
        val vertXml = vertices.joinToString("\n") { (x, y, z) ->
            """<vertex x="$x" y="$y" z="$z" />"""
        }
        val triXml = triangles.joinToString("\n") { (v1, v2, v3) ->
            """<triangle v1="$v1" v2="$v2" v3="$v3" />"""
        }
        val transformAttr = if (transform != null) """ transform="$transform"""" else ""
        return """<?xml version="1.0" encoding="UTF-8"?>
            <model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources>
                <object id="1" type="model">
                    <mesh>
                        <vertices>
                            $vertXml
                        </vertices>
                        <triangles>
                            $triXml
                        </triangles>
                    </mesh>
                </object>
            </resources>
            <build>
                <item objectid="1"$transformAttr />
            </build>
            </model>"""
    }

    private fun parseModel(xml: String): MeshData? {
        val file = create3mfZip(xml)
        return ThreeMfMeshParser.parse(file)
    }

    private fun create3mfZip(
        modelXml: String,
        extraEntries: Map<String, String> = emptyMap()
    ): File {
        val file = tempFolder.newFile("test.3mf")
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zos.write(modelXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            extraEntries.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return file
    }
}
