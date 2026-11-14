package com.u1.slicer

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Result
import org.junit.runner.notification.RunListener
import java.io.File

/** Orchestrator kills each test process, but leaves its large model workspaces on disk. */
class TestWorkspaceCleanupListener : RunListener() {
    override fun testRunFinished(result: Result) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = DiagnosticsStore(context).sessionId
        for (root in listOf(context.filesDir, context.cacheDir)) {
            val workspace = File(root, "transient/$sessionId")
            check(workspace.deleteRecursively()) { "Could not remove test workspace $workspace" }
        }
    }
}
