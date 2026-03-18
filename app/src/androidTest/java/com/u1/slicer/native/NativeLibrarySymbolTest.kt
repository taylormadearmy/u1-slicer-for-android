package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.NativeLibrary
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke tests that verify every declared JNI symbol in NativeLibrary
 * is actually present in the packaged .so.
 *
 * These tests catch the class of bug where a new `external fun` is added to
 * NativeLibrary.kt but the corresponding C++ function is missing from the
 * packaged .so (e.g. because jniLibs/ still holds a stale pre-built).
 *
 * Each test calls the native method with safe/no-op inputs and asserts it doesn't
 * throw UnsatisfiedLinkError. We don't verify correctness here (the model isn't
 * loaded), just that the symbol is linked.
 */
@RunWith(AndroidJUnit4::class)
class NativeLibrarySymbolTest {

    @Before
    fun requireNativeLibrary() {
        assertTrue(
            "Native library must be loaded on device (arm64 required)",
            NativeLibrary.isLoaded
        )
    }

    private val lib = NativeLibrary()

    @Test
    fun getCoreVersion_isLinked() {
        val version = lib.getCoreVersion()
        assertNotNull(version)
        assertTrue("Version should mention PrusaSlicer", version.contains("2."))
    }

    @Test
    fun loadModel_isLinked() {
        // Non-existent path → should return false, not throw UnsatisfiedLinkError
        val result = lib.loadModel("/nonexistent/path/model.stl")
        assertFalse(result)
    }

    @Test
    fun clearModel_isLinked() {
        // Just verify no UnsatisfiedLinkError
        lib.clearModel()
    }

    @Test
    fun getModelInfo_isLinked() {
        // Symbol linkage check only — empty model (all-zero floats) does NOT exercise
        // the JNI float→double promotion bug because zero bytes are the same regardless
        // of promotion width. See NativeLibraryCorrectnessTest for the full regression.
        lib.clearModel()
        val info = lib.getModelInfo()
        assertNotNull(info)
    }

    @Test
    fun getGcodePreview_isLinked() {
        val preview = lib.getGcodePreview(10)
        assertNotNull(preview)
    }

    @Test
    fun getPreparePreviewMesh_isLinked() {
        lib.clearModel()
        val preview = lib.getPreparePreviewMesh()
        assertNull(preview)
    }

    @Test
    fun setModelInstances_isLinked() {
        // No model loaded → should return false (not throw UnsatisfiedLinkError)
        val result = lib.setModelInstances(floatArrayOf(5f, 5f))
        assertFalse(result)
    }
}
