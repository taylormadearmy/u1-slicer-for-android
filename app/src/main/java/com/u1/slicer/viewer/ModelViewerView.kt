package com.u1.slicer.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewConfiguration

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

        /**
         * Standard slab-method ray-AABB intersection. Returns true iff the ray
         * intersects the axis-aligned bounding box in front of the origin.
         * Exposed (internal) so the Chubby tap-to-select fallback can be
         * unit-tested without spinning up a Compose / GL harness.
         */
        internal fun rayHitsAABBStatic(
            ray: FloatArray,
            minX: Float, maxX: Float,
            minY: Float, maxY: Float,
            minZ: Float, maxZ: Float,
        ): Boolean {
            val ox = ray[0]; val oy = ray[1]; val oz = ray[2]
            val dx = ray[3]; val dy = ray[4]; val dz = ray[5]
            val invDx = if (dx != 0f) 1f / dx else Float.POSITIVE_INFINITY
            val invDy = if (dy != 0f) 1f / dy else Float.POSITIVE_INFINITY
            val invDz = if (dz != 0f) 1f / dz else Float.POSITIVE_INFINITY
            val t1x = (minX - ox) * invDx; val t2x = (maxX - ox) * invDx
            val t1y = (minY - oy) * invDy; val t2y = (maxY - oy) * invDy
            val t1z = (minZ - oz) * invDz; val t2z = (maxZ - oz) * invDz
            val tMin = maxOf(minOf(t1x, t2x), minOf(t1y, t2y), minOf(t1z, t2z))
            val tMax = minOf(maxOf(t1x, t2x), maxOf(t1y, t2y), maxOf(t1z, t2z))
            return tMax >= maxOf(0f, tMin)
        }
    }

    val renderer = ModelRenderer(context)
    override val camera: Camera get() = renderer.camera

    // Placement mode: when true, single-finger drag moves objects on the bed
    var placementMode = false

    // Callback when an object/tower is moved: (index, deltaX, deltaY) in bed mm
    var onObjectMoved: ((Int, Float, Float) -> Unit)? = null

    // Fired once when a drag interaction ends (ACTION_UP after draggingIndex >= 0).
    // Used by multi-object mode to batch native position updates to drag-end rather
    // than re-applying on every MOVE event.
    var onDragEnded: (() -> Unit)? = null

    // Callback when the user taps a triangle (single tap, no drag). Receives the triangle index
    // into the FloatArray supplied to setTrianglePickingPositions. Disabled when null.
    var onTriangleTapped: ((Int) -> Unit)? = null

    // Brush mode: when [brushRadiusWorld] > 0 AND [onBrushPaint] is set, taps find ALL triangles
    // within that world-space radius of the hit triangle's centroid and emit them as a list.
    // The list is what the AI Paint screen passes to AiPaintViewModel.paintTriangles.
    var onBrushPaint: ((List<Int>) -> Unit)? = null

    // Fired once at ACTION_DOWN of every brush touch sequence so the consumer can snapshot
    // for undo before the stroke modifies anything.
    var onBrushStrokeStart: (() -> Unit)? = null

    // Fired continuously while a brush touch is dragging — gives the consumer the current
    // touch screen coordinates so it can draw a brush ring overlay. (-1f, -1f) on lift.
    var onBrushTouchAt: ((Float, Float) -> Unit)? = null

    /** fix35.2: fired when a tap lands on the viewer but doesn't hit any triangle (i.e. empty
     *  background space). Used by AI Paint to "click off the model to clear the highlight". */
    var onEmptyTap: (() -> Unit)? = null

    /** F66 — persistent tap-selection. Set by Compose layer whenever
     *  `viewModel.selection.objectIndex` changes; -1 = no selection. The
     *  drag-cancel/drag-end handlers below restore the renderer's
     *  `highlightIndex` to this value (not to -1) so the selection highlight
     *  survives an intervening drag gesture. */
    var persistentSelectionIndex: Int = -1

    var brushRadiusWorld: Float = 0f

    /** fix42 polygon lasso mode. When `lassoMode = true` AND `onLassoLoop` is set:
     *  DOWN starts a path; MOVE appends screen-space points (streamed via `onLassoPathUpdate`
     *  so the consumer can draw the live polygon overlay); UP closes the loop and fires
     *  `onLassoLoop` with the list of FRONT-FACING triangle indices whose centroids project
     *  inside the closed polygon. No brush radius involved.
     *
     *  Mutually exclusive with brush mode — the consumer ensures `onBrushPaint` is null when
     *  `lassoMode` is on. */
    var lassoMode: Boolean = false
    var onLassoLoop: ((triangleIds: List<Int>) -> Unit)? = null
    var onLassoPathUpdate: ((path: List<Pair<Float, Float>>) -> Unit)? = null

    // Lasso path accumulator (screen-space, in view-local pixels).
    private val lassoPath = mutableListOf<Pair<Float, Float>>()
    private var lassoActive = false

    // Positions used for triangle picking. Separate from the mesh VBO so callers don't have to
    // rebuild the picking data when only colours change.
    private var pickingPositions: FloatArray? = null

    // Drag state
    private var draggingIndex = -1
    private var lastBedX = 0f
    private var lastBedY = 0f

    // Tap detection state
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownTime = 0L
    private var tapMovedTooFar = false
    private val tapSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Brush stroke state — tracks whether we've already emitted onBrushStrokeStart for this
    // touch sequence, and throttles per-frame paint emissions to ~30 Hz.
    private var brushStrokeActive = false
    private var lastBrushEmitMs = 0L

    // F54 fix34: while paint mode is engaged a second finger landing transitions the gesture
    // into a two-finger ORBIT (rotate) — pan/zoom still come from the base class. We track
    // the previous midpoint so we can compute deltas across MOVE events.
    private var brushRotateActive = false
    private var brushRotateLastMidX = 0f
    private var brushRotateLastMidY = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setMesh(mesh: MeshData, objectRanges: List<ModelRenderer.ObjectMeshRange>? = null) {
        renderer.setPendingObjectMeshRanges(objectRanges)
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

    /** Re-upload vertex buffer to GPU without recoloring (use after recolorByZBands). Thread-safe. */
    fun refreshColors() {
        renderer.pendingVboRefresh = true
        requestRender()
    }

    /** Supply the per-triangle world-space positions used by [onTriangleTapped] ray picking. */
    fun setTrianglePickingPositions(positions: FloatArray) {
        pickingPositions = positions
    }

    /** Replace the per-triangle extruder-index byte array used by the renderer's recolor step.
     *  Thread-safe: queues the update; the GL thread copies it into MeshData.extruderIndices on
     *  the next frame. Triggers a render. */
    fun updateExtruderIndices(indices: ByteArray) {
        renderer.pendingExtruderUpdate = indices.copyOf()
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
        renderer.pendingCameraState = state
        requestRender()
    }

    fun resetView() {
        renderer.pendingCameraReset = true
        requestRender()
    }

    override fun handleActionDown(event: MotionEvent) {
        draggingIndex = -1
        tapDownX = event.x
        tapDownY = event.y
        tapDownTime = event.eventTime
        tapMovedTooFar = false
        brushStrokeActive = false
        // fix42 lasso: DOWN starts a fresh polygon path. Suppresses orbit so single-finger
        // drag is consumed as a lasso stroke instead of camera rotation.
        if (lassoMode && onLassoLoop != null) {
            lassoActive = true
            lassoPath.clear()
            lassoPath.add(event.x to event.y)
            onLassoPathUpdate?.invoke(lassoPath.toList())
            onActionDownHandled = true
            return
        }
        // Brush mode: start the stroke immediately on DOWN — emit a stroke-start callback
        // (used to snapshot for undo) and paint the touch point. Subsequent MOVE events
        // continue the stroke. Suppresses orbit by setting onActionDownHandled.
        if (onBrushPaint != null) {
            brushStrokeActive = true
            onBrushStrokeStart?.invoke()
            val tris = pickTrianglesWithinRadius(event.x, event.y, brushRadiusWorld)
            if (tris.isNotEmpty()) onBrushPaint?.invoke(tris)
            onBrushTouchAt?.invoke(event.x, event.y)
            lastBrushEmitMs = event.eventTime
            onActionDownHandled = true
        }
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
                    // F66 — DO NOT set renderer.highlightIndex here. A bare
                    // touch on the model (intended as "tap to select" or
                    // "start an orbit") used to flash an orange drag-anchor
                    // highlight on the model the instant the finger landed,
                    // which read as "the model was already selected" before
                    // the user did anything. The drag highlight is now
                    // applied lazily on the first MOVE past touch slop
                    // (see handleActionMove).
                    onActionDownHandled = true  // suppress long-press pan while dragging an object
                }
            }
        }
    }

    override fun handlePointerDown() {
        if (draggingIndex >= 0) {
            draggingIndex = -1
            // F66: restore selection highlight rather than clearing.
            renderer.highlightIndex = persistentSelectionIndex
            requestRender()
        }
        // F54 fix34 — second finger landed while a brush stroke was active. Stop the stroke
        // and transition the gesture into a two-finger orbit so the user can rotate the
        // model without leaving paint mode.
        if (brushStrokeActive) {
            brushStrokeActive = false
            onBrushTouchAt?.invoke(-1f, -1f)
            brushRotateActive = true
            // Midpoint baseline captured here so the first MOVE delta starts at zero.
            // event.getX(0/1) isn't available in this callback signature; the actual midpoint
            // will be initialised on the first 2-pointer MOVE.
            brushRotateLastMidX = -1f
            brushRotateLastMidY = -1f
        }
    }

    override fun handleActionMove(event: MotionEvent): Boolean {
        if (!tapMovedTooFar) {
            val mdx = event.x - tapDownX
            val mdy = event.y - tapDownY
            if (mdx * mdx + mdy * mdy > tapSlopPx * tapSlopPx) tapMovedTooFar = true
        }
        // fix42 lasso: append the current touch point to the polygon path. Coalesce so we
        // don't flood the consumer — only append when we've moved at least a few pixels from
        // the last sample (avoids duplicate vertices that confuse the point-in-polygon test).
        if (lassoActive && event.pointerCount == 1) {
            val last = lassoPath.lastOrNull()
            val far = last == null ||
                (event.x - last.first).let { it * it } + (event.y - last.second).let { it * it } > 4f
            if (far) {
                lassoPath.add(event.x to event.y)
                onLassoPathUpdate?.invoke(lassoPath.toList())
            }
            return true
        }
        // Brush mode: continue painting along the drag, throttled to ~30 Hz so we don't flood
        // the viewmodel. The screen receives one paintTriangles call per emit and the GL view
        // recolors in the same frame thanks to fix19's deferred-write pipeline.
        if (brushStrokeActive && event.pointerCount == 1) {
            onBrushTouchAt?.invoke(event.x, event.y)
            if (event.eventTime - lastBrushEmitMs >= 30L) {
                val tris = pickTrianglesWithinRadius(event.x, event.y, brushRadiusWorld)
                if (tris.isNotEmpty()) onBrushPaint?.invoke(tris)
                lastBrushEmitMs = event.eventTime
            }
            return true
        }
        // F54 fix34 — two-finger orbit takes precedence over the base class's two-finger PAN
        // when the user was painting. Rotate using the midpoint delta; matches the single-
        // finger orbit gain (0.3) so the gesture feels familiar.
        if (brushRotateActive && event.pointerCount >= 2) {
            val midX = (event.getX(0) + event.getX(1)) / 2f
            val midY = (event.getY(0) + event.getY(1)) / 2f
            if (brushRotateLastMidX < 0f) {
                brushRotateLastMidX = midX
                brushRotateLastMidY = midY
                return true
            }
            val dx = midX - brushRotateLastMidX
            val dy = midY - brushRotateLastMidY
            camera.rotate(-dx.toDouble() * 0.3, dy.toDouble() * 0.3)
            requestRender()
            brushRotateLastMidX = midX
            brushRotateLastMidY = midY
            return true
        }
        if (placementMode && draggingIndex >= 0 && event.pointerCount == 1) {
            val bed = renderer.screenToBed(event.x, event.y) ?: return true
            val dx = bed[0] - lastBedX
            val dy = bed[1] - lastBedY
            lastBedX = bed[0]
            lastBedY = bed[1]
            // F66 — only commit a move (visual highlight + position callback)
            // once the gesture has crossed touch slop. Otherwise every tap
            // with a tiny finger jitter would commit a sub-mm position
            // change that the user perceives as the model "jumping" on
            // release, AND would flash a drag-anchor highlight that read as
            // "the model is already selected".
            if (tapMovedTooFar) {
                renderer.highlightIndex = draggingIndex
                onObjectMoved?.invoke(draggingIndex, dx, dy)
                requestRender()
            }
            return true
        }
        return false
    }

    override fun handleActionUp(event: MotionEvent) {
        val wasDragging = draggingIndex >= 0
        if (wasDragging) {
            draggingIndex = -1
            // F66: restore selection highlight rather than clearing.
            renderer.highlightIndex = persistentSelectionIndex
            requestRender()
            onDragEnded?.invoke()
        }
        // fix42 lasso: UP closes the polygon and emits the enclosed front-facing triangles.
        // Need ≥ 3 distinct points to form a meaningful loop; below that we treat it as a
        // tap (no commit, just clear the path).
        if (lassoActive) {
            lassoActive = false
            val path = lassoPath.toList()
            lassoPath.clear()
            onLassoPathUpdate?.invoke(emptyList())
            if (path.size >= 3) {
                val inside = pickTrianglesInsideLasso(path)
                onLassoLoop?.invoke(inside)
            }
            return
        }
        // End the two-finger orbit when the second finger lifts (handleActionUp fires on both
        // ACTION_UP and ACTION_POINTER_UP via the base class dispatcher).
        if (brushRotateActive && event.pointerCount <= 2) {
            brushRotateActive = false
            brushRotateLastMidX = -1f
            brushRotateLastMidY = -1f
        }
        if (brushStrokeActive) {
            // Brush already painted on DOWN and during MOVE; just clear the touch-indicator.
            brushStrokeActive = false
            onBrushTouchAt?.invoke(-1f, -1f)
        } else if (!tapMovedTooFar && (onTriangleTapped != null || onEmptyTap != null)) {
            // F66 fix: a touch on the model in placement mode immediately sets
            // draggingIndex on ACTION_DOWN, so `wasDragging` is true even when the
            // user never moved their finger. Treat any finger-down/finger-up
            // sequence with no movement-past-touch-slop and <300ms duration as a
            // tap, regardless of whether a drag candidate was set up. The drag
            // branch above already ran for actually-dragged gestures and cleaned
            // up state, so firing onTriangleTapped here is additive.
            val dt = event.eventTime - tapDownTime
            if (dt < 300L) {
                val triIdx = pickTriangle(event.x, event.y)
                if (triIdx >= 0) {
                    onTriangleTapped?.invoke(triIdx)
                } else {
                    // fix35.2: tap on empty viewer background → fire onEmptyTap so the screen
                    // can clear its selection state.
                    onEmptyTap?.invoke()
                }
            }
        }
    }

    /** fix42 — return every triangle whose centroid PROJECTS inside the screen-space lasso
     *  polygon AND whose normal faces the camera (front-facing). Backfaces are filtered out
     *  so lassoing the goat's front doesn't also commit to the matching back-of-goat triangles
     *  the user can't see. */
    private fun pickTrianglesInsideLasso(path: List<Pair<Float, Float>>): List<Int> {
        val positions = pickingPositions ?: return emptyList()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || path.size < 3) return emptyList()

        // Camera eye in world space — recomputed from the camera's spherical parameters.
        val cam = renderer.camera
        val radAz = Math.toRadians(cam.azimuth)
        val radEl = Math.toRadians(cam.elevation)
        val eyeX = (cam.targetX + cam.panX + cam.distance * kotlin.math.cos(radEl) * kotlin.math.cos(radAz)).toFloat()
        val eyeY = (cam.targetY + cam.panY + cam.distance * kotlin.math.cos(radEl) * kotlin.math.sin(radAz)).toFloat()
        val eyeZ = (cam.targetZ + cam.distance * kotlin.math.sin(radEl)).toFloat()

        // Recompute MVP for an identity model matrix — pickingPositions are already in world
        // space, so model = I. updateViewMatrix has been called by the most recent render.
        cam.updateViewMatrix()
        cam.updateProjectionMatrix(width, height)
        cam.computeMVP()
        val mvp = cam.mvpMatrix

        val nTri = positions.size / 9
        val out = ArrayList<Int>(256)
        val v = FloatArray(4)
        val clip = FloatArray(4)
        for (i in 0 until nTri) {
            val b = i * 9
            // Centroid.
            val cx = (positions[b]     + positions[b + 3] + positions[b + 6]) / 3f
            val cy = (positions[b + 1] + positions[b + 4] + positions[b + 7]) / 3f
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f

            // Front-facing test — compute face normal and dot against view direction.
            // View vector points FROM eye TO centroid; a triangle whose normal opposes the
            // view vector (dot < 0) is facing the camera.
            val ax = positions[b + 3] - positions[b]
            val ay = positions[b + 4] - positions[b + 1]
            val az = positions[b + 5] - positions[b + 2]
            val bx = positions[b + 6] - positions[b]
            val by = positions[b + 7] - positions[b + 1]
            val bz = positions[b + 8] - positions[b + 2]
            val nx = ay * bz - az * by
            val ny = az * bx - ax * bz
            val nz = ax * by - ay * bx
            val vx = cx - eyeX
            val vy = cy - eyeY
            val vz = cz - eyeZ
            if (nx * vx + ny * vy + nz * vz >= 0f) continue   // backface (or edge-on)

            // Project centroid to screen via MVP.
            v[0] = cx; v[1] = cy; v[2] = cz; v[3] = 1f
            android.opengl.Matrix.multiplyMV(clip, 0, mvp, 0, v, 0)
            if (clip[3] <= 0f) continue                       // behind camera
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            val ndcZ = clip[2] / clip[3]
            if (ndcZ < -1f || ndcZ > 1f) continue             // outside near/far clip
            val sx = (ndcX + 1f) * 0.5f * w
            val sy = (1f - ndcY) * 0.5f * h

            if (pointInPolygon(sx, sy, path)) out.add(i)
        }
        return out
    }

    // pointInPolygon extracted to LassoGeometry.kt for unit-testability (fix44).

    /** Pick the hit triangle and gather every triangle whose centroid is within [radiusWorld]
     *  units of the hit centroid. radiusWorld = 0 returns just the hit triangle. */
    private fun pickTrianglesWithinRadius(screenX: Float, screenY: Float, radiusWorld: Float): List<Int> {
        val positions = pickingPositions ?: return emptyList()
        val hit = pickTriangle(screenX, screenY)
        if (hit < 0) return emptyList()
        if (radiusWorld <= 0f) return listOf(hit)
        val b0 = hit * 9
        val hx = (positions[b0]     + positions[b0 + 3] + positions[b0 + 6]) / 3f
        val hy = (positions[b0 + 1] + positions[b0 + 4] + positions[b0 + 7]) / 3f
        val hz = (positions[b0 + 2] + positions[b0 + 5] + positions[b0 + 8]) / 3f
        val r2 = radiusWorld * radiusWorld
        val out = ArrayList<Int>(64)
        val n = positions.size / 9
        for (i in 0 until n) {
            val b = i * 9
            val cx = (positions[b]     + positions[b + 3] + positions[b + 6]) / 3f
            val cy = (positions[b + 1] + positions[b + 4] + positions[b + 7]) / 3f
            val cz = (positions[b + 2] + positions[b + 5] + positions[b + 8]) / 3f
            val dx = cx - hx; val dy = cy - hy; val dz = cz - hz
            if (dx * dx + dy * dy + dz * dz <= r2) out.add(i)
        }
        return out
    }

    private fun pickTriangle(screenX: Float, screenY: Float): Int {
        val positions = pickingPositions ?: return -1
        val ray = renderer.screenToRay(screenX, screenY) ?: return -1
        if (positions.isEmpty()) {
            // Chubby fix follow-on: when MeshData.toWorldSpacePickingPositions
            // skipped allocating per-triangle positions (mesh > 1M vertices →
            // would OOM), we still need tap-to-select to fire on the mesh but
            // tap-on-empty-bed to deselect. Fall back to a ray–AABB hit test
            // against the mesh's world-space bounding box. A hit returns
            // triangle 0 (the higher-level dispatcher maps that to "object 0"
            // via the empty-objectMeshRanges branch); a miss returns -1 so
            // onEmptyTap fires and deselects.
            val mesh = renderer.meshData ?: return -1
            val inst = renderer.instancePositions
            val s = renderer.modelScale
            // Same world-space transform as MeshData.toWorldSpacePickingPositions
            // single-mesh branch: T(x+halfW*sx, y+halfH*sy, halfD*sz) * S * T(-min - half)
            val halfW = (mesh.maxX - mesh.minX) / 2f
            val halfH = (mesh.maxY - mesh.minY) / 2f
            val halfD = (mesh.maxZ - mesh.minZ) / 2f
            val tx = (inst?.getOrNull(0) ?: 0f) + halfW * s[0]
            val ty = (inst?.getOrNull(1) ?: 0f) + halfH * s[1]
            val tz = halfD * s[2]
            val ox = -mesh.minX - halfW
            val oy = -mesh.minY - halfH
            val oz = -mesh.minZ - halfD
            // AABB-min and AABB-max in world space after the transform.
            val aMinX = (mesh.minX + ox) * s[0] + tx
            val aMaxX = (mesh.maxX + ox) * s[0] + tx
            val aMinY = (mesh.minY + oy) * s[1] + ty
            val aMaxY = (mesh.maxY + oy) * s[1] + ty
            val aMinZ = (mesh.minZ + oz) * s[2] + tz
            val aMaxZ = (mesh.maxZ + oz) * s[2] + tz
            return if (rayHitsAABB(
                    ray, minOf(aMinX, aMaxX), maxOf(aMinX, aMaxX),
                    minOf(aMinY, aMaxY), maxOf(aMinY, aMaxY),
                    minOf(aMinZ, aMaxZ), maxOf(aMinZ, aMaxZ),
                )) 0 else -1
        }
        return TrianglePicker.pick(
            positions,
            ray[0], ray[1], ray[2],
            ray[3], ray[4], ray[5]
        )
    }

    private fun rayHitsAABB(
        ray: FloatArray,
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        minZ: Float, maxZ: Float,
    ): Boolean = rayHitsAABBStatic(ray, minX, maxX, minY, maxY, minZ, maxZ)

    override fun handleActionCancel() {
        draggingIndex = -1
        // F66 — restore the selection highlight on cancel (parallels the
        // ACTION_UP and pointer-down paths). Without this, an interrupted
        // gesture (multi-touch, system overlay) would clear the selection
        // visual until the user re-tapped.
        renderer.highlightIndex = persistentSelectionIndex
        if (brushStrokeActive) {
            brushStrokeActive = false
            onBrushTouchAt?.invoke(-1f, -1f)
        }
        brushRotateActive = false
        brushRotateLastMidX = -1f
        brushRotateLastMidY = -1f
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
        val perSizes = renderer.perObjectSizes
        val usePerObject = perSizes != null && perSizes.size / 3 == count

        for (i in (0 until count).reversed()) {
            val ox = positions[i * 2]
            val oy = positions[i * 2 + 1]
            // perObjectSizes come from native getObjectBoundingBoxes() which includes instance
            // scale — already reflects user scale, so use directly. Single-object path uses
            // unscaled mesh.sizeX/Y and must multiply by modelScale.
            val sizeX = if (usePerObject) perSizes!![i * 3] else mesh.sizeX * s[0]
            val sizeY = if (usePerObject) perSizes!![i * 3 + 1] else mesh.sizeY * s[1]
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
