package com.u1.slicer.aipaint

import com.u1.slicer.viewer.NativePreviewMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B113 guard: the Smart Paint subsample threshold must only fire above the native
 * decimator's cap (`MAX_DECIMATED_TRIANGLES`). Lowering it back to a 30k-style value
 * re-introduces the v2.2.0 benchy confetti regression — stride subsample destroys
 * mesh adjacency, topology flood-fill explodes to one-component-per-triangle.
 *
 * If you find yourself wanting to lower this threshold "just for one fixture",
 * stop and read [shouldSubsampleForAiPaint]'s docstring. Add a different gate
 * (e.g. only-when-paint-state-present) instead — never a smaller universal stride.
 */
class ShouldSubsampleForAiPaintTest {

    @Test fun nativeDecimatedMeshIsNeverSubsampled() {
        // A bare STL with the standard native cap (99996 for 3DBenchy) must NOT
        // get strided — that's the regression class this test protects against.
        assertFalse(shouldSubsampleForAiPaint(99_996))
    }

    @Test fun exactlyAtNativeCapIsNotSubsampled() {
        assertFalse(shouldSubsampleForAiPaint(NativePreviewMesh.MAX_DECIMATED_TRIANGLES))
    }

    @Test fun oneOverNativeCapIsSubsampled() {
        // First tri over the native cap means MMU path bypassed decimation.
        assertTrue(shouldSubsampleForAiPaint(NativePreviewMesh.MAX_DECIMATED_TRIANGLES + 1))
    }

    @Test fun largeMmuPaintedFixtureIsSubsampled() {
        // axolotl 880_184, colored_3DBenchy 603_000, korok 788_000 — all hit MMU
        // bypass and need the band-aid to bound topology *alternate* compute.
        assertTrue(shouldSubsampleForAiPaint(880_184))
        assertTrue(shouldSubsampleForAiPaint(603_000))
        assertTrue(shouldSubsampleForAiPaint(788_000))
    }

    @Test fun smallMeshIsNotSubsampled() {
        // Tiny mesh — no risk of cascade slowness, no need to strip adjacency.
        assertFalse(shouldSubsampleForAiPaint(0))
        assertFalse(shouldSubsampleForAiPaint(1))
        assertFalse(shouldSubsampleForAiPaint(1_000))
        assertFalse(shouldSubsampleForAiPaint(25_000))
    }

    @Test fun thresholdValueIsTheNativeCap() {
        // Sanity: the threshold is documented as MAX_DECIMATED_TRIANGLES, not a
        // separate constant that could drift. If someone introduces a new constant
        // here, this test will fail and force them to update the docstring too.
        assertEquals(100_000, NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
    }
}
