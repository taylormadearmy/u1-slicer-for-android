package com.u1.slicer.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the ray-AABB intersection used by [ModelViewerView.pickTriangle]
 * as a fallback when [MeshData.toWorldSpacePickingPositions] returned an empty
 * array (oversize meshes that would OOM the per-triangle allocator — see
 * Chubby_Darth_Vader_MULTI_COLOR.3mf regression, 2026-05-31).
 *
 * Pre-fix: tap on Chubby fired onEmptyTap (deselect) because the per-triangle
 * picker returned -1 on an empty positions array.
 * Post-fix: ray-AABB returns true if the tap-ray hits the mesh bbox →
 * pickTriangle returns 0 → onTriangleTapped fires → selectObject(0) executes.
 *
 * The slab-method implementation is in [ModelViewerView.rayHitsAABBStatic].
 */
class ModelViewerRayAABBTest {

    /** Ray pointing from (0,0,10) toward -Z through the origin. */
    private fun rayThroughOrigin(): FloatArray =
        floatArrayOf(0f, 0f, 10f, 0f, 0f, -1f)

    /** Ray pointing from (50,50,10) toward -Z, far from origin. */
    private fun rayBesideOrigin(): FloatArray =
        floatArrayOf(50f, 50f, 10f, 0f, 0f, -1f)

    @Test
    fun ray_through_unit_aabb_hits() {
        val hit = ModelViewerView.rayHitsAABBStatic(
            rayThroughOrigin(),
            minX = -1f, maxX = 1f,
            minY = -1f, maxY = 1f,
            minZ = -1f, maxZ = 1f,
        )
        assertTrue("Ray from (0,0,10) toward -Z passes through unit AABB centred on origin", hit)
    }

    @Test
    fun ray_beside_aabb_misses() {
        val hit = ModelViewerView.rayHitsAABBStatic(
            rayBesideOrigin(),
            minX = -1f, maxX = 1f,
            minY = -1f, maxY = 1f,
            minZ = -1f, maxZ = 1f,
        )
        assertFalse("Ray from (50,50,10) toward -Z misses unit AABB centred on origin", hit)
    }

    @Test
    fun ray_pointing_away_from_aabb_misses() {
        // Ray starts above the AABB and points further away from it (+Z).
        val ray = floatArrayOf(0f, 0f, 10f, 0f, 0f, 1f)
        val hit = ModelViewerView.rayHitsAABBStatic(
            ray,
            minX = -1f, maxX = 1f,
            minY = -1f, maxY = 1f,
            minZ = -1f, maxZ = 1f,
        )
        assertFalse(
            "Ray pointing AWAY from the AABB (no front-facing intersection) must miss; " +
                "without the `tMax >= max(0, tMin)` guard, an infinite line would falsely hit.",
            hit,
        )
    }

    @Test
    fun ray_inside_aabb_hits() {
        // Origin inside the AABB, ray any direction — should report a hit.
        val ray = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f)
        val hit = ModelViewerView.rayHitsAABBStatic(
            ray,
            minX = -1f, maxX = 1f,
            minY = -1f, maxY = 1f,
            minZ = -1f, maxZ = 1f,
        )
        assertTrue("Ray origin inside the AABB must report hit", hit)
    }

    @Test
    fun ray_grazes_corner_hits() {
        // Aim a ray squarely at the AABB's max corner; standard slab method must
        // accept boundary contact.
        val ray = floatArrayOf(0f, 0f, 10f, 0.1f, 0.1f, -1f)
        val hit = ModelViewerView.rayHitsAABBStatic(
            ray,
            minX = -1f, maxX = 1f,
            minY = -1f, maxY = 1f,
            minZ = -1f, maxZ = 1f,
        )
        assertTrue("Ray angled toward AABB corner must hit", hit)
    }

    @Test
    fun chubby_sized_aabb_at_bed_centre_with_screen_centre_ray_hits() {
        // Simulates the Chubby scenario: a tall figure (60×40×144 mm) placed at
        // bed centre (135, 135), tap at screen centre projects roughly to a
        // ray through the figure's centroid.
        //
        // Approximate world-space AABB after the toWorldSpacePickingPositions
        // single-mesh transform with instancePositions = [135-30, 135-20]:
        //   minX = 105, maxX = 165
        //   minY = 115, maxY = 155
        //   minZ = 0,   maxZ = 144
        // Camera looks down at the bed centre, so the unprojected tap ray
        // approximates origin (135, 135, 500) toward (0, 0, -1).
        val ray = floatArrayOf(135f, 135f, 500f, 0f, 0f, -1f)
        val hit = ModelViewerView.rayHitsAABBStatic(
            ray,
            minX = 105f, maxX = 165f,
            minY = 115f, maxY = 155f,
            minZ = 0f, maxZ = 144f,
        )
        assertTrue(
            "Tap-on-Vader (bed-centre screen tap unprojected to vertical ray) must hit " +
                "the figure's bbox so pickTriangle returns 0 → selectObject(0) fires.",
            hit,
        )
    }

    @Test
    fun chubby_sized_aabb_with_off_bed_ray_misses() {
        // Same Chubby AABB, but the tap is on the far side of the bed (240, 240)
        // away from the figure → ray vertical at that XY position should miss.
        val ray = floatArrayOf(240f, 240f, 500f, 0f, 0f, -1f)
        val hit = ModelViewerView.rayHitsAABBStatic(
            ray,
            minX = 105f, maxX = 165f,
            minY = 115f, maxY = 155f,
            minZ = 0f, maxZ = 144f,
        )
        assertFalse(
            "Tap on empty bed (far from Vader's bbox) must miss so pickTriangle " +
                "returns -1 → onEmptyTap fires → object deselects.",
            hit,
        )
    }
}
