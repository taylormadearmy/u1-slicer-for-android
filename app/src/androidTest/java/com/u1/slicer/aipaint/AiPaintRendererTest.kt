package com.u1.slicer.aipaint

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiPaintRendererTest {

    private fun singleTriangle(): FloatArray = floatArrayOf(
        0f, 0f, 0f,   1f, 0f, 0f,   0.5f, 1f, 0f
    )

    @Test
    fun renderShaded_returnsCorrectDimensions() {
        val bitmap = AiPaintRenderer.renderShaded(singleTriangle(), 256, 256, CameraAngle.FRONT)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun renderRegions_returnsCorrectDimensions() {
        val regionIds = intArrayOf(0)
        val bitmap = AiPaintRenderer.renderRegions(singleTriangle(), regionIds, 256, 256, CameraAngle.FRONT)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun renderShaded_backgroundIsNotPureWhite() {
        val bitmap = AiPaintRenderer.renderShaded(singleTriangle(), 64, 64, CameraAngle.FRONT)
        val topLeft = bitmap.getPixel(0, 0)
        assertNotEquals(0xFFFFFFFF.toInt(), topLeft)
    }
}
