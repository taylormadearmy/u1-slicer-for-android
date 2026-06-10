package com.u1.slicer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B141 (2026-06-10): assigning a part to a mix on a canonical 3MF didn't update
 * the Prepare preview — the canonical non-MMU recolor palette was ordered by the
 * file's static `usedExtruderIndices`, while the native mesh compacts over the
 * LIVE per-volume extruder set (captured in `_meshSourceExtruders0Based`, which
 * is refreshed on every assignment and includes mix ids). The palette must
 * prefer the live set whenever one exists.
 *
 * Structural guard (the combine flow has no JVM harness; the colour math itself
 * is behaviourally pinned by BuildCanonicalNonMmuPaletteTest).
 */
class MeshPaletteLiveSourceTest {

    @Test
    fun `canonical palette prefers live mesh source extruders over file metadata`() {
        val src = File("src/main/java/com/u1/slicer/SlicerViewModel.kt").readText()
        val canonicalBranch = src.substringAfter("Non-MMU path — derive the source-extruder compaction order")
            .substringBefore("buildCanonicalNonMmuPalette(")
        assertTrue(
            "live meshSourceExtruders must be consulted before usedExtruderIndices",
            canonicalBranch.contains("meshSourceExtruders.takeIf { it.isNotEmpty() }")
        )
        assertTrue(
            "file-declared set must remain the pre-assignment fallback",
            canonicalBranch.contains("usedExtruderIndices")
        )
    }
}
