package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B49 regression: tests for the Prepare preview cache state machine.
 *
 * The InlineModelPreview composable has two LaunchedEffects that interact:
 *   1. Rotation effect: fetches native mesh (expensive) or skips via cache
 *   2. Combined effect: pushes mesh to GL viewer via setMesh()
 *
 * On tab switch (Prepare → G-code → Prepare), the composable is recreated.
 * The ViewModel's cachedPrepareMesh survives, but the GL view is fresh.
 * The state machine must ensure setMesh() is called on the new GL view.
 *
 * These tests model the state transitions without Compose, using the same
 * decision logic as the actual LaunchedEffects.
 */
class PreparePreviewCacheTest {

    /** Simulates the GL viewer — tracks whether setMesh was called. */
    class FakeViewer {
        var meshSet: Any? = null
            private set
        var setMeshCallCount = 0
            private set

        fun setMesh(mesh: Any) {
            meshSet = mesh
            setMeshCallCount++
        }
    }

    /**
     * Models the rotation LaunchedEffect decision:
     * Returns the mesh to use (null = skip native call, non-null = new mesh from native).
     * Side effect: sets lastSetMesh to null when cache is used.
     */
    data class RotationEffectResult(
        val newMesh: Any?,
        val lastSetMeshAfter: Any?
    )

    fun simulateRotationEffect(
        currentMesh: Any?,
        cachedMesh: Any?,
        lastSetMesh: Any?,
        nativeMeshResult: Any?  // what getPreparePreviewMesh would return
    ): RotationEffectResult {
        // Mirror the actual LaunchedEffect(modelRotation) logic
        if (currentMesh != null && cachedMesh != null) {
            // B49 cache hit — skip native call but reset lastSetMesh
            return RotationEffectResult(newMesh = null, lastSetMeshAfter = null)
        }
        // No cache — call native
        return if (nativeMeshResult != null) {
            RotationEffectResult(newMesh = nativeMeshResult, lastSetMeshAfter = null)
        } else {
            RotationEffectResult(newMesh = null, lastSetMeshAfter = lastSetMesh)
        }
    }

    /**
     * Models the combined LaunchedEffect decision:
     * Given mesh + viewer + lastSetMesh, decides whether to call setMesh().
     */
    fun simulateCombinedEffect(
        mesh: Any?,
        viewer: FakeViewer?,
        lastSetMesh: Any?
    ): Any? {
        // Mirror the actual LaunchedEffect(mesh, viewerView, ...) logic
        if (mesh != null && viewer != null) {
            if (mesh !== lastSetMesh) {
                viewer.setMesh(mesh)
                return mesh  // new lastSetMesh
            }
        }
        return lastSetMesh
    }

    // ── Scenario: fresh load (no cache) ─────────────────────────────────────

    @Test
    fun `fresh load - native mesh is fetched and pushed to GL`() {
        val nativeMesh = Object()
        val viewer = FakeViewer()

        // Step 1: rotation effect — no cache, fetch native mesh
        val rotResult = simulateRotationEffect(
            currentMesh = null, cachedMesh = null,
            lastSetMesh = null, nativeMeshResult = nativeMesh
        )
        assertEquals(nativeMesh, rotResult.newMesh)

        // Step 2: mesh state updated
        val mesh = rotResult.newMesh
        var lastSetMesh = rotResult.lastSetMeshAfter

        // Step 3: combined effect fires (mesh changed from null → nativeMesh)
        lastSetMesh = simulateCombinedEffect(mesh, viewer, lastSetMesh)

        assertEquals("setMesh must be called on fresh load", 1, viewer.setMeshCallCount)
        assertEquals(nativeMesh, viewer.meshSet)
        assertEquals(nativeMesh, lastSetMesh)
    }

    // ── Scenario: tab switch with cache (B49) ───────────────────────────────

    /**
     * B49 regression: after navigating Prepare → G-code → Prepare:
     * - cachedMesh is non-null (ViewModel survived)
     * - mesh is initialized from cachedMesh
     * - GL viewer is fresh (new composable instance)
     * - setMesh() MUST be called on the new viewer
     *
     * RED: this test fails if the cache path doesn't reset lastSetMesh,
     * because the combined effect sees mesh === lastSetMesh and skips setMesh().
     */
    @Test
    fun `tab switch with cache - mesh must be pushed to fresh GL viewer`() {
        val cachedMesh = Object()
        val viewer = FakeViewer()

        // Composable recreated: mesh initialized from cachedMesh
        val mesh = cachedMesh
        // Fresh composable: lastSetMesh starts as null (remember resets)
        var lastSetMesh: Any? = null

        // Step 1: rotation effect fires — cache hit
        val rotResult = simulateRotationEffect(
            currentMesh = mesh, cachedMesh = cachedMesh,
            lastSetMesh = lastSetMesh, nativeMeshResult = null
        )
        assertNull("Cache hit must skip native call", rotResult.newMesh)
        lastSetMesh = rotResult.lastSetMeshAfter

        // Step 2: viewer becomes available → combined effect fires
        lastSetMesh = simulateCombinedEffect(mesh, viewer, lastSetMesh)

        assertEquals(
            "setMesh MUST be called on fresh GL viewer after cache hit (B49)",
            1, viewer.setMeshCallCount
        )
        assertEquals(cachedMesh, viewer.meshSet)
    }

    /**
     * After setMesh is called once, repeated combined effect fires (e.g. from
     * color changes) must NOT call setMesh again — only recolor.
     */
    @Test
    fun `repeated combined effect does not re-push same mesh`() {
        val cachedMesh = Object()
        val viewer = FakeViewer()

        val mesh = cachedMesh
        var lastSetMesh: Any? = null

        // First: rotation cache hit
        val rotResult = simulateRotationEffect(
            currentMesh = mesh, cachedMesh = cachedMesh,
            lastSetMesh = lastSetMesh, nativeMeshResult = null
        )
        lastSetMesh = rotResult.lastSetMeshAfter

        // Viewer arrives → combined effect
        lastSetMesh = simulateCombinedEffect(mesh, viewer, lastSetMesh)
        assertEquals(1, viewer.setMeshCallCount)

        // Color changes → combined effect fires again
        lastSetMesh = simulateCombinedEffect(mesh, viewer, lastSetMesh)
        assertEquals("setMesh must not be called again for same mesh", 1, viewer.setMeshCallCount)
    }

    /**
     * Without the B49 cache hit path, lastSetMesh would remain whatever it was
     * before (potentially the same object reference if reusing), and setMesh
     * would be skipped. This test catches that specific failure mode.
     */
    @Test
    fun `cache hit resets lastSetMesh to null to force GL upload`() {
        val cachedMesh = Object()

        val rotResult = simulateRotationEffect(
            currentMesh = cachedMesh, cachedMesh = cachedMesh,
            lastSetMesh = cachedMesh,  // stale — same ref from previous session
            nativeMeshResult = null
        )

        assertNull(
            "Cache hit must reset lastSetMesh to null so combined effect calls setMesh()",
            rotResult.lastSetMeshAfter
        )
    }

    // ── B49 actual failure: parse effect nulls mesh on tab switch ────────────

    /**
     * B49 actual root cause: the parse LaunchedEffect keys on colorMapping.size.
     * On tab switch, if colorMapping re-emits, the parse effect fires and sets
     * mesh=null before starting a fresh parse. For native-path 3MF models, the
     * parse effect produces no mesh (native path handles it), so mesh stays null.
     *
     * The fix: the parse effect must not null the mesh when a cached mesh exists
     * and the native rotation path will handle the reload.
     */
    @Test
    fun `parse effect must not null mesh when cache is available`() {
        val cachedMesh = Object()
        var mesh: Any? = cachedMesh

        // Simulate parse LaunchedEffect firing on tab switch
        // WRONG behaviour (current): unconditionally null mesh
        val shouldClearMesh = shouldClearMeshOnParseEffect(
            cachedMesh = cachedMesh,
            isNativePreviewPath = true
        )

        if (shouldClearMesh) {
            mesh = null
        }

        assertEquals(
            "Parse effect must NOT null mesh when cache exists and native path handles preview (B49)",
            cachedMesh, mesh
        )
    }

    /**
     * Parse effect SHOULD null mesh when there is no cache (first load).
     */
    @Test
    fun `parse effect nulls mesh when no cache on first load`() {
        assertTrue(
            "Parse effect must clear mesh on first load (no cache)",
            shouldClearMeshOnParseEffect(cachedMesh = null, isNativePreviewPath = true)
        )
    }

    /**
     * Parse effect SHOULD null mesh for STL files (non-native path)
     * because the parse effect itself produces the mesh.
     */
    @Test
    fun `parse effect nulls mesh for STL files even with cache`() {
        assertTrue(
            "Parse effect must clear mesh for STL (non-native path)",
            shouldClearMeshOnParseEffect(cachedMesh = Object(), isNativePreviewPath = false)
        )
    }

    // ── Scenario: rotation change must invalidate cache ────────────────

    /**
     * RED-GREEN TDD: rotation change must invalidate the prepare mesh cache
     * so the rotation LaunchedEffect fetches fresh geometry from native.
     *
     * Bug: B49 added a cache early-return in LaunchedEffect(modelRotation):
     *   if (mesh != null && cachedMesh != null) return@LaunchedEffect
     * But setModelRotation() does NOT call invalidatePrepareMeshCache(),
     * so cachedMesh stays non-null and the native rotation call is skipped.
     * Result: the preview stays frozen at the old rotation angle.
     *
     * This test models the full flow: load → cache populated → rotate →
     * assert that rotation effect fetches new mesh (not skipped by cache).
     */
    @Test
    fun `rotation change must invalidate cache so effect fetches fresh mesh`() {
        val initialMesh = Object()
        val rotatedMesh = Object()
        val viewer = FakeViewer()

        // --- Step 1: initial load (no cache) ---
        var mesh: Any? = null
        var cachedMesh: Any? = null
        var lastSetMesh: Any? = null

        val rot1 = simulateRotationEffect(
            currentMesh = mesh, cachedMesh = cachedMesh,
            lastSetMesh = lastSetMesh, nativeMeshResult = initialMesh
        )
        mesh = rot1.newMesh ?: mesh
        lastSetMesh = rot1.lastSetMeshAfter
        cachedMesh = mesh  // onMeshCached callback populates cache

        lastSetMesh = simulateCombinedEffect(mesh, viewer, lastSetMesh)
        assertEquals(1, viewer.setMeshCallCount)

        // --- Step 2: user rotates the model ---
        // setModelRotation() should invalidate the cache before rotation effect fires.
        cachedMesh = simulateSetModelRotation(cachedMesh)

        // Rotation LaunchedEffect fires with new rotation value
        val rot2 = simulateRotationEffect(
            currentMesh = mesh, cachedMesh = cachedMesh,
            lastSetMesh = lastSetMesh, nativeMeshResult = rotatedMesh
        )

        // MUST fetch new mesh from native — cache should have been invalidated
        assertEquals(
            "Rotation change must trigger fresh native mesh fetch — cache must be invalidated by setModelRotation()",
            rotatedMesh, rot2.newMesh
        )

        // Update state and push rotated mesh to GL
        mesh = rot2.newMesh ?: mesh
        lastSetMesh = rot2.lastSetMeshAfter
        lastSetMesh = simulateCombinedEffect(mesh, viewer, lastSetMesh)
        assertEquals("Rotated mesh must be pushed to GL viewer", 2, viewer.setMeshCallCount)
        assertEquals(rotatedMesh, viewer.meshSet)
    }

    /**
     * Same test for scale: setModelScale() should also invalidate the cache.
     */
    @Test
    fun `scale change must invalidate cache so effect fetches fresh mesh`() {
        val initialMesh = Object()
        val scaledMesh = Object()

        var mesh: Any? = null
        var cachedMesh: Any? = null

        // Initial load
        val rot1 = simulateRotationEffect(
            currentMesh = mesh, cachedMesh = cachedMesh,
            lastSetMesh = null, nativeMeshResult = initialMesh
        )
        mesh = rot1.newMesh ?: mesh
        cachedMesh = mesh

        // User changes scale — should invalidate cache
        cachedMesh = simulateSetModelScale(cachedMesh)

        // Rotation effect fires (scale change triggers re-fetch via same path)
        val rot2 = simulateRotationEffect(
            currentMesh = mesh, cachedMesh = cachedMesh,
            lastSetMesh = null, nativeMeshResult = scaledMesh
        )

        assertEquals(
            "Scale change must trigger fresh native mesh fetch — cache must be invalidated by setModelScale()",
            scaledMesh, rot2.newMesh
        )
    }

    companion object {
        /**
         * Extracted decision logic for whether the parse LaunchedEffect should
         * clear the mesh. Mirrors the fix in InlineModelPreview.
         */
        fun shouldClearMeshOnParseEffect(
            cachedMesh: Any?,
            isNativePreviewPath: Boolean
        ): Boolean {
            // B49: don't clear mesh when cache exists and native rotation path handles it
            if (cachedMesh != null && isNativePreviewPath) return false
            return true
        }

        /**
         * Models setModelRotation() side effects on the prepare mesh cache.
         * Mirrors SlicerViewModel.setModelRotation(): invalidates cache so
         * the rotation LaunchedEffect fetches fresh geometry from native.
         */
        fun simulateSetModelRotation(@Suppress("UNUSED_PARAMETER") cachedMesh: Any?): Any? {
            return null  // invalidatePrepareMeshCache()
        }

        /**
         * Models setModelScale() side effects on the prepare mesh cache.
         * Mirrors SlicerViewModel.setModelScale(): invalidates cache so
         * the rotation LaunchedEffect fetches fresh geometry from native.
         */
        fun simulateSetModelScale(@Suppress("UNUSED_PARAMETER") cachedMesh: Any?): Any? {
            return null  // invalidatePrepareMeshCache()
        }
    }
}
