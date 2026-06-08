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
        // Simulate what SlicerViewModel does just before slice(): C-1 part B — serialize
        // is called with the fixed PHYSICAL base (TARGET_SLOTS = 4), NOT extruderCount, so
        // a mix's virtual id lines up with the painted byte (4 + k → engine state 5 + k).
        val cfg = com.u1.slicer.data.SliceConfig(
            extruderCount = 2,
            mixedFilamentDefinitions = mgr.serialize(
                numPhysicalFilaments = com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS
            ),
        )
        // M4: serializeRow now emits g<ids>,w<weights> gradient tokens (N-way path).
        // For components=[1,2] weights=[50,50]: encodeIds -> "12", encodeWeights -> "50/50".
        assertTrue(cfg.mixedFilamentDefinitions.startsWith("1,2,1,1,50,0,g12,w50/50,m0,"))
    }
}
