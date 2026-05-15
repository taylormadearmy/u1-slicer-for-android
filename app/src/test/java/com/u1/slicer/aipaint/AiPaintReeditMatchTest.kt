package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class AiPaintReeditMatchTest {

    @Test
    fun `same painting matches when current model has embedded_ prefix`() {
        val cached = "/data/data/com.u1.slicer/cache/ai_paint_1778647761784.3mf"
        val current = "/data/data/com.u1.slicer/files/transient/abcd/embedded_ai_paint_1778647761784.3mf"
        assertTrue(AiPaintViewModel.isSamePainting(cached, current))
    }

    @Test
    fun `same painting matches when both have embedded_ prefix`() {
        val cached = "/path/A/embedded_ai_paint_42.3mf"
        val current = "/path/B/embedded_ai_paint_42.3mf"
        assertTrue(AiPaintViewModel.isSamePainting(cached, current))
    }

    @Test
    fun `same painting matches when both lack embedded_ prefix`() {
        val cached = "/cache/ai_paint_42.3mf"
        val current = "/work/ai_paint_42.3mf"
        assertTrue(AiPaintViewModel.isSamePainting(cached, current))
    }

    @Test
    fun `different paintings do not match`() {
        val cached = "/cache/ai_paint_1.3mf"
        val current = "/work/embedded_ai_paint_2.3mf"
        assertFalse(AiPaintViewModel.isSamePainting(cached, current))
    }

    @Test
    fun `painting does not match unrelated model`() {
        val cached = "/cache/ai_paint_1.3mf"
        val current = "/work/embedded_3DBenchy.3mf"
        assertFalse(AiPaintViewModel.isSamePainting(cached, current))
    }

    @Test
    fun `null cached path returns false`() {
        assertFalse(AiPaintViewModel.isSamePainting(null, "/work/ai_paint_1.3mf"))
    }

    @Test
    fun `null current path returns false`() {
        assertFalse(AiPaintViewModel.isSamePainting("/cache/ai_paint_1.3mf", null))
    }

    @Test
    fun `blank paths return false`() {
        assertFalse(AiPaintViewModel.isSamePainting("", "/work/ai_paint_1.3mf"))
        assertFalse(AiPaintViewModel.isSamePainting("/cache/ai_paint_1.3mf", ""))
    }

    @Test
    fun `same painting matches through sanitizer plus embedder chain`() {
        // Real chain observed on Pixel 8a:
        //   AiPaintViewModel writes:  /cache/ai_paint_1778648795168.3mf
        //   loadModelFromFile copies: /files/transient/.../ai_paint_1778648795168.3mf
        //   BambuSanitizer outputs:   /files/transient/.../sanitized_ai_paint_1778648795168.3mf
        //   ProfileEmbedder outputs:  /files/transient/.../embedded_sanitized_ai_paint_1778648795168.3mf
        val cached = "/data/data/com.u1.slicer.orca/cache/ai_paint_1778648795168.3mf"
        val current = "/data/data/com.u1.slicer.orca/files/transient/X/embedded_sanitized_ai_paint_1778648795168.3mf"
        assertTrue(AiPaintViewModel.isSamePainting(cached, current))
    }

    @Test
    fun `same painting matches when only sanitized_ prefix is present`() {
        val cached = "/cache/ai_paint_42.3mf"
        val current = "/work/sanitized_ai_paint_42.3mf"
        assertTrue(AiPaintViewModel.isSamePainting(cached, current))
    }

    @Test
    fun `same painting matches when prefixes appear in opposite order`() {
        // Defensive: handle either ordering of the pipeline prefixes.
        val cached = "/cache/sanitized_embedded_ai_paint_99.3mf"
        val current = "/work/embedded_sanitized_ai_paint_99.3mf"
        assertTrue(AiPaintViewModel.isSamePainting(cached, current))
    }

    @Test
    fun `different paintings with sanitizer + embedder prefixes still do not match`() {
        val cached = "/cache/ai_paint_1.3mf"
        val current = "/work/embedded_sanitized_ai_paint_2.3mf"
        assertFalse(AiPaintViewModel.isSamePainting(cached, current))
    }
}
