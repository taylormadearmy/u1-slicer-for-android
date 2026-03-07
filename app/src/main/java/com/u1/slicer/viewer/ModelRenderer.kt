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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class ModelRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    var meshData: MeshData? = null
        private set
    private var modelShader: ShaderProgram? = null
    private var gridShader: ShaderProgram? = null
    private var textureShader: ShaderProgram? = null
    private var modelVAO = 0
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

    // Per-instance colors from extruder slot assignments (RGBA 0..1). When set, each instance
    // is tinted with its assigned extruder color; single-color models use the first entry.
    @Volatile var instanceColors: List<FloatArray>? = null

    // Instance positions for placement mode (XY pairs in mm, bed coordinates)
    // null = single instance at model's original position (no offset applied)
    @Volatile var instancePositions: FloatArray? = null

    // Wipe tower placement (null = not shown)
    @Volatile var wipeTower: WipeTowerInfo? = null

    // Index of the instance/tower currently being dragged (-1=none, N=object index, instances.size=tower)
    @Volatile var highlightIndex: Int = -1

    private var viewportWidth = 1
    private var viewportHeight = 1

    @Volatile
    var pendingMesh: MeshData? = null

    // Set to true to trigger a camera re-centre on the next frame (e.g. after placement
    // positions arrive on the main thread after the mesh was already uploaded).
    @Volatile
    var pendingCameraReset = false

    data class WipeTowerInfo(val x: Float, val y: Float, val width: Float, val depth: Float)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.059f, 0.059f, 0.118f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        modelShader = ShaderProgram(context, "shaders/model.vert", "shaders/model.frag")
        gridShader = ShaderProgram(context, "shaders/grid.vert", "shaders/grid.frag")
        textureShader = ShaderProgram(context, "shaders/texture.vert", "shaders/texture.frag")

        setupBedMesh()
        setupGrid()
        setupLogoTexture()
        setupBox()

        // Initialize camera to plate-centred view immediately so the first frame is correct.
        // Without this, the camera starts at default (azimuth=0, elevation=30) and only snaps
        // to the bed view once the mesh loads, causing a visible flash on first open.
        resetCameraToDefaultView()
    }

    private fun resetCameraToDefaultView() {
        camera.setTarget(135f, 135f, 0f)
        camera.distance = 500f
        camera.elevation = 62f
        camera.azimuth = -90f
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        camera.updateProjectionMatrix(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingMesh?.let { mesh ->
            uploadMesh(mesh)
            meshData = mesh
            pendingMesh = null
            pendingCameraReset = true  // camera will be set up below (after instancePositions may be set)
        }

        if (pendingCameraReset) {
            val mesh = meshData
            if (mesh != null) {
                pendingCameraReset = false
                resetCameraToDefaultView()
                camera.panX = 0f
                camera.panY = 0f
                camera.updateProjectionMatrix(viewportWidth, viewportHeight)
            }
        }

        camera.updateViewMatrix()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawGrid()

        // Draw model instances
        meshData?.let { mesh ->
            val positions = instancePositions
            val colors = instanceColors
            if (positions != null && positions.size >= 2) {
                val count = positions.size / 2
                for (i in 0 until count) {
                    val px = positions[i * 2]
                    val py = positions[i * 2 + 1]
                    val highlighted = (highlightIndex == i)
                    val color = colors?.getOrNull(i) ?: colors?.getOrNull(0) ?: modelColorDefault
                    drawModelAt(mesh, px, py, highlighted, color)
                }
            } else {
                val color = colors?.getOrNull(0) ?: modelColorDefault
                drawModel(mesh, color)
            }
        }

        // Draw wipe tower
        wipeTower?.let { tower ->
            val highlighted = instancePositions != null &&
                    highlightIndex == (instancePositions!!.size / 2)
            drawWipeTower(tower, highlighted)
        }
    }

    private fun uploadMesh(mesh: MeshData) {
        if (modelVAO != 0) {
            val vaos = intArrayOf(modelVAO)
            GLES30.glDeleteVertexArrays(1, vaos, 0)
        }

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        modelVAO = vaos[0]

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)

        GLES30.glBindVertexArray(modelVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])

        mesh.vertices.position(0)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            mesh.vertexCount * MeshData.BYTES_PER_VERTEX,
            mesh.vertices,
            GLES30.GL_STATIC_DRAW
        )

        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, MeshData.BYTES_PER_VERTEX, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, MeshData.BYTES_PER_VERTEX, 12)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glBindVertexArray(0)
    }

    private fun drawModel(mesh: MeshData, color: FloatArray = modelColorDefault) {
        val shader = modelShader ?: return
        shader.use()
        camera.computeMVP()
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)
        GLES30.glUniform4fv(shader.getUniformLocation("u_Color"), 1, color, 0)
        GLES30.glBindVertexArray(modelVAO)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES30.glBindVertexArray(0)
    }

    private fun drawModelAt(mesh: MeshData, x: Float, y: Float, highlighted: Boolean,
                            baseColor: FloatArray = modelColorDefault) {
        val shader = modelShader ?: return
        shader.use()

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        // Offset by -minX/-minY so the model's bottom-left corner lands at (x, y) on the bed
        Matrix.translateM(modelMatrix, 0, x - mesh.minX, y - mesh.minY, -mesh.minZ)

        camera.computeMVP(modelMatrix)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

        val color = if (highlighted) floatArrayOf(1f, 0.6f, 0.2f, 1f) else baseColor
        GLES30.glUniform4fv(shader.getUniformLocation("u_Color"), 1, color, 0)

        GLES30.glBindVertexArray(modelVAO)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES30.glBindVertexArray(0)
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

        val color = if (highlighted) floatArrayOf(1f, 0.85f, 0.2f, 1f) else wipeTowerColor
        GLES30.glUniform4fv(shader.getUniformLocation("u_Color"), 1, color, 0)

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
        val eyeX = camera.targetX + camera.panX + (camera.distance * cos(radEl) * cos(radAz)).toFloat()
        val eyeY = camera.targetY + camera.panY + (camera.distance * cos(radEl) * sin(radAz)).toFloat()
        val eyeZ = camera.targetZ + (camera.distance * sin(radEl)).toFloat()
        val localView = FloatArray(16)
        Matrix.setLookAtM(localView, 0, eyeX, eyeY, eyeZ,
            camera.targetX + camera.panX, camera.targetY + camera.panY, camera.targetZ,
            0f, 0f, 1f)

        // Build projection matrix locally
        val localProj = FloatArray(16)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(localProj, 0, 45f, aspect,
            (camera.distance * 0.01f).coerceAtLeast(0.1f), camera.distance * 10f)

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
}
