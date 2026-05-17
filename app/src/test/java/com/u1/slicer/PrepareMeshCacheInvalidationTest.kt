package com.u1.slicer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B109 v2.2.6 review contract: `SlicerViewModel.invalidatePrepareMeshCache()`
 * must clear the rotated-mesh-AABB StateFlow (`_rotatedMeshSizeXY`) alongside
 * the cached mesh + path. If a future change drops that clear, stale rotated
 * bounds from the previous rotation will silently leak into the auto-center
 * and bed-warning math whenever `setModelRotation` invalidates the cache.
 *
 * Test seam: structural grep on the function body. The contract can't be
 * exercised at the JVM unit level without instantiating `SlicerViewModel`
 * (which depends on `Application` + Room + DataStore), so this guards the
 * minimum invariant a future refactor must preserve.
 */
class PrepareMeshCacheInvalidationTest {

    private fun slicerViewModelSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/SlicerViewModel.kt"),
            File("../app/src/main/java/com/u1/slicer/SlicerViewModel.kt"),
            File("src/main/java/com/u1/slicer/SlicerViewModel.kt"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("SlicerViewModel.kt not found from ${File(".").absolutePath}")
        return f.readText()
    }

    private fun invalidateBody(): String {
        val src = slicerViewModelSource()
        val header = "fun invalidatePrepareMeshCache()"
        val start = src.indexOf(header)
        require(start >= 0) { "invalidatePrepareMeshCache function not found in SlicerViewModel.kt" }
        val open = src.indexOf('{', start)
        require(open >= 0) { "function body opening brace not found" }
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        error("invalidatePrepareMeshCache body not terminated")
    }

    @Test
    fun invalidate_clearsCachedMesh() {
        val body = invalidateBody()
        assertTrue(
            "invalidatePrepareMeshCache must clear `cachedPrepareMesh`. Body: $body",
            body.contains("cachedPrepareMesh = null")
        )
    }

    @Test
    fun invalidate_clearsCachedMeshPath() {
        val body = invalidateBody()
        assertTrue(
            "invalidatePrepareMeshCache must clear `cachedPrepareMeshPath`. Body: $body",
            body.contains("cachedPrepareMeshPath = null")
        )
    }

    @Test
    fun invalidate_clearsRotatedMeshSize() {
        // B109 v2.2.6: the rotated-mesh-AABB cache feeds getPlacementPositions and
        // setCopyCount via CopyArrangeCalculator.effectivePlacementFootprint. If
        // it isn't cleared here, a rotation that invalidates the cached mesh
        // will leave the OLD rotation's mesh bounds in place — stale bounds
        // then drive the auto-center and bed-warning until the new mesh fetch
        // repopulates. That race is exactly the issue this fix closes.
        val body = invalidateBody()
        assertTrue(
            "B109 v2.2.6: invalidatePrepareMeshCache must clear " +
                "`_rotatedMeshSizeXY.value = null` to prevent stale rotated " +
                "bounds from leaking across a rotation change. Body: $body",
            body.contains("_rotatedMeshSizeXY.value = null")
        )
    }
}
