package com.u1.slicer.aipaint

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.viewer.NativePreviewMesh
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F54 cascade end-to-end: load each fixture, build the SegmentationCascade.Input from the
 * native snapshot, run the cascade, assert which branch fired and a sane leaf count.
 *
 * Branch B / C tests are guarded on `mesh.volumeRanges != null` — those branches need
 * per-volume triangle ranges that today's native pipeline does not populate (see plan
 * Task 18 / spec §12). When the JSON gains a triangleCount field these will fire.
 */
@RunWith(AndroidJUnit4::class)
class SegmentationCascadeIntegrationTest {

    private fun copyAsset(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, name)
        ctx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun loadAndRunCascade(file: File, plateIdx: Int = -1): CascadeResult {
        val lib = NativeLibrary()
        assertTrue("loadModelForPlate failed for ${file.name}",
            lib.loadModelForPlate(file.absolutePath, plateIdx = plateIdx))
        val mesh = lib.getPreparePreviewMesh(maxTriangles = NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
        assertNotNull(mesh)
        val triCount = mesh!!.trianglePositions.size / 9
        val perTriPaint = mesh.extruderIndices.takeIf { it.size == triCount } ?: ByteArray(triCount)
        val ranges = mesh.volumeRanges ?: emptyList()
        return SegmentationCascade.run(
            SegmentationCascade.Input(
                positions = mesh.trianglePositions,
                perTrianglePaintState = perTriPaint,
                volumes = parseObjectVolumesForTest(
                    runCatching { lib.nativeGetAllVolumeExtruders() }.getOrNull(), ranges
                ),
                objects = parseObjectInfosForTest(
                    runCatching { lib.nativeGetObjectExtruderMap() }.getOrNull(), ranges
                ),
                perTriangleIndex = perTriPaint,
            )
        )
    }

    @Test
    fun coloredBenchy_paintStateBranch() {
        val r = loadAndRunCascade(copyAsset("colored_3DBenchy (1).3mf"))
        assertEquals(SegmentationSource.PAINT_STATE, r.source)
        val leafCount = r.tree.firstOrNull()?.leafCount() ?: 0
        assertTrue("expected ≥ 2 paint-state leaves, got $leafCount", leafCount >= 2)
    }

    @Test
    fun h2cBenchy_paintStateBranch_sevenStates() {
        val r = loadAndRunCascade(copyAsset("3DBenchy-H2C-Multi-Color.3mf"))
        assertEquals(SegmentationSource.PAINT_STATE, r.source)
        val leafCount = r.tree.firstOrNull()?.leafCount() ?: 0
        assertTrue("expected ≥ 7 H2C leaves (no folding), got $leafCount", leafCount >= 7)
    }

    @Test
    fun rawBenchyStl_topologyOrZBands() {
        val r = loadAndRunCascade(copyAsset("3DBenchy.stl"))
        assertTrue(
            "expected topology* or Z-band, got ${r.source}",
            r.source in listOf(
                SegmentationSource.TOPOLOGY,
                SegmentationSource.TOPOLOGY_RECURSIVE,
                SegmentationSource.Z_BAND,
            )
        )
    }

    // -- helpers — duplicate of the ViewModel parsers, kept here so the test isn't coupled to
    // the AiPaintViewModel construction lifecycle.

    private fun parseObjectVolumesForTest(json: String?, ranges: List<IntRange>): List<SegmentationCascade.ObjectVolumes> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SegmentationCascade.ObjectVolumes>()
        var volumeCursor = 0
        for (o in 0 until arr.length()) {
            val obj = arr.getJSONObject(o)
            val objId = obj.optLong("objectIndex", o.toLong())
            val name = obj.optString("objectName", "Object ${o + 1}").ifBlank { "Object ${o + 1}" }
            val volsArr = obj.optJSONArray("volumes") ?: continue
            val vols = mutableListOf<SegmentationCascade.VolumeInfo>()
            for (v in 0 until volsArr.length()) {
                val vobj = volsArr.getJSONObject(v)
                val ext = vobj.optInt("extruder", -1).takeIf { it > 0 }
                val range = ranges.getOrNull(volumeCursor) ?: continue
                vols += SegmentationCascade.VolumeInfo(
                    volumeIndex = v,
                    extruder = ext,
                    triangleIds = (range.first..range.last).toList().toIntArray(),
                )
                volumeCursor++
            }
            if (vols.isNotEmpty()) out += SegmentationCascade.ObjectVolumes(objId, name, vols)
        }
        return out
    }

    private fun parseObjectInfosForTest(json: String?, ranges: List<IntRange>): List<SegmentationCascade.ObjectInfo> {
        if (json.isNullOrBlank() || ranges.isEmpty()) return emptyList()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        var cursor = 0
        val out = mutableListOf<SegmentationCascade.ObjectInfo>()
        for (o in 0 until arr.length()) {
            val obj = arr.getJSONObject(o)
            val id = obj.optLong("objectId", o.toLong())
            val name = obj.optString("name", "Object ${o + 1}").ifBlank { "Object ${o + 1}" }
            val ext = obj.optInt("extruder", -1).takeIf { it > 0 }
            val volCount = obj.optInt("volumeCount", 1).coerceAtLeast(1)
            val objRanges = (0 until volCount).mapNotNull { ranges.getOrNull(cursor + it) }
            cursor += volCount
            if (objRanges.isEmpty()) continue
            out += SegmentationCascade.ObjectInfo(
                objectId = id, name = name, extruder = ext,
                triangleIds = objRanges.flatMap { (it.first..it.last).toList() }.toIntArray(),
            )
        }
        return out
    }
}
