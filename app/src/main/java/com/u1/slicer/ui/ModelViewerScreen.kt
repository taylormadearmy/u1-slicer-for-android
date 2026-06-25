package com.u1.slicer.ui

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.u1.slicer.viewer.MeshData
import com.u1.slicer.viewer.ModelViewerView
import com.u1.slicer.viewer.NativePreviewMesh
import com.u1.slicer.viewer.NativeRenderBatch
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.NativePlateState
import com.u1.slicer.viewer.StlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelViewerScreen(
    modelFilePath: String,
    onBack: () -> Unit
) {
    var mesh by remember { mutableStateOf<MeshData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewerView by remember { mutableStateOf<ModelViewerView?>(null) }

    // Parse mesh on background thread
    LaunchedEffect(modelFilePath) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(modelFilePath)
                mesh = when {
                    file.name.endsWith(".stl", ignoreCase = true) -> StlParser.parse(file)
                    file.name.endsWith(".3mf", ignoreCase = true) -> {
                        // Load then read under previewMutex so we don't race the Prepare
                        // screen's concurrent slicing / rotation actions; loadModel is
                        // idempotent for the same file.
                        val native = NativeLibrary()
                        NativeLibrary.previewMutex.withLock {
                            if (!native.loadModel(file.absolutePath)) {
                                null
                            } else {
                                val hasPaintData = runCatching {
                                    NativePlateState.parseVolumeMapJson(native.nativeGetAllVolumeExtruders()).hasPaintData
                                }.getOrDefault(false)
                                val sceneHandle = native.buildPrepareRenderScene()
                                val batches = mutableListOf<NativeRenderBatch>()
                                try {
                                    while (true) {
                                        val batchCount = native.nativeGetPrepareRenderSceneBatchCount(sceneHandle)
                                        val isComplete = native.nativeIsPrepareRenderSceneComplete(sceneHandle)
                                        while (batches.size < batchCount) {
                                            val i = batches.size
                                            val triCount = native.nativeGetPrepareRenderSceneTriangleCount(sceneHandle, i)
                                            val geoBuf = native.nativeGetPrepareRenderSceneGeometryBuffer(sceneHandle, i)
                                            val matBuf = native.nativeGetPrepareRenderSceneMaterialBuffer(sceneHandle, i)
                                            if (geoBuf != null && matBuf != null) {
                                                geoBuf.order(java.nio.ByteOrder.nativeOrder())
                                                matBuf.order(java.nio.ByteOrder.nativeOrder())
                                                val nativeBounds = native.nativeGetPrepareRenderSceneBoundingBox(sceneHandle, i)
                                                batches.add(NativeRenderBatch(geoBuf.asFloatBuffer(), matBuf, triCount, nativeBounds))
                                            } else {
                                                break
                                            }
                                        }
                                        if (batches.isNotEmpty() && mesh == null) {
                                            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
                                            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
                                            for (b in batches) {
                                                val nativeBounds = b.bounds
                                                if (nativeBounds != null && nativeBounds.size == 6) {
                                                    if (nativeBounds[0] < minX) minX = nativeBounds[0]
                                                    if (nativeBounds[1] < minY) minY = nativeBounds[1]
                                                    if (nativeBounds[2] < minZ) minZ = nativeBounds[2]
                                                    if (nativeBounds[3] > maxX) maxX = nativeBounds[3]
                                                    if (nativeBounds[4] > maxY) maxY = nativeBounds[4]
                                                    if (nativeBounds[5] > maxZ) maxZ = nativeBounds[5]
                                                }
                                            }
                                            mesh = MeshData(
                                                batches = batches.toList(),
                                                minX = minX, minY = minY, minZ = minZ,
                                                maxX = maxX, maxY = maxY, maxZ = maxZ,
                                                sceneHandle = sceneHandle
                                            )
                                        }
                                        if (isComplete && batches.size == batchCount) break
                                        kotlinx.coroutines.delay(16)
                                    }
                                } catch (e: Exception) {
                                    native.nativeReleasePrepareRenderScene(sceneHandle)
                                    throw e
                                }
                                
                                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
                                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
                                for (b in batches) {
                                    val buf = b.geometry
                                    for (v in 0 until b.triangleCount * 3) {
                                        val base = v * 10
                                        val x = buf.get(base); val y = buf.get(base + 1); val z = buf.get(base + 2)
                                        if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                                        if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
                                    }
                                }
                                val modStart = native.nativeGetPreviewModifierBlockStart()
                                var currentStart = 0
                                val batchRanges = batches.map { b ->
                                    val r = currentStart until (currentStart + b.triangleCount)
                                    currentStart += b.triangleCount
                                    r
                                }
                                MeshData(
                                    batches = batches.toList(),
                                    minX = minX, minY = minY, minZ = minZ,
                                    maxX = maxX, maxY = maxY, maxZ = maxZ,
                                    batchRanges = batchRanges,
                                    modifierBlockStartTriangle = if (modStart >= 0) modStart else null,
                                    sceneHandle = sceneHandle
                                )
                            }
                        }
                }
                    else -> null
                }
                if (mesh == null) error = "Unsupported file format for 3D preview"
            } catch (e: Throwable) {
                Log.e("ModelViewer", "Parse failed", e)
                error = "Failed to load model: ${e.message}"
            }
            loading = false
        }
    }

    // Send mesh to GL view when ready
    LaunchedEffect(mesh, viewerView) {
        val m = mesh
        val v = viewerView
        if (m != null && v != null) {
            v.setMesh(m)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("3D Preview", fontWeight = FontWeight.Bold)
                        mesh?.let {
                            Text(
                                "${it.vertexCount / 3} triangles  %.0fx%.0fx%.0f mm".format(
                                    it.sizeX, it.sizeY, it.sizeZ
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val v = viewerView ?: return@IconButton
                        val m = mesh ?: return@IconButton
                        v.renderer.camera.apply {
                            setTarget(m.centerX.toDouble(), m.centerY.toDouble(), m.centerZ.toDouble())
                            distance = m.maxDimension.toDouble() * 2.0
                            elevation = 25.0
                            azimuth = -45.0
                            panX = 0.0
                            panY = 0.0
                        }
                        v.requestRender()
                    }) {
                        Icon(Icons.Default.FitScreen, "Reset view")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            } else {
                AndroidView(
                    factory = { ctx ->
                        ModelViewerView(ctx).also { view ->
                            viewerView = view
                            mesh?.let { view.setMesh(it) }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
