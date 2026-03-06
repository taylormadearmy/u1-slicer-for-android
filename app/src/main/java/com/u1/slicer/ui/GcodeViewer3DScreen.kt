package com.u1.slicer.ui

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
import com.u1.slicer.gcode.ParsedGcode
import com.u1.slicer.viewer.GcodeViewerView
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GcodeViewer3DScreen(
    parsedGcode: ParsedGcode,
    extruderColors: List<String> = emptyList(),
    onBack: () -> Unit
) {
    val totalLayers = parsedGcode.layers.size
    var viewerView by remember { mutableStateOf<GcodeViewerView?>(null) }
    var minLayer by remember { mutableIntStateOf(0) }
    var maxLayer by remember { mutableIntStateOf(totalLayers - 1) }

    // Set colors + upload gcode atomically on the GL thread.
    // Using queueEvent ensures both run in the same frame — no race where VBOs
    // get built with default colors before the real extruder colors are applied.
    LaunchedEffect(parsedGcode, extruderColors, viewerView) {
        val v = viewerView ?: return@LaunchedEffect
        val colors = extruderColors
        val gcode = parsedGcode
        v.queueEvent {
            if (colors.isNotEmpty()) v.renderer.setExtruderColors(colors)
            v.renderer.uploadGcode(gcode)
        }
        v.requestRender()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("3D G-code View", fontWeight = FontWeight.Bold)
                        Text(
                            "$totalLayers layers  Showing ${minLayer + 1}–${maxLayer + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
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
                        v.renderer.camera.apply {
                            setTarget(135f, 135f, parsedGcode.layers.lastOrNull()?.z?.div(2) ?: 0f)
                            distance = 400f
                            elevation = 35f
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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // GL view
            AndroidView(
                factory = { ctx ->
                    GcodeViewerView(ctx).also { view ->
                        viewerView = view
                        // Don't upload gcode here — the LaunchedEffect handles both
                        // colors and gcode atomically to avoid a race where VBOs are
                        // built with default colors before the real colors are set.
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            // Layer range controls
            if (totalLayers > 1) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Max layer slider (which layer to show up to)
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Layer",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(48.dp)
                            )
                            Slider(
                                value = maxLayer.toFloat(),
                                onValueChange = {
                                    maxLayer = it.roundToInt()
                                    if (minLayer > maxLayer) minLayer = maxLayer
                                    viewerView?.setLayerRange(minLayer, maxLayer)
                                },
                                valueRange = 0f..(totalLayers - 1).toFloat(),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${maxLayer + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(48.dp)
                            )
                        }

                        // Range slider for min layer
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "From",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(48.dp)
                            )
                            Slider(
                                value = minLayer.toFloat(),
                                onValueChange = {
                                    minLayer = it.roundToInt()
                                    if (maxLayer < minLayer) maxLayer = minLayer
                                    viewerView?.setLayerRange(minLayer, maxLayer)
                                },
                                valueRange = 0f..(totalLayers - 1).toFloat(),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${minLayer + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(48.dp)
                            )
                        }

                        // Z height info
                        val maxZ = parsedGcode.layers.getOrNull(maxLayer)?.z ?: 0f
                        val minZ = parsedGcode.layers.getOrNull(minLayer)?.z ?: 0f
                        Text(
                            "Z: %.2f – %.2f mm".format(minZ, maxZ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}
