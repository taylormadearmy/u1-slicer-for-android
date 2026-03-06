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
import com.u1.slicer.viewer.StlParser
import kotlinx.coroutines.Dispatchers
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
                    file.name.endsWith(".3mf", ignoreCase = true) ->
                        com.u1.slicer.viewer.ThreeMfMeshParser.parse(file)
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
                            setTarget(m.centerX, m.centerY, m.centerZ)
                            distance = m.maxDimension * 2f
                            elevation = 25f
                            azimuth = -45f
                            panX = 0f
                            panY = 0f
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
