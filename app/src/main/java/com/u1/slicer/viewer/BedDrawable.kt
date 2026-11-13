package com.u1.slicer.viewer

import android.content.Context
import android.opengl.GLES30
import com.u1.slicer.machine.MachineFamily
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BedRenderProfile(
    val sizeXmm: Float,
    val sizeYmm: Float,
    val bedStlAsset: String?,
    val machineFamily: MachineFamily,
)

/**
 * Shared bed rendering: U1 bed fill mesh, minor grid (10mm), major grid (50mm), border.
 * Instantiated by both ModelRenderer and GcodeRenderer so camera/gesture fixes
 * automatically apply to both viewers.
 */
class BedDrawable(
    private val context: Context,
    initialBedStlAsset: String? = LEGACY_U1_BED_STL_ASSET,
) {

    private var bedSizeXmm = 270f
    private var bedSizeYmm = 270f
    private var bedStlAsset: String? = initialBedStlAsset

    fun setBedProfile(sizeXmm: Float, sizeYmm: Float, bedStlAsset: String?) {
        if (sizeXmm == bedSizeXmm && sizeYmm == bedSizeYmm && bedStlAsset == this.bedStlAsset) return
        bedSizeXmm = sizeXmm
        bedSizeYmm = sizeYmm
        this.bedStlAsset = bedStlAsset
        setupBedMesh(context)
        setupGrid()
    }

    fun setBedProfile(profile: BedRenderProfile) {
        setBedProfile(profile.sizeXmm, profile.sizeYmm, profile.bedStlAsset)
    }

    private var gridShader: ShaderProgram? = null

    private var bedFillVAO = 0
    private var bedFillVertexCount = 0
    private var gridVAO = 0
    private var gridVertexCount = 0
    private var majorGridVAO = 0
    private var majorGridVertexCount = 0
    private var bedBorderVAO = 0

    /** Call from onSurfaceCreated on the GL thread. */
    fun setup(context: Context) {
        gridShader = ShaderProgram(context, "shaders/grid.vert", "shaders/grid.frag")
        setupBedMesh(context)
        setupGrid()
    }

    /** Call from onDrawFrame on the GL thread. */
    fun draw(camera: Camera) {
        val shader = gridShader ?: return
        shader.use()
        camera.computeMVP()
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)

        // Bed fill — push behind grid lines so polygon offset removes z-fighting
        if (bedFillVAO != 0) {
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(2f, 2f)
            GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.22f, 0.25f, 0.28f, 1f)
            GLES30.glBindVertexArray(bedFillVAO)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, bedFillVertexCount)
            GLES30.glBindVertexArray(0)
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        }

        // Minor grid
        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.26f, 0.30f, 0.34f, 1f)
        GLES30.glBindVertexArray(gridVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridVertexCount)
        GLES30.glBindVertexArray(0)

        // Major grid (50mm)
        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.38f, 0.44f, 0.50f, 1f)
        GLES30.glBindVertexArray(majorGridVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, majorGridVertexCount)
        GLES30.glBindVertexArray(0)

        // Border
        GLES30.glUniform4f(shader.getUniformLocation("u_Color"), 0.55f, 0.62f, 0.70f, 1f)
        GLES30.glBindVertexArray(bedBorderVAO)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 8)
        GLES30.glBindVertexArray(0)
    }

    private fun setupBedMesh(context: Context) {
        val asset = bedStlAsset
        if (asset == null) {
            uploadBedFill(rectangleVertices(bedSizeXmm, bedSizeYmm))
            return
        }

        val bytes = runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()
        if (bytes == null || bytes.size < 84) {
            uploadBedFill(rectangleVertices(bedSizeXmm, bedSizeYmm))
            return
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80)
        val triCount = buf.int
        if (triCount < 0 || 84L + triCount.toLong() * 50L > bytes.size) {
            uploadBedFill(rectangleVertices(bedSizeXmm, bedSizeYmm))
            return
        }

        val verts = FloatArray(triCount * 9)
        var index = 0
        repeat(triCount) {
            buf.position(buf.position() + 12) // skip normal
            repeat(3) {
                val x = (buf.float + 135f) * (bedSizeXmm / 270f)
                val y = (buf.float + 135f) * (bedSizeYmm / 270f)
                buf.float // Z — flatten to 0
                verts[index++] = x
                verts[index++] = y
                verts[index++] = 0f
            }
            buf.position(buf.position() + 2) // attribute
        }

        uploadBedFill(verts)
    }

    private fun uploadBedFill(verts: FloatArray) {
        bedFillVertexCount = verts.size / 3
        val fbuf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fbuf.put(verts); fbuf.flip()

        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); bedFillVAO = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0)
        GLES30.glBindVertexArray(bedFillVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, fbuf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
    }

    private fun setupGrid() {
        val bedW = bedSizeXmm; val bedH = bedSizeYmm

        fun makeLineVao(step: Float): Pair<Int, Int> {
            val lines = mutableListOf<Float>()
            var x = 0f; while (x <= bedW) { lines += listOf(x, 0f, 0f, x, bedH, 0f); x += step }
            var y = 0f; while (y <= bedH) { lines += listOf(0f, y, 0f, bedW, y, 0f); y += step }
            val count = lines.size / 3
            val buf = ByteBuffer.allocateDirect(lines.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(lines.toFloatArray()); buf.flip()
            val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0)
            val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0)
            GLES30.glBindVertexArray(vaos[0])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lines.size * 4, buf, GLES30.GL_STATIC_DRAW)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glBindVertexArray(0)
            return Pair(vaos[0], count)
        }

        makeLineVao(10f).also { (vao, count) -> gridVAO = vao; gridVertexCount = count }
        makeLineVao(50f).also { (vao, count) -> majorGridVAO = vao; majorGridVertexCount = count }

        // Border
        val border = floatArrayOf(
            0f, 0f, 0f,  bedW, 0f, 0f,
            bedW, 0f, 0f, bedW, bedH, 0f,
            bedW, bedH, 0f, 0f, bedH, 0f,
            0f, bedH, 0f, 0f, 0f, 0f
        )
        val borderBuf = ByteBuffer.allocateDirect(border.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
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

    companion object {
        private const val LEGACY_U1_BED_STL_ASSET = "bed/u1_bed.stl"

        internal fun rectangleVertices(width: Float, height: Float): FloatArray = floatArrayOf(
            0f, 0f, 0f,
            width, 0f, 0f,
            width, height, 0f,
            0f, 0f, 0f,
            width, height, 0f,
            0f, height, 0f,
        )
    }
}
