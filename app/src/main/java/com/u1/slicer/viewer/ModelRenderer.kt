package com.u1.slicer.viewer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    private var meshData: MeshData? = null
    private var modelShader: ShaderProgram? = null
    private var gridShader: ShaderProgram? = null
    private var modelVAO = 0
    private var gridVAO = 0
    private var gridVertexCount = 0
    private var bedBorderVAO = 0

    // Model color (orange)
    private val modelColor = floatArrayOf(0.91f, 0.48f, 0f, 1f)

    @Volatile
    var pendingMesh: MeshData? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.059f, 0.059f, 0.118f, 1f) // Dark background
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        modelShader = ShaderProgram(context, "shaders/model.vert", "shaders/model.frag")
        gridShader = ShaderProgram(context, "shaders/grid.vert", "shaders/grid.frag")

        setupGrid()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.updateProjectionMatrix(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Check for pending mesh upload
        pendingMesh?.let { mesh ->
            uploadMesh(mesh)
            meshData = mesh
            pendingMesh = null

            // Auto-frame: center on bed if model fits, otherwise center on model
            val bedSize = 270f
            if (mesh.maxX <= bedSize * 1.5f && mesh.maxY <= bedSize * 1.5f) {
                // Model likely on a bed — show bed-centric view
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

        // Draw grid
        drawGrid()

        // Draw model
        meshData?.let { drawModel(it) }
    }

    private fun uploadMesh(mesh: MeshData) {
        // Delete old VAO/VBO
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

        // Position attribute (location 0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, MeshData.BYTES_PER_VERTEX, 0)
        GLES30.glEnableVertexAttribArray(0)

        // Normal attribute (location 1)
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

    private fun setupGrid() {
        val bedW = 270f
        val bedH = 270f
        val step = 10f
        val lines = mutableListOf<Float>()

        // Grid lines
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

        // Grid lines (dim)
        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.15f, 0.15f, 0.25f, 1f)
        GLES30.glBindVertexArray(gridVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridVertexCount)
        GLES30.glBindVertexArray(0)

        // Bed border (brighter)
        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.3f, 0.3f, 0.5f, 1f)
        GLES30.glBindVertexArray(bedBorderVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 8)
        GLES30.glBindVertexArray(0)
    }
}
