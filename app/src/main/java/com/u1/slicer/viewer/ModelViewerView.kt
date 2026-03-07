package com.u1.slicer.viewer

import android.content.Context
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector

class ModelViewerView(context: Context) : GLSurfaceView(context) {

    val renderer = ModelRenderer(context)
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var previousX = 0f
    private var previousY = 0f
    private var pointerCount = 0
    private var previousMidX = 0f
    private var previousMidY = 0f
    // When true, single-finger drag pans instead of rotating (activated by long-press)
    private var isPanMode = false

    // Placement mode: when true, single-finger drag moves objects on the bed
    var placementMode = false

    // Callback when an object/tower is moved: (index, newX, newY)
    // index < instanceCount → object; index == instanceCount → wipe tower
    var onObjectMoved: ((Int, Float, Float) -> Unit)? = null

    // Drag state
    private var draggingIndex = -1
    private var lastBedX = 0f
    private var lastBedY = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.camera.zoom(1f / detector.scaleFactor)
                requestRender()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                // Long-press with one finger switches to pan mode
                if (!placementMode && pointerCount == 1) {
                    isPanMode = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Prevent Android system gesture navigation (edge-swipe / swipe-up) from
        // stealing touch events mid-drag, which caused drags to stop after a short distance.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    fun setMesh(mesh: MeshData) {
        renderer.pendingMesh = mesh
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Tell the Compose verticalScroll parent not to intercept this touch.
                // Without this, the scroll container steals the gesture after ~8dp and
                // sends ACTION_CANCEL, which stops the drag mid-gesture.
                parent?.requestDisallowInterceptTouchEvent(true)
                previousX = event.x
                previousY = event.y
                pointerCount = 1
                isPanMode = false
                draggingIndex = -1  // always reset on new touch

                if (placementMode) {
                    // Use Z=sizeZ/2 (model visual midpoint) for hit detection so the user
                    // can tap where the model appears on screen (not just its Z=0 shadow).
                    // Use Z=0 for lastBedX/Y so drag deltas are in the bed plane.
                    val halfZ = renderer.meshData?.sizeZ?.div(2f) ?: 0f
                    val bedHit = renderer.screenToBed(event.x, event.y, halfZ)
                    val bed0  = renderer.screenToBed(event.x, event.y)
                    if (bedHit != null && bed0 != null) {
                        draggingIndex = hitTest(bedHit[0], bedHit[1])
                        if (draggingIndex >= 0) {
                            lastBedX = bed0[0]
                            lastBedY = bed0[1]
                            renderer.highlightIndex = draggingIndex
                            requestRender()
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger → cancel any drag/pan, switch to camera control
                if (draggingIndex >= 0) {
                    draggingIndex = -1
                    renderer.highlightIndex = -1
                    requestRender()
                }
                isPanMode = false
                pointerCount = event.pointerCount
                if (pointerCount >= 2) {
                    previousMidX = (event.getX(0) + event.getX(1)) / 2
                    previousMidY = (event.getY(0) + event.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (placementMode && draggingIndex >= 0 && event.pointerCount == 1) {
                    // Drag object on bed plane
                    val bed = renderer.screenToBed(event.x, event.y)
                    if (bed != null) {
                        val dx = bed[0] - lastBedX
                        val dy = bed[1] - lastBedY
                        lastBedX = bed[0]
                        lastBedY = bed[1]
                        onObjectMoved?.invoke(draggingIndex, dx, dy)
                        requestRender()
                    }
                } else if (pointerCount == 1 && event.pointerCount == 1 && draggingIndex < 0) {
                    val dx = event.x - previousX
                    val dy = event.y - previousY
                    if (isPanMode) {
                        // Long-press activated pan mode: translate camera
                        val panScale = renderer.camera.distance * 0.003f
                        renderer.camera.pan(-dx * panScale, dy * panScale)
                    } else {
                        // Normal single-finger: orbit/rotate
                        renderer.camera.rotate(-dx * 0.3f, dy * 0.3f)
                    }
                    requestRender()
                    previousX = event.x
                    previousY = event.y
                } else if (event.pointerCount >= 2) {
                    // Two fingers: pan camera
                    val midX = (event.getX(0) + event.getX(1)) / 2
                    val midY = (event.getY(0) + event.getY(1)) / 2
                    val dx = midX - previousMidX
                    val dy = midY - previousMidY
                    val panScale = renderer.camera.distance * 0.002f
                    renderer.camera.pan(-dx * panScale, dy * panScale)
                    requestRender()
                    previousMidX = midX
                    previousMidY = midY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                isPanMode = false
                if (draggingIndex >= 0) {
                    draggingIndex = -1
                    renderer.highlightIndex = -1
                    requestRender()
                }
                pointerCount = event.pointerCount - 1
                if (event.pointerCount <= 1) {
                    previousX = event.x
                    previousY = event.y
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isPanMode = false
                draggingIndex = -1
                renderer.highlightIndex = -1
                pointerCount = 0
                requestRender()
            }
        }
        return true
    }

    /**
     * Hit-test: find which object/tower the bed coordinate (bx, by) is over.
     * Returns object index (0..N-1), instanceCount for wipe tower, or -1 for none.
     */
    private fun hitTest(bx: Float, by: Float): Int {
        val mesh = renderer.meshData ?: return -1
        val positions = renderer.instancePositions ?: return -1

        val count = positions.size / 2
        val sizeX = mesh.sizeX
        val sizeY = mesh.sizeY

        // Check objects (reverse order so topmost is picked first)
        for (i in (0 until count).reversed()) {
            val ox = positions[i * 2]
            val oy = positions[i * 2 + 1]
            if (bx >= ox && bx <= ox + sizeX && by >= oy && by <= oy + sizeY) {
                return i
            }
        }

        // Check wipe tower
        val tower = renderer.wipeTower
        if (tower != null) {
            if (bx >= tower.x && bx <= tower.x + tower.width &&
                by >= tower.y && by <= tower.y + tower.depth) {
                return count
            }
        }

        return -1
    }
}
