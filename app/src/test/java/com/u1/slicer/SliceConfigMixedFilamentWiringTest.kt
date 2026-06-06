package com.u1.slicer

import com.u1.slicer.data.MixedFilamentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceConfigMixedFilamentWiringTest {

    @Test
    fun `serialize from empty manager produces empty string`() {
        val mgr = com.u1.slicer.data.MixedFilamentManager(
            loadProject = { emptyList() }, loadLibrary = { emptyList() },
            saveProject = {}, saveLibrary = {},
        )
        assertEquals("", mgr.serialize(numPhysicalFilaments = 4))
    }

    @Test
    fun `manager rows are reflected in SliceConfig mixedFilamentDefinitions when applied`() {
        val mgr = com.u1.slicer.data.MixedFilamentManager(
            loadProject = { emptyList() }, loadLibrary = { emptyList() },
            saveProject = {}, saveLibrary = {},
        )
        mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        // Simulate what SlicerViewModel does just before slice():
        val cfg = com.u1.slicer.data.SliceConfig(
            extruderCount = 2,
            mixedFilamentDefinitions = mgr.serialize(numPhysicalFilaments = 2),
        )
        assertTrue(cfg.mixedFilamentDefinitions.startsWith("1,2,1,1,50,0,g,w,m0,"))
    }
}
