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
import kotlin.math.sqrt

/**
 * Renders G-code toolpaths as 3D box-tube geometry (GL_TRIANGLES) when move count allows;
 * falls back to GL_LINES for very large files. Travel moves are always GL_LINES.
 * Box tubes have top + left + right faces with proper normals for lighting.
 * All layers share a single VBO; each layer is a (firstVertex, count) range within it.
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

        // Decide whether to use box-tube geometry for extrusions
        // 18 vertices per move (3 quads: top + left + right) * 10 floats * 4 bytes = 720 bytes per move
        val verticesPerTubeMove = 18
        val tubeBytes = totalExtrudeMoves.toLong() * verticesPerTubeMove * tubeFloatsPerVertex * 4
        useTubes = totalExtrudeMoves < 200_000 && tubeBytes <= maxBufferBytes

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

        // Allocate tube buffer: 18 vertices per extrusion move (box-tube: top + left + right faces)
        val tubeData = if (useTubes) FloatArray(totalExtrudeMoves * verticesPerTubeMove * tubeFloatsPerVertex) else FloatArray(0)
        var tubeOffset = 0
        val halfWidth = 0.28f  // display-scaled width (physically ~0.56mm) — slightly wider than actual 0.42mm for visibility
        val halfHeight = 0.18f // display-scaled height (physically ~0.36mm) — close to actual 0.2mm layer height

        for ((layerIdx, layer) in gcode.layers.withIndex()) {
            // --- Extrusion pass ---
            val extrudeFirst = lineOffset / floatsPerVertex
            val tubeFirst = tubeOffset / tubeFloatsPerVertex

            // Bottom-to-top brightness gradient: dark at bottom, bright at top (like u1-slicer-bridge)
            val layerBrightness = if (totalLayers <= 1) 1.0f
                else 0.45f + 0.55f * (layerIdx.toFloat() / (totalLayers - 1))

            // Collect extrusion moves for this layer (needed for miter look-ahead)
            val extrudeMoves = if (useTubes) layer.moves.filter { it.type == MoveType.EXTRUDE } else emptyList()
            var extrudeCounter = 0

            for ((moveIdx, move) in layer.moves.withIndex()) {
                if (move.type != MoveType.EXTRUDE) continue
                val baseColor = if (useFeatureColors) {
                    featureTypeColors[move.featureType.toInt().coerceIn(0, featureTypeColors.size - 1)]
                } else {
                    extruderColors[move.extruder.coerceIn(0, 3)]
                }
                val color = floatArrayOf(
                    (baseColor[0] * layerBrightness).coerceAtMost(1.0f),
                    (baseColor[1] * layerBrightness).coerceAtMost(1.0f),
                    (baseColor[2] * layerBrightness).coerceAtMost(1.0f),
                    baseColor[3]
                )

                if (useTubes) {
                    // Box-tube: 3 faces (top + left + right) = 18 vertices
                    val dx = move.x1 - move.x0
                    val dy = move.y1 - move.y0
                    val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (len < 0.001f) continue  // skip zero-length moves

                    // This move's perpendicular unit vector (pointing right of travel direction)
                    val ux = -dy / len   // unit perp x
                    val uy = dx / len    // unit perp y

                    val zBot = layer.z - halfHeight
                    val zTop = layer.z + halfHeight

                    if (tubeOffset + verticesPerTubeMove * tubeFloatsPerVertex > tubeData.size) break

                    // --- Miter join at start endpoint ---
                    // Use incremental counter (O(1)) instead of indexOf (O(n))
                    val extrudeIdx = extrudeCounter++
                    val prevMove = if (extrudeIdx > 0) extrudeMoves[extrudeIdx - 1] else null
                    val startMiter = if (prevMove != null &&
                        kotlin.math.abs(prevMove.x1 - move.x0) < 0.01f &&
                        kotlin.math.abs(prevMove.y1 - move.y0) < 0.01f
                    ) {
                        val pdx = prevMove.x1 - prevMove.x0
                        val pdy = prevMove.y1 - prevMove.y0
                        val pLen = sqrt((pdx * pdx + pdy * pdy).toDouble()).toFloat()
                        if (pLen > 0.001f) {
                            val pux = -pdy / pLen
                            val puy = pdx / pLen
                            // Miter direction = average of both perp vectors, normalized
                            val mx = pux + ux
                            val my = puy + uy
                            val mLen = sqrt((mx * mx + my * my).toDouble()).toFloat()
                            if (mLen > 0.001f) {
                                // Scale so projection onto this perp = halfWidth
                                val dot = mx / mLen * ux + my / mLen * uy
                                val scale = if (dot > 0.2f) halfWidth / dot else halfWidth * 2f // clamp spike
                                floatArrayOf(mx / mLen * scale, my / mLen * scale)
                            } else floatArrayOf(ux * halfWidth, uy * halfWidth)
                        } else floatArrayOf(ux * halfWidth, uy * halfWidth)
                    } else floatArrayOf(ux * halfWidth, uy * halfWidth)

                    // --- Miter join at end endpoint ---
                    val nextMove = if (extrudeIdx < extrudeMoves.size - 1) extrudeMoves[extrudeIdx + 1] else null
                    val endMiter = if (nextMove != null &&
                        kotlin.math.abs(nextMove.x0 - move.x1) < 0.01f &&
                        kotlin.math.abs(nextMove.y0 - move.y1) < 0.01f
                    ) {
                        val ndx = nextMove.x1 - nextMove.x0
                        val ndy = nextMove.y1 - nextMove.y0
                        val nLen = sqrt((ndx * ndx + ndy * ndy).toDouble()).toFloat()
                        if (nLen > 0.001f) {
                            val nux = -ndy / nLen
                            val nuy = ndx / nLen
                            val mx = nux + ux
                            val my = nuy + uy
                            val mLen = sqrt((mx * mx + my * my).toDouble()).toFloat()
                            if (mLen > 0.001f) {
                                val dot = mx / mLen * ux + my / mLen * uy
                                val scale = if (dot > 0.2f) halfWidth / dot else halfWidth * 2f
                                floatArrayOf(mx / mLen * scale, my / mLen * scale)
                            } else floatArrayOf(ux * halfWidth, uy * halfWidth)
                        } else floatArrayOf(ux * halfWidth, uy * halfWidth)
                    } else floatArrayOf(ux * halfWidth, uy * halfWidth)

                    // Perpendicular offsets at start and end (mitered)
                    val spx = startMiter[0]; val spy = startMiter[1]
                    val epx = endMiter[0];   val epy = endMiter[1]

                    // Side normal: this segment's perp (not miter-blended, for correct lighting)
                    val snx = ux
                    val sny = uy

                    // === Top face (normal pointing up: 0,0,1) ===
                    // BL=x0-spx,top  BR=x0+spx,top  TR=x1+epx,top  TL=x1-epx,top
                    // Tri1: BL, BR, TR
                    tubeData[tubeOffset++] = move.x0 - spx; tubeData[tubeOffset++] = move.y0 - spy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 1f
                    tubeData[tubeOffset++] = move.x0 + spx; tubeData[tubeOffset++] = move.y0 + spy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 1f
                    tubeData[tubeOffset++] = move.x1 + epx; tubeData[tubeOffset++] = move.y1 + epy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 1f
                    // Tri2: BL, TR, TL
                    tubeData[tubeOffset++] = move.x0 - spx; tubeData[tubeOffset++] = move.y0 - spy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 1f
                    tubeData[tubeOffset++] = move.x1 + epx; tubeData[tubeOffset++] = move.y1 + epy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 1f
                    tubeData[tubeOffset++] = move.x1 - epx; tubeData[tubeOffset++] = move.y1 - epy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 0f; tubeData[tubeOffset++] = 1f

                    // === Right face (normal pointing right: snx, sny, 0) ===
                    // Tri1: BotStart, TopStart, TopEnd
                    tubeData[tubeOffset++] = move.x0 + spx; tubeData[tubeOffset++] = move.y0 + spy; tubeData[tubeOffset++] = zBot
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = snx; tubeData[tubeOffset++] = sny; tubeData[tubeOffset++] = 0f
                    tubeData[tubeOffset++] = move.x0 + spx; tubeData[tubeOffset++] = move.y0 + spy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = snx; tubeData[tubeOffset++] = sny; tubeData[tubeOffset++] = 0f
                    tubeData[tubeOffset++] = move.x1 + epx; tubeData[tubeOffset++] = move.y1 + epy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = snx; tubeData[tubeOffset++] = sny; tubeData[tubeOffset++] = 0f
                    // Tri2: BotStart, TopEnd, BotEnd
                    tubeData[tubeOffset++] = move.x0 + spx; tubeData[tubeOffset++] = move.y0 + spy; tubeData[tubeOffset++] = zBot
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = snx; tubeData[tubeOffset++] = sny; tubeData[tubeOffset++] = 0f
                    tubeData[tubeOffset++] = move.x1 + epx; tubeData[tubeOffset++] = move.y1 + epy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = snx; tubeData[tubeOffset++] = sny; tubeData[tubeOffset++] = 0f
                    tubeData[tubeOffset++] = move.x1 + epx; tubeData[tubeOffset++] = move.y1 + epy; tubeData[tubeOffset++] = zBot
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = snx; tubeData[tubeOffset++] = sny; tubeData[tubeOffset++] = 0f

                    // === Left face (normal pointing left: -snx, -sny, 0) ===
                    // Tri1: BotStart, TopEnd, TopStart (wound CCW from outside)
                    tubeData[tubeOffset++] = move.x0 - spx; tubeData[tubeOffset++] = move.y0 - spy; tubeData[tubeOffset++] = zBot
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = -snx; tubeData[tubeOffset++] = -sny; tubeData[tubeOffset++] = 0f
                    tubeData[tubeOffset++] = move.x1 - epx; tubeData[tubeOffset++] = move.y1 - epy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = -snx; tubeData[tubeOffset++] = -sny; tubeData[tubeOffset++] = 0f
                    tubeData[tubeOffset++] = move.x0 - spx; tubeData[tubeOffset++] = move.y0 - spy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = -snx; tubeData[tubeOffset++] = -sny; tubeData[tubeOffset++] = 0f
                    // Tri2: BotStart, BotEnd, TopEnd
                    tubeData[tubeOffset++] = move.x0 - spx; tubeData[tubeOffset++] = move.y0 - spy; tubeData[tubeOffset++] = zBot
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = -snx; tubeData[tubeOffset++] = -sny; tubeData[tubeOffset++] = 0f
                    tubeData[tubeOffset++] = move.x1 - epx; tubeData[tubeOffset++] = move.y1 - epy; tubeData[tubeOffset++] = zBot
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = -snx; tubeData[tubeOffset++] = -sny; tubeData[tubeOffset++] = 0f
                    tubeData[tubeOffset++] = move.x1 - epx; tubeData[tubeOffset++] = move.y1 - epy; tubeData[tubeOffset++] = zTop
                    tubeData[tubeOffset++] = color[0]; tubeData[tubeOffset++] = color[1]; tubeData[tubeOffset++] = color[2]; tubeData[tubeOffset++] = color[3]
                    tubeData[tubeOffset++] = -snx; tubeData[tubeOffset++] = -sny; tubeData[tubeOffset++] = 0f
                } else {
                    // Fallback: GL_LINES
                    if (sampleRate > 1 && moveIdx % sampleRate != 0) continue
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
