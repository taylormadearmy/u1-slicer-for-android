package com.u1.slicer.viewer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import com.u1.slicer.gcode.FeatureType
import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders G-code toolpaths as 3D box-tube geometry via GPU instancing (glDrawArraysInstanced).
 * Each extrusion move is a single instance; the vertex shader generates 18 vertices (box tube)
 * from gl_VertexID. Travel moves are always GL_LINES.
 *
 * This replaces the previous CPU-expanded tube geometry, dropping per-move memory from 720 to 48
 * bytes and eliminating the 200k-move thin-line fallback.
 */
class GcodeRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    var preserveRestoredCameraOnSurfaceInit = false
    @Volatile
    var onContentReady: (() -> Unit)? = null
    @Volatile
    private var pendingContentReadyDispatch = false
    private var toolpathShader: ShaderProgram? = null
    private var instancedShader: ShaderProgram? = null
    private val bed = BedDrawable(context)

    // Travel-only line VBO/VAO
    private var masterVAO = 0
    private var masterVBO = 0
    private data class LayerRange(val travelFirst: Int, val travelCount: Int)
    private val layerRanges = mutableListOf<LayerRange>()

    // Instanced extrusion VBO/VAO
    private var instanceVAO = 0
    private var instanceVBO = 0
    private var instanceLayerRanges = listOf<InstanceLayerRange>()
    private var hasInstances = false

    private var totalLayers = 0
    var minLayer = 0
        set(value) { field = value.coerceIn(0, totalLayers - 1) }
    var maxLayer = 0
        set(value) { field = value.coerceIn(0, totalLayers - 1) }
    var showTravel = false

    @Volatile
    var pendingGcode: ParsedGcode? = null

    @Volatile
    var preserveCameraOnNextUpload = false

    @Volatile
    var pendingExtruderColors: List<String>? = null

    // Extruder colors — defaults match the 2D viewer; overridden via setExtruderColors()
    private val extruderColors = arrayOf(
        floatArrayOf(1.0f, 0.6f, 0.0f, 1.0f),  // T0: orange
        floatArrayOf(0.2f, 0.7f, 1.0f, 1.0f),  // T1: blue
        floatArrayOf(0.0f, 0.9f, 0.4f, 1.0f),  // T2: green
        floatArrayOf(0.9f, 0.2f, 0.5f, 1.0f)   // T3: pink
    )
    private val travelColor = floatArrayOf(0.6f, 0.6f, 0.6f, 0.6f)

    // Feature-type color palette — indexed by FeatureType constants (0–11)
    private val featureTypeColors = arrayOf(
        floatArrayOf(1.00f, 0.85f, 0.00f, 1.0f),  // OUTER_WALL:       yellow
        floatArrayOf(0.53f, 0.81f, 0.92f, 1.0f),  // INNER_WALL:       sky blue
        floatArrayOf(0.30f, 0.71f, 0.68f, 1.0f),  // SPARSE_INFILL:    teal
        floatArrayOf(0.40f, 0.73f, 0.42f, 1.0f),  // SOLID_INFILL:     green
        floatArrayOf(0.00f, 0.74f, 0.83f, 1.0f),  // TOP_SURFACE:      cyan
        floatArrayOf(0.00f, 0.59f, 0.53f, 1.0f),  // BOTTOM_SURFACE:   dark teal
        floatArrayOf(0.67f, 0.28f, 0.74f, 1.0f),  // SUPPORT:          purple
        floatArrayOf(0.81f, 0.58f, 0.85f, 1.0f),  // SUPPORT_INTERFACE:light purple
        floatArrayOf(1.00f, 0.25f, 0.51f, 1.0f),  // PRIME_TOWER:      hot pink
        floatArrayOf(1.00f, 0.44f, 0.26f, 1.0f),  // BRIDGE:           orange-red
        floatArrayOf(0.69f, 0.75f, 0.76f, 1.0f),  // SKIRT:            light gray
        floatArrayOf(0.62f, 0.62f, 0.62f, 1.0f)   // OTHER:            gray
    )

    /** true = color by feature type; false = color by extruder (default) */
    @Volatile var pendingColorMode: Boolean? = null
    private var useFeatureColors = false
    private var lastGcode: ParsedGcode? = null

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
        instancedShader = ShaderProgram(context, "shaders/toolpath_instanced.vert", "shaders/toolpath_instanced.frag")
        bed.setup(context)

        if (preserveRestoredCameraOnSurfaceInit) {
            preserveRestoredCameraOnSurfaceInit = false
        } else {
            // Default camera: plate-centred top-down view matching model viewer
            camera.setTarget(135f, 135f, 0f)
            camera.distance = 500f
            camera.elevation = 62f
            camera.azimuth = -90f
        }
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

        pendingColorMode?.let { mode ->
            pendingColorMode = null
            if (mode != useFeatureColors) {
                useFeatureColors = mode
                lastGcode?.let { gcode ->
                    preserveCameraOnNextUpload = true
                    uploadGcode(gcode)
                }
            }
        }

        pendingGcode?.let { gcode ->
            uploadGcode(gcode)
            pendingGcode = null

            if (preserveCameraOnNextUpload) {
                preserveCameraOnNextUpload = false
            } else {
                // Auto-frame: plate-centred view matching model viewer
                camera.setTarget(135f, 135f, 0f)
                camera.distance = 500f
                camera.elevation = 62f
                camera.azimuth = -90f
                camera.panX = 0f
                camera.panY = 0f
            }
            pendingContentReadyDispatch = true
        }

        camera.updateViewMatrix()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        bed.draw(camera)
        drawToolpaths()

        if (pendingContentReadyDispatch) {
            pendingContentReadyDispatch = false
            onContentReady?.let { callback ->
                mainHandler.post { callback() }
            }
        }
    }

    fun uploadGcode(gcode: ParsedGcode) {
        lastGcode = gcode

        // Delete previous master VBO/VAO (travel lines)
        if (masterVAO != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(masterVAO), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(masterVBO), 0)
            masterVAO = 0; masterVBO = 0
        }
        layerRanges.clear()

        // Delete previous instance VBO/VAO
        if (instanceVAO != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(instanceVAO), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(instanceVBO), 0)
            instanceVAO = 0; instanceVBO = 0
        }
        instanceLayerRanges = emptyList()
        hasInstances = false

        totalLayers = gcode.layers.size
        maxLayer = totalLayers - 1
        if (totalLayers == 0) return

        // --- Pack and upload instanced extrusion data ---
        val packResult = GcodeInstancePacker.pack(gcode, extruderColors, featureTypeColors, useFeatureColors)
        instanceLayerRanges = packResult.layerRanges
        hasInstances = packResult.totalInstances > 0

        if (hasInstances) {
            val bytesPerInstance = GcodeInstancePacker.FLOATS_PER_INSTANCE * 4  // 48
            val buf = ByteBuffer.allocateDirect(packResult.instanceData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buf.put(packResult.instanceData)
            buf.flip()

            val vaos = IntArray(1); GLES30.glGenVertexArrays(1, vaos, 0); instanceVAO = vaos[0]
            val vbos = IntArray(1); GLES30.glGenBuffers(1, vbos, 0); instanceVBO = vbos[0]

            GLES30.glBindVertexArray(instanceVAO)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVBO)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, packResult.instanceData.size * 4, buf, GLES30.GL_STATIC_DRAW)

            // a_Start (location 0): vec3 at offset 0
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, bytesPerInstance, 0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribDivisor(0, 1)
            // a_End (location 1): vec3 at offset 12
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, bytesPerInstance, 12)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribDivisor(1, 1)
            // a_Color (location 2): vec4 at offset 24
            GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, bytesPerInstance, 24)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribDivisor(2, 1)
            // a_Dimensions (location 3): vec2 at offset 40
            GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, bytesPerInstance, 40)
            GLES30.glEnableVertexAttribArray(3)
            GLES30.glVertexAttribDivisor(3, 1)

            GLES30.glBindVertexArray(0)
        }

        // --- Build travel-only line VBO ---
        val floatsPerVertex = 7  // 3 pos + 4 color
        var totalTravelMoves = 0
        for (layer in gcode.layers) {
            for (move in layer.moves) {
                if (move.type != MoveType.EXTRUDE) totalTravelMoves++
            }
        }

        if (totalTravelMoves > 0) {
            val lineData = FloatArray(totalTravelMoves * 2 * floatsPerVertex)
            var lineOffset = 0

            for (layer in gcode.layers) {
                val travelFirst = lineOffset / floatsPerVertex
                for (move in layer.moves) {
                    if (move.type == MoveType.EXTRUDE) continue
                    if (lineOffset + floatsPerVertex * 2 > lineData.size) break
                    lineData[lineOffset++] = move.x0; lineData[lineOffset++] = move.y0; lineData[lineOffset++] = layer.z
                    lineData[lineOffset++] = travelColor[0]; lineData[lineOffset++] = travelColor[1]; lineData[lineOffset++] = travelColor[2]; lineData[lineOffset++] = travelColor[3]
                    lineData[lineOffset++] = move.x1; lineData[lineOffset++] = move.y1; lineData[lineOffset++] = layer.z
                    lineData[lineOffset++] = travelColor[0]; lineData[lineOffset++] = travelColor[1]; lineData[lineOffset++] = travelColor[2]; lineData[lineOffset++] = travelColor[3]
                }
                val travelCount = lineOffset / floatsPerVertex - travelFirst
                layerRanges.add(LayerRange(travelFirst, travelCount))
            }

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
        } else {
            // No travel moves — still need empty layer ranges for indexing
            for (layer in gcode.layers) {
                layerRanges.add(LayerRange(0, 0))
            }
        }
    }

    private fun drawToolpaths() {
        val layerCount = maxOf(layerRanges.size, instanceLayerRanges.size)
        if (layerCount == 0) return

        val min = minLayer.coerceIn(0, layerCount - 1)
        val max = maxLayer.coerceIn(0, layerCount - 1)

        // Draw extrusion tubes via instanced rendering
        if (hasInstances && instanceLayerRanges.isNotEmpty()) {
            val shader = instancedShader ?: return
            shader.use()
            camera.computeMVP()
            GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

            val bytesPerInstance = GcodeInstancePacker.FLOATS_PER_INSTANCE * 4  // 48

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVBO)

            for (i in min..max) {
                if (i >= instanceLayerRanges.size) break
                val r = instanceLayerRanges[i]
                if (r.instanceCount <= 0) continue

                // ES 3.0 lacks glDrawArraysInstancedBaseInstance, so rebind attrib pointers
                // with byte offset to simulate base instance
                val byteOffset = r.firstInstance * bytesPerInstance
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, bytesPerInstance, byteOffset)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribDivisor(0, 1)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, bytesPerInstance, byteOffset + 12)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribDivisor(1, 1)
                GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, bytesPerInstance, byteOffset + 24)
                GLES30.glEnableVertexAttribArray(2)
                GLES30.glVertexAttribDivisor(2, 1)
                GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, bytesPerInstance, byteOffset + 40)
                GLES30.glEnableVertexAttribArray(3)
                GLES30.glVertexAttribDivisor(3, 1)

                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 18, r.instanceCount)
            }

            // Reset divisors to avoid polluting other draw calls
            for (loc in 0..3) {
                GLES30.glVertexAttribDivisor(loc, 0)
            }
        }

        // Draw travel moves (always GL_LINES via toolpath shader)
        if (showTravel && layerRanges.isNotEmpty() && masterVAO != 0) {
            val shader = toolpathShader ?: return
            shader.use()
            camera.computeMVP()
            GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_MVPMatrix"), 1, false, camera.mvpMatrix, 0)
            GLES30.glUniformMatrix4fv(shader.getUniformLocation("u_NormalMatrix"), 1, false, camera.normalMatrix, 0)

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
