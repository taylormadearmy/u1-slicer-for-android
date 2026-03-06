package com.u1.slicer.viewer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    var meshData: MeshData? = null
        private set
    private var modelShader: ShaderProgram? = null
    private var gridShader: ShaderProgram? = null
    private var modelVAO = 0
    private var gridVAO = 0
    private var gridVertexCount = 0
    private var bedBorderVAO = 0
    private var boxVAO = 0
    private var boxVertexCount = 0

    // Model color (orange) — used when no per-instance colors are set
    private val modelColor = floatArrayOf(0.91f, 0.48f, 0f, 1f)
    private val wipeTowerColor = floatArrayOf(1f, 0.76f, 0.03f, 0.7f)

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

    data class WipeTowerInfo(val x: Float, val y: Float, val width: Float, val depth: Float)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.059f, 0.059f, 0.118f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        modelShader = ShaderProgram(context, "shaders/model.vert", "shaders/model.frag")
        gridShader = ShaderProgram(context, "shaders/grid.vert", "shaders/grid.frag")

        setupGrid()
        setupBox()
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

            val bedSize = 270f
            if (mesh.maxX <= bedSize * 1.5f && mesh.maxY <= bedSize * 1.5f) {
                camera.setTarget(bedSize / 2, bedSize / 2, mesh.sizeZ / 2)
                camera.distance = bedSize * 1.2f
            } else {
                camera.setTarget(mesh.centerX, mesh.centerY, mesh.centerZ)
                camera.distance = mesh.maxDimension * 2f
            }
            camera.elevation = 30f
            camera.azimuth = -45f
            camera.panX = 0f
            camera.panY = 0f
        }

        camera.updateViewMatrix()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawGrid()

        // Draw model instances
        meshData?.let { mesh ->
            val positions = instancePositions
            if (positions != null && positions.size >= 2) {
                val count = positions.size / 2
                for (i in 0 until count) {
                    val px = positions[i * 2]
                    val py = positions[i * 2 + 1]
                    val highlighted = (highlightIndex == i)
                    drawModelAt(mesh, px, py, highlighted)
                }
            } else {
                drawModel(mesh)
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

    private fun drawModel(mesh: MeshData) {
        val shader = modelShader ?: return
        shader.use()
        camera.computeMVP()
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)
        GLES30.glUniform4fv(shader.getUniformLocation("u_Color"), 1, modelColor, 0)
        GLES30.glBindVertexArray(modelVAO)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES30.glBindVertexArray(0)
    }

    private fun drawModelAt(mesh: MeshData, x: Float, y: Float, highlighted: Boolean) {
        val shader = modelShader ?: return
        shader.use()

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, 0f)

        camera.computeMVP(modelMatrix)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

        val color = if (highlighted) floatArrayOf(1f, 0.6f, 0.2f, 1f) else modelColor
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
     * Unproject a screen touch (px, py) onto the Z=0 bed plane.
     * Returns (bedX, bedY) in mm, or null if the ray is parallel to the plane.
     */
    fun screenToBed(screenX: Float, screenY: Float): FloatArray? {
        camera.updateViewMatrix()
        camera.computeMVP()

        val invertedVP = FloatArray(16)
        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, camera.projectionMatrix, 0, camera.viewMatrix, 0)
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
        if (kotlin.math.abs(dz) < 1e-6f) return null // parallel to bed

        // Intersect with Z=0 plane
        val t = -nz / dz
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

    private fun setupGrid() {
        val bedW = 270f
        val bedH = 270f
        val step = 10f
        val lines = mutableListOf<Float>()

        var x = 0f
        while (x <= bedW) {
            lines.addAll(listOf(x, 0f, 0f, x, bedH, 0f))
            x += step
        }
        var y = 0f
        while (y <= bedH) {
            lines.addAll(listOf(0f, y, 0f, bedW, y, 0f))
            y += step
        }
        gridVertexCount = lines.size / 3

        val buf = ByteBuffer.allocateDirect(lines.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(lines.toFloatArray())
        buf.flip()

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        gridVAO = vaos[0]

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)

        GLES30.glBindVertexArray(gridVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lines.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)

        // Bed border
        val border = floatArrayOf(
            0f, 0f, 0f,  bedW, 0f, 0f,
            bedW, 0f, 0f,  bedW, bedH, 0f,
            bedW, bedH, 0f,  0f, bedH, 0f,
            0f, bedH, 0f,  0f, 0f, 0f
        )
        val borderBuf = ByteBuffer.allocateDirect(border.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        borderBuf.put(border)
        borderBuf.flip()

        val bVaos = IntArray(1)
        GLES30.glGenVertexArrays(1, bVaos, 0)
        bedBorderVAO = bVaos[0]

        val bVbos = IntArray(1)
        GLES30.glGenBuffers(1, bVbos, 0)

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

        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.15f, 0.15f, 0.25f, 1f)
        GLES30.glBindVertexArray(gridVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridVertexCount)
        GLES30.glBindVertexArray(0)

        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.3f, 0.3f, 0.5f, 1f)
        GLES30.glBindVertexArray(bedBorderVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 8)
        GLES30.glBindVertexArray(0)
    }
}
