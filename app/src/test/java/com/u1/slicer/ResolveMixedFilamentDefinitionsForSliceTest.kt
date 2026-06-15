package com.u1.slicer

import com.u1.slicer.data.MixedFilamentDefinitionSource
import com.u1.slicer.data.MixedFilamentManager
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.MixedFilamentSliceSummary
import com.u1.slicer.data.resolveMixedFilamentDefinitionsForSliceDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveMixedFilamentDefinitionsForSliceTest {

    @Test
    fun `embedded file recipe wins over manager state`() {
        val mgr = MixedFilamentManager(
            loadProject = {
                listOf(
                    MixedFilamentRow.fromLegacy(
                        id = 1L,
                        componentA = 1,
                        componentB = 2,
                        mixBPercent = 50,
                        distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                        label = "E1+E2",
                        inLibrary = false,
                    )
                )
            },
            loadLibrary = { emptyList() },
            saveProject = {},
            saveLibrary = {},
        )
        val embedded = "1,2,1,1,50,0,g12,w50/50,m0,z0,xa0,xb0,d0,o0,t0,f0,i0,u123"

        val resolved = resolveMixedFilamentDefinitionsForSliceDetails(
            sourceConfig = mapOf("mixed_filament_definitions" to embedded),
            mixedFilamentManager = mgr,
            numPhysicalFilaments = 4,
        )

        assertEquals(MixedFilamentDefinitionSource.FILE_EMBEDDED, resolved.source)
        assertEquals(embedded, resolved.recipe)
        assertTrue(resolved.rows.isNotEmpty())
        assertEquals(listOf(1, 2), resolved.rows.single().componentIds)
        assertEquals(listOf(50, 50), resolved.rows.single().weights)
    }

    @Test
    fun `manager state is used when file has no embedded recipe`() {
        val mgr = MixedFilamentManager(
            loadProject = {
                listOf(
                    MixedFilamentRow.fromLegacy(
                        id = 7L,
                        componentA = 1,
                        componentB = 3,
                        mixBPercent = 25,
                        distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
                        label = "E1+E3",
                        inLibrary = false,
                    )
                )
            },
            loadLibrary = { emptyList() },
            saveProject = {},
            saveLibrary = {},
        )

        val resolved = resolveMixedFilamentDefinitionsForSliceDetails(
            sourceConfig = mapOf("some_other_key" to "value"),
            mixedFilamentManager = mgr,
            numPhysicalFilaments = 4,
        )

        assertEquals(MixedFilamentDefinitionSource.MANAGER_STATE, resolved.source)
        assertEquals("1,3,1,1,25,0,g13,w75/25,m1,z0,xa0,xb0,d0,o0,t0,f0,i0,u7", resolved.recipe)
    }

    @Test
    fun `parser tolerates empty gradient tokens in imported rows`() {
        val embedded = "1,2,0,0,50,0,g,w,m2,d1,o1,u1;1,3,1,1,25,0,g13,w75/25,m1,z0,xa0,xb0,d0,o0,t2,f1,i1,u2"

        val summary = com.u1.slicer.data.parseMixedFilamentRecipe(embedded)

        assertEquals(MixedFilamentDefinitionSource.FILE_EMBEDDED, summary.source)
        assertEquals(2, summary.activeMixCount)
        assertEquals(listOf(1, 2), summary.rows[0].componentIds)
        assertEquals(listOf(50, 50), summary.rows[0].toEditableRow()?.weights)
        assertFalse(summary.hasParseWarnings)
        assertEquals(listOf(1, 3), summary.rows[1].componentIds)
    }

    @Test
    fun `editable rows skip invalid data cleanly`() {
        val empty = MixedFilamentSliceSummary.empty()
        assertEquals(0, empty.activeMixCount)
        assertTrue(empty.editableRows.isEmpty())
    }
}
