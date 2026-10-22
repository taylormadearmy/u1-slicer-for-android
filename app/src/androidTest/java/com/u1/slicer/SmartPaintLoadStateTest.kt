package com.u1.slicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.CanonicalColourDestination
import com.u1.slicer.data.CanonicalColourRemap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runner.Result
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

@RunWith(AndroidJUnit4::class)
class SmartPaintLoadStateTest {
    @Test
    fun offlineU1PrinterRule_restoresPreviousSelection_whenTestFails() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as U1SlicerApplication
        val repository = app.container.printersRepository
        repository.runMigrationIfNeeded(app.container.settingsRepository)
        val previous = repository.config.first()
        val failure = AssertionError("test failure probe")
        val statement = object : Statement() {
            override fun evaluate() {
                val active = runBlocking { repository.activePrinter.first() }!!
                assertEquals("test-u1", active.id)
                assertEquals(4, app.container.machineProfileRepository.activeFor(active).extruderCount)
                assertEquals(null, active.machineProfileId)
                throw failure
            }
        }
        val actual = runCatching {
            OfflineU1PrinterRule().apply(statement, Description.EMPTY).evaluate()
        }.exceptionOrNull()
        assertTrue("The original test failure must propagate", actual === failure)
        assertEquals(previous, repository.config.first())
    }

    @Test
    fun testWorkspaceCleanup_removesOnlyCurrentSession_andIsSafeToRepeat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = DiagnosticsStore(context).sessionId
        val roots = listOf(context.filesDir, context.cacheDir)
        val workspaces = roots.map { File(it, "transient/$sessionId") }
        val preserved = roots.map { File(it, "transient/cleanup-other-session") }
        val savedFile = File(context.filesDir, "cleanup-saved-file-probe")
        try {
            (workspaces + preserved).forEach { dir ->
                dir.mkdirs()
                File(dir, "model.3mf").writeText("probe")
            }
            savedFile.writeText("saved data")
            val listener = TestWorkspaceCleanupListener()
            listener.testRunFinished(Result())
            listener.testRunFinished(Result())
            workspaces.forEach { assertTrue("Test workspace remains: $it", !it.exists()) }
            preserved.forEach { assertEquals("probe", File(it, "model.3mf").readText()) }
            assertEquals("saved data", savedFile.readText())
        } finally {
            preserved.forEach { it.deleteRecursively() }
            savedFile.delete()
        }
    }

    @Test
    fun importingAndClearingModel_discardsPreviousFileColourRemaps() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as U1SlicerApplication
        val file = File(app.cacheDir, "remap-load-benchy.stl")
        instrumentation.context.assets.open("3DBenchy.stl").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val vm = SlicerViewModel(app)
        val remaps = listOf(CanonicalColourRemap(0, CanonicalColourDestination.PhysicalSlot(2)))
        try {
            vm.setCanonicalColourRemaps(remaps)
            vm.loadModelFromFile(file)
            assertEquals(emptyList<CanonicalColourRemap>(), vm.canonicalColourRemaps.value)
            val state = withTimeout(30_000) {
                vm.state.first { it is SlicerViewModel.SlicerState.ModelLoaded || it is SlicerViewModel.SlicerState.Error }
            }
            assertTrue("Import failed: $state", state is SlicerViewModel.SlicerState.ModelLoaded)
            vm.setCanonicalColourRemaps(remaps)
            vm.clearModel()
            assertEquals(emptyList<CanonicalColourRemap>(), vm.canonicalColourRemaps.value)
        } finally {
            vm.clearModel()
            file.delete()
        }
    }
}
