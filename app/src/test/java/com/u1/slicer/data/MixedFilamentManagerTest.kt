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
        MixedFilamentRow(
            id = id,
            componentA = a,
            componentB = b,
            mixBPercent = pct,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            label = MixedFilamentRow.autoLabel(a, b, pct),
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
        assertEquals("E1+E2 @ 50%", project[0].label)
        assertEquals("E1+E3 @ 33%", project[1].label)
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
}
