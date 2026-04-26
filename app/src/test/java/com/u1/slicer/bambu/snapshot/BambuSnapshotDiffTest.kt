package com.u1.slicer.bambu.snapshot

import org.junit.Test
import org.junit.Assert.*

class BambuSnapshotDiffTest {

    private fun blank() = BambuFileSnapshot("x", true, "", emptyList(), emptyList(), emptyList())

    @Test
    fun `identical snapshots produce no disagreements`() {
        val s = blank().copy(objects = listOf(ObjectSnapshot(1, "a", 1, "")))
        val diffs = BambuSnapshotDiff.diff(s, s)
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun `header field mismatch is reported with path`() {
        val k = blank().copy(isBbl = true)
        val n = blank().copy(isBbl = false)
        val diffs = BambuSnapshotDiff.diff(k, n)
        assertEquals(1, diffs.size)
        assertEquals("isBbl", diffs[0].path)
        assertEquals("true", diffs[0].kotlinValue)
        assertEquals("false", diffs[0].nativeValue)
    }

    @Test
    fun `plate count mismatch is reported once at top level`() {
        val k = blank().copy(plates = listOf(PlateSnapshot(1, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())))
        val n = blank()
        val diffs = BambuSnapshotDiff.diff(k, n)
        assertEquals("plates.size", diffs.single().path)
    }

    @Test
    fun `per-plate filament colour mismatch reports path with index`() {
        val plate = { colours: List<String> ->
            PlateSnapshot(1, colours, listOf("A", "B"), emptyList(), emptyList(), emptyMap())
        }
        val k = blank().copy(plates = listOf(plate(listOf("#FF0000", "#00FF00"))))
        val n = blank().copy(plates = listOf(plate(listOf("#FF0000", "#0000FF"))))
        val diffs = BambuSnapshotDiff.diff(k, n)
        assertEquals(1, diffs.size)
        assertEquals("plates[0].filamentColours[1]", diffs[0].path)
    }

    @Test
    fun `per-volume paint state count mismatch reports path with state key`() {
        val vol = { counts: Map<Int, Int> ->
            VolumeSnapshot(5, 0, null, counts, emptyMap(), true, false)
        }
        val k = blank().copy(volumes = listOf(vol(mapOf(1 to 100, 2 to 50))))
        val n = blank().copy(volumes = listOf(vol(mapOf(1 to 100, 2 to 60))))
        val diffs = BambuSnapshotDiff.diff(k, n)
        assertEquals("volumes[0].paintStateSet[2]", diffs.single().path)
    }

    @Test
    fun `volumes size mismatch is reported once at top level`() {
        // The common case: Kotlin returns empty, native populates. Previously this
        // emitted volumes[0]..volumes[N-1] (one per native volume) which created
        // hundreds of brittle baseline entries. Now it should collapse to one.
        val nVolumes = (0 until 50).map { i ->
            VolumeSnapshot(objectId = i, volumeIndex = 0, extruder = null,
                paintStateSet = emptyMap(), paintSupportsStateSet = emptyMap(),
                isMmPainted = false, isSeamPainted = false)
        }
        val k = blank()
        val n = blank().copy(volumes = nVolumes)
        val diffs = BambuSnapshotDiff.diff(k, n)
        assertEquals(1, diffs.size)
        assertEquals("volumes.size", diffs[0].path)
        assertEquals("0", diffs[0].kotlinValue)
        assertEquals("50", diffs[0].nativeValue)
    }
}
