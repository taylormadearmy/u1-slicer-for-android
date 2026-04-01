package com.u1.slicer

import com.u1.slicer.viewer.NativePreviewMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LargeModelLoadingMessageTest {

    @Test fun `small file uses filename message`() {
        val msg = loadingMessageFor(filename = "model.stl", fileSizeBytes = 10 * 1024 * 1024L)
        assertEquals("Loading model.stl…", msg)
    }

    @Test fun `file over 50MB triggers large model message`() {
        val msg = loadingMessageFor(filename = "big.3mf", fileSizeBytes = 51 * 1024 * 1024L)
        assertEquals("Large model — this may take a moment…", msg)
    }

    @Test fun `exactly 50MB is not large`() {
        val msg = loadingMessageFor(filename = "model.stl", fileSizeBytes = 50 * 1024 * 1024L)
        assertEquals("Loading model.stl…", msg)
    }

    @Test fun `small triangles not flagged as large`() {
        assertFalse(isLargeTriangleCount(NativePreviewMesh.MAX_KOTLIN_PREVIEW_TRIANGLES))
    }

    @Test fun `triangle count over threshold is large`() {
        assertTrue(isLargeTriangleCount(NativePreviewMesh.MAX_KOTLIN_PREVIEW_TRIANGLES + 1))
    }
}
