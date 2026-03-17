package com.u1.slicer.viewer

import android.content.Context
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * Base class for GL-based 3D viewers. Handles all touch gestures:
 *  - Pinch to zoom
 *  - Single-finger orbit (rotate)
 *  - Two-finger pan
 *  - Long-press activates single-finger pan mode (with haptic)
 *  - systemGestureExclusionRects so Android edge-swipe doesn't steal events
 *  - requestDisallowInterceptTouchEvent so Compose scroll containers don't steal events
 *  - ACTION_CANCEL resets all state cleanly
 *
 * Subclasses provide [camera] and override [onActionDown] to intercept placement drag etc.
 */
abstract class BaseGLViewerView(context: Context) : GLSurfaceView(context) {

    abstract val camera: Camera
    var onCameraChanged: ((CameraViewState) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                camera.zoom(1f / detector.scaleFactor)
                notifyCameraChanged()
                requestRender()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (pointerCount == 1 && !onActionDownHandled) {
                    isPanMode = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        })

    protected var isPanMode = false
    private var pointerCount = 0
    private var previousX = 0f
    private var previousY = 0f
    private var previousMidX = 0f
    private var previousMidY = 0f

    // Subclasses set this to true in onActionDown if they handle the event themselves
    // (e.g., placement drag started). When true, long-press pan is suppressed.
    protected var onActionDownHandled = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                previousX = event.x
                previousY = event.y
                pointerCount = 1
                isPanMode = false
                onActionDownHandled = false
                handleActionDown(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown()
                pointerCount = event.pointerCount
                if (pointerCount >= 2) {
                    previousMidX = (event.getX(0) + event.getX(1)) / 2
                    previousMidY = (event.getY(0) + event.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!handleActionMove(event)) {
                    // Default: orbit / two-finger pan
                    if (pointerCount == 1 && event.pointerCount == 1) {
                        val dx = event.x - previousX
                        val dy = event.y - previousY
                        if (isPanMode) {
                            val panScale = camera.distance * 0.003f
                            camera.pan(-dx * panScale, dy * panScale)
                        } else {
                            camera.rotate(-dx * 0.3f, dy * 0.3f)
                        }
                        notifyCameraChanged()
                        requestRender()
                        previousX = event.x
                        previousY = event.y
                    } else if (event.pointerCount >= 2) {
                        val midX = (event.getX(0) + event.getX(1)) / 2
                        val midY = (event.getY(0) + event.getY(1)) / 2
                        val dx = midX - previousMidX
                        val dy = midY - previousMidY
                        val panScale = camera.distance * 0.002f
                        camera.pan(-dx * panScale, dy * panScale)
                        notifyCameraChanged()
                        requestRender()
                        previousMidX = midX
                        previousMidY = midY
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                isPanMode = false
                handleActionUp(event)
                pointerCount = event.pointerCount - 1
                if (event.pointerCount == 2) {
                    // Transitioning 2→1 finger: capture the REMAINING pointer's position,
                    // not the lifted one (event.x/y), to prevent a jump on the next MOVE.
                    val remainingIdx = if (event.actionIndex == 0) 1 else 0
                    previousX = event.getX(remainingIdx)
                    previousY = event.getY(remainingIdx)
                } else if (event.pointerCount <= 1) {
                    previousX = event.x
                    previousY = event.y
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isPanMode = false
                pointerCount = 0
                handleActionCancel()
                requestRender()
            }
        }
        return true
    }

    /** Called on ACTION_DOWN. Override to start placement drag etc. */
    protected open fun handleActionDown(event: MotionEvent) {}

    /** Called on ACTION_POINTER_DOWN (second finger). Override to cancel drag. */
    protected open fun handlePointerDown() {}

    /**
     * Called on ACTION_MOVE. Return true if the move was fully handled (e.g., placement drag)
     * so the base orbit/pan logic is skipped.
     */
    protected open fun handleActionMove(event: MotionEvent): Boolean = false

    /** Called on ACTION_UP / ACTION_POINTER_UP. Override to finish drag. */
    protected open fun handleActionUp(event: MotionEvent) {}

    /** Called on ACTION_CANCEL. Override to reset drag state. */
    protected open fun handleActionCancel() {}

    protected fun notifyCameraChanged() {
        onCameraChanged?.invoke(camera.snapshot())
    }
}
