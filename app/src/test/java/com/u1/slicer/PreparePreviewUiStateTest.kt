package com.u1.slicer

import org.junit.Assert.assertTrue
import org.junit.Test

class PreparePreviewUiStateTest {

    @Test
    fun preparePreviewLoadingBody_mentionsMetadataAndPreviewForPaintedModels() {
        val body = preparePreviewLoadingBody(
            modelTriangleCount = 8_000_000,
            hasPaintData = true
        )

        assertTrue(body.contains("8000000"))
        assertTrue(body.contains("Filaments and plate settings are already available"))
        assertTrue(body.contains("colour preview streams in"))
    }

    @Test
    fun preparePreviewLoadingBody_usesGenericPreviewCopy_forNonPaintedModels() {
        val body = preparePreviewLoadingBody(
            modelTriangleCount = 0,
            hasPaintData = false
        )

        assertTrue(body.contains("The model metadata is ready."))
        assertTrue(body.contains("preview streams in"))
    }
}
