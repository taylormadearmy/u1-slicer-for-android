package com.u1.slicer.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class ModelRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    private val mainHandler = Handler(Looper.getMainLooper())
    var meshData: MeshData? = null
        private set
    private var modelShader: ShaderProgram? = null
    private var gridShader: ShaderProgram? = null
    private var textureShader: ShaderProgram? = null
    private var modelVAOs = IntArray(0)
    private var modelVBOs = IntArray(0)
    private var colorVBOs = IntArray(0)
    private var useVertexColorLoc = -1
    private var gridVAO = 0
    private var gridVertexCount = 0
    private var majorGridVAO = 0
    private var majorGridVertexCount = 0
    private var bedFillVAO = 0
    private var bedFillVertexCount = 0
    private var bedBorderVAO = 0
    private var logoVAO = 0
    private var logoTexture = 0
    private var boxVAO = 0
    private var boxVertexCount = 0

    // Model color (orange) — used when no per-instance colors are set
    private val modelColorDefault = floatArrayOf(0.91f, 0.48f, 0f, 1f)
    private val wipeTowerColor = floatArrayOf(1f, 0.76f, 0.03f, 0.7f)

    // F66 — selection-outline silhouette colour + thickness.
    // OUTLINE_EXPAND is in clip-space NDC units (vertex shader does
    // screen-space expansion), so visible thickness stays constant across
    // zoom levels. 0.020 NDC ≈ 11 px on a 1080-wide screen — visible from
    // bed-overview zoom and large enough to mask inverted-hull artifacts
    // (sharp-edge normal flips) when zoomed in close.
    private val OUTLINE_COLOR = floatArrayOf(1f, 0.65f, 0.1f, 1f)
    private val OUTLINE_EXPAND_NDC = 0.020f

    // Per-instance colors from extruder slot assignments (RGBA 0..1). When set, each instance
    // is tinted with its assigned extruder color; single-color models use the first entry.
    @Volatile var instanceColors: List<FloatArray>? = null

    // Instance positions for placement mode (XY pairs in mm, bed coordinates)
    // null = single instance at model's original position (no offset applied)
    @Volatile var instancePositions: FloatArray? = null

    // Per-object bounding box sizes: flat [sizeX0,sizeY0,sizeZ0, sizeX1,...].
    // When non-null and size matches instancePositions, hit test uses per-object sizes
    // instead of the merged mesh AABB (needed when objects from different files have
    // different footprints).
    @Volatile var perObjectSizes: FloatArray? = null

    // When true, the preview mesh vertices are already in world/bed coordinates (instance
    // transforms baked in by setObjectPositions). Draw the mesh ONCE at origin instead of
    // once per instancePositions entry — the per-entry loop would otherwise multiply the
    // N-object combined mesh by N positions, showing N² objects on screen.
    @Volatile var multiObjectMode: Boolean = false

    // Wipe tower placement (null = not shown)
    @Volatile var wipeTower: WipeTowerInfo? = null

    // Index of the instance/tower currently being dragged (-1=none, N=object index, instances.size=tower)
    @Volatile var highlightIndex: Int = -1

    // Model scale (from SlicerViewModel.ModelScale) — applied visually in draw calls
    // AND used by ModelViewerView.hitTest() for scale-aware hit detection.
    // @Volatile covers the reference only — always assign a new FloatArray, never mutate elements in-place.
    @Volatile var modelScale = floatArrayOf(1f, 1f, 1f)

    private var viewportWidth = 1
    private var viewportHeight = 1

    @Volatile
    var pendingMesh: MeshData? = null

    @Volatile
    var pendingClearMesh = false

    /** Pending per-triangle extruder index update. When set, the next [onDrawFrame] copies the
     *  bytes into [MeshData.extruderIndices] in place. Used by the AI Paint brush so we can
     *  repaint individual triangles without rebuilding the whole mesh. */
    @Volatile
    var pendingExtruderUpdate: ByteArray? = null

    @Volatile
    var preserveCameraOnNextMeshUpload = false

    @Volatile
    var onContentReady: (() -> Unit)? = null

    @Volatile
    private var pendingContentReadyDispatch = false

    // Set to true to trigger a camera re-centre on the next frame (e.g. after placement
    // positions arrive on the main thread after the mesh was already uploaded).
    @Volatile
    var pendingCameraReset = false

    data class WipeTowerInfo(val x: Float, val y: Float, val width: Float, val depth: Float)

    /**
     * Describes a contiguous vertex range in the (sorted) combined VBO belonging to one object,
     * along with that object's world-space bounding box. Used by [drawObjectRange] to apply an
     * independent model matrix per object so drag feedback is immediate (no mesh re-fetch needed).
     */
    data class ObjectMeshRange(
        val vertexStart: Int,
        val vertexCount: Int,
        val minX: Float, val maxX: Float,
        val minY: Float, val maxY: Float,
        val minZ: Float, val maxZ: Float,
    )

    // Per-object vertex ranges. Non-null when a sorted multi-object mesh has been uploaded.
    @Volatile var objectMeshRanges: List<ObjectMeshRange>? = null

    // Pending ranges applied atomically with the pending mesh on the GL thread.
    // hasPendingObjectMeshRanges=true means a change (possibly null=clear) is pending.
    @Volatile private var hasPendingObjectMeshRanges: Boolean = false
    @Volatile private var pendingObjectMeshRanges: List<ObjectMeshRange>? = null

    internal fun setPendingObjectMeshRanges(ranges: List<ObjectMeshRange>?) {
        pendingObjectMeshRanges = ranges
        hasPendingObjectMeshRanges = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.059f, 0.059f, 0.118f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)

        modelShader = ShaderProgram(context, "shaders/model.vert", "shaders/model.frag")
        gridShader = ShaderProgram(context, "shaders/grid.vert", "shaders/grid.frag")
        textureShader = ShaderProgram(context, "shaders/texture.vert", "shaders/texture.frag")

        useVertexColorLoc = modelShader!!.getUniformLocation("u_UseVertexColor")

        setupBedMesh()
        setupGrid()
        setupLogoTexture()
        setupBox()

        meshData?.let {
            uploadMeshBatches(it.batches)
            updateColorData(it)
        }

        // Initialize camera to plate-centred view immediately so the first frame is correct.
        // Without this, the camera starts at default (azimuth=0, elevation=30) and only snaps
        // to the bed view once the mesh loads, causing a visible flash on first open.
        resetCameraToDefaultView()
    }

    internal fun resetCameraToDefaultView() {
        camera.setTarget(135.0, 135.0, 0.0)
        camera.distance = 500.0
        camera.elevation = 62.0
        camera.azimuth = -90.0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        camera.updateProjectionMatrix(width, height)
    }

    // Pending recolor: set from main thread, consumed on GL thread
    @Volatile
    var pendingRecolor: List<FloatArray>? = null

    // Pending VBO refresh: re-uploads vertex buffer without recolor (used after recolorByZBands)
    @Volatile
    var pendingVboRefresh: Boolean = false

    // Pending camera state: written by main thread (applyCameraState, resetView),
    // consumed at top of onDrawFrame before matrix math.
    @Volatile
    var pendingCameraState: CameraViewState? = null

    override fun onDrawFrame(gl: GL10?) {
        pendingCameraState?.let { state ->
            camera.restore(state)
            pendingCameraState = null
        }

        if (pendingClearMesh) {
            pendingClearMesh = false
            deleteMeshBuffers()
            meshData?.release(com.u1.slicer.NativeLibrary())
            meshData = null
            objectMeshRanges = null
            highlightIndex = -1
        }

        pendingMesh?.let { mesh ->
            // B48: recolor BEFORE uploading when both arrive on the same frame,
            // so the initial glBufferData gets the recolored vertex data.
            pendingRecolor?.let { palette ->
                mesh.recolor(palette)
                pendingRecolor = null
            }
            if (this.meshData != null && isAppend(this.meshData!!.batches, mesh.batches)) {
                for (i in this.meshData!!.batches.size until mesh.batches.size) {
                    appendMeshBatch(mesh.batches[i])
                }
            } else {
                uploadMeshBatches(mesh.batches)
            }
            if (mesh.batches.any { it.colorBuffer != null }) {
                updateColorData(mesh)
            }
            meshData = mesh
            pendingMesh = null
            if (hasPendingObjectMeshRanges) {
                objectMeshRanges = pendingObjectMeshRanges
                pendingObjectMeshRanges = null
                hasPendingObjectMeshRanges = false
            }
            pendingCameraReset = !preserveCameraOnNextMeshUpload
            preserveCameraOnNextMeshUpload = false
            pendingContentReadyDispatch = true
        }

        // Process pending per-triangle extruder index update BEFORE recolor, so the recolor
        // step picks up the new indices when it looks up palette[index].
        if (pendingExtruderUpdate != null) {
            meshData?.let { mesh ->
                val update = pendingExtruderUpdate!!
                val totalTriangles = mesh.batches.sumOf { it.triangleCount }
                if (update.size == totalTriangles) {
                    var offset = 0
                    for (batch in mesh.batches) {
                        batch.materialIndices?.let { mat ->
                            mat.position(0)
                            mat.put(update, offset, batch.triangleCount)
                        }
                        offset += batch.triangleCount
                    }
                }
                pendingExtruderUpdate = null
            }
        }

        // Process pending recolor for existing mesh (no new mesh upload)
        if (pendingRecolor != null) {
            meshData?.let { mesh ->
                pendingRecolor?.let { palette ->
                    mesh.recolor(palette)
                    updateColorData(mesh)
                    pendingRecolor = null
                }
            }
        }

        // Re-upload vertex buffer after recolorByZBands (colors already written, just need VBO sync)
        if (pendingVboRefresh) {
            pendingVboRefresh = false
            meshData?.let { updateColorData(it) }
        }

        if (pendingCameraReset) {
            if (meshData != null) {
                pendingCameraReset = false
                resetCameraToDefaultView()
                camera.panX = 0.0
                camera.panY = 0.0
            }
        }

        camera.updateViewMatrix()
        if (viewportWidth > 0 && viewportHeight > 0) {
            camera.updateProjectionMatrix(viewportWidth, viewportHeight)
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawGrid()

        // Draw model instances
        meshData?.let { mesh ->
            val positions = instancePositions
            val colors = instanceColors
            if (multiObjectMode) {
                val ranges = objectMeshRanges
                if (ranges != null && positions != null && ranges.size == positions.size / 2) {
                    // Per-object rendering: each sub-range drawn at its current instancePositions[i].
                    // The drawObjectRange matrix centers on the range's own committed bounds and
                    // repositions to the live drag position — no mesh re-fetch needed during drag.
                    for (i in ranges.indices) {
                        val r = ranges[i]
                        val px = positions[i * 2]
                        val py = positions[i * 2 + 1]
                        val color = colors?.getOrNull(i) ?: colors?.getOrNull(0) ?: modelColorDefault
                        drawObjectRange(mesh, r, px, py, color)
                    }
                    // F66 — outline pass for the selected object. Drawn after
                    // the normal passes so the silhouette sits on top of the
                    // colour render. Uses front-face culling so only the
                    // back-faces of the expanded mesh are visible — the
                    // classic "inverted hull" outline technique.
                    if (highlightIndex in ranges.indices) {
                        val r = ranges[highlightIndex]
                        val px = positions[highlightIndex * 2]
                        val py = positions[highlightIndex * 2 + 1]
                        drawObjectOutline(mesh, r, px, py)
                    }
                } else {
                    // Fallback: no per-object ranges yet (pre-first fetch). Draw combined mesh
                    // at origin — it's already in world/bed coordinates from setObjectPositions.
                    drawModel(mesh, colors?.getOrNull(0) ?: modelColorDefault)
                }
            } else if (positions != null && positions.size >= 2) {
                val count = positions.size / 2
                for (i in 0 until count) {
                    val px = positions[i * 2]
                    val py = positions[i * 2 + 1]
                    val color = colors?.getOrNull(i) ?: colors?.getOrNull(0) ?: modelColorDefault
                    drawModelAt(mesh, px, py, color)
                }
                // F66 — outline pass for the selected instance.
                if (highlightIndex in 0 until count) {
                    val px = positions[highlightIndex * 2]
                    val py = positions[highlightIndex * 2 + 1]
                    drawInstanceOutline(mesh, px, py)
                }
            } else {
                val color = colors?.getOrNull(0) ?: modelColorDefault
                drawModel(mesh, color)
                // F66 — single-mesh outline. highlightIndex 0 means the lone object.
                if (highlightIndex == 0) drawSingleMeshOutline(mesh)
            }
        }

        // Draw wipe tower
        wipeTower?.let { tower ->
            // F66 review-2026-05-30 P0: capture @Volatile instancePositions once
            // so the !!.size read can't NPE if the UI thread nulls it between
            // the null-check and the size read.
            val pos = instancePositions
            val highlighted = pos != null && highlightIndex == (pos.size / 2)
            drawWipeTower(tower, highlighted)
        }
        if (pendingContentReadyDispatch) {
            pendingContentReadyDispatch = false
            onContentReady?.let { callback ->
                mainHandler.post { callback() }
            }
        }
    }

    private fun deleteMeshBuffers() {
        if (modelVAOs.isNotEmpty()) {
            GLES30.glDeleteVertexArrays(modelVAOs.size, modelVAOs, 0)
            modelVAOs = IntArray(0)
        }
        if (modelVBOs.isNotEmpty()) {
            GLES30.glDeleteBuffers(modelVBOs.size, modelVBOs, 0)
            modelVBOs = IntArray(0)
        }
        if (colorVBOs.isNotEmpty()) {
            GLES30.glDeleteBuffers(colorVBOs.size, colorVBOs, 0)
            colorVBOs = IntArray(0)
        }
    }

    private fun isAppend(oldBatches: List<NativeRenderBatch>, newBatches: List<NativeRenderBatch>): Boolean {
        if (newBatches.size <= oldBatches.size) return false
        for (i in oldBatches.indices) {
            if (oldBatches[i] !== newBatches[i]) return false
        }
        return true
    }

    private fun uploadMeshBatches(batches: List<NativeRenderBatch>) {
        deleteMeshBuffers()

        val count = batches.size
        if (count == 0) return

        modelVAOs = IntArray(count)
        modelVBOs = IntArray(count)
        colorVBOs = IntArray(count)
        GLES30.glGenVertexArrays(count, modelVAOs, 0)
        GLES30.glGenBuffers(count, modelVBOs, 0)
        GLES30.glGenBuffers(count, colorVBOs, 0)

        for (i in 0 until count) {
            setupBatchVao(modelVAOs[i], modelVBOs[i], batches[i])
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun appendMeshBatch(batch: NativeRenderBatch) {
        val oldSize = modelVAOs.size
        val newVAOs = IntArray(oldSize + 1)
        val newVBOs = IntArray(oldSize + 1)
        val newColorVBOs = IntArray(oldSize + 1)
        System.arraycopy(modelVAOs, 0, newVAOs, 0, oldSize)
        System.arraycopy(modelVBOs, 0, newVBOs, 0, oldSize)
        System.arraycopy(colorVBOs, 0, newColorVBOs, 0, oldSize)

        GLES30.glGenVertexArrays(1, newVAOs, oldSize)
        GLES30.glGenBuffers(1, newVBOs, oldSize)
        GLES30.glGenBuffers(1, newColorVBOs, oldSize)

        setupBatchVao(newVAOs[oldSize], newVBOs[oldSize], batch)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)

        modelVAOs = newVAOs
        modelVBOs = newVBOs
        colorVBOs = newColorVBOs
    }

    private fun setupBatchVao(vao: Int, vbo: Int, batch: NativeRenderBatch) {
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)

        val batchBytes = batch.triangleCount * 3 * MeshData.BYTES_PER_VERTEX
        batch.geometry.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batchBytes, batch.geometry, GLES30.GL_DYNAMIC_DRAW)

        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, MeshData.BYTES_PER_VERTEX, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, MeshData.BYTES_PER_VERTEX, 12)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, MeshData.BYTES_PER_VERTEX, 24)
        GLES30.glEnableVertexAttribArray(2)
    }

    private fun updateColorData(mesh: MeshData) {
        if (modelVAOs.isEmpty() || modelVAOs.size != mesh.batches.size) return
        if (colorVBOs.isEmpty() || colorVBOs.size != mesh.batches.size) return
        
        for (i in mesh.batches.indices) {
            val batch = mesh.batches[i]
            val cb = batch.colorBuffer ?: continue
            
            GLES30.glBindVertexArray(modelVAOs[i])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, colorVBOs[i])
            
            cb.position(0)
            val batchBytes = batch.triangleCount * 3 * 16
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batchBytes, cb, GLES30.GL_DYNAMIC_DRAW)
            
            GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(2)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawTriangleRange(mesh: MeshData, startTriangle: Int, triangleCount: Int) {
        if (triangleCount <= 0) return
        var remaining = triangleCount
        var currentTri = startTriangle

        while (remaining > 0) {
            var cumTri = 0
            var batchIdx = -1
            for (i in mesh.batches.indices) {
                val c = mesh.batches[i].triangleCount
                if (currentTri >= cumTri && currentTri < cumTri + c) {
                    batchIdx = i
                    break
                }
                cumTri += c
            }
            if (batchIdx == -1 || batchIdx >= modelVAOs.size) break

            val localTri = currentTri - cumTri
            val batchCount = mesh.batches[batchIdx].triangleCount
            val drawCount = minOf(remaining, batchCount - localTri)

            GLES30.glBindVertexArray(modelVAOs[batchIdx])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, localTri * 3, drawCount * 3)

            remaining -= drawCount
            currentTri += drawCount
        }
        GLES30.glBindVertexArray(0)
    }

    private fun drawTriangleRanges(mesh: MeshData, triangleStart: Int, triangleCount: Int) {
        if (triangleCount <= 0) return
        val batchRanges = mesh.batchRanges
        if (batchRanges.isNullOrEmpty()) {
            drawTriangleRange(mesh, triangleStart, triangleCount)
            return
        }

        val triangleEnd = triangleStart + triangleCount
        var coalescedStart = -1
        var coalescedEnd = -1
        for (range in batchRanges) {
            val start = maxOf(triangleStart, range.first)
            val endExclusive = minOf(triangleEnd, range.last + 1)
            if (endExclusive <= start) continue
            if (coalescedStart < 0) {
                coalescedStart = start
                coalescedEnd = endExclusive
            } else if (start <= coalescedEnd) {
                coalescedEnd = maxOf(coalescedEnd, endExclusive)
            } else {
                drawTriangleRange(mesh, coalescedStart, coalescedEnd - coalescedStart)
                coalescedStart = start
                coalescedEnd = endExclusive
            }
        }
        if (coalescedStart >= 0) {
            drawTriangleRange(mesh, coalescedStart, coalescedEnd - coalescedStart)
        }
    }

    /**
     * F95: vertex count of the opaque model-part block. Triangles at/after
     * [MeshData.modifierBlockStartTriangle] are the translucent negative/modifier-volume
     * trailing block; the opaque pass stops before them. Returns the full count when there's
     * no modifier block.
     */
    private fun modelPartVertexCount(mesh: MeshData): Int =
        mesh.modifierBlockStartTriangle?.let { (it * 3).coerceIn(0, mesh.vertexCount) }
            ?: mesh.vertexCount

    /**
     * F95: second draw pass for the trailing negative/modifier-volume block. Caller must have
     * already set the shader program, model matrix, colour uniforms, and bound the VAO (the
     * modifier triangles share the model's coordinate space, so the same matrix positions them
     * correctly). Drawn with alpha blending and depth-write disabled so the translucent cut/
     * modifier region shows through the solid body without occluding it (desktop-Orca parity).
     */
    private fun drawModifierTail(mesh: MeshData, modelPartVtx: Int) {
        if (mesh.modifierBlockStartTriangle == null) return
        val count = mesh.vertexCount - modelPartVtx
        if (count <= 0) return
        // B140: negative/modifier volumes are almost always INSIDE the model (joint-clearance
        // cutters, internal voids). With depth-test on they'd be occluded by the opaque body
        // (drawn first, depth written) and never appear — which is exactly what made the F95
        // feature invisible in v2.10.16. Draw the modifier block as a translucent x-ray overlay:
        // depth-test OFF so it shows THROUGH the solid body (desktop OrcaSlicer/PrusaSlicer
        // behaviour), depth-write OFF so it never occludes, alpha blended. Restore both after.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        drawTriangleRanges(mesh, modelPartVtx / 3, count / 3)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawModel(mesh: MeshData, color: FloatArray = modelColorDefault) {
        val shader = modelShader ?: return
        shader.use()

        val s = modelScale
        if (s[0] != 1f || s[1] != 1f || s[2] != 1f) {
            val modelMatrix = FloatArray(16)
            Matrix.setIdentityM(modelMatrix, 0)
            val cx = (mesh.minX + mesh.maxX) / 2f
            val cy = (mesh.minY + mesh.maxY) / 2f
            val cz = (mesh.minZ + mesh.maxZ) / 2f
            Matrix.translateM(modelMatrix, 0, cx, cy, cz)
            Matrix.scaleM(modelMatrix, 0, s[0], s[1], s[2])
            Matrix.translateM(modelMatrix, 0, -cx, -cy, -cz)
            camera.computeMVP(modelMatrix)
        } else {
            camera.computeMVP()
        }
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)
        GLES30.glUniform4fv(shader.getUniformLocation("u_Color"), 1, color, 0)
        GLES30.glUniform1f(useVertexColorLoc, if (mesh.hasPerVertexColor) 1f else 0f)
        // F66: drawModel is the single-mesh fallback path with no per-object
        // selection; clear the highlight uniform so a previous draw's tint
        // doesn't leak into this one.
        GLES30.glUniform4f(shader.getUniformLocation("u_Highlight"), 0f, 0f, 0f, 0f)
        val modelPartVtx = modelPartVertexCount(mesh)
        drawTriangleRanges(mesh, 0, modelPartVtx / 3)
        drawModifierTail(mesh, modelPartVtx)
    }

    private fun drawModelAt(mesh: MeshData, x: Float, y: Float,
                            baseColor: FloatArray = modelColorDefault) {
        val shader = modelShader ?: return
        shader.use()

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        val s = modelScale
        val halfW = (mesh.maxX - mesh.minX) / 2f
        val halfH = (mesh.maxY - mesh.minY) / 2f
        val halfD = (mesh.maxZ - mesh.minZ) / 2f
        Matrix.translateM(modelMatrix, 0, x + halfW * s[0], y + halfH * s[1], halfD * s[2])
        Matrix.scaleM(modelMatrix, 0, s[0], s[1], s[2])
        Matrix.translateM(modelMatrix, 0, -mesh.minX - halfW, -mesh.minY - halfH, -mesh.minZ - halfD)

        camera.computeMVP(modelMatrix)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)
        GLES30.glUniform4fv(shader.getUniformLocation("u_Color"), 1, baseColor, 0)
        GLES30.glUniform1f(useVertexColorLoc, if (mesh.hasPerVertexColor) 1f else 0f)
        // F66 — selection highlight migrated from u_Highlight tint to a
        // dedicated outline pass (see drawObjectOutline). Clear both
        // uniforms so a stale value from a previous draw doesn't leak.
        GLES30.glUniform4f(shader.getUniformLocation("u_Highlight"), 0f, 0f, 0f, 0f)
        GLES30.glUniform1f(shader.getUniformLocation("u_OutlineExpand"), 0f)

        val modelPartVtx = modelPartVertexCount(mesh)
        drawTriangleRanges(mesh, 0, modelPartVtx / 3)
        drawModifierTail(mesh, modelPartVtx)
    }

    private fun drawObjectRange(
        mesh: MeshData, range: ObjectMeshRange,
        x: Float, y: Float, baseColor: FloatArray,
    ) {
        val shader = modelShader ?: return
        shader.use()

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        val s = modelScale
        val halfW = (range.maxX - range.minX) / 2f
        val halfH = (range.maxY - range.minY) / 2f
        val halfD = (range.maxZ - range.minZ) / 2f
        Matrix.translateM(modelMatrix, 0, x + halfW * s[0], y + halfH * s[1], halfD * s[2])
        Matrix.scaleM(modelMatrix, 0, s[0], s[1], s[2])
        Matrix.translateM(modelMatrix, 0, -range.minX - halfW, -range.minY - halfH, -range.minZ - halfD)

        camera.computeMVP(modelMatrix)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)
        GLES30.glUniform4fv(shader.getUniformLocation("u_Color"), 1, baseColor, 0)
        GLES30.glUniform1f(useVertexColorLoc, if (mesh.hasPerVertexColor) 1f else 0f)
        GLES30.glUniform4f(shader.getUniformLocation("u_Highlight"), 0f, 0f, 0f, 0f)
        GLES30.glUniform1f(shader.getUniformLocation("u_OutlineExpand"), 0f)

        drawTriangleRanges(mesh, range.vertexStart / 3, range.vertexCount / 3)
    }

    /**
     * F66 — selection outline via the classic "inverted hull" technique.
     * Same model matrix as [drawObjectRange]; the vertex shader pushes
     * each vertex outward by `u_OutlineExpand` along its model-space
     * normal, and front-face culling means only the back-faces of the
     * expanded mesh are visible — producing a thin silhouette around the
     * object that's visible regardless of the surface colour.
     */
    private fun drawObjectOutline(
        mesh: MeshData, range: ObjectMeshRange,
        x: Float, y: Float,
    ) {
        val shader = modelShader ?: return
        shader.use()

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        val s = modelScale
        val halfW = (range.maxX - range.minX) / 2f
        val halfH = (range.maxY - range.minY) / 2f
        val halfD = (range.maxZ - range.minZ) / 2f
        Matrix.translateM(modelMatrix, 0, x + halfW * s[0], y + halfH * s[1], halfD * s[2])
        Matrix.scaleM(modelMatrix, 0, s[0], s[1], s[2])
        Matrix.translateM(modelMatrix, 0, -range.minX - halfW, -range.minY - halfH, -range.minZ - halfD)

        camera.computeMVP(modelMatrix)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)
        // Expand is in NDC; vertex shader handles screen-space projection
        // and compensates for perspective divide. Visible thickness stays
        // constant across zoom because the offset is post-MVP.
        GLES30.glUniform1f(shader.getUniformLocation("u_OutlineExpand"), OUTLINE_EXPAND_NDC)
        GLES30.glUniform4fv(shader.getUniformLocation("u_OutlineColor"), 1, OUTLINE_COLOR, 0)
        // useVertexColor doesn't matter when u_OutlineExpand > 0 (fragment
        // shader bails out to u_OutlineColor) but set 0 defensively.
        GLES30.glUniform1f(useVertexColorLoc, 0f)

        GLES30.glCullFace(GLES30.GL_FRONT)
        drawTriangleRanges(mesh, range.vertexStart / 3, range.vertexCount / 3)
        GLES30.glCullFace(GLES30.GL_BACK)

        // Clear so subsequent normal draws aren't affected.
        GLES30.glUniform1f(shader.getUniformLocation("u_OutlineExpand"), 0f)
    }

    private fun drawInstanceOutline(mesh: MeshData, x: Float, y: Float) {
        val shader = modelShader ?: return
        shader.use()
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        val s = modelScale
        val halfW = (mesh.maxX - mesh.minX) / 2f
        val halfH = (mesh.maxY - mesh.minY) / 2f
        val halfD = (mesh.maxZ - mesh.minZ) / 2f
        Matrix.translateM(modelMatrix, 0, x + halfW * s[0], y + halfH * s[1], halfD * s[2])
        Matrix.scaleM(modelMatrix, 0, s[0], s[1], s[2])
        Matrix.translateM(modelMatrix, 0, -mesh.minX - halfW, -mesh.minY - halfH, -mesh.minZ - halfD)
        camera.computeMVP(modelMatrix)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)
        GLES30.glUniform1f(shader.getUniformLocation("u_OutlineExpand"), OUTLINE_EXPAND_NDC)
        GLES30.glUniform4fv(shader.getUniformLocation("u_OutlineColor"), 1, OUTLINE_COLOR, 0)
        GLES30.glUniform1f(useVertexColorLoc, 0f)
        GLES30.glCullFace(GLES30.GL_FRONT)
        drawTriangleRanges(mesh, 0, modelPartVertexCount(mesh) / 3)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glUniform1f(shader.getUniformLocation("u_OutlineExpand"), 0f)
    }

    private fun drawSingleMeshOutline(mesh: MeshData) {
        val shader = modelShader ?: return
        shader.use()
        // drawModel uses identity (or just modelScale-centred) — mirror it.
        val s = modelScale
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        if (s[0] != 1f || s[1] != 1f || s[2] != 1f) {
            val cx = (mesh.minX + mesh.maxX) / 2f
            val cy = (mesh.minY + mesh.maxY) / 2f
            val cz = (mesh.minZ + mesh.maxZ) / 2f
            Matrix.translateM(modelMatrix, 0, cx, cy, cz)
            Matrix.scaleM(modelMatrix, 0, s[0], s[1], s[2])
            Matrix.translateM(modelMatrix, 0, -cx, -cy, -cz)
        }
        camera.computeMVP(modelMatrix)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)
        GLES30.glUniform1f(shader.getUniformLocation("u_OutlineExpand"), OUTLINE_EXPAND_NDC)
        GLES30.glUniform4fv(shader.getUniformLocation("u_OutlineColor"), 1, OUTLINE_COLOR, 0)
        GLES30.glUniform1f(useVertexColorLoc, 0f)
        GLES30.glCullFace(GLES30.GL_FRONT)
        drawTriangleRanges(mesh, 0, modelPartVertexCount(mesh) / 3)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glUniform1f(shader.getUniformLocation("u_OutlineExpand"), 0f)
    }

    private fun drawWipeTower(tower: WipeTowerInfo, highlighted: Boolean) {
        val shader = modelShader ?: return
        shader.use()

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, tower.x, tower.y, 0f)
        Matrix.scaleM(modelMatrix, 0, tower.width, tower.depth, 30f) // 30mm tall

        camera.computeMVP(modelMatrix)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

        GLES30.glUniform4fv(shader.getUniformLocation("u_Color"), 1, wipeTowerColor, 0)
        GLES30.glUniform1f(useVertexColorLoc, 0f)
        GLES30.glVertexAttrib4f(2, 1f, 1f, 1f, 1f)
        // F66: wipe tower drag-highlight via u_Highlight (was previously a
        // hard u_Color swap which worked for the wipe tower since it has no
        // per-vertex colour, but unifies the code path with model highlight).
        if (highlighted) {
            GLES30.glUniform4f(shader.getUniformLocation("u_Highlight"), 1f, 0.85f, 0.2f, 0.6f)
        } else {
            GLES30.glUniform4f(shader.getUniformLocation("u_Highlight"), 0f, 0f, 0f, 0f)
        }

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glBindVertexArray(boxVAO)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, boxVertexCount)
        GLES30.glBindVertexArray(0)

        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /**
     * Unproject a screen touch (px, py) onto a horizontal plane at the given Z height.
     * Defaults to Z=0 (bed plane). Pass planeZ=mesh.sizeZ/2 for hit detection at the
     * model's visual midpoint (so touches on the model's visible face register correctly
     * with a camera elevated at ~35°).
     *
     * IMPORTANT: Called from the UI thread. Must NOT write shared camera FloatArrays.
     */
    fun screenToBed(screenX: Float, screenY: Float, planeZ: Float = 0f): FloatArray? {
        // Build view matrix locally without touching camera's shared arrays
        val radAz = Math.toRadians(camera.azimuth.toDouble())
        val radEl = Math.toRadians(camera.elevation.toDouble())
        val eyeX = (camera.targetX + camera.panX + camera.distance * cos(radEl) * cos(radAz)).toFloat()
        val eyeY = (camera.targetY + camera.panY + camera.distance * cos(radEl) * sin(radAz)).toFloat()
        val eyeZ = (camera.targetZ + camera.distance * sin(radEl)).toFloat()
        val localView = FloatArray(16)
        Matrix.setLookAtM(localView, 0, eyeX, eyeY, eyeZ,
            (camera.targetX + camera.panX).toFloat(), (camera.targetY + camera.panY).toFloat(), camera.targetZ.toFloat(),
            0f, 0f, 1f)

        // Build projection matrix locally
        val localProj = FloatArray(16)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(localProj, 0, 45f, aspect,
            (camera.distance * 0.01).coerceAtLeast(0.1).toFloat(), (camera.distance * 10.0).toFloat())

        val invertedVP = FloatArray(16)
        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, localProj, 0, localView, 0)
        if (!Matrix.invertM(invertedVP, 0, vpMatrix, 0)) return null

        // NDC coords
        val ndcX = (2f * screenX / viewportWidth) - 1f
        val ndcY = 1f - (2f * screenY / viewportHeight) // flip Y

        // Near point
        val nearW = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val nearWorld = FloatArray(4)
        Matrix.multiplyMV(nearWorld, 0, invertedVP, 0, nearW, 0)
        if (nearWorld[3] == 0f) return null
        val nx = nearWorld[0] / nearWorld[3]
        val ny = nearWorld[1] / nearWorld[3]
        val nz = nearWorld[2] / nearWorld[3]

        // Far point
        val farW = floatArrayOf(ndcX, ndcY, 1f, 1f)
        val farWorld = FloatArray(4)
        Matrix.multiplyMV(farWorld, 0, invertedVP, 0, farW, 0)
        if (farWorld[3] == 0f) return null
        val fx = farWorld[0] / farWorld[3]
        val fy = farWorld[1] / farWorld[3]
        val fz = farWorld[2] / farWorld[3]

        // Ray direction
        val dx = fx - nx; val dy = fy - ny; val dz = fz - nz
        if (kotlin.math.abs(dz) < 1e-6f) return null // parallel to plane

        // Intersect with Z=planeZ plane
        val t = (planeZ - nz) / dz
        return floatArrayOf(nx + dx * t, ny + dy * t)
    }

    /**
     * Unproject a screen-space tap into a world-space ray. Returns [ox,oy,oz, dx,dy,dz]
     * (origin + un-normalised direction) or null if the unprojection failed. Mirrors the
     * near/far-point logic in [screenToBed] but stops short of intersecting a plane.
     */
    fun screenToRay(screenX: Float, screenY: Float): FloatArray? {
        val radAz = Math.toRadians(camera.azimuth.toDouble())
        val radEl = Math.toRadians(camera.elevation.toDouble())
        val eyeX = (camera.targetX + camera.panX + camera.distance * cos(radEl) * cos(radAz)).toFloat()
        val eyeY = (camera.targetY + camera.panY + camera.distance * cos(radEl) * sin(radAz)).toFloat()
        val eyeZ = (camera.targetZ + camera.distance * sin(radEl)).toFloat()
        val localView = FloatArray(16)
        Matrix.setLookAtM(localView, 0, eyeX, eyeY, eyeZ,
            (camera.targetX + camera.panX).toFloat(), (camera.targetY + camera.panY).toFloat(), camera.targetZ.toFloat(),
            0f, 0f, 1f)

        val localProj = FloatArray(16)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(localProj, 0, 45f, aspect,
            (camera.distance * 0.01).coerceAtLeast(0.1).toFloat(), (camera.distance * 10.0).toFloat())

        val invertedVP = FloatArray(16)
        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, localProj, 0, localView, 0)
        if (!Matrix.invertM(invertedVP, 0, vpMatrix, 0)) return null

        val ndcX = (2f * screenX / viewportWidth) - 1f
        val ndcY = 1f - (2f * screenY / viewportHeight)

        val nearW = floatArrayOf(ndcX, ndcY, -1f, 1f)
        val nearWorld = FloatArray(4)
        Matrix.multiplyMV(nearWorld, 0, invertedVP, 0, nearW, 0)
        if (nearWorld[3] == 0f) return null
        val nx = nearWorld[0] / nearWorld[3]
        val ny = nearWorld[1] / nearWorld[3]
        val nz = nearWorld[2] / nearWorld[3]

        val farW = floatArrayOf(ndcX, ndcY, 1f, 1f)
        val farWorld = FloatArray(4)
        Matrix.multiplyMV(farWorld, 0, invertedVP, 0, farW, 0)
        if (farWorld[3] == 0f) return null
        val fx = farWorld[0] / farWorld[3]
        val fy = farWorld[1] / farWorld[3]
        val fz = farWorld[2] / farWorld[3]

        return floatArrayOf(nx, ny, nz, fx - nx, fy - ny, fz - nz)
    }

    private fun setupBox() {
        // Unit cube (0,0,0)→(1,1,1) with normals for Gouraud shading
        val v = floatArrayOf(
            // Front face (z=1)
            0f,0f,1f, 0f,0f,1f,  1f,0f,1f, 0f,0f,1f,  1f,1f,1f, 0f,0f,1f,
            0f,0f,1f, 0f,0f,1f,  1f,1f,1f, 0f,0f,1f,  0f,1f,1f, 0f,0f,1f,
            // Back face (z=0)
            1f,0f,0f, 0f,0f,-1f,  0f,0f,0f, 0f,0f,-1f,  0f,1f,0f, 0f,0f,-1f,
            1f,0f,0f, 0f,0f,-1f,  0f,1f,0f, 0f,0f,-1f,  1f,1f,0f, 0f,0f,-1f,
            // Right face (x=1)
            1f,0f,1f, 1f,0f,0f,  1f,0f,0f, 1f,0f,0f,  1f,1f,0f, 1f,0f,0f,
            1f,0f,1f, 1f,0f,0f,  1f,1f,0f, 1f,0f,0f,  1f,1f,1f, 1f,0f,0f,
            // Left face (x=0)
            0f,0f,0f, -1f,0f,0f,  0f,0f,1f, -1f,0f,0f,  0f,1f,1f, -1f,0f,0f,
            0f,0f,0f, -1f,0f,0f,  0f,1f,1f, -1f,0f,0f,  0f,1f,0f, -1f,0f,0f,
            // Top face (y=1)
            0f,1f,1f, 0f,1f,0f,  1f,1f,1f, 0f,1f,0f,  1f,1f,0f, 0f,1f,0f,
            0f,1f,1f, 0f,1f,0f,  1f,1f,0f, 0f,1f,0f,  0f,1f,0f, 0f,1f,0f,
            // Bottom face (y=0)
            0f,0f,0f, 0f,-1f,0f,  1f,0f,0f, 0f,-1f,0f,  1f,0f,1f, 0f,-1f,0f,
            0f,0f,0f, 0f,-1f,0f,  1f,0f,1f, 0f,-1f,0f,  0f,0f,1f, 0f,-1f,0f,
        )
        boxVertexCount = v.size / 6

        val buf = ByteBuffer.allocateDirect(v.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(v); buf.flip()

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        boxVAO = vaos[0]

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)

        GLES30.glBindVertexArray(boxVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glBindVertexArray(0)
    }

    private fun setupBedMesh() {
        // Parse the U1 bed STL (binary format) from assets.
        // The STL is centred at (0,0); our print area is (0..270, 0..270) centred at (135,135).
        // Translate by (+135, +135). Flatten to Z=0 so grid lines at Z=0.1+ sit flush above.
        val verts = mutableListOf<Float>()
        try {
            context.assets.open("bed/u1_bed.stl").use { stream ->
                val bytes = stream.readBytes()
                val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.position(80) // skip header
                val triCount = buf.int
                repeat(triCount) {
                    buf.position(buf.position() + 12) // skip normal
                    repeat(3) {
                        val x = buf.float + 135f
                        val y = buf.float + 135f
                        buf.float // discard original Z — flatten to Z=0
                        verts.add(x); verts.add(y); verts.add(0f)
                    }
                    buf.position(buf.position() + 2) // skip attribute
                }
            }
        } catch (e: Exception) {
            // Fallback: simple quad if STL fails to load
            verts.addAll(listOf(0f,0f,0f, 270f,0f,0f, 270f,270f,0f,
                                0f,0f,0f, 270f,270f,0f, 0f,270f,0f))
        }

        bedFillVertexCount = verts.size / 3
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts.toFloatArray()); buf.flip()

        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); bedFillVAO = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0)
        GLES30.glBindVertexArray(bedFillVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
    }

    private fun setupLogoTexture() {
        // Render "snapmaker" text to a bitmap, upload as GL texture
        val bmpW = 512; val bmpH = 128
        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = bmpH * 0.55f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
        }
        canvas.drawText("snapmaker", bmpW / 2f, bmpH * 0.72f, paint)

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        logoTexture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, logoTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        // Logo quad: 150mm wide × 37mm tall, centered on bed at (135, 135), Z=0
        val lx = 135f - 75f; val rx = 135f + 75f
        val ly = 135f - 18.5f; val ry = 135f + 18.5f
        val z = 0f
        // position (xyz) + texcoord (uv) — stride 20 bytes
        val verts = floatArrayOf(
            lx, ly, z,  0f, 1f,
            rx, ly, z,  1f, 1f,
            rx, ry, z,  1f, 0f,
            lx, ry, z,  0f, 0f
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts); buf.flip()

        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); logoVAO = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0)
        GLES30.glBindVertexArray(logoVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glBindVertexArray(0)
    }

    private fun setupGrid() {
        val bedW = 270f
        val bedH = 270f

        // Minor grid lines every 10mm — all at Z=0 (polygon offset pushes bed behind)
        val minorLines = mutableListOf<Float>()
        var x = 0f
        while (x <= bedW) {
            minorLines.addAll(listOf(x, 0f, 0f, x, bedH, 0f))
            x += 10f
        }
        var y = 0f
        while (y <= bedH) {
            minorLines.addAll(listOf(0f, y, 0f, bedW, y, 0f))
            y += 10f
        }
        gridVertexCount = minorLines.size / 3

        val buf = ByteBuffer.allocateDirect(minorLines.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(minorLines.toFloatArray()); buf.flip()

        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); gridVAO = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0)
        GLES30.glBindVertexArray(gridVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, minorLines.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)

        // Major grid lines every 50mm — also Z=0
        val majorLines = mutableListOf<Float>()
        for (v in listOf(0f, 50f, 100f, 150f, 200f, 250f, 270f)) {
            majorLines.addAll(listOf(v, 0f, 0f, v, bedH, 0f))
            majorLines.addAll(listOf(0f, v, 0f, bedW, v, 0f))
        }
        majorGridVertexCount = majorLines.size / 3

        val majBuf = ByteBuffer.allocateDirect(majorLines.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        majBuf.put(majorLines.toFloatArray()); majBuf.flip()

        val majVaos = IntArray(1); GLES30.glGenVertexArrays(1, majVaos, 0); majorGridVAO = majVaos[0]
        val majVbos = IntArray(1); GLES30.glGenBuffers(1, majVbos, 0)
        GLES30.glBindVertexArray(majorGridVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, majVbos[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, majorLines.size * 4, majBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)

        // Bed border at Z=0
        val border = floatArrayOf(
            0f, 0f, 0f,    bedW, 0f, 0f,
            bedW, 0f, 0f,  bedW, bedH, 0f,
            bedW, bedH, 0f, 0f, bedH, 0f,
            0f, bedH, 0f,  0f, 0f, 0f
        )
        val borderBuf = ByteBuffer.allocateDirect(border.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        borderBuf.put(border); borderBuf.flip()

        val bVaos = IntArray(1); GLES30.glGenVertexArrays(1, bVaos, 0); bedBorderVAO = bVaos[0]
        val bVbos = IntArray(1); GLES30.glGenBuffers(1, bVbos, 0)
        GLES30.glBindVertexArray(bedBorderVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bVbos[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, border.size * 4, borderBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
    }

    private fun drawGrid() {
        val shader = gridShader ?: return
        shader.use()
        camera.computeMVP()
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)

        // 1. U1 bed mesh — polygon offset pushes it behind lines/points at same Z,
        //    eliminating z-fighting artifacts with the grid lines.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2f, 2f)
        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.22f, 0.25f, 0.28f, 1f)
        GLES30.glBindVertexArray(bedFillVAO)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, bedFillVertexCount)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)

        // 2. Minor grid lines (10mm) — visible gray
        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.26f, 0.30f, 0.34f, 1f)
        GLES30.glBindVertexArray(gridVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridVertexCount)
        GLES30.glBindVertexArray(0)

        // 3. Major grid lines (50mm) — brighter
        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.38f, 0.44f, 0.50f, 1f)
        GLES30.glBindVertexArray(majorGridVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, majorGridVertexCount)
        GLES30.glBindVertexArray(0)

        // 4. Bed border — bright highlight
        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.55f, 0.62f, 0.70f, 1f)
        GLES30.glBindVertexArray(bedBorderVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 8)
        GLES30.glBindVertexArray(0)

        // 5. Snapmaker logo — blended text texture
        val texShader = textureShader ?: return
        texShader.use()
        GLES30.glUniformMatrix4fv(texShader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniform1i(texShader.getUniformLocation("u_Texture"), 0)
        GLES30.glUniform1f(texShader.getUniformLocation("u_Alpha"), 0.18f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1f, 1f) // slightly behind grid lines, in front of bed
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, logoTexture)
        GLES30.glBindVertexArray(logoVAO)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    companion object {
        /**
         * Splits a combined world-space mesh into per-object vertex ranges for independent rendering.
         *
         * Returns a new [MeshData] with vertices sorted (object 0 first, object 1 next, …) and a
         * matching list of [ObjectMeshRange] entries describing each object's vertex range and
         * world-space bounding box. The bounding boxes let [drawObjectRange] compute the correct
         * centering matrix independently of the combined mesh bounds.
         *
         * Triangles are assigned to the nearest object centre (by XY centroid). For well-separated
         * objects this is exact; for touching/overlapping objects it degrades gracefully to
         * a best-guess split that at least prevents the worst visual artefacts.
         */
        fun splitMeshByObjects(
            mesh: MeshData,
            positions: FloatArray,
            sizes: FloatArray,
        ): Pair<MeshData, List<ObjectMeshRange>>? {
            val objectCount = positions.size / 2
            if (objectCount < 2 || mesh.vertexCount == 0) return null
            // B132c: defensive guard. The caller's gate should already ensure
            // positions.size/2 == sizes.size/3, but tolerate a mismatch by
            // returning null rather than ArrayIndexOutOfBoundsException —
            // the renderer's fallback (drawModel) handles a null splitResult.
            if (sizes.size < objectCount * 3) return null
            val fpp = MeshData.FLOATS_PER_VERTEX
            val triCount = mesh.vertexCount / 3

            // Classify each triangle using AABB containment first, nearest centre as fallback.
            // Pure nearest-centre misclassifies edge triangles when a neighbour's centre is
            // closer than the owning object's centre (causes fragment-sticks-to-wrong-object
            // visual bug during drag). Objects on the bed don't overlap, so AABB containment
            // is unambiguous for the vast majority of triangles.
            val triObjects = IntArray(triCount)
            var globalTri = 0
            for (batch in mesh.batches) {
                val buf = batch.geometry
                for (tri in 0 until batch.triangleCount) {
                    val b = tri * 3 * fpp
                    val cx = (buf.get(b) + buf.get(b + fpp) + buf.get(b + fpp * 2)) / 3f
                    val cy = (buf.get(b + 1) + buf.get(b + fpp + 1) + buf.get(b + fpp * 2 + 1)) / 3f

                    // First pass: unambiguous AABB containment
                    var aabbMatch = -1
                    for (i in 0 until objectCount) {
                        val minX = positions[i * 2]
                        val minY = positions[i * 2 + 1]
                        if (cx >= minX && cx <= minX + sizes[i * 3] &&
                            cy >= minY && cy <= minY + sizes[i * 3 + 1]
                        ) {
                            if (aabbMatch == -1) aabbMatch = i
                            else { aabbMatch = -2; break } // overlapping AABBs — use fallback
                        }
                    }
                    if (aabbMatch >= 0) { triObjects[globalTri] = aabbMatch; globalTri++; continue }

                    // Fallback: nearest centre (gap triangles or overlapping AABBs)
                    var bestObj = 0; var bestDist = Float.MAX_VALUE
                    for (i in 0 until objectCount) {
                        val ox = positions[i * 2] + sizes[i * 3] / 2f
                        val oy = positions[i * 2 + 1] + sizes[i * 3 + 1] / 2f
                        val d = (cx - ox) * (cx - ox) + (cy - oy) * (cy - oy)
                        if (d < bestDist) { bestDist = d; bestObj = i }
                    }
                    triObjects[globalTri] = bestObj
                    globalTri++
                }
            }

            // Group triangle indices by object
            val triLists = Array(objectCount) { mutableListOf<Int>() }
            for (tri in 0 until triCount) triLists[triObjects[tri]].add(tri)

            // Build sorted vertex buffer and compute per-object ranges + bounds
            val newGeoBuf = java.nio.ByteBuffer.allocateDirect(triCount * 3 * fpp * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            val newMatBuf = if (mesh.hasPerVertexColor) {
                java.nio.ByteBuffer.allocateDirect(triCount).order(java.nio.ByteOrder.nativeOrder())
            } else null

            val ranges = mutableListOf<ObjectMeshRange>()
            var destTriIdx = 0

            // Pre-compute cumulative triangle counts for O(log K) batch lookup
            val batchStarts = IntArray(mesh.batches.size)
            var cumTri = 0
            for (b in mesh.batches.indices) {
                batchStarts[b] = cumTri
                cumTri += mesh.batches[b].triangleCount
            }

            for (i in 0 until objectCount) {
                val tris = triLists[i]
                val rangeStart = destTriIdx * 3
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

                for (globalTriIndex in tris) {
                    // Binary search for the batch containing this global triangle
                    var batchIdx = java.util.Arrays.binarySearch(batchStarts, globalTriIndex)
                    if (batchIdx < 0) batchIdx = -(batchIdx + 1) - 1  // insertion point - 1
                    val batch = mesh.batches[batchIdx]
                    val localTri = globalTriIndex - batchStarts[batchIdx]
                    val srcBase = localTri * 3 * fpp
                    val buf = batch.geometry
                    for (v in 0 until 3) {
                        val vBase = srcBase + v * fpp
                        val vx = buf.get(vBase)
                        val vy = buf.get(vBase + 1)
                        val vz = buf.get(vBase + 2)
                        if (vx < minX) minX = vx; if (vx > maxX) maxX = vx
                        if (vy < minY) minY = vy; if (vy > maxY) maxY = vy
                        if (vz < minZ) minZ = vz; if (vz > maxZ) maxZ = vz
                        for (f in 0 until fpp) newGeoBuf.put(buf.get(vBase + f))
                    }
                    if (newMatBuf != null && batch.materialIndices != null) {
                        newMatBuf.put(batch.materialIndices.get(localTri))
                    }
                    destTriIdx++
                }

                // Guard empty range (shouldn't happen but avoids NaN in matrix math)
                if (tris.isEmpty()) {
                    minX = positions[i * 2]; maxX = minX + sizes[i * 3]
                    minY = positions[i * 2 + 1]; maxY = minY + sizes[i * 3 + 1]
                    minZ = 0f; maxZ = 1f
                }
                ranges.add(ObjectMeshRange(rangeStart, tris.size * 3, minX, maxX, minY, maxY, minZ, maxZ))
            }

            newGeoBuf.rewind()
            newMatBuf?.rewind()
            val newBatch = com.u1.slicer.viewer.NativeRenderBatch(newGeoBuf, newMatBuf, triCount)
            val sortedMesh = MeshData(
                batches = listOf(newBatch),
                minX = mesh.minX, minY = mesh.minY, minZ = mesh.minZ,
                maxX = mesh.maxX, maxY = mesh.maxY, maxZ = mesh.maxZ
            )
            return Pair(sortedMesh, ranges)
        }
    }
}
