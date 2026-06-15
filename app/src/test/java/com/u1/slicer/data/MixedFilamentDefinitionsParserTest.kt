package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MixedFilamentDefinitionsParserTest {

    @Test
    fun `parseMixedFilamentDefinitions round-trips a serialized recipe`() {
        val manager = MixedFilamentManager(
            loadProject = {
                listOf(
                    MixedFilamentRow(
                        id = 9L,
                        components = listOf(1, 3, 4),
                        weights = listOf(20, 30, 50),
                        distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
                        label = "E1+E3+E4",
                        inLibrary = false,
                        topMixMode = MixedFilamentRow.TopMixMode.DITHER,
                        fineTopLines = true,
                        ironingGlaze = true,
                    )
                )
            },
            loadLibrary = { emptyList() },
            saveProject = {},
            saveLibrary = {},
        )

        val recipe = manager.serialize(numPhysicalFilaments = 4)
        val parsed = parseMixedFilamentDefinitions(recipe)

        assertEquals(1, parsed.size)
        assertEquals(listOf(1, 3, 4), parsed.single().components)
        assertEquals(listOf(20, 30, 50), parsed.single().weights)
        assertEquals(MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS, parsed.single().distributionMode)
        assertEquals(MixedFilamentRow.TopMixMode.DITHER, parsed.single().topMixMode)
        assertEquals(true, parsed.single().fineTopLines)
        assertEquals(true, parsed.single().ironingGlaze)
    }

    @Test
    fun `display summary follows manager state when editable copy is active`() {
        val manager = MixedFilamentManager(
            loadProject = {
                listOf(
                    MixedFilamentRow.fromLegacy(
                        id = 11L,
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
        val imported = parseMixedFilamentRecipe(
            "1,2,1,1,50,0,g12,w50/50,m0,z0,xa0,xb0,d0,o0,t0,f0,i0,u9"
        )

        val display = resolveImportedMixRecipeDisplaySummary(
            source = MixedFilamentDefinitionSource.MANAGER_STATE,
            importedRecipe = imported,
            mixedFilamentManager = manager,
            numPhysicalFilaments = 4,
        )

        assertNotNull(display)
        assertEquals(MixedFilamentDefinitionSource.MANAGER_STATE, display!!.source)
        assertEquals(listOf(1, 3), display.rows.single().componentIds)
    }
}
