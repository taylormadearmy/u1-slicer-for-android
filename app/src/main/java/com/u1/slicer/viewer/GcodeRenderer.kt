package com.u1.slicer.viewer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders G-code toolpaths as 3D colored lines.
 * Extrusion moves are colored by extruder; travel moves are dim gray.
 * All layers share a single VBO; each layer is a (firstVertex, count) range within it.
 */
class GcodeRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    private var toolpathShader: ShaderProgram? = null
    private val bed = BedDrawable(context)

    // Single VAO/VBO for all toolpath layers
    private var masterVAO = 0
    private var masterVBO = 0
    private data class LayerRange(val firstVertex: Int, val vertexCount: Int)
    private val layerRanges = mutableListOf<LayerRange>()

    private var totalLayers = 0
    var minLayer = 0
        set(value) { field = value.coerceIn(0, totalLayers - 1) }
    var maxLayer = 0
        set(value) { field = value.coerceIn(0, totalLayers - 1) }
    var showTravel = false

    @Volatile
    var pendingGcode: ParsedGcode? = null

    @Volatile
    var pendingExtruderColors: List<String>? = null

    // Extruder colors — defaults match the 2D viewer; overridden via setExtruderColors()
    private val extruderColors = arrayOf(
        floatArrayOf(1.0f, 0.6f, 0.0f, 1.0f),  // T0: orange
        floatArrayOf(0.2f, 0.7f, 1.0f, 1.0f),  // T1: blue
        floatArrayOf(0.0f, 0.9f, 0.4f, 1.0f),  // T2: green
        floatArrayOf(0.9f, 0.2f, 0.5f, 1.0f)   // T3: pink
    )
    private val travelColor = floatArrayOf(0.3f, 0.3f, 0.3f, 0.4f)

    /** Override extruder colors from the confirmed multi-color assignment.
     *  Empty/blank entries are skipped (unused slots keep their defaults). */
    fun setExtruderColors(hexColors: List<String>) {
        hexColors.forEachIndexed { i, hex ->
            if (i >= extruderColors.size || hex.isBlank()) return@forEachIndexed
            try {
                val c = android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
                extruderColors[i] = floatArrayOf(
                    android.graphics.Color.red(c) / 255f,
                    android.graphics.Color.green(c) / 255f,
                    android.graphics.Color.blue(c) / 255f,
                    1.0f
                )
            } catch (_: Exception) { /* keep default */ }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.059f, 0.059f, 0.118f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glLineWidth(1.5f)

        toolpathShader = ShaderProgram(context, "shaders/toolpath.vert", "shaders/toolpath.frag")
        bed.setup(context)

        // Default camera: plate-centred top-down view matching model viewer
        camera.setTarget(135f, 135f, 0f)
        camera.distance = 500f
        camera.elevation = 62f
        camera.azimuth = -90f
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.updateProjectionMatrix(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingExtruderColors?.let { colors ->
            setExtruderColors(colors)
            pendingExtruderColors = null
        }

        pendingGcode?.let { gcode ->
            uploadGcode(gcode)
            pendingGcode = null

            // Auto-frame: plate-centred view matching model viewer
            camera.setTarget(135f, 135f, 0f)
            camera.distance = 500f
            camera.elevation = 62f
            camera.azimuth = -90f
            camera.panX = 0f
            camera.panY = 0f
        }

        camera.updateViewMatrix()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        bed.draw(camera)
        drawToolpaths()
    }

    fun uploadGcode(gcode: ParsedGcode) {
        // Delete previous master VBO/VAO
        if (masterVAO != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(masterVAO), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(masterVBO), 0)
            masterVAO = 0; masterVBO = 0
        }
        layerRanges.clear()

        totalLayers = gcode.layers.size
        maxLayer = totalLayers - 1
        if (totalLayers == 0) return

        // Count total moves across all layers for a single allocation
        val totalMoves = gcode.layers.sumOf { it.moves.size }
        if (totalMoves == 0) return

        // Each move = 2 vertices, each vertex = 3 pos + 4 color = 7 floats
        val floatsPerVertex = 7
        val maxBufferBytes = 80_000_000L  // 80MB limit for GPU buffer
        val fullBytes = totalMoves.toLong() * 2 * floatsPerVertex * 4

        // Downsample if needed: keep every Nth extrusion move, skip travel moves
        val sampleRate = if (fullBytes > maxBufferBytes) {
            val rate = ((fullBytes + maxBufferBytes - 1) / maxBufferBytes).toInt()
            android.util.Log.i("GcodeRenderer", "Downsampling G-code preview: keeping 1/$rate of $totalMoves moves (${fullBytes / 1_000_000}MB → ~${fullBytes / rate / 1_000_000}MB)")
            rate
        } else 1

        // Allocate for the downsampled size
        val estimatedMoves = if (sampleRate > 1) totalMoves / sampleRate + totalLayers else totalMoves
        val data = FloatArray(estimatedMoves * 2 * floatsPerVertex)
        var offset = 0

        for (layer in gcode.layers) {
            val layerStart = offset / floatsPerVertex
            var moveIdx = 0
            for (move in layer.moves) {
                // When downsampling, skip travel moves and sample extrusions
                if (sampleRate > 1) {
                    if (move.type != MoveType.EXTRUDE) continue
                    if (moveIdx++ % sampleRate != 0) continue
                }
                val color = if (move.type == MoveType.EXTRUDE) {
                    extruderColors[move.extruder.coerceIn(0, 3)]
                } else {
                    travelColor
                }
                // Bounds check — downsampling estimate may be slightly off
                if (offset + floatsPerVertex * 2 > data.size) break
                // Start vertex
                data[offset++] = move.x0; data[offset++] = move.y0; data[offset++] = layer.z
                data[offset++] = color[0]; data[offset++] = color[1]; data[offset++] = color[2]; data[offset++] = color[3]
                // End vertex
                data[offset++] = move.x1; data[offset++] = move.y1; data[offset++] = layer.z
                data[offset++] = color[0]; data[offset++] = color[1]; data[offset++] = color[2]; data[offset++] = color[3]
            }
            val layerVertexCount = offset / floatsPerVertex - layerStart
            layerRanges.add(LayerRange(layerStart, layerVertexCount))
        }

        // Single GPU upload for all layers
        val buf = ByteBuffer.allocateDirect(offset * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(data, 0, offset)
        buf.flip()

        val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); masterVAO = vaos[0]
        val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); masterVBO = vbos[0]

        GLES30.glBindVertexArray(masterVAO)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, masterVBO)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, offset * 4, buf, GLES30.GL_STATIC_DRAW)

        val stride = floatsPerVertex * 4 // 28 bytes
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glBindVertexArray(0)
    }

    private fun drawToolpaths() {
        val shader = toolpathShader ?: return
        if (layerRanges.isEmpty()) return

        shader.use()
        camera.computeMVP()
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)

        val min = minLayer.coerceIn(0, layerRanges.size - 1)
        val max = maxLayer.coerceIn(0, layerRanges.size - 1)

        GLES30.glBindVertexArray(masterVAO)
        // Layers are packed consecutively — draw the full min..max range in one call
        val firstVertex = layerRanges[min].firstVertex
        val lastRange = layerRanges[max]
        val totalVertices = lastRange.firstVertex + lastRange.vertexCount - firstVertex
        if (totalVertices > 0) {
            GLES30.glDrawArrays(GLES30.GL_LINES, firstVertex, totalVertices)
        }
        GLES30.glBindVertexArray(0)
    }

}
