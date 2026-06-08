package com.u1.slicer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedFilamentManagerTest {

    private fun newManager() = MixedFilamentManager(
        // In-memory backing for unit tests. Persistence is tested in Tasks 5+6.
        loadProject = { emptyList() },
        loadLibrary = { emptyList() },
        saveProject = { /* no-op */ },
        saveLibrary = { /* no-op */ },
    )

    private fun sampleRow(id: Long, a: Int = 1, b: Int = 2, pct: Int = 50, inLib: Boolean = false) =
        MixedFilamentRow.fromLegacy(
            id = id,
            componentA = a,
            componentB = b,
            mixBPercent = pct,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            label = MixedFilamentRow.autoLabel(listOf(a, b)),
            inLibrary = inLib,
        )

    @Test
    fun `add appends to project list and bumps counter`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(componentA = 1, componentB = 2, mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val r2 = mgr.add(componentA = 1, componentB = 3, mixBPercent = 33,
            distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS)
        val project = mgr.projectMixes.first()
        assertEquals(2, project.size)
        assertEquals(r1.id, project[0].id)
        assertEquals(r2.id, project[1].id)
        assertEquals("E1+E2", project[0].label)
        assertEquals("E1+E3", project[1].label)
    }

    @Test
    fun `edit replaces at same id and preserves order`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val r2 = mgr.add(1, 3, 33, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        mgr.edit(r1.id, componentA = 2, componentB = 4, mixBPercent = 75,
            distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS)
        val project = mgr.projectMixes.first()
        assertEquals(2, project.size)
        assertEquals(r1.id, project[0].id)   // same id
        assertEquals(2, project[0].componentA)
        assertEquals(4, project[0].componentB)
        assertEquals(75, project[0].mixBPercent)
        assertEquals(r2.id, project[1].id)   // r2 untouched
    }

    @Test
    fun `delete removes from project list`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val r2 = mgr.add(1, 3, 33, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        mgr.delete(r1.id)
        val project = mgr.projectMixes.first()
        assertEquals(1, project.size)
        assertEquals(r2.id, project[0].id)
    }

    @Test
    fun `promoteToLibrary copies project row into library and sets inLibrary`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        mgr.promoteToLibrary(r1.id)
        val project = mgr.projectMixes.first()
        val library = mgr.libraryMixes.first()
        assertEquals(1, project.size)
        assertEquals(1, library.size)
        assertTrue(project[0].inLibrary)
        assertTrue(library[0].inLibrary)
        assertEquals(project[0].id, library[0].id)
    }

    @Test
    fun `demoteFromLibrary removes the library copy`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        mgr.promoteToLibrary(r1.id)
        mgr.demoteFromLibrary(r1.id)
        val library = mgr.libraryMixes.first()
        assertEquals(0, library.size)
    }

    @Test
    fun `serialize emits empty string when no rows`() = runTest {
        val mgr = newManager()
        assertEquals("", mgr.serialize(numPhysicalFilaments = 4))
    }

    @Test
    fun `serialize emits engine row format for a single project row`() = runTest {
        val mgr = newManager()
        val r = mgr.add(componentA = 1, componentB = 2, mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val out = mgr.serialize(numPhysicalFilaments = 4)
        // Engine format (mirrors libslic3r/MixedFilament.cpp::serialize_custom_entries):
        //   <a>,<b>,<enabled>,<custom>,<mix_b_pct>,<pointillism>,g<ids>,w<weights>,m<dist>,z0,xa0,xb0,d0,o0,u<stable_id>
        assertEquals("1,2,1,1,50,0,g12,w50/50,m0,z0,xa0,xb0,d0,o0,u${r.id}", out)
    }

    @Test
    fun `serialize concatenates multiple rows with semicolon`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val r2 = mgr.add(2, 3, 33, MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS)
        val out = mgr.serialize(numPhysicalFilaments = 4)
        val expected =
            "1,2,1,1,50,0,g12,w50/50,m0,z0,xa0,xb0,d0,o0,u${r1.id}" +
            ";" +
            "2,3,1,1,33,0,g23,w67/33,m1,z0,xa0,xb0,d0,o0,u${r2.id}"
        assertEquals(expected, out)
    }

    @Test
    fun `serialize layer cycle uses m0 and same layer dots uses m1`() = runTest {
        val mgr = newManager()
        val rL = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val rD = mgr.add(1, 3, 50, MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS)
        val out = mgr.serialize(numPhysicalFilaments = 4).split(";")
        assertTrue(out[0].contains(",m0,"))
        assertFalse(out[0].contains(",m1,"))
        assertTrue(out[1].contains(",m1,"))
        assertFalse(out[1].contains(",m0,"))
    }

    @Test
    fun `serialize skips library rows whose components exceed numPhysicalFilaments`() = runTest {
        // Library rows referencing slots beyond the current physical count must be
        // hidden from the engine — they were created in a richer project and are
        // unusable here.
        val mgr = MixedFilamentManager(
            loadProject = { emptyList() },
            loadLibrary = {
                listOf(
                    MixedFilamentRow.fromLegacy(
                        id = 100L,
                        componentA = 1,
                        componentB = 4, // beyond a 2-extruder project
                        mixBPercent = 50,
                        distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                        label = "test",
                        inLibrary = true,
                    ),
                    MixedFilamentRow.fromLegacy(
                        id = 200L,
                        componentA = 1,
                        componentB = 2, // fits in a 2-extruder project
                        mixBPercent = 25,
                        distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                        label = "test",
                        inLibrary = true,
                    ),
                )
            },
            saveProject = {},
            saveLibrary = {},
        )
        val out = mgr.serialize(numPhysicalFilaments = 2)
        assertEquals("1,2,1,1,25,0,g12,w75/25,m0,z0,xa0,xb0,d0,o0,u200", out)
    }

    @Test
    fun `serialize concatenates project rows then library rows`() = runTest {
        val mgr = MixedFilamentManager(
            loadProject = { emptyList() },
            loadLibrary = {
                listOf(MixedFilamentRow.fromLegacy(999L, 1, 4, 25,
                    MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                    "lib", true))
            },
            saveProject = {},
            saveLibrary = {},
        )
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val out = mgr.serialize(numPhysicalFilaments = 4)
        val rows = out.split(";")
        assertEquals(2, rows.size)
        assertTrue(rows[0].startsWith("1,2,"))   // project row first
        assertTrue(rows[1].startsWith("1,4,"))   // library row second
    }

    @Test
    fun addN_threeComponents_serializesGradientTokens() {
        val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
        mgr.addN(
            components = listOf(1, 2, 3), weights = listOf(50, 30, 20),
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        )
        val recipe = mgr.serialize(numPhysicalFilaments = 4)
        assert(recipe.contains(",g123,")) { "ids token missing in: $recipe" }
        assert(recipe.contains(",w50/30/20,")) { "weights token missing in: $recipe" }
        assert(recipe.startsWith("1,2,1,1,30,")) { "legacy prefix wrong: $recipe" }
    }

    @Test
    fun addN_fourComponents_serializesAllIdsAndWeights() {
        val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
        mgr.addN(
            components = listOf(1, 2, 3, 4), weights = listOf(40, 30, 20, 10),
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        )
        val recipe = mgr.serialize(numPhysicalFilaments = 4)
        assert(recipe.contains(",g1234,")) { "ids token wrong in: $recipe" }
        assert(recipe.contains(",w40/30/20/10,")) { "weights token wrong in: $recipe" }
    }

    @Test
    fun editN_growsTwoWayToThreeComponents() {
        val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
        val row = mgr.add(componentA = 1, componentB = 2, mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        mgr.editN(row.id, listOf(1, 2, 3), listOf(50, 30, 20),
            MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val updated = mgr.projectMixes.value.first { it.id == row.id }
        org.junit.Assert.assertEquals(listOf(1, 2, 3), updated.components)
        org.junit.Assert.assertEquals(listOf(50, 30, 20), updated.weights)
        val recipe = mgr.serialize(4)
        assert(recipe.contains(",g123,w50/30/20,")) { recipe }
    }

    @Test
    fun addTwoWay_stillWorksViaOverload() {
        val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
        mgr.add(componentA = 1, componentB = 2, mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val recipe = mgr.serialize(4)
        assert(recipe.contains(",g12,")) { recipe }
        assert(recipe.contains(",w50/50,")) { recipe }
    }
}
