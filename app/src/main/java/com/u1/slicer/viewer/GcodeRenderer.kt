package com.u1.slicer.viewer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders G-code toolpaths as view-adaptive ribbons via GPU texture instancing.
 *
 * Ported from Prusa's libvgcode ShadersES.hpp. Per-vertex data (position,
 * height/width/angle, color) is stored in 2D textures read via texelFetch.
 * A segment template VAO of 24 vertices (8 triangles) is instanced once per
 * segment. The vertex shader generates ribbon geometry that always faces the camera.
 *
 * Travel moves are rendered as GL_LINES via a separate shader.
 */
class GcodeRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var viewportWidth = 0
    private var viewportHeight = 0
    @Volatile var preserveRestoredCameraOnSurfaceInit = false
    @Volatile var onContentReady: (() -> Unit)? = null
    @Volatile private var pendingContentReadyDispatch = false
    private var segmentShader: ShaderProgram? = null
    private var toolpathShader: ShaderProgram? = null
    private val bed = BedDrawable(context)

    // Segment template: 24 vertex IDs for 8 triangles (created once)
    private var templateVAO = 0
    private var templateVBO = 0

    // Data textures (recreated on each uploadGcode)
    private var positionTexId = 0
    private var hwaTexId = 0
    private var activeColorTexId = 0
    private var extruderColorTexId = 0
    private var featureColorTexId = 0
    private var segmentIndexTexId = 0

    // Layer data
    private var segmentLayerRanges = listOf<SegmentLayerRange>()
    private var hasSegments = false
    private var maxTexSize = 4096  // updated from GL_MAX_TEXTURE_SIZE in onSurfaceCreated

    // Travel lines
    private var travelVAO = 0
    private var travelVBO = 0
    private data class TravelLayerRange(val first: Int, val count: Int)
    private val travelLayerRanges = mutableListOf<TravelLayerRange>()

    private var totalLayers = 0
    // B129: clamp the upper bound to >= 0 so a setLayerRange() call that races
    // ahead of an async G-code upload (totalLayers still 0) can't throw
    // "Cannot coerce value to an empty range" (coerceIn(0, -1)).
    var minLayer = 0
        set(value) { field = value.coerceIn(0, (totalLayers - 1).coerceAtLeast(0)) }
    var maxLayer = 0
        set(value) { field = value.coerceIn(0, (totalLayers - 1).coerceAtLeast(0)) }
    var showTravel = false

    @Volatile var pendingGcode: ParsedGcode? = null
    @Volatile var preserveCameraOnNextUpload = false
    @Volatile var pendingExtruderColors: List<String>? = null
    @Volatile var pendingColorMode: Boolean? = null
    private var useFeatureColors = false
    private var lastGcode: ParsedGcode? = null
    private var lastPackResult: SegmentPackResult? = null

    /**
     * Phase 2 (2026-04-28, post-delta-review F8 fix) — palette grows
     * with the canonical filament list. Pre-fix this was a fixed
     * `Array<FloatArray>` of 4 entries, and `setExtruderColors`
     * dropped any entry beyond index 3 with `if (i >= extruderColors.size)
     * return@forEachIndexed`. The MainActivity-side
     * `normalizeGcodePreviewColors` change in commit `09b2daf` produced
     * a canonical-length palette but the renderer truncated it back to
     * 4 — high-T tools (T4..T9+) collapsed onto the last colour because
     * `GcodeSegmentPacker.pack` clamps via
     * `extruderPalette[move.extruder.coerceIn(0, extruderPalette.size - 1)]`.
     *
     * Now the array is reassigned on each `setExtruderColors` call so
     * canonical-width palettes flow through to the packer unmolested.
     * Default initial length is 4 (matching legacy behaviour for
     * non-canonical / single-colour cases).
     */
    private var extruderColors: Array<FloatArray> = arrayOf(
        floatArrayOf(1.0f, 0.6f, 0.0f, 1.0f),
        floatArrayOf(0.2f, 0.7f, 1.0f, 1.0f),
        floatArrayOf(0.0f, 0.9f, 0.4f, 1.0f),
        floatArrayOf(0.9f, 0.2f, 0.5f, 1.0f)
    )
    private val travelColor = floatArrayOf(0.6f, 0.6f, 0.6f, 0.6f)

    private val featureTypeColors = arrayOf(
        floatArrayOf(1.00f, 0.85f, 0.00f, 1.0f),
        floatArrayOf(0.53f, 0.81f, 0.92f, 1.0f),
        floatArrayOf(0.30f, 0.71f, 0.68f, 1.0f),
        floatArrayOf(0.40f, 0.73f, 0.42f, 1.0f),
        floatArrayOf(0.00f, 0.74f, 0.83f, 1.0f),
        floatArrayOf(0.00f, 0.59f, 0.53f, 1.0f),
        floatArrayOf(0.67f, 0.28f, 0.74f, 1.0f),
        floatArrayOf(0.81f, 0.58f, 0.85f, 1.0f),
        floatArrayOf(1.00f, 0.25f, 0.51f, 1.0f),
        floatArrayOf(1.00f, 0.44f, 0.26f, 1.0f),
        floatArrayOf(0.69f, 0.75f, 0.76f, 1.0f),
        floatArrayOf(0.62f, 0.62f, 0.62f, 1.0f)
    )

    fun setExtruderColors(hexColors: List<String>) {
        if (hexColors.isEmpty()) return
        // Phase 2 (2026-04-28, post-delta-review F8) — replace the
        // palette wholesale instead of mutating a fixed-size array.
        // Length grows to max(hexColors.size, 4) so downstream
        // GcodeSegmentPacker sees the full canonical palette and
        // T-indices beyond 3 don't get clamped to the last colour.
        // Entries with blank hex preserve the previous palette's
        // colour at that index (or fall back to a neutral grey if no
        // previous entry existed).
        val length = maxOf(hexColors.size, 4)
        val previous = extruderColors
        val parsed = Array(length) { i ->
            val hex = hexColors.getOrNull(i)
            if (!hex.isNullOrBlank()) {
                try {
                    val c = android.graphics.Color.parseColor(
                        if (hex.startsWith("#")) hex else "#$hex"
                    )
                    floatArrayOf(
                        android.graphics.Color.red(c) / 255f,
                        android.graphics.Color.green(c) / 255f,
                        android.graphics.Color.blue(c) / 255f,
                        1.0f,
                    )
                } catch (_: Exception) {
                    previous.getOrNull(i)?.copyOf()
                        ?: floatArrayOf(0.6f, 0.6f, 0.6f, 1.0f)
                }
            } else {
                previous.getOrNull(i)?.copyOf()
                    ?: floatArrayOf(0.6f, 0.6f, 0.6f, 1.0f)
            }
        }
        extruderColors = parsed
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.059f, 0.059f, 0.118f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glLineWidth(1.5f)

        segmentShader = ShaderProgram(context, "shaders/segment.vert", "shaders/segment.frag")
        toolpathShader = ShaderProgram(context, "shaders/toolpath.vert", "shaders/toolpath.frag")
        bed.setup(context)
        createSegmentTemplate()

        // Query actual GPU texture size limit for computeTexDimensions
        val buf = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, buf, 0)
        if (buf[0] > 0) maxTexSize = buf[0]

        if (preserveRestoredCameraOnSurfaceInit) {
            preserveRestoredCameraOnSurfaceInit = false
        } else {
            camera.setTarget(135.0, 135.0, 0.0)
            camera.distance = 500.0
            camera.elevation = 62.0
            camera.azimuth = -90.0
        }
    }

    private fun createSegmentTemplate() {
        val templateData = floatArrayOf(
            0f, 1f, 2f,  0f, 2f, 3f,
            0f, 3f, 4f,  0f, 4f, 5f,
            0f, 5f, 6f,  0f, 6f, 1f,
            5f, 4f, 7f,  5f, 7f, 6f
        )
        val buf = ByteBuffer.allocateDirect(templateData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(templateData).flip()

        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); templateVAO = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); templateVBO = vbos[0]

        GLES30.glBindVertexArray(templateVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, templateVBO)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, templateData.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(0, 1, GLES30.GL_FLOAT, false, 4, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        camera.updateProjectionMatrix(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingExtruderColors?.let { colors ->
            setExtruderColors(colors)
            pendingExtruderColors = null
            rebuildExtruderColorTexture()
        }

        pendingColorMode?.let { mode ->
            pendingColorMode = null
            if (mode != useFeatureColors) {
                useFeatureColors = mode
                activeColorTexId = if (useFeatureColors) featureColorTexId else extruderColorTexId
            }
        }

        pendingGcode?.let { gcode ->
            uploadGcode(gcode)
            pendingGcode = null
            if (preserveCameraOnNextUpload) {
                preserveCameraOnNextUpload = false
            } else {
                frameContentCamera(gcode)
            }
            pendingContentReadyDispatch = true
        }

        camera.updateViewMatrix()
        if (viewportWidth > 0 && viewportHeight > 0) {
            camera.updateProjectionMatrix(viewportWidth, viewportHeight)
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        bed.draw(camera)
        drawSegments()
        drawTravel()

        if (pendingContentReadyDispatch) {
            pendingContentReadyDispatch = false
            onContentReady?.let { cb -> mainHandler.post { cb() } }
        }
    }

    private fun frameContentCamera(gcode: ParsedGcode) {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.x0 < minX) minX = move.x0; if (move.x0 > maxX) maxX = move.x0
                if (move.x1 < minX) minX = move.x1; if (move.x1 > maxX) maxX = move.x1
                if (move.y0 < minY) minY = move.y0; if (move.y0 > maxY) maxY = move.y0
                if (move.y1 < minY) minY = move.y1; if (move.y1 > maxY) maxY = move.y1
            }
        }
        if (minX == Float.MAX_VALUE) {
            camera.setTarget(135.0, 135.0, 0.0)
            camera.distance = 500.0
        } else {
            val pad = 20f
            camera.setTarget(((minX + maxX) / 2f).toDouble(), ((minY + maxY) / 2f).toDouble(), 0.0)
            val dist = maxOf((maxX - minX + 2 * pad).toDouble(), (maxY - minY + 2 * pad).toDouble()) * 2.0
            camera.distance = dist.coerceAtLeast(100.0)
        }
        camera.elevation = 62.0
        camera.azimuth = -90.0
        camera.panX = 0.0
        camera.panY = 0.0
    }

    // --- Texture helpers ---

    private fun createDataTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun deleteTexture(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    private fun uploadFloatTexture(texId: Int, internalFormat: Int, format: Int,
                                   width: Int, height: Int, data: FloatArray, components: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        val paddedSize = width * height * components
        val padded = if (data.size < paddedSize) data.copyOf(paddedSize) else data
        val buf = ByteBuffer.allocateDirect(paddedSize * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(padded, 0, paddedSize).flip()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
            format, GLES30.GL_FLOAT, buf)
    }

    private fun uploadUintTexture(texId: Int, width: Int, height: Int, data: IntArray) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        val paddedSize = width * height
        val padded = if (data.size < paddedSize) data.copyOf(paddedSize) else data
        val buf = ByteBuffer.allocateDirect(paddedSize * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        buf.put(padded, 0, paddedSize).flip()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32UI, width, height, 0,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_INT, buf)
    }

    // --- Data upload ---

    fun uploadGcode(gcode: ParsedGcode) {
        lastGcode = gcode
        totalLayers = gcode.layers.size
        maxLayer = totalLayers - 1
        if (totalLayers == 0) {
            hasSegments = false
            segmentLayerRanges = emptyList()
            return
        }

        val pack = GcodeSegmentPacker.pack(gcode, extruderColors, featureTypeColors)
        lastPackResult = pack
        segmentLayerRanges = pack.layerRanges
        hasSegments = pack.totalSegments > 0

        if (hasSegments) {
            val (vTexW, vTexH) = GcodeSegmentPacker.computeTexDimensions(pack.totalVertices, maxTexSize)
            val (sTexW, sTexH) = GcodeSegmentPacker.computeTexDimensions(pack.totalSegments, maxTexSize)

            deleteTexture(positionTexId);      positionTexId = createDataTexture()
            deleteTexture(hwaTexId);           hwaTexId = createDataTexture()
            deleteTexture(extruderColorTexId); extruderColorTexId = createDataTexture()
            deleteTexture(featureColorTexId);  featureColorTexId = createDataTexture()
            deleteTexture(segmentIndexTexId);  segmentIndexTexId = createDataTexture()

            uploadFloatTexture(positionTexId, GLES30.GL_RGB32F, GLES30.GL_RGB, vTexW, vTexH, pack.positions, 3)
            uploadFloatTexture(hwaTexId, GLES30.GL_RGB32F, GLES30.GL_RGB, vTexW, vTexH, pack.heightsWidthsAngles, 3)
            uploadFloatTexture(extruderColorTexId, GLES30.GL_R32F, GLES30.GL_RED, vTexW, vTexH, pack.extruderColors, 1)
            uploadFloatTexture(featureColorTexId, GLES30.GL_R32F, GLES30.GL_RED, vTexW, vTexH, pack.featureColors, 1)
            uploadUintTexture(segmentIndexTexId, sTexW, sTexH, pack.segmentIndices)

            activeColorTexId = if (useFeatureColors) featureColorTexId else extruderColorTexId
        }

        uploadTravelLines(gcode)
    }

    private fun rebuildExtruderColorTexture() {
        val gcode = lastGcode ?: return
        if (!hasSegments) return
        val newPack = GcodeSegmentPacker.pack(gcode, extruderColors, featureTypeColors)
        lastPackResult = newPack
        val (vTexW, vTexH) = GcodeSegmentPacker.computeTexDimensions(newPack.totalVertices, maxTexSize)
        uploadFloatTexture(extruderColorTexId, GLES30.GL_R32F, GLES30.GL_RED, vTexW, vTexH, newPack.extruderColors, 1)
        if (!useFeatureColors) activeColorTexId = extruderColorTexId
    }

    private fun uploadTravelLines(gcode: ParsedGcode) {
        if (travelVAO != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(travelVAO), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(travelVBO), 0)
            travelVAO = 0; travelVBO = 0
        }
        travelLayerRanges.clear()

        val fpv = 7
        var totalTravel = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.type != MoveType.EXTRUDE) totalTravel++
            }
        }

        if (totalTravel > 0) {
            val lineData = FloatArray(totalTravel * 2 * fpv)
            var off = 0
            for (layer in gcode.layers) {
                val first = off / fpv
                for (move in layer.moves) {
                    if (move.type == MoveType.EXTRUDE) continue
                    if (off + fpv * 2 > lineData.size) break
                    lineData[off++] = move.x0; lineData[off++] = move.y0; lineData[off++] = layer.z
                    lineData[off++] = travelColor[0]; lineData[off++] = travelColor[1]; lineData[off++] = travelColor[2]; lineData[off++] = travelColor[3]
                    lineData[off++] = move.x1; lineData[off++] = move.y1; lineData[off++] = layer.z
                    lineData[off++] = travelColor[0]; lineData[off++] = travelColor[1]; lineData[off++] = travelColor[2]; lineData[off++] = travelColor[3]
                }
                travelLayerRanges.add(TravelLayerRange(first, off / fpv - first))
            }
            if (off > 0) {
                val buf = ByteBuffer.allocateDirect(off * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                buf.put(lineData, 0, off).flip()
                val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); travelVAO = vaos[0]
                val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); travelVBO = vbos[0]
                GLES30.glBindVertexArray(travelVAO)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, travelVBO)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, off * 4, buf, GLES30.GL_STATIC_DRAW)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, fpv * 4, 0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, fpv * 4, 12)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glBindVertexArray(0)
            }
        } else {
            for (layer in gcode.layers) travelLayerRanges.add(TravelLayerRange(0, 0))
        }
    }

    // --- Drawing ---

    private fun drawSegments() {
        if (!hasSegments || segmentLayerRanges.isEmpty() || templateVAO == 0) return
        val shader = segmentShader ?: return
        shader.use()

        camera.computeMVP()

        val v = camera.viewMatrix
        val camX = -(v[0] * v[12] + v[1] * v[13] + v[2] * v[14])
        val camY = -(v[4] * v[12] + v[5] * v[13] + v[6] * v[14])
        val camZ = -(v[8] * v[12] + v[9] * v[13] + v[10] * v[14])

        GLES30.glUniformMatrix4fv(shader.getUniformLocation("view_matrix"), 1, false, camera.viewMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("projection_matrix"), 1, false, camera.projectionMatrix, 0)
        GLES30.glUniform3f(shader.getUniformLocation("camera_position"), camX, camY, camZ)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, positionTexId)
        GLES30.glUniform1i(shader.getUniformLocation("position_tex"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hwaTexId)
        GLES30.glUniform1i(shader.getUniformLocation("height_width_angle_tex"), 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, activeColorTexId)
        GLES30.glUniform1i(shader.getUniformLocation("color_tex"), 2)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, segmentIndexTexId)
        GLES30.glUniform1i(shader.getUniformLocation("segment_index_tex"), 3)

        val instanceOffsetLoc = shader.getUniformLocation("instance_offset")

        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glBindVertexArray(templateVAO)
        val min = minLayer.coerceIn(0, segmentLayerRanges.size - 1)
        val max = maxLayer.coerceIn(0, segmentLayerRanges.size - 1)
        for (i in min..max) {
            if (i >= segmentLayerRanges.size) break
            val range = segmentLayerRanges[i]
            if (range.segmentCount <= 0) continue
            GLES30.glUniform1i(instanceOffsetLoc, range.firstSegment)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 24, range.segmentCount)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun drawTravel() {
        if (!showTravel || travelLayerRanges.isEmpty() || travelVAO == 0) return
        val shader = toolpathShader ?: return
        shader.use()
        camera.computeMVP()
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

        val min = minLayer.coerceIn(0, travelLayerRanges.size - 1)
        val max = maxLayer.coerceIn(0, travelLayerRanges.size - 1)
        GLES30.glBindVertexArray(travelVAO)
        for (i in min..max) {
            if (i >= travelLayerRanges.size) break
            val r = travelLayerRanges[i]
            if (r.count > 0) GLES30.glDrawArrays(GLES30.GL_LINES, r.first, r.count)
        }
        GLES30.glBindVertexArray(0)
    }
}
