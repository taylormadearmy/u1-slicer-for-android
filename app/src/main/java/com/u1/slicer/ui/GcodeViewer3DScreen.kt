package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.u1.slicer.gcode.ParsedGcode
import com.u1.slicer.viewer.GcodeViewerView
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

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
    var isLoading by remember { mutableStateOf(true) }

    // Set colors + upload gcode atomically on the GL thread.
    // suspendCancellableCoroutine waits for the GL work to finish before
    // clearing the loading indicator.
    LaunchedEffect(parsedGcode, extruderColors, viewerView) {
        val v = viewerView ?: return@LaunchedEffect
        isLoading = true
        val colors = extruderColors
        val gcode = parsedGcode
        suspendCancellableCoroutine { cont ->
            v.queueEvent {
                if (colors.isNotEmpty()) v.renderer.setExtruderColors(colors)
                v.renderer.uploadGcode(gcode)
                // Post back to main thread to resume the coroutine
                v.post { if (cont.isActive) cont.resume(Unit) }
            }
            v.requestRender()
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("3D G-code View", fontWeight = FontWeight.Bold)
                        val layerRange = if (minLayer > 0) "Layers ${minLayer + 1}–${maxLayer + 1}" else "Layer ${maxLayer + 1}"
                        val minZ = parsedGcode.layers.getOrNull(minLayer)?.z ?: 0f
                        val maxZ = parsedGcode.layers.getOrNull(maxLayer)?.z ?: 0f
                        val zInfo = if (minLayer > 0) "%.1f–%.1fmm".format(minZ, maxZ) else "%.1fmm".format(maxZ)
                        Text(
                            "$totalLayers layers  $layerRange  $zInfo",
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
        Row(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // GL view + loading overlay
            Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        GcodeViewerView(ctx).also { view ->
                            viewerView = view
                            // Don't upload gcode here — the LaunchedEffect handles both
                            // colors and gcode atomically to avoid a race where VBOs are
                            // built with default colors before the real colors are set.
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "Building preview…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Vertical layer range slider on the right
            if (totalLayers > 1) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(52.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top label: current max layer
                    Text(
                        "${maxLayer + 1}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Vertical range slider (rotated horizontal RangeSlider)
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val sliderLength = maxHeight
                        RangeSlider(
                            value = minLayer.toFloat()..maxLayer.toFloat(),
                            onValueChange = { range ->
                                minLayer = range.start.roundToInt()
                                maxLayer = range.endInclusive.roundToInt()
                                viewerView?.setLayerRange(minLayer, maxLayer)
                            },
                            valueRange = 0f..(totalLayers - 1).toFloat(),
                            modifier = Modifier
                                .requiredWidth(sliderLength)
                                .graphicsLayer { rotationZ = -90f }
                        )
                    }
                    // Bottom label: min layer (or 1 if at bottom)
                    Text(
                        if (minLayer > 0) "${minLayer + 1}" else "1",
                        fontSize = 11.sp,
                        color = if (minLayer > 0) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
