package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixTopSurfaceSettingsTest {

    private fun mgr() = MixedFilamentManager(
        loadProject = { emptyList() }, loadLibrary = { emptyList() },
        saveProject = {}, saveLibrary = {},
    )

    @Test
    fun defaults_serializeAsZeroTokens() {
        val m = mgr()
        m.addN(listOf(1, 2), listOf(50, 50), MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val row = m.serialize(numPhysicalFilaments = 4)
        assertTrue("default row must carry t0,f0,i0: $row", row.contains(",t0,f0,i0,"))
    }

    @Test
    fun topMixSettings_roundTripThroughSerialize() {
        val m = mgr()
        m.addN(listOf(1, 2), listOf(70, 30), MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val id = m.projectMixes.value.first().id
        m.updateTopSurfaceSettings(
            id,
            topMixMode = MixedFilamentRow.TopMixMode.DITHER,
            fineTopLines = true,
            ironingGlaze = true,
        )
        val row = m.serialize(numPhysicalFilaments = 4)
        assertTrue("dither+fine+glaze tokens expected: $row", row.contains(",t2,f1,i1,"))
        val updated = m.projectMixes.value.first()
        assertEquals(MixedFilamentRow.TopMixMode.DITHER, updated.topMixMode)
        assertTrue(updated.fineTopLines)
        assertTrue(updated.ironingGlaze)
    }

    @Test
    fun proportionalMode_serializesT1() {
        val m = mgr()
        m.addN(listOf(1, 2), listOf(50, 50), MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        m.updateTopSurfaceSettings(
            m.projectMixes.value.first().id,
            topMixMode = MixedFilamentRow.TopMixMode.PROPORTIONAL,
            fineTopLines = false, ironingGlaze = false,
        )
        assertTrue(m.serialize(4).contains(",t1,f0,i0,"))
    }
}
