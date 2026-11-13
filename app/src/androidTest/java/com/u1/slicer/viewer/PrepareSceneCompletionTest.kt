package com.u1.slicer.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrepareSceneCompletionTest {
    @Test fun cachedPreviewSceneIsCompleteWhenRequestedAgainAfterScaling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "scene-completion.stl")
        val lib = NativeLibrary()
        try {
            instrumentation.context.assets.open("tetrahedron.stl").use { input ->
                file.outputStream().use(input::copyTo)
            }
            assertTrue(lib.loadModel(file.absolutePath))
            assertNotNull(lib.getPreparePreviewMesh())
            // Whole-model scaling is applied by the renderer. Its next scene request
            // reuses the native geometry cache, just like returning to Prepare.
            repeat(2) {
                val handle = lib.buildPrepareRenderScene()
                try {
                    assertTrue("Cached geometry must be available", lib.nativeGetPrepareRenderSceneBatchCount(handle) > 0)
                    assertTrue("Cached scene must finish so Loading full details disappears",
                        lib.nativeIsPrepareRenderSceneComplete(handle))
                } finally {
                    lib.nativeReleasePrepareRenderScene(handle)
                }
            }
        } finally {
            lib.clearModel()
            file.delete()
        }
    }
}
