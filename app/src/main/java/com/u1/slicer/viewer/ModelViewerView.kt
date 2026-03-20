package com.u1.slicer.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy

class ModelViewerView(context: Context) : BaseGLViewerView(context) {

    internal companion object {
        fun resolveDragTarget(
            bedHit: FloatArray?,
            bedPlaneHit: FloatArray?,
            hitTest: (Float, Float) -> Int
        ): Int {
            if (bedHit != null) {
                val primary = hitTest(bedHit[0], bedHit[1])
                if (primary >= 0) return primary
            }
            if (bedPlaneHit != null) {
                return hitTest(bedPlaneHit[0], bedPlaneHit[1])
            }
            return -1
        }
    }

    val renderer = ModelRenderer(context)
    override val camera: Camera get() = renderer.camera

    // Placement mode: when true, single-finger drag moves objects on the bed
    var placementMode = false

    // Callback when an object/tower is moved: (index, deltaX, deltaY) in bed mm
    var onObjectMoved: ((Int, Float, Float) -> Unit)? = null

    // Drag state
    private var draggingIndex = -1
    private var lastBedX = 0f
    private var lastBedY = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setMesh(mesh: MeshData) {
        renderer.pendingMesh = mesh
        requestRender()
    }

    fun clearMesh() {
        renderer.pendingClearMesh = true
        requestRender()
    }

    fun setOnContentReady(listener: (() -> Unit)?) {
        renderer.onContentReady = listener
    }

    /** Recolor the mesh using the given palette. Thread-safe: queues work on GL thread. */
    fun recolorMesh(colorPalette: List<FloatArray>) {
        renderer.pendingRecolor = colorPalette
        requestRender()
    }

    fun setExtruderColors(hexColors: List<String>) {
        renderer.instanceColors = hexColors.map { hex ->
            try {
                val c = android.graphics.Color.parseColor(hex)
                floatArrayOf(
                    android.graphics.Color.red(c) / 255f,
                    android.graphics.Color.green(c) / 255f,
                    android.graphics.Color.blue(c) / 255f,
                    1f
                )
            } catch (_: Exception) { floatArrayOf(0.91f, 0.48f, 0f, 1f) }
        }
        requestRender()
    }

    fun applyCameraState(state: CameraViewState) {
        renderer.preserveCameraOnNextMeshUpload = true
        renderer.pendingCameraReset = false
        renderer.camera.restore(state)
        requestRender()
    }

    override fun handleActionDown(event: MotionEvent) {
        draggingIndex = -1
        if (placementMode) {
            // Use Z=scaledSizeZ/2 for hit detection so tap lands on visible model face, not Z=0 shadow.
            val halfZ = (renderer.meshData?.sizeZ ?: 0f) * renderer.modelScale[2] / 2f
            val bedHit = renderer.screenToBed(event.x, event.y, halfZ)
            val bed0  = renderer.screenToBed(event.x, event.y)
            if (bedHit != null || bed0 != null) {
                // Prefer the visible-face projection, but fall back to the stable bed-plane
                // projection when the closer preview camera makes the higher plane miss.
                draggingIndex = resolveDragTarget(bedHit, bed0, ::hitTest)
                if (draggingIndex >= 0) {
                    val anchor = bed0 ?: bedHit!!
                    lastBedX = anchor[0]
                    lastBedY = anchor[1]
                    renderer.highlightIndex = draggingIndex
                    requestRender()
                    onActionDownHandled = true  // suppress long-press pan while dragging an object
                }
            }
        }
    }

    override fun handlePointerDown() {
        if (draggingIndex >= 0) {
            draggingIndex = -1
            renderer.highlightIndex = -1
            requestRender()
        }
    }

    override fun handleActionMove(event: MotionEvent): Boolean {
        if (placementMode && draggingIndex >= 0 && event.pointerCount == 1) {
            val bed = renderer.screenToBed(event.x, event.y) ?: return true
            val dx = bed[0] - lastBedX
            val dy = bed[1] - lastBedY
            lastBedX = bed[0]
            lastBedY = bed[1]
            onObjectMoved?.invoke(draggingIndex, dx, dy)
            requestRender()
            return true
        }
        return false
    }

    override fun handleActionUp(event: MotionEvent) {
        if (draggingIndex >= 0) {
            draggingIndex = -1
            renderer.highlightIndex = -1
            requestRender()
        }
    }

    override fun handleActionCancel() {
        draggingIndex = -1
        renderer.highlightIndex = -1
    }

    /**
     * Capture the current GL frame as a Bitmap using PixelCopy (API 26+).
     * Calls back on the main thread with the bitmap, or null on failure.
     */
    fun captureBitmap(callback: (android.graphics.Bitmap?) -> Unit) {
        if (width <= 0 || height <= 0) { callback(null); return }
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        PixelCopy.request(this, bmp, { result ->
            if (result == PixelCopy.SUCCESS) callback(bmp)
            else { bmp.recycle(); callback(null) }
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * Hit-test: find which object/tower the bed coordinate (bx, by) is over.
     * Returns object index (0..N-1), instanceCount for wipe tower, or -1 for none.
     */
    private fun hitTest(bx: Float, by: Float): Int {
        val mesh = renderer.meshData ?: return -1
        val positions = renderer.instancePositions ?: return -1

        val count = positions.size / 2
        val s = renderer.modelScale
        val sizeX = mesh.sizeX * s[0]
        val sizeY = mesh.sizeY * s[1]

        for (i in (0 until count).reversed()) {
            val ox = positions[i * 2]
            val oy = positions[i * 2 + 1]
            if (bx >= ox && bx <= ox + sizeX && by >= oy && by <= oy + sizeY) return i
        }

        val tower = renderer.wipeTower
        if (tower != null) {
            if (bx >= tower.x && bx <= tower.x + tower.width &&
                by >= tower.y && by <= tower.y + tower.depth
            ) return count
        }

        return -1
    }
}
