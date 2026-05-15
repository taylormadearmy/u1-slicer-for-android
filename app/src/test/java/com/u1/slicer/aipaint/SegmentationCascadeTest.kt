package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                expectedBand, result.triangleSegments[t])
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
    fun `run picks paint state first when present`() {
        val input = SegmentationCascade.Input(
            positions = ladderPositions(30),
            perTrianglePaintState = ByteArray(30) { i -> ((i / 10) + 1).toByte() },
            volumes = emptyList(),
            objects = emptyList(),
            perTriangleIndex = ByteArray(0),
        )
        assertEquals(SegmentationSource.PAINT_STATE, SegmentationCascade.run(input).source)
    }

    @Test
    fun `run picks volume branch over object branch when both present`() {
        val obj1 = volumeInfo(1L, "Object", listOf(
            Triple(1, 0, 50),
            Triple(2, 50, 50),
        ))
        val input = SegmentationCascade.Input(
            positions = ladderPositions(100),
            perTrianglePaintState = ByteArray(100),
            volumes = listOf(obj1),
            objects = listOf(SegmentationCascade.ObjectInfo(1L, "Object", null, IntArray(100) { it })),
            perTriangleIndex = ByteArray(100),
        )
        assertEquals(SegmentationSource.VOLUME, SegmentationCascade.run(input).source)
    }

    @Test
    fun `run picks object branch over topology when both present`() {
        val input = SegmentationCascade.Input(
            positions = ladderPositions(100),
            perTrianglePaintState = ByteArray(100),
            volumes = emptyList(),
            objects = listOf(
                SegmentationCascade.ObjectInfo(1L, "A", null, intArrayOf(0, 1, 2, 3, 4)),
                SegmentationCascade.ObjectInfo(2L, "B", null, IntArray(95) { it + 5 }),
            ),
            perTriangleIndex = ByteArray(100),
        )
        assertEquals(SegmentationSource.OBJECT, SegmentationCascade.run(input).source)
    }

    @Test
    fun `run falls all the way to Z-bands when no branch fires`() {
        val input = SegmentationCascade.Input(
            positions = ladderPositions(120),
            perTrianglePaintState = ByteArray(120),
            volumes = emptyList(),
            objects = emptyList(),
            perTriangleIndex = ByteArray(120),
        )
        val r = SegmentationCascade.run(input)
        assertTrue(
            "expected TOPOLOGY*, or Z_BAND: got ${r.source}",
            r.source == SegmentationSource.TOPOLOGY ||
            r.source == SegmentationSource.TOPOLOGY_RECURSIVE ||
            r.source == SegmentationSource.Z_BAND,
        )
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

    // fix44 unit-test additions covering the fix40.x height-banding work in topologyBranch.

    /** Helper — generate a connected strip of triCount triangles (must be even) as one
     *  flood-fill component. Each row contributes a quad of two triangles sharing an edge;
     *  consecutive rows share an edge too. All triangles are at z = [z], lie in the XY
     *  plane (normal +Z), so the dihedral flood-fill at CREASE_DOT keeps them in one
     *  component. */
    private fun cluster(centerX: Float, z: Float, triCount: Int): FloatArray {
        require(triCount % 2 == 0) { "triCount must be even (2 triangles per quad row)" }
        val out = FloatArray(triCount * 9)
        val rows = triCount / 2
        for (r in 0 until rows) {
            val y0 = r.toFloat()
            val y1 = (r + 1).toFloat()
            // up triangle at row r: (cx, y0), (cx+1, y0), (cx, y1)
            val bUp = (r * 2) * 9
            out[bUp + 0] = centerX;     out[bUp + 1] = y0; out[bUp + 2] = z
            out[bUp + 3] = centerX + 1f; out[bUp + 4] = y0; out[bUp + 5] = z
            out[bUp + 6] = centerX;     out[bUp + 7] = y1; out[bUp + 8] = z
            // down triangle at row r: (cx+1, y0), (cx+1, y1), (cx, y1)
            // — shares edge (cx+1,y0)→(cx,y1) with the up triangle above
            // — shares edge (cx,y1)→(cx+1,y1) with row r+1's up triangle
            val bDn = (r * 2 + 1) * 9
            out[bDn + 0] = centerX + 1f; out[bDn + 1] = y0; out[bDn + 2] = z
            out[bDn + 3] = centerX + 1f; out[bDn + 4] = y1; out[bDn + 5] = z
            out[bDn + 6] = centerX;     out[bDn + 7] = y1; out[bDn + 8] = z
        }
        return out
    }

    private fun concatenate(vararg arrays: FloatArray): FloatArray {
        val total = arrays.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (a in arrays) { a.copyInto(out, offset); offset += a.size }
        return out
    }

    @Test
    fun `topology branch height-bands components by centroid Z when Z-span is meaningful`() {
        // 4 disjoint clusters at different Z heights: 0, 20, 40, 60. Should land in
        // separate bands. The tree's top-level children are band parents (id < 0); their
        // children are the component leaves.
        val pos = concatenate(
            cluster(0f,   0f, 30),
            cluster(100f, 20f, 30),
            cluster(200f, 40f, 30),
            cluster(300f, 60f, 30),
        )
        val r = SegmentationCascade.topologyBranch(pos)
        val bandParents = r.tree.firstOrNull()?.children ?: emptyList()
        assertEquals("4 components at distinct heights → 4 bands", 4, bandParents.size)
        bandParents.forEach { parent ->
            assertTrue("band parent ids are negative (clear of leaf ids)",
                parent.region.id < 0)
            assertTrue("each band parent has at least one component child",
                parent.children.isNotEmpty())
        }
    }

    @Test
    fun `topology branch falls back to per-component when Z-span is degenerate`() {
        // 3 coplanar clusters (all at z=0). Height-banding can't split them; falls back to
        // per-component leaves directly under the model root (no band-parent layer).
        val pos = concatenate(
            cluster(0f,   0f, 30),
            cluster(100f, 0f, 30),
            cluster(200f, 0f, 30),
        )
        val r = SegmentationCascade.topologyBranch(pos)
        val children = r.tree.firstOrNull()?.children ?: emptyList()
        assertEquals("3 coplanar components → 3 leaves at root", 3, children.size)
        children.forEach { child ->
            assertTrue("perComponent fallback emits leaves directly (no band parents)",
                child.children.isEmpty())
            assertTrue("leaf ids are non-negative component indices",
                child.region.id >= 0)
        }
    }

    @Test
    fun `topology branch groups bilaterally symmetric components into the same band`() {
        // Two components at the same Z but mirrored across X — the goat-symmetry case.
        // Both must land in the same band so they share a slot/colour by default.
        val pos = concatenate(
            cluster(-200f, 40f, 30),   // "left horn"
            cluster( 200f, 40f, 30),   // "right horn"
            cluster(0f,    0f, 30),    // body — different Z so a separate band exists
        )
        val r = SegmentationCascade.topologyBranch(pos)
        val bandParents = r.tree.firstOrNull()?.children ?: emptyList()
        // Find a band containing 2 children — that's the horn pair.
        val pairBand = bandParents.firstOrNull { it.children.size == 2 }
        assertNotNull("expected one band with both horn components", pairBand)
        assertEquals("both horns share the band's slot",
            pairBand!!.children[0].region.slot,
            pairBand.children[1].region.slot)
    }

    @Test
    fun `topology branch keeps band parent ids unique and negative`() {
        // 5 clusters at distinct heights → 5 bands. Each parent id must be unique so
        // findNodeById on tap-to-highlight doesn't ambiguously resolve.
        val pos = concatenate(
            cluster(0f,    0f, 30),
            cluster(100f,  15f, 30),
            cluster(200f,  30f, 30),
            cluster(300f,  45f, 30),
            cluster(400f,  60f, 30),
        )
        val r = SegmentationCascade.topologyBranch(pos)
        val parentIds = (r.tree.firstOrNull()?.children ?: emptyList()).map { it.region.id }
        assertEquals("all parent ids must be distinct", parentIds.size, parentIds.toSet().size)
        assertTrue("all parent ids must be negative (clear of leaf ids)",
            parentIds.all { it < 0 })
    }
}
