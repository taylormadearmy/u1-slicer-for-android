package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationCascadeTest {

    /** Synthetic triangle list: N triangles arranged at increasing Z. Used for Z-band tests. */
    internal fun ladderPositions(triCount: Int): FloatArray {
        val out = FloatArray(triCount * 9)
        for (t in 0 until triCount) {
            val z = t.toFloat()
            val b = t * 9
            // three vertices at z, z, z (degenerate-but-fine for centroid computation)
            for (v in 0 until 3) {
                out[b + v * 3 + 0] = 0f
                out[b + v * 3 + 1] = 0f
                out[b + v * 3 + 2] = z
            }
        }
        return out
    }

    @Test
    fun `zBand branch produces TARGET_SEGMENTS leaves with monotonic z`() {
        val tris = 240
        val result = SegmentationCascade.zBandBranch(
            ladderPositions(tris),
            bandCount = 12,
        )
        assertEquals(SegmentationSource.Z_BAND, result.source)
        assertEquals(1, result.tree.size) // single root
        val root = result.tree.first()
        assertEquals(12, root.children.size)
        // Each band gets exactly tris / 12 = 20 triangles.
        root.children.forEachIndexed { i, child ->
            assertEquals("band $i triangle count", 20, child.triangleIds.size)
        }
        // triangleSegments labels triangles 0..bandCount-1 in monotonic order.
        for (t in 0 until tris) {
            val expectedBand = (t / 20).coerceAtMost(11)
            assertEquals("triangle $t belongs to band $expectedBand",
                expectedBand, result.triangleSegments[t].toInt() and 0xFF)
        }
    }

    @Test
    fun `zBand assigns slots round-robin`() {
        val result = SegmentationCascade.zBandBranch(ladderPositions(24), bandCount = 12)
        val slots = result.tree.first().children.map { it.region.slot }
        assertEquals(listOf(0,1,2,3,0,1,2,3,0,1,2,3), slots)
    }

    private fun objectInfo(id: Long, name: String, extruder: Int?, triCount: Int): SegmentationCascade.ObjectInfo =
        SegmentationCascade.ObjectInfo(
            objectId = id,
            name = name,
            extruder = extruder,
            triangleIds = IntArray(triCount) { it },
        )

    @Test
    fun `objectBranch produces one leaf per object`() {
        val objects = listOf(
            objectInfo(1, "Hull", 1, 100),
            objectInfo(2, "Cabin", 2, 50),
            objectInfo(3, "Smokestack", 3, 25),
        )
        val r = SegmentationCascade.objectBranch(totalTriangles = 175, objects = objects)
        assertEquals(SegmentationSource.OBJECT, r.source)
        val root = r.tree.first()
        assertEquals(3, root.children.size)
        assertEquals(listOf("Hull", "Cabin", "Smokestack"),
            root.children.map { it.region.label })
        assertEquals(listOf(0, 1, 2),
            root.children.map { it.region.slot })
    }

    @Test
    fun `objectBranch null when only one object`() {
        val r = SegmentationCascade.objectBranch(
            totalTriangles = 100,
            objects = listOf(objectInfo(1, "Solo", null, 100)),
        )
        assertTrue(r.tree.isEmpty())
    }

    @Test
    fun `objectBranch falls back to round-robin slots when extruder missing`() {
        val r = SegmentationCascade.objectBranch(
            totalTriangles = 300,
            objects = listOf(
                objectInfo(1, "A", null, 100),
                objectInfo(2, "B", null, 100),
                objectInfo(3, "C", null, 100),
            ),
        )
        val slots = r.tree.first().children.map { it.region.slot }
        assertEquals(listOf(0, 1, 2), slots)
    }

    private fun volumeInfo(objId: Long, objName: String, volumes: List<Triple<Int?, Int, Int>>): SegmentationCascade.ObjectVolumes {
        // volumes = list of (extruder?, firstTri, triCount)
        val vols = volumes.mapIndexed { idx, (ext, first, count) ->
            SegmentationCascade.VolumeInfo(
                volumeIndex = idx,
                extruder = ext,
                triangleIds = IntArray(count) { first + it },
            )
        }
        return SegmentationCascade.ObjectVolumes(objId, objName, vols)
    }

    @Test
    fun `volumeBranch nests volumes under an object when more than one`() {
        val obj = volumeInfo(1L, "Dragon", listOf(
            Triple(1, 0, 100),
            Triple(2, 100, 50),
            Triple(3, 150, 25),
        ))
        val r = SegmentationCascade.volumeBranch(totalTriangles = 175, objects = listOf(obj))
        assertEquals(SegmentationSource.VOLUME, r.source)
        val root = r.tree.first()
        assertEquals(1, root.children.size)
        assertEquals("Dragon", root.children.first().region.label)
        assertEquals(3, root.children.first().children.size)
    }

    @Test
    fun `volumeBranch flattens to leaves when each object has one volume`() {
        val objs = listOf(
            volumeInfo(1L, "A", listOf(Triple(1, 0, 50))),
            volumeInfo(2L, "B", listOf(Triple(2, 50, 50))),
        )
        val r = SegmentationCascade.volumeBranch(totalTriangles = 100, objects = objs)
        val root = r.tree.first()
        assertEquals(2, root.children.size)
        assertEquals(true, root.children.all { it.children.isEmpty() })
    }

    @Test
    fun `volumeBranch null when only one volume across all objects`() {
        val obj = volumeInfo(1L, "Solo", listOf(Triple(1, 0, 100)))
        val r = SegmentationCascade.volumeBranch(totalTriangles = 100, objects = listOf(obj))
        assertTrue(r.tree.isEmpty())
    }

    @Test
    fun `paintStateBranch produces one leaf per distinct state`() {
        val perTriState = ByteArray(30) { i -> ((i / 10) + 1).toByte() }
        val r = SegmentationCascade.paintStateBranch(perTriState)
        assertEquals(SegmentationSource.PAINT_STATE, r.source)
        val root = r.tree.first()
        assertEquals(3, root.children.size)
        assertEquals(listOf(0, 1, 2), root.children.map { it.region.slot })
    }

    @Test
    fun `paintStateBranch handles seven H2C states without folding`() {
        val perTriState = ByteArray(70) { i -> ((i / 10) + 1).toByte() }
        val r = SegmentationCascade.paintStateBranch(perTriState)
        val root = r.tree.first()
        assertEquals(7, root.children.size)
        assertEquals(listOf(0,1,2,3,0,1,2), root.children.map { it.region.slot })
    }

    @Test
    fun `paintStateBranch null when only one state present`() {
        val perTriState = ByteArray(50) { 1 }
        val r = SegmentationCascade.paintStateBranch(perTriState)
        assertTrue(r.tree.isEmpty())
    }

    @Test
    fun `triangleIndexBranch fires when preview indices distinct`() {
        val perTriIndex = ByteArray(20) { i -> (i % 3).toByte() }
        val r = SegmentationCascade.triangleIndexBranch(perTriIndex)
        assertEquals(SegmentationSource.TRIANGLE_INDEX, r.source)
        assertEquals(3, r.tree.first().children.size)
    }

    @Test
    fun `topology branch yields at least 2 leaves on disjoint clusters`() {
        // 3 disjoint triangle clusters of 30 each.
        val positions = FloatArray(90 * 9)
        for (cluster in 0 until 3) {
            val cx = cluster * 100f
            for (t in 0 until 30) {
                val b = (cluster * 30 + t) * 9
                positions[b + 0] = cx; positions[b + 1] = t * 0.01f; positions[b + 2] = 0f
                positions[b + 3] = cx + 1f; positions[b + 4] = t * 0.01f; positions[b + 5] = 0f
                positions[b + 6] = cx; positions[b + 7] = t * 0.01f + 1f; positions[b + 8] = 0f
            }
        }
        val r = SegmentationCascade.topologyBranch(positions)
        assertTrue("topology branch must yield ≥ 2 leaves on disjoint clusters",
            (r.tree.firstOrNull()?.children?.size ?: 0) >= 2)
        assertTrue(r.source == SegmentationSource.TOPOLOGY ||
                   r.source == SegmentationSource.TOPOLOGY_RECURSIVE)
    }
}
