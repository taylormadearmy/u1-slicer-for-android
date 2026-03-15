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
import kotlin.math.sqrt

/**
 * Renders G-code toolpaths as 3D colored lines/ribbon quads.
 * Extrusion moves are rendered as flat ribbon quads (GL_TRIANGLES) when move count < 500K;
 * falls back to GL_LINES for very large files. Travel moves are always GL_LINES.
 * All layers share a single VBO; each layer is a (firstVertex, count) range within it.
 */
class GcodeRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    private var toolpathShader: ShaderProgram? = null
    private val bed = BedDrawable(context)

    // Single VAO/VBO for travel moves (and extrusion fallback when useTubes=false)
    // Extrusion and travel vertices are stored in separate contiguous ranges per layer
    // so they can be drawn independently.
    private var masterVAO = 0
    private var masterVBO = 0
    private data class LayerRange(
        val extrudeFirst: Int, val extrudeCount: Int,
        val travelFirst: Int, val travelCount: Int
    )
    private val layerRanges = mutableListOf<LayerRange>()

    // Separate VAO/VBO for extrusion ribbon quads (GL_TRIANGLES)
    private var tubeVAO = 0
    private var tubeVBO = 0
    private data class TubeLayerRange(val firstVertex: Int, val vertexCount: Int)
    private val tubeLayerRanges = mutableListOf<TubeLayerRange>()
    private var useTubes = false

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

        // Delete previous tube VBO/VAO
        if (tubeVAO != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(tubeVAO), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(tubeVBO), 0)
            tubeVAO = 0; tubeVBO = 0
        }
        tubeLayerRanges.clear()
        useTubes = false

        totalLayers = gcode.layers.size
        maxLayer = totalLayers - 1
        if (totalLayers == 0) return

        // Count total moves across all layers for a single allocation
        val totalMoves = gcode.layers.sumOf { it.moves.size }
        if (totalMoves == 0) return

        // Count extrusion vs travel moves
        var totalExtrudeMoves = 0
        var totalTravelMoves = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.type == MoveType.EXTRUDE) totalExtrudeMoves++ else totalTravelMoves++
            }
        }

        // Each move = 2 vertices, each vertex = 3 pos + 4 color = 7 floats
        val floatsPerVertex = 7
        val tubeFloatsPerVertex = 10  // 3 pos + 4 color + 3 normal
        val maxBufferBytes = 80_000_000L  // 80MB limit per GPU buffer

        // Decide whether to use ribbon quads for extrusions
        // 6 vertices per move (2 triangles) * 10 floats * 4 bytes = 240 bytes per move
        val tubeBytes = totalExtrudeMoves.toLong() * 6 * tubeFloatsPerVertex * 4
        useTubes = totalExtrudeMoves < 500_000 && tubeBytes <= maxBufferBytes

        // --- Build line VBO (travels, and extrusion fallback when !useTubes) ---
        // Downsample extrusions if needed (only relevant in fallback path)
        val extrudeBytesAsLines = totalExtrudeMoves.toLong() * 2 * floatsPerVertex * 4
        val travelBytesAsLines = totalTravelMoves.toLong() * 2 * floatsPerVertex * 4
        val lineBytesNeeded = (if (useTubes) 0L else extrudeBytesAsLines) + travelBytesAsLines

        val sampleRate = if (!useTubes && lineBytesNeeded > maxBufferBytes) {
            val rate = ((lineBytesNeeded + maxBufferBytes - 1) / maxBufferBytes).toInt()
            android.util.Log.i("GcodeRenderer", "Downsampling G-code preview: keeping 1/$rate of $totalExtrudeMoves extrusion moves (${lineBytesNeeded / 1_000_000}MB → ~${lineBytesNeeded / rate / 1_000_000}MB)")
            rate
        } else 1

        // Allocate line buffer: extrusions (fallback) + travels
        val estimatedLineMoves = if (!useTubes) {
            if (sampleRate > 1) totalExtrudeMoves / sampleRate + totalLayers else totalExtrudeMoves
        } else 0
        val estimatedTravelMoves = if (sampleRate == 1) totalTravelMoves else 0
        val lineData = FloatArray((estimatedLineMoves + estimatedTravelMoves) * 2 * floatsPerVertex + floatsPerVertex)
        var lineOffset = 0

        // Allocate tube buffer: 6 vertices per extrusion move
        val tubeData = if (useTubes) FloatArray(totalExtrudeMoves * 6 * tubeFloatsPerVertex) else FloatArray(0)
        var tubeOffset = 0
        val halfWidth = 0.2f  // 0.4mm extrusion width / 2

        for (layer in gcode.layers) {
            // --- Extrusion pass ---
            val extrudeFirst = lineOffset / floatsPerVertex
            val tubeFirst = tubeOffset / tubeFloatsPerVertex
            var moveIdx = 0

            for (move in layer.moves) {
                if (move.type != MoveType.EXTRUDE) continue
                val color = extruderColors[move.extruder.coerceIn(0, 3)]

                if (useTubes) {
                    // Ribbon quad: 6 vertices (2 triangles)
                    val dx = move.x1 - move.x0
                    val dy = move.y1 - move.y0
                    val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (len < 0.001f) continue  // skip zero-length moves

                    val px = -dy / len * halfWidth
                    val py = dx / len * halfWidth
                    val z = layer.z

                    // v0: x0-px, y0-py   v1: x0+px, y0+py
                    // v3: x1-px, y1-py   v2: x1+px, y1+py
                    // Tri1: v0, v1, v2   Tri2: v0, v2, v3
                    if (tubeOffset + 6 * tubeFloatsPerVertex > tubeData.size) break

                    // Normal: perpendicular to move direction with slight outward tilt
                    val nx = px / halfWidth * 0.3f
                    val ny = py / halfWidth * 0.3f
                    val nz = 0.95f

                    // v0
                    tubeData[tubeOffset++] = move.x0 - px; tubeData[tubeOffset++] = move.y0 - py; tubeData[tubeOffset++] = z
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = -nx; tubeData[tubeOffset++] = -ny; tubeData[tubeOffset++] = nz
                    // v1
                    tubeData[tubeOffset++] = move.x0 + px; tubeData[tubeOffset++] = move.y0 + py; tubeData[tubeOffset++] = z
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = nx; tubeData[tubeOffset++] = ny; tubeData[tubeOffset++] = nz
                    // v2
                    tubeData[tubeOffset++] = move.x1 + px; tubeData[tubeOffset++] = move.y1 + py; tubeData[tubeOffset++] = z
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = nx; tubeData[tubeOffset++] = ny; tubeData[tubeOffset++] = nz
                    // Tri2
                    // v0 again
                    tubeData[tubeOffset++] = move.x0 - px; tubeData[tubeOffset++] = move.y0 - py; tubeData[tubeOffset++] = z
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = -nx; tubeData[tubeOffset++] = -ny; tubeData[tubeOffset++] = nz
                    // v2 again
                    tubeData[tubeOffset++] = move.x1 + px; tubeData[tubeOffset++] = move.y1 + py; tubeData[tubeOffset++] = z
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = nx; tubeData[tubeOffset++] = ny; tubeData[tubeOffset++] = nz
                    // v3
                    tubeData[tubeOffset++] = move.x1 - px; tubeData[tubeOffset++] = move.y1 - py; tubeData[tubeOffset++] = z
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = -nx; tubeData[tubeOffset++] = -ny; tubeData[tubeOffset++] = nz
                } else {
                    // Fallback: GL_LINES
                    if (sampleRate > 1 && moveIdx++ % sampleRate != 0) continue
                    if (lineOffset + floatsPerVertex * 2 > lineData.size) break
                    lineData[lineOffset++] = move.x0; lineData[lineOffset++] = move.y0; lineData[lineOffset++] = layer.z
                    lineData[lineOffset++] = color[0]; lineData[lineOffset++] = color[1]; lineData[lineOffset++] = color[2]; lineData[lineOffset++] = color[3]
                    lineData[lineOffset++] = move.x1; lineData[lineOffset++] = move.y1; lineData[lineOffset++] = layer.z
                    lineData[lineOffset++] = color[0]; lineData[lineOffset++] = color[1]; lineData[lineOffset++] = color[2]; lineData[lineOffset++] = color[3]
                }
            }
            val extrudeCount = lineOffset / floatsPerVertex - extrudeFirst
            val tubeVertexCount = tubeOffset / tubeFloatsPerVertex - tubeFirst

            // --- Travel pass (skipped entirely when downsampling) ---
            val travelFirst = lineOffset / floatsPerVertex
            if (sampleRate == 1) {
                for (move in layer.moves) {
                    if (move.type == MoveType.EXTRUDE) continue
                    if (lineOffset + floatsPerVertex * 2 > lineData.size) break
                    lineData[lineOffset++] = move.x0; lineData[lineOffset++] = move.y0; lineData[lineOffset++] = layer.z
                    lineData[lineOffset++] = travelColor[0]; lineData[lineOffset++] = travelColor[1]; lineData[lineOffset++] = travelColor[2]; lineData[lineOffset++] = travelColor[3]
                    lineData[lineOffset++] = move.x1; lineData[lineOffset++] = move.y1; lineData[lineOffset++] = layer.z
                    lineData[lineOffset++] = travelColor[0]; lineData[lineOffset++] = travelColor[1]; lineData[lineOffset++] = travelColor[2]; lineData[lineOffset++] = travelColor[3]
                }
            }
            val travelCount = lineOffset / floatsPerVertex - travelFirst

            layerRanges.add(LayerRange(extrudeFirst, extrudeCount, travelFirst, travelCount))
            tubeLayerRanges.add(TubeLayerRange(tubeFirst, tubeVertexCount))
        }

        // Upload line VBO (travel + optional extrusion fallback)
        if (lineOffset > 0) {
            val buf = ByteBuffer.allocateDirect(lineOffset * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buf.put(lineData, 0, lineOffset)
            buf.flip()

            val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); masterVAO = vaos[0]
            val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); masterVBO = vbos[0]

            GLES30.glBindVertexArray(masterVAO)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, masterVBO)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineOffset * 4, buf, GLES30.GL_STATIC_DRAW)

            val stride = floatsPerVertex * 4 // 28 bytes
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glBindVertexArray(0)
        }

        // Upload tube VBO (extrusion ribbon quads)
        if (useTubes && tubeOffset > 0) {
            val tubeBuf = ByteBuffer.allocateDirect(tubeOffset * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            tubeBuf.put(tubeData, 0, tubeOffset)
            tubeBuf.flip()

            val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); tubeVAO = vaos[0]
            val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); tubeVBO = vbos[0]

            GLES30.glBindVertexArray(tubeVAO)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, tubeVBO)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tubeOffset * 4, tubeBuf, GLES30.GL_STATIC_DRAW)

            val tubeStride = tubeFloatsPerVertex * 4 // 40 bytes
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, tubeStride, 0)   // position
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, tubeStride, 12)  // color
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, tubeStride, 28)  // normal
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glBindVertexArray(0)
        }
    }

    private fun drawToolpaths() {
        val shader = toolpathShader ?: return
        if (layerRanges.isEmpty() && tubeLayerRanges.isEmpty()) return

        shader.use()
        camera.computeMVP()
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

        val layerCount = maxOf(layerRanges.size, tubeLayerRanges.size)
        val min = minLayer.coerceIn(0, layerCount - 1)
        val max = maxLayer.coerceIn(0, layerCount - 1)

        // Draw extrusion: tubes (GL_TRIANGLES) when available, lines (GL_LINES) as fallback
        if (useTubes && tubeLayerRanges.isNotEmpty()) {
            GLES30.glBindVertexArray(tubeVAO)
            for (i in min..max) {
                if (i >= tubeLayerRanges.size) break
                val r = tubeLayerRanges[i]
                if (r.vertexCount > 0) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, r.firstVertex, r.vertexCount)
            }
            GLES30.glBindVertexArray(0)
        } else if (layerRanges.isNotEmpty()) {
            // Fallback: draw extrusion as lines
            GLES30.glBindVertexArray(masterVAO)
            for (i in min..max) {
                if (i >= layerRanges.size) break
                val r = layerRanges[i]
                if (r.extrudeCount > 0) GLES30.glDrawArrays(GLES30.GL_LINES, r.extrudeFirst, r.extrudeCount)
            }
            GLES30.glBindVertexArray(0)
        }

        // Draw travel moves (always GL_LINES)
        if (showTravel && layerRanges.isNotEmpty() && masterVAO != 0) {
            GLES30.glBindVertexArray(masterVAO)
            for (i in min..max) {
                if (i >= layerRanges.size) break
                val r = layerRanges[i]
                if (r.travelCount > 0) GLES30.glDrawArrays(GLES30.GL_LINES, r.travelFirst, r.travelCount)
            }
            GLES30.glBindVertexArray(0)
        }
    }

}
