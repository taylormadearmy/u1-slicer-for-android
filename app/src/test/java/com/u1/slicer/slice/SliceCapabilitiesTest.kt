package com.u1.slicer.slice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceCapabilitiesTest {

    @Test
    fun `snapmaker target keeps stable feature set`() {
        val caps = capabilityProfileFor(SlicerTarget.SnapmakerU1)

        assertFalse(caps.beta)
        assertTrue(caps.supportsUpload)
        assertTrue(caps.supportsStart)
        assertTrue(caps.supportsImportedProcessProfiles)
        assertTrue(caps.supportsColorMix)
        assertTrue(caps.supportsTopSurfaceMixModes)
    }

    @Test
    fun `a1 mini target is beta and limited to proven feature set`() {
        val caps = capabilityProfileFor(SlicerTarget.BambuA1Mini)

        assertTrue(caps.beta)
        assertTrue(caps.supportsUpload)
        assertTrue(caps.supportsStart)
        assertTrue(caps.supportsAmsMapping)
        assertFalse(caps.supportsImportedProcessProfiles)
        assertFalse(caps.supportsColorMix)
        assertFalse(caps.supportsTopSurfaceMixModes)
    }
}
