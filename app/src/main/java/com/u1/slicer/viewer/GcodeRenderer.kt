package com.u1.slicer.viewer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import com.u1.slicer.gcode.ParsedGcode
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders G-code toolpaths using PrusaSlicer's libvgcode (instanced tube rendering).
 * Bed is drawn in Kotlin; toolpaths are delegated to the native library.
 */
class GcodeRenderer(private val context: Context) : GLSurfaceView.Renderer {

    val camera = Camera()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile var preserveRestoredCameraOnSurfaceInit = false
    @Volatile var onContentReady: (() -> Unit)? = null
    @Volatile private var pendingContentReadyDispatch = false
    private val bed = BedDrawable(context)

    // libvgcode native pointer — 0 means not created
    private var vgcodePtr: Long = 0
    private var totalLayers = 0

    var minLayer = 0
        set(value) { field = value.coerceIn(0, (totalLayers - 1).coerceAtLeast(0)) }
    var maxLayer = 0
        set(value) { field = value.coerceIn(0, (totalLayers - 1).coerceAtLeast(0)) }
    var showTravel = false

    @Volatile var pendingGcode: ParsedGcode? = null
    @Volatile var preserveCameraOnNextUpload = false
    @Volatile var pendingExtruderColors: List<String>? = null
    @Volatile var pendingColorMode: Boolean? = null

    /** Current extruder colors as packed RGB ints for libvgcode. */
    private var toolColors = VGCodeNative.packToolColors(emptyList())

    private var lastGcode: ParsedGcode? = null
    private var useFeatureColors = false
    // Track state to avoid redundant JNI calls per frame
    private var lastShowTravel = false
    private var lastMinLayer = -1
    private var lastMaxLayer = -1

    fun setExtruderColors(hexColors: List<String>) {
        toolColors = VGCodeNative.packToolColors(hexColors)
        if (vgcodePtr != 0L) {
            VGCodeNative.setToolColors(vgcodePtr, toolColors)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.059f, 0.059f, 0.118f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        bed.setup(context)

        // Create and init libvgcode
        if (vgcodePtr != 0L) {
            VGCodeNative.destroy(vgcodePtr)
        }
        vgcodePtr = VGCodeNative.create()
        VGCodeNative.init(vgcodePtr)

        if (preserveRestoredCameraOnSurfaceInit) {
            preserveRestoredCameraOnSurfaceInit = false
        } else {
            camera.setTarget(135f, 135f, 0f)
            camera.distance = 500f
            camera.elevation = 62f
            camera.azimuth = -90f
        }

        // Re-upload if we had data before context loss
        lastGcode?.let { gcode ->
            preserveCameraOnNextUpload = true
            uploadGcode(gcode)
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
            useFeatureColors = mode
            if (vgcodePtr != 0L) {
                val viewType = if (mode) VGCodeNative.VIEW_TYPE_FEATURE else VGCodeNative.VIEW_TYPE_TOOL
                VGCodeNative.setViewType(vgcodePtr, viewType)
            }
        }

        pendingGcode?.let { gcode ->
            uploadGcode(gcode)
            pendingGcode = null

            if (preserveCameraOnNextUpload) {
                preserveCameraOnNextUpload = false
            } else {
                camera.setTarget(135f, 135f, 0f)
                camera.distance = 500f
                camera.elevation = 62f
                camera.azimuth = -90f
                camera.panX = 0f
                camera.panY = 0f
            }
            pendingContentReadyDispatch = true
        }

        // Sync travel visibility
        if (showTravel != lastShowTravel && vgcodePtr != 0L) {
            VGCodeNative.toggleOptionVisibility(vgcodePtr, VGCodeNative.OPTION_TRAVELS)
            lastShowTravel = showTravel
        }

        camera.updateViewMatrix()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        bed.draw(camera)

        // Render toolpaths via libvgcode
        if (vgcodePtr != 0L && totalLayers > 0) {
            val min = minLayer.coerceIn(0, totalLayers - 1)
            val max = maxLayer.coerceIn(0, totalLayers - 1)
            if (min != lastMinLayer || max != lastMaxLayer) {
                VGCodeNative.setLayersViewRange(vgcodePtr, min, max)
                lastMinLayer = min
                lastMaxLayer = max
            }
            VGCodeNative.render(vgcodePtr, camera.viewMatrix, camera.projectionMatrix)
        }

        if (pendingContentReadyDispatch) {
            pendingContentReadyDispatch = false
            onContentReady?.let { callback -> mainHandler.post { callback() } }
        }
    }

    fun uploadGcode(gcode: ParsedGcode) {
        lastGcode = gcode
        totalLayers = gcode.layers.size
        maxLayer = totalLayers - 1
        if (totalLayers == 0 || vgcodePtr == 0L) return

        VGCodeNative.loadGcode(vgcodePtr, gcode, toolColors)

        // Use libvgcode's layer count — it may differ from our parsed layer count
        // because libvgcode creates layers from sequential layer_id values in PathVertex
        val vgcodeLayers = VGCodeNative.getLayersCount(vgcodePtr).toInt()
        android.util.Log.i("GcodeRenderer", "uploadGcode: parsed ${gcode.layers.size} layers, " +
            "totalMoves=${gcode.totalMoves}, libvgcode reports $vgcodeLayers layers")
        totalLayers = vgcodeLayers
        maxLayer = totalLayers - 1

        // Apply current view type
        val viewType = if (useFeatureColors) VGCodeNative.VIEW_TYPE_FEATURE else VGCodeNative.VIEW_TYPE_TOOL
        VGCodeNative.setViewType(vgcodePtr, viewType)

        // Sync travel visibility — after load, libvgcode defaults to travels hidden
        val travelsVisible = VGCodeNative.isOptionVisible(vgcodePtr, VGCodeNative.OPTION_TRAVELS)
        if (travelsVisible != showTravel) {
            VGCodeNative.toggleOptionVisibility(vgcodePtr, VGCodeNative.OPTION_TRAVELS)
        }
        lastShowTravel = showTravel
    }
}
