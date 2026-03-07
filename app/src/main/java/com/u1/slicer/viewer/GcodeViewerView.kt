package com.u1.slicer.viewer

import android.content.Context
import android.view.MotionEvent
import com.u1.slicer.gcode.ParsedGcode

class GcodeViewerView(context: Context) : BaseGLViewerView(context) {

    val renderer = GcodeRenderer(context)
    override val camera: Camera get() = renderer.camera

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setGcode(gcode: ParsedGcode) {
        renderer.pendingGcode = gcode
        requestRender()
    }

    fun setExtruderColors(hexColors: List<String>) {
        renderer.pendingExtruderColors = hexColors
        requestRender()
    }

    fun setLayerRange(min: Int, max: Int) {
        renderer.minLayer = min
        renderer.maxLayer = max
        requestRender()
    }
}
